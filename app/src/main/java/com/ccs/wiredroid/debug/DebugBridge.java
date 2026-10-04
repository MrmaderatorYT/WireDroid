package com.ccs.wiredroid.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * Static bridge called by instrumented bytecode at breakpoints.
 * Runs in the debuggee thread; communicates with DebuggerController
 * via the lock/condition + event queue pattern.
 */
public final class DebugBridge {

    public enum StepMode { NONE, STEP_OVER, STEP_INTO, STEP_OUT }

    private static final ReentrantLock lock = new ReentrantLock();
    private static final Condition canContinue = lock.newCondition();
    private static final Condition eventReady = lock.newCondition();

    private static volatile boolean paused = false;
    private static volatile boolean stopped = false;
    private static volatile StepMode stepMode = StepMode.NONE;
    private static volatile int stepTargetLine = -1;
    private static volatile String stepTargetMethod = null;
    private static volatile int stepDepth = -1;

    private static volatile String currentClassName;
    private static volatile int currentLine;
    private static volatile String currentMethod;
    private static volatile String currentMethodSig;
    private static volatile Object[] currentLocals;
    private static volatile String[] currentLocalNames;
    private static volatile String[] currentLocalTypes;
    private static volatile String currentSourceFile;

    private static volatile boolean stepping = false;

    /** key = рядок; value = умова (null/порожня = безумовний). */
    private static final Map<Integer, String> breakpoints = new ConcurrentHashMap<>();

    private DebugBridge() {}

    public static void setBreakpoints(Map<Integer, String> bp) {
        breakpoints.clear();
        if (bp != null) breakpoints.putAll(bp);
    }

    public static void addBreakpoint(int line, String condition) {
        String cond = (condition == null || condition.trim().isEmpty()) ? "" : condition;
        breakpoints.put(line, cond);
    }

    /** Зворотна сумісність — безумовний брейкпоінт. */
    public static void addBreakpoint(int line) {
        breakpoints.put(line, null);
    }

    public static void removeBreakpoint(int line) {
        breakpoints.remove(line);
    }

    public static void clearBreakpoints() {
        breakpoints.clear();
    }

    public static boolean isBreakpoint(int line) {
        return breakpoints.containsKey(line);
    }

    public static String getCondition(int line) {
        return breakpoints.get(line);
    }

