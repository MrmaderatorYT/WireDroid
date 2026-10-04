package com.ccs.wiredroid.javase;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.ccs.wiredroid.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Binds to the disposable Java SE process and returns its captured output. */
public final class JavaSeRunner {

    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    public interface Callback {
        void onComplete(Result result);
    }

    public static final class Request {
        /**
         * Called with the program's output as it is produced, or left null.
         *
         * <p>The VM writes to a file, so this is fed by polling that file — the
         * only way to show a prompt before the program blocks reading the
         * answer to it.</p>
         */
        public java.util.function.Consumer<String> onOutput;
        public File runtimeHome;
        public File workingDirectory;
        public final List<String> arguments = new ArrayList<>();
        public final Map<String, String> environment = new LinkedHashMap<>();
    }

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final String error;
        public final boolean processDied;

        Result(int exitCode, String output, String error, boolean processDied) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
            this.processDied = processDied;
        }
    }

    private static volatile LaunchController activeController = null;

    private JavaSeRunner() {}

    public static void stopCurrent() {
        LaunchController c = activeController;
        if (c != null) {
            c.stop();
        }
    }

    public static void launch(Context context, Request request, Callback callback) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(
                () -> {
                    LaunchController controller = new LaunchController(app, request, callback);
                    activeController = controller;
                    controller.bind();
                });
    }

    private static final class LaunchController implements ServiceConnection, IBinder.DeathRecipient {
        private final Context context;
        private final Request request;
        private final Callback callback;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean completed = new AtomicBoolean();
        private final File outputFile;
        private final Messenger reply;
        private IBinder serviceBinder;
        private boolean bound;

        LaunchController(Context context, Request request, Callback callback) {
            this.context = context;
            this.request = request;
            this.callback = callback;
            this.outputFile = new File(context.getCacheDir(),
                    "java_se_output_" + System.currentTimeMillis() + "_"
                            + Integer.toHexString(System.identityHashCode(this)) + ".txt");
            this.reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (message.what != JavaSeRunnerService.MSG_RESULT) return false;
                Bundle data = message.getData();
                finish(data.getInt(JavaSeRunnerService.KEY_EXIT_CODE, -1),
                        data.getString(JavaSeRunnerService.KEY_ERROR), false);
                return true;
            }));
        }

        void bind() {
            if (request.runtimeHome == null || request.workingDirectory == null
                    || request.arguments.isEmpty()) {
                finish(-30, "Invalid Java SE launch request", false);
                return;
            }
            Intent intent = new Intent(context, JavaSeRunnerService.class);
            try {
                bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
            } catch (Throwable throwable) {
                finish(-31, describe(throwable), false);
                return;
            }
            if (!bound) finish(-32, "Could not start the Java SE process", false);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceBinder = binder;
            try {
                binder.linkToDeath(this, 0);
                Message message = Message.obtain(null, JavaSeRunnerService.MSG_RUN);
                Bundle data = new Bundle();
                data.putString(JavaSeRunnerService.KEY_RUNTIME,
                        request.runtimeHome.getAbsolutePath());
                data.putString(JavaSeRunnerService.KEY_WORKING_DIRECTORY,
                        request.workingDirectory.getAbsolutePath());
                data.putString(JavaSeRunnerService.KEY_OUTPUT, outputFile.getAbsolutePath());
                data.putStringArrayList(JavaSeRunnerService.KEY_ARGUMENTS,
                        new ArrayList<>(request.arguments));
                ArrayList<String> environment = new ArrayList<>();
                for (Map.Entry<String, String> entry : request.environment.entrySet()) {
                    environment.add(entry.getKey() + "=" + entry.getValue());
                }
                data.putStringArrayList(JavaSeRunnerService.KEY_ENVIRONMENT, environment);
                message.setData(data);
                message.replyTo = reply;
                new Messenger(binder).send(message);
                // From here the console's input field has somewhere to send to.
                com.ccs.wiredroid.tools.compilers.ConsoleInputHolder.setRemoteSink(this::sendStdin);
                startTailing();
            } catch (Throwable throwable) {
                finish(-33, describe(throwable), false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!completed.get()) main.postDelayed(() -> finish(-34, null, true), 100);
        }

        @Override
        public void binderDied() {
            main.postDelayed(() -> finish(-35, null, true), 100);
        }

        /**
         * Sends one line to the program's stdin.
         *
         * <p>The pipe lives in the {@code :javase} process, so this is a binder
         * message rather than a write — {@link JavaSeNativeLauncher#writeStdin}
         * here would find no descriptor.</p>
         */
        private boolean sendStdin(String line) {
            IBinder binder = serviceBinder;
            if (binder == null || completed.get()) return false;
            try {
                Message message = Message.obtain(null, JavaSeRunnerService.MSG_STDIN);
                Bundle data = new Bundle();
                data.putString(JavaSeRunnerService.KEY_STDIN, line + "\n");
                message.setData(data);
                new Messenger(binder).send(message);
                return true;
            } catch (Throwable throwable) {
                return false;
            }
        }

        /**
         * Reports the output file as it grows.
         *
         * <p>The VM writes to a file rather than to this process, so there is no
         * stream to hook — the file is polled instead, and only what is new
         * since the last read is reported.</p>
         */
        private void startTailing() {
            Thread tail = new Thread(() -> {
                while (!completed.get()) {
                    emitNewOutput();
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "javase-tail");
            tail.setDaemon(true);
            tail.start();
        }

        /** The bootstrap's protocol lines, which are not program output. */
        private static final java.util.regex.Pattern MARKER =
                java.util.regex.Pattern.compile("\u001eWIREDROID:[A-Z_]+\\R?");

        /**
         * How much of the output file has been reported.
         *
         * <p>Shared with {@link #finish}, which does the last read itself: the
         * poller cannot be relied on for it, because finishing deletes the file
         * and the program's closing lines were being lost to that race.</p>
         */
        private final java.util.concurrent.atomic.AtomicLong emitted =
                new java.util.concurrent.atomic.AtomicLong();

        private void emitNewOutput() {
            long from = emitted.get();
            try {
                long size = outputFile.length();
                if (size <= from) return;
                byte[] buffer = new byte[(int) Math.min(size - from, 64 * 1024)];
                try (java.io.RandomAccessFile raf =
                             new java.io.RandomAccessFile(outputFile, "r")) {
                    raf.seek(from);
                    int read = raf.read(buffer);
                    if (read <= 0) return;
                    String chunk = new String(buffer, 0, read,
                            java.nio.charset.StandardCharsets.UTF_8);
                    // The bootstrap's own markers are protocol, not program output.
                    java.util.function.Consumer<String> listener = request.onOutput;
                    if (listener != null) {
                        // Strip the bootstrap's markers rather than dropping the
                        // chunk that carries them: a marker shares its read with
                        // whatever the program printed next, so discarding the
                        // whole chunk threw away real output.
                        String visible = MARKER.matcher(chunk).replaceAll("");
                        if (!visible.isEmpty()) listener.accept(visible);
                    }
                    emitted.set(from + read);
                }
            } catch (Throwable ignored) {
                // A read that fails is retried on the next poll; the final one
                // in finish() is what guarantees nothing is left unreported.
            }
        }

        void stop() {
            IBinder binder = serviceBinder;
            if (binder != null) {
                try {
                    Message message = Message.obtain(null, JavaSeRunnerService.MSG_STOP);
                    new Messenger(binder).send(message);
                } catch (Throwable ignored) {}
            }
            finish(-99, context.getString(R.string.run_stopped_by_user), true);
        }

        private void finish(int exitCode, String error, boolean processDied) {
            if (!completed.compareAndSet(false, true)) return;
            if (activeController == this) activeController = null;
            com.ccs.wiredroid.tools.compilers.ConsoleInputHolder.setRemoteSink(null);
            // Whatever the program printed between the last poll and exiting.
            emitNewOutput();
            String output = readOutput(outputFile);
            if (processDied && output.trim().isEmpty() && error == null) {
                error = context.getString(R.string.javase_process_died);
            }
            outputFile.delete();
            if (serviceBinder != null) {
                try {
                    serviceBinder.unlinkToDeath(this, 0);
                } catch (Throwable ignored) {}
            }
            if (bound) {
                try {
                    context.unbindService(this);
                } catch (Throwable ignored) {}
                bound = false;
            }
            if (callback != null) callback.onComplete(
                    new Result(exitCode, output, error, processDied));
        }
    }

    private static String readOutput(File file) {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            int remaining = MAX_OUTPUT_BYTES;
            while (remaining > 0 && (count = in.read(buffer, 0,
                    Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, count);
                remaining -= count;
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (in.read() != -1) text += "\n[output truncated by WireDroid]";
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
