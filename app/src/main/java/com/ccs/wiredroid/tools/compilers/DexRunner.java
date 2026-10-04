package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.util.Log;

import com.ccs.wiredroid.util.AppPreferences;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import dalvik.system.DexClassLoader;

/**
 * Handles running compiled DEX classes in-process with stream interception and argument passing.
 */
public final class DexRunner {

    /**
     * Held for as long as a program owns {@code System.out}/{@code in}.
     *
     * <p>A lock rather than a monitor because a program that ignores
     * {@link Thread#interrupt()} — a bare {@code while (true) {}} — survives Stop
     * and keeps holding it. {@code synchronized} would then park the user's next
     * Run here forever with nothing on screen; a bounded {@link #tryLock} can at
     * least say what happened.</p>
     */
    private static final ReentrantLock SYSTEM_STREAM_LOCK = new ReentrantLock();

    /** How long a new run waits for a previous, unstoppable one to let go. */
    private static final long STREAM_LOCK_TIMEOUT_SECONDS = 3;

    private static volatile Thread activeRunThread = null;

    private DexRunner() {}

    /**
     * Unblocks a program parked on {@code System.in} so it can notice the stop.
     *
     * <p>The interrupt itself comes from {@link RunCancellation}, which owns
     * every thread in the run rather than just this one.</p>
     */
    public static void stop() {
        Thread t = activeRunThread;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {}
        }
        ConsoleInputHolder.end();
    }

    private static boolean cancelled() {
        return RunCancellation.isStopRequested();
    }

    private static String stoppedMessage(Context context) {
        try {
            return context.getString(com.ccs.wiredroid.R.string.run_stopped_by_user);
        } catch (Throwable ignored) {
            return "Execution stopped by user";
        }
    }

    public static void runDexMain(Context context, File jniLibsDir, File dexDir, String className,
                                  ProjectCompiler.Callback callback) {
        // Not reset here: Stop pressed while the project was still compiling has
        // to survive into this phase, or the program the user cancelled launches
        // anyway the moment the build finishes.
        if (cancelled()) {
            ProjectCompiler.postResult(callback, stoppedMessage(context));
            return;
        }
        activeRunThread = Thread.currentThread();
        try {
            File dexFile = new File(dexDir, "classes.dex");
            if (!dexFile.exists()) {
                ProjectCompiler.postResult(callback, "Error: classes.dex not found in " + dexDir.getAbsolutePath());
                return;
            }

            // Android 14+ (API 34+) requires read-only dex file
            File secureDexDir = new File(context.getDir("dex", Context.MODE_PRIVATE), "run_" + System.currentTimeMillis());
            secureDexDir.mkdirs();
            File secureDex = new File(secureDexDir, "classes.dex");
            try (FileInputStream fis = new FileInputStream(dexFile);
                 FileOutputStream fos = new FileOutputStream(secureDex)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
            secureDex.setReadOnly();

            File nativeLibDir = jniLibsDir != null ? jniLibsDir : new File(context.getCacheDir(), "jni_libs");
            if (!nativeLibDir.exists()) nativeLibDir.mkdirs();

            File optDir = new File(secureDexDir, "opt");
            if (optDir.exists()) deleteRecursive(optDir);
            optDir.mkdirs();

            DexClassLoader cl = new DexClassLoader(
                    secureDex.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    nativeLibDir.getAbsolutePath(),
                    context.getClassLoader()
            );
            Class<?> cls = cl.loadClass(className);
            Method main;
            boolean takesArgs = true;
            try {
                main = cls.getMethod("main", String[].class);
            } catch (NoSuchMethodException e) {
                try {
                    main = cls.getMethod("main");
                    takesArgs = false;
                } catch (NoSuchMethodException e2) {
                    throw new NoSuchMethodException("No main(String[]) or main() method found in " + className);
                }
            }
            final RunConfig runConfig = RunConfig.from(context);
            final boolean verbose = new AppPreferences(context).isVerboseLoggingEnabled();
            ByteArrayOutputStream execOut = new ByteArrayOutputStream();
            OutputStream interceptor = new OutputStream() {
                private StringBuilder line = new StringBuilder();
                /**
                 * Written but not yet handed to the console.
                 *
                 * <p>Sent on every newline, and on flush for a prompt printed
                 * without one — {@code System.out.print("Name: ")} is exactly
                 * the case that must reach the screen before the program blocks
                 * waiting for the answer.</p>
                 */
                private final StringBuilder pending = new StringBuilder();

                private void take(char c) {
                    pending.append(c);
                    if (c == '\n') emit();
                }

                private void emit() {
                    if (pending.length() == 0) return;
                    ProjectCompiler.postOutput(callback, pending.toString());
                    pending.setLength(0);
                }

                @Override
                public void write(int b) throws IOException {
                    execOut.write(b);
                    take((char) b);
                    if (verbose) {
                        if (b == '\n') {
                            Log.d("WireDroidProgram", line.toString());
                            line.setLength(0);
                        } else if (b != '\r') {
                            line.append((char) b);
                        }
                    }
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    execOut.write(b, off, len);
                    for (int i = off; i < off + len; i++) take((char) b[i]);
                    if (verbose) {
                        for (int i = off; i < off + len; i++) {
                            if (b[i] == '\n') {
                                Log.d("WireDroidProgram", line.toString());
                                line.setLength(0);
                            } else if (b[i] != '\r') {
                                line.append((char) b[i]);
                            }
                        }
                    }
                }
                @Override
                public void flush() throws IOException {
                    execOut.flush();
                    emit();
                    if (verbose && line.length() > 0) {
                        Log.d("WireDroidProgram", line.toString());
                        line.setLength(0);
                    }
                }
            };
            PrintStream ps = new PrintStream(interceptor, true);
            if (!SYSTEM_STREAM_LOCK.tryLock(STREAM_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // A previous program is still on the console and did not answer
                // Stop. Saying so beats hanging here with an empty panel.
                ProjectCompiler.postResult(callback,
                        context.getString(com.ccs.wiredroid.R.string.run_previous_still_running));
                return;
            }
            try {
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;
                java.io.InputStream oldIn = System.in;
                // Without this the program reads the app's own stdin, which is
                // empty — Scanner.nextLine() threw NoSuchElementException before
                // the user had any chance to type.
                ConsoleInput consoleIn = ConsoleInputHolder.begin();
                consoleIn.setBeforeBlock(ps::flush);
                System.setOut(ps);
                System.setErr(ps);
                System.setIn(consoleIn);
                Runnable restoreEnv = runConfig.applyEnv();
                try {
                    if (takesArgs) {
                        main.invoke(null, new Object[]{runConfig.args});
                    } else {
                        main.invoke(null);
                    }
                    ps.flush();
                    if (cancelled()) {
                        ProjectCompiler.postResult(callback, stoppedMessage(context));
                    } else {
                        ProjectCompiler.postResult(callback, execOut.toString("UTF-8"));
                    }
                } catch (Throwable e) {
                    if (cancelled() || (e instanceof InterruptedException)
                            || (e.getCause() instanceof InterruptedException)) {
                        ProjectCompiler.postResult(callback, stoppedMessage(context));
                    } else {
                        e.printStackTrace(ps);
                        ps.flush();
                        ProjectCompiler.postResult(callback, "Execution Exception:\n" + execOut.toString("UTF-8"));
                    }
                } finally {
                    restoreEnv.run();
                    System.setOut(oldOut);
                    System.setErr(oldErr);
                    System.setIn(oldIn);
                    ConsoleInputHolder.end();
                    ps.close();
                }
            } finally {
                SYSTEM_STREAM_LOCK.unlock();
            }
        } catch (Throwable e) {
            if (cancelled()) {
                ProjectCompiler.postResult(callback, stoppedMessage(context));
            } else {
                ProjectCompiler.postResult(callback, "System Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            }
        } finally {
            if (activeRunThread == Thread.currentThread()) {
                activeRunThread = null;
            }
        }
    }

    public static void debugRunDex(Context context, String className, File dexDir,
                                   File debugCacheDir, File jniLibsDir, ProjectCompiler.Callback callback) {
        try {
            File dexFile = new File(dexDir, "classes.dex");
            if (!dexFile.exists()) {
                ProjectCompiler.postResult(callback, "Error: classes.dex not found in " + dexDir.getAbsolutePath());
                return;
            }

            File secureDexDir = new File(context.getDir("dex", Context.MODE_PRIVATE), "debug_" + System.currentTimeMillis());
            secureDexDir.mkdirs();
            File secureDex = new File(secureDexDir, "classes.dex");
            try (FileInputStream fis = new FileInputStream(dexFile);
                 FileOutputStream fos = new FileOutputStream(secureDex)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
            secureDex.setReadOnly();

            File optDir = new File(secureDexDir, "opt");
            if (optDir.exists()) deleteRecursive(optDir);
            optDir.mkdirs();

            File nativeLibDir;
            if (jniLibsDir != null && jniLibsDir.exists()) {
                nativeLibDir = jniLibsDir;
            } else {
                nativeLibDir = NativeBuildHelper.findLatestJniLibsDir(context);
                if (nativeLibDir == null) {
                    nativeLibDir = new File(context.getCacheDir(), "jni_libs");
                }
            }
            if (!nativeLibDir.exists()) nativeLibDir.mkdirs();

            DexClassLoader cl = new DexClassLoader(
                    secureDex.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    nativeLibDir.getAbsolutePath(),
                    context.getClassLoader()
            );

            Class<?> cls = cl.loadClass(className);
            Method main;
            boolean takesArgs = true;
            try {
                main = cls.getMethod("main", String[].class);
            } catch (NoSuchMethodException e) {
                try {
                    main = cls.getMethod("main");
                    takesArgs = false;
                } catch (NoSuchMethodException e2) {
                    throw new NoSuchMethodException("No main(String[]) or main() method found in " + className);
                }
            }
            final RunConfig runConfig = RunConfig.from(context);

            ByteArrayOutputStream execOut = new ByteArrayOutputStream();
            OutputStream interceptor = new OutputStream() {
                private StringBuilder line = new StringBuilder();
                @Override
                public void write(int b) throws IOException {
                    execOut.write(b);
                    if (b == '\n') {
                        line.setLength(0);
                    } else if (b != '\r') {
                        line.append((char) b);
                    }
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    execOut.write(b, off, len);
                    for (int i = off; i < off + len; i++) {
                        if (b[i] == '\n') {
                            line.setLength(0);
                        } else if (b[i] != '\r') {
                            line.append((char) b[i]);
                        }
                    }
                }
                @Override
                public void flush() throws IOException {
                    execOut.flush();
                    if (line.length() > 0) {
                        line.setLength(0);
                    }
                }
            };
            PrintStream ps = new PrintStream(interceptor, true);
            if (!SYSTEM_STREAM_LOCK.tryLock(STREAM_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ProjectCompiler.postResult(callback,
                        context.getString(com.ccs.wiredroid.R.string.run_previous_still_running));
                return;
            }
            try {
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;
                System.setOut(ps);
                System.setErr(ps);
                Runnable restoreEnv = runConfig.applyEnv();
                try {
                    if (takesArgs) {
                        main.invoke(null, new Object[]{runConfig.args});
                    } else {
                        main.invoke(null);
                    }
                    ps.flush();
                    ProjectCompiler.postResult(callback, execOut.toString("UTF-8"));
                } catch (Throwable e) {
                    Log.e("WireDroidDebug", "Debug execution exception", e);
                    e.printStackTrace(ps);
                    ps.flush();
                    ProjectCompiler.postResult(callback, "Execution Exception:\n" + execOut.toString("UTF-8"));
                } finally {
                    restoreEnv.run();
                    System.setOut(oldOut);
                    System.setErr(oldErr);
                    ps.close();
                }
            } finally {
                SYSTEM_STREAM_LOCK.unlock();
            }
        } catch (Throwable e) {
            ProjectCompiler.postResult(callback, "System Error: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }

    public static String findMainClass(List<Path> classes) {
        for (Path c : classes) {
            try {
                ClassReader cr = new ClassReader(Files.readAllBytes(c));
                final String[] foundMain = new String[1];
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ("main".equals(name)
                                && ("([Ljava/lang/String;)V".equals(descriptor) || "()V".equals(descriptor))
                                && (access & Opcodes.ACC_STATIC) != 0
                                && (access & Opcodes.ACC_PUBLIC) != 0) {
                            foundMain[0] = cr.getClassName().replace('/', '.');
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
                if (foundMain[0] != null) return foundMain[0];
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }
}
