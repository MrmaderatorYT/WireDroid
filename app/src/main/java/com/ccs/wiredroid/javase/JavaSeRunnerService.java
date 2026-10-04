package com.ccs.wiredroid.javase;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hosts HotSpot in the dedicated {@code :javase} Android process. */
public final class JavaSeRunnerService extends Service {

    static final int MSG_RUN = 1;
    static final int MSG_RESULT = 2;
    /** A line the user typed into the console, on its way to the program's stdin. */
    static final int MSG_STDIN = 3;
    static final int MSG_STOP = 4;
    static final String KEY_STDIN = "stdin";
    static final String KEY_RUNTIME = "runtime";
    static final String KEY_WORKING_DIRECTORY = "working_directory";
    static final String KEY_ARGUMENTS = "arguments";
    static final String KEY_ENVIRONMENT = "environment";
    static final String KEY_OUTPUT = "output";
    static final String KEY_EXIT_CODE = "exit_code";
    static final String KEY_ERROR = "error";

    private final AtomicBoolean running = new AtomicBoolean();
    private final Messenger messenger = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleMessage));

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean handleMessage(Message message) {
        if (message.what == MSG_STOP) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if (message.what == MSG_STDIN) {
            // The pipe's write end lives in this process, not the app's, so the
            // text has to cross the binder to reach it.
            String line = message.getData().getString(KEY_STDIN);
            if (line != null) {
                JavaSeNativeLauncher.writeStdin(
                        line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return true;
        }
        if (message.what != MSG_RUN) return false;
        Messenger reply = message.replyTo;
        Bundle data = message.getData();
        if (!running.compareAndSet(false, true)) {
            send(reply, -20, "The Java SE process is already running");
            return true;
        }
        new Thread(() -> runJvm(data, reply), "embedded-jvm").start();
        return true;
    }

    private void runJvm(Bundle data, Messenger reply) {
        int exitCode = -21;
        String error = null;
        try {
            ArrayList<String> args = data.getStringArrayList(KEY_ARGUMENTS);
            ArrayList<String> env = data.getStringArrayList(KEY_ENVIRONMENT);
            if (args == null || args.isEmpty()) throw new IllegalArgumentException("No JVM arguments");
            exitCode = JavaSeNativeLauncher.launch(
                    required(data, KEY_RUNTIME),
                    required(data, KEY_WORKING_DIRECTORY),
                    args.toArray(new String[0]),
                    env != null ? env.toArray(new String[0]) : new String[0],
                    required(data, KEY_OUTPUT));
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            error = throwable.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message);
        }
        send(reply, exitCode, error);

        // A HotSpot VM is not relaunched in the same ART process. Let the Binder
        // reply leave first, then discard this helper process; the editor lives on.
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "embedded-jvm-exit").start();
    }

    private static String required(Bundle data, String key) {
        String value = data.getString(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static void send(Messenger reply, int exitCode, String error) {
        if (reply == null) return;
        Message result = Message.obtain(null, MSG_RESULT);
        Bundle data = new Bundle();
        data.putInt(KEY_EXIT_CODE, exitCode);
        if (error != null) data.putString(KEY_ERROR, error);
        result.setData(data);
        try {
            reply.send(result);
        } catch (RemoteException ignored) {
            // The editor went away while the VM was running.
        }
    }
}
