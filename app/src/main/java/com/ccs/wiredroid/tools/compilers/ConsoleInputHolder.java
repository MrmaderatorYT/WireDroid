package com.ccs.wiredroid.tools.compilers;

import androidx.annotation.Nullable;

/**
 * The console input stream of the run that is happening right now.
 *
 * <p>The program runs on a background thread inside the runner, while the text
 * the user types arrives on the main thread from a view the runner knows
 * nothing about. This is the one place both can name — the same shape as
 * {@code PendingEdits}, and for the same reason.</p>
 *
 * <p>One run at a time, which is what the editor allows: starting a run while
 * one is going is blocked by {@code isRunning}.</p>
 */
public final class ConsoleInputHolder {

    private static volatile ConsoleInput current;

    /**
     * Where input goes when the program is not running in this process.
     *
     * <p>The Java SE runtime hosts the program in its own {@code :javase}
     * process, where {@code System.in} is a real file descriptor — replacing a
     * stream here would reach nothing. That runner registers a sink that puts
     * the line on the binder instead.</p>
     */
    public interface RemoteSink {
        boolean send(String line);
    }

    private static volatile RemoteSink remoteSink;

    /** Registers, or clears, the sink used while a Java SE program runs. */
    public static void setRemoteSink(RemoteSink sink) {
        remoteSink = sink;
    }

    private ConsoleInputHolder() {}

    /** Opens a stream for a run and makes it the current one. */
    public static ConsoleInput begin() {
        ConsoleInput previous = current;
        // A run that ended without closing its stream would otherwise leave a
        // reader parked on a queue nobody will ever feed.
        if (previous != null) previous.close();
        ConsoleInput fresh = new ConsoleInput();
        current = fresh;
        return fresh;
    }

    /** Ends the current run's input, releasing anything blocked on it. */
    public static void end() {
        ConsoleInput open = current;
        current = null;
        if (open != null) open.close();
    }

    /** The stream to feed, or null when nothing is running. */
    @Nullable
    public static ConsoleInput current() {
        return current;
    }

    /** Passes a line to the running program, if there is one. */
    public static boolean submit(String line) {
        RemoteSink sink = remoteSink;
        if (sink != null && sink.send(line)) return true;
        ConsoleInput open = current;
        if (open == null || open.isClosed()) return false;
        open.submitLine(line);
        return true;
    }
}
