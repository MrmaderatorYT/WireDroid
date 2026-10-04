package com.ccs.wiredroid.tools.compilers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place that knows whether the user has asked the current run to stop.
 *
 * <p>Stopping a program on Android cannot be a single kill: the work is spread
 * across a build phase (ECJ, D8, dependency resolution), a test phase (JUnit
 * invoked reflectively) and an execution phase (ART or the {@code :javase}
 * process). Only the last of those can be terminated outright. The other two
 * have to notice and unwind, which is what {@link #isStopRequested()} is for —
 * every long phase checks it at its own boundaries.</p>
 *
 * <p>The generation counter covers what interrupting cannot. A thread stuck in
 * a loop that ignores interrupts keeps running after Stop, and its output would
 * otherwise land in the console of whatever the user does next. Every callback
 * captures the generation it was created in and drops anything posted after
 * that generation has passed, so a run the user walked away from stays silent.</p>
 */
public final class RunCancellation {

    private static final AtomicLong generation = new AtomicLong(1);
    private static volatile boolean stopRequested = false;
    private static final Set<Thread> workers =
            ConcurrentHashMap.newKeySet();

    private RunCancellation() {}

    /** Called once per run, before any work is queued. */
    public static long beginRun() {
        stopRequested = false;
        return generation.incrementAndGet();
    }

    /** The generation a callback should compare itself against. */
    public static long current() {
        return generation.get();
    }

    public static boolean isCurrent(long capturedGeneration) {
        return capturedGeneration == generation.get();
    }

    /**
     * Ask every phase of the current run to stop.
     *
     * <p>Bumping the generation is what makes this safe to call even when a
     * phase cannot actually be stopped: the work may continue, but nothing it
     * says reaches the screen any more.</p>
     */
    public static void requestStop() {
        stopRequested = true;
        generation.incrementAndGet();
        for (Thread worker : workers) {
            try {
                worker.interrupt();
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * A thread that counts as part of the current run, so Stop can interrupt it.
     *
     * <p>Registration is by construction rather than by a call inside the body:
     * a phase that forgot the call would be silently unstoppable, which is the
     * bug this class exists to remove.</p>
     */
    public static Thread newWorker(Runnable body) {
        return newWorker(body, null);
    }

    /** As {@link #newWorker(Runnable)}, keeping a name worth reading in a stack dump. */
    public static Thread newWorker(Runnable body, String name) {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        if (monitor != null) monitor.taskQueued();
        Thread thread = new Thread(() -> {
            Thread self = Thread.currentThread();
            workers.add(self);
            if (monitor != null) monitor.taskStarted();
            try {
                body.run();
            } finally {
                workers.remove(self);
                if (monitor != null) monitor.taskFinished();
            }
        });
        if (name != null) thread.setName(name);
        return thread;
    }
}