    public static void resume() {
        lock.lock();
        try {
            paused = false;
            stepMode = StepMode.NONE;
            canContinue.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static void stepOver() {
        lock.lock();
        try {
            stepMode = StepMode.STEP_OVER;
            stepDepth = getStackDepth();
            paused = false;
            canContinue.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static void stepInto() {
        lock.lock();
        try {
            stepMode = StepMode.STEP_INTO;
            paused = false;
            canContinue.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static void stepOut() {
        lock.lock();
        try {
            stepMode = StepMode.STEP_OUT;
            stepDepth = getStackDepth();
            paused = false;
            canContinue.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static void stop() {
        lock.lock();
        try {
            stopped = true;
            paused = false;
            canContinue.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public static void reset() {
        lock.lock();
        try {
            stopped = false;
            paused = false;
            stepMode = StepMode.NONE;
            breakpoints.clear();
        } finally {
            lock.unlock();
        }
    }

    public static boolean isStopped() {
        return stopped;
    }

    public static boolean isPaused() {
        return paused;
    }

    private static volatile boolean inHit = false;

    /**
     * Called by instrumented bytecode at each breakpoint line.
     * Blocks the debuggee thread until resumed.
     */
    public static void hitBreakpoint(String className, String sourceFile,
                                      String methodName, String methodSig,
                                      int line, String[] localNames,
                                      String[] localTypes, Object[] localValues) {
        if (stopped || inHit) return;
        inHit = true;
        try {
            boolean isBp = breakpoints.containsKey(line);
            boolean isStep = (stepMode != StepMode.NONE);

            if (!isBp && !isStep) return;

            // Умовний брейкпоінт: паузимо лише якщо умова істинна.
            // Помилка обчислення = не паузимо (як у IntelliJ за замовчуванням).
            if (isBp && !isStep) {
                String condition = breakpoints.get(line);
                if (condition != null && !condition.trim().isEmpty()) {
                    if (!checkCondition(localNames, localTypes, localValues, condition)) {
                        return;
                    }
                }
            }

            if (isStep) {
                if (stepMode == StepMode.STEP_OVER && getStackDepth() > stepDepth) return;
                if (stepMode == StepMode.STEP_OUT && getStackDepth() >= stepDepth) return;
                if (stepMode == StepMode.STEP_OVER || stepMode == StepMode.STEP_INTO || stepMode == StepMode.STEP_OUT) {
                    stepMode = StepMode.NONE;
                }
            }

            lock.lock();
            try {
                currentClassName = className;
                currentSourceFile = sourceFile;
                currentMethod = methodName;
                currentMethodSig = methodSig;
                currentLine = line;
                currentLocalNames = localNames;
                currentLocalTypes = localTypes;
                currentLocals = localValues;

                paused = true;
                eventReady.signalAll();

                while (paused && !stopped) {
                    try {
                        canContinue.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                lock.unlock();
            }
        } finally {
            inHit = false;
        }
    }

    public static void waitForEvent() {
        lock.lock();
        try {
            while (!paused && !stopped) {
                try {
                    eventReady.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by the monitor thread after processing a breakpoint event.
     * Blocks until the user resumes/steps (i.e., paused becomes false),
     * preventing the monitor from re-reading the same event in a tight loop.
     */
    public static void waitForResume() {
        lock.lock();
        try {
            while (paused && !stopped) {
                try {
                    canContinue.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public static String getCurrentClassName() { return currentClassName; }
    public static int getCurrentLine() { return currentLine; }
    public static String getCurrentMethod() { return currentMethod; }
    public static String getCurrentMethodSig() { return currentMethodSig; }
    public static String getCurrentSourceFile() { return currentSourceFile; }
    public static Object[] getCurrentLocals() { return currentLocals; }
    public static String[] getCurrentLocalNames() { return currentLocalNames; }
    public static String[] getCurrentLocalTypes() { return currentLocalTypes; }

    private static int getStackDepth() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int depth = 0;
        for (StackTraceElement e : stack) {
            if (e.getClassName().startsWith("com.ccs.wiredroid.debug")) continue;
            if (e.getClassName().startsWith("dalvik.system")) continue;
            if (e.getClassName().startsWith("java.lang.reflect")) continue;
            depth++;
        }
        return depth;
    }

    public static StackTraceElement[] getDebugStackTrace() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        java.util.List<StackTraceElement> filtered = new java.util.ArrayList<>();
        for (StackTraceElement e : stack) {
            if (e.getClassName().startsWith("com.ccs.wiredroid.debug")) continue;
            if (e.getClassName().startsWith("dalvik.system")) continue;
            if (e.getClassName().startsWith("java.lang.reflect")) continue;
            if (e.getClassName().equals("java.lang.Thread")) continue;
            filtered.add(e);
        }
        return filtered.toArray(new StackTraceElement[0]);
    }

    /**
     * Перевіряє умову умовного брейкпоінта через {@link ExpressionEvaluator}.
     * Будує список локальних змінних з масивів, що їх передав хук інструментації,
     * й обчислює вираз як boolean. Будь-яка помилка → false (не паузимо).
     */
    private static boolean checkCondition(String[] names, String[] types,
                                          Object[] values, String condition) {
        try {
            java.util.List<DebugVariable> vars = new java.util.ArrayList<>();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    String type = (types != null && i < types.length) ? types[i] : "Object";
                    Object val = (values != null && i < values.length) ? values[i] : null;
                    // скидаємо 2-слотові top-записи (long/double займають 2 слоти в масиві)
                    if ("J".equals(type) || "D".equals(type)) i++;
                    vars.add(new DebugVariable(names[i], type, val));
                }
            }
            return ExpressionEvaluator.evaluateBoolean(condition, vars);
        } catch (Throwable t) {
            return false;
        }
    }
}
