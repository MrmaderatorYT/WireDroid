package com.ccs.wiredroid.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static bridge called by instrumented bytecode at method entry/exit.
 * Collects timing data for CPU profiling.
 */
public final class ProfilerBridge {

    private static volatile boolean active = false;
    private static final ConcurrentHashMap<String, MethodProfile> profiles = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> entryTime = new ThreadLocal<>();

    private ProfilerBridge() {}

    public static void start() {
        active = true;
        profiles.clear();
    }

    public static void stop() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void reset() {
        profiles.clear();
    }

    /**
     * Called at method entry. Records the start time.
     */
    public static void methodEnter(String className, String methodName, String methodDesc) {
        if (!active) return;
        entryTime.set(System.nanoTime());
    }

    /**
     * Called at method exit (RETURN instructions). Records elapsed time.
     */
    public static void methodExit(String className, String methodName, String methodDesc) {
        if (!active) return;
        Long start = entryTime.get();
        if (start == null) return;
        long elapsed = System.nanoTime() - start;
        entryTime.remove();

        String key = className + "." + methodName + methodDesc;
        MethodProfile mp = profiles.computeIfAbsent(key, k -> new MethodProfile(className, methodName, methodDesc));
        mp.totalTime.addAndGet(elapsed);
        mp.invocationCount.incrementAndGet();
        if (elapsed > mp.maxTime.get()) {
            mp.maxTime.set(elapsed);
        }
        long min;
        do {
            min = mp.minTime.get();
        } while (elapsed < min && !mp.minTime.compareAndSet(min, elapsed));
    }

    /**
     * Called at method exit via ATHROW. Records elapsed time for exceptional exits.
     */
    public static void methodExitException(String className, String methodName, String methodDesc) {
        methodExit(className, methodName, methodDesc);
    }

    public static List<MethodProfile> getResults() {
        return new ArrayList<>(profiles.values());
    }

    public static long getTotalTime() {
        long total = 0;
        for (MethodProfile mp : profiles.values()) {
            total += mp.totalTime.get();
        }
        return total;
    }

    public static void setSamplingInterval(long nanos) {
        // Reserved for future sampling profiler support
    }

    public static class MethodProfile {
        public final String className;
        public final String methodName;
        public final String methodDesc;
        public final AtomicLong totalTime = new AtomicLong(0);
        public final AtomicLong invocationCount = new AtomicLong(0);
        public final AtomicLong maxTime = new AtomicLong(0);
        public final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);

        public MethodProfile(String className, String methodName, String methodDesc) {
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        public String shortSignature() {
            String simpleName = className;
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) simpleName = className.substring(lastDot + 1);
            return simpleName + "." + methodName;
        }

        public String fullSignature() {
            return className + "." + methodName + methodDesc;
        }

        public double getTotalTimeMs() {
            return totalTime.get() / 1_000_000.0;
        }

        public double getAvgTimeMs() {
            long count = invocationCount.get();
            return count > 0 ? (totalTime.get() / 1_000_000.0) / count : 0;
        }

        public double getMaxTimeMs() {
            return maxTime.get() / 1_000_000.0;
        }
    }
}
