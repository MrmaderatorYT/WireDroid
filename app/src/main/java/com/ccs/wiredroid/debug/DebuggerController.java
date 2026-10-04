package com.ccs.wiredroid.debug;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages a debug session: compilation with instrumentation, launching,
 * and receiving debug events from DebugBridge.
 */
public class DebuggerController {

    public interface DebugListener {
        void onBreakpointHit(DebugEvent event);
        void onDebugOutput(String text);
        void onDebugError(String text);
        void onDebugStarted();
        void onDebugEnded();
    }

    public static class DebugEvent {
        public final String className;
        public final String sourceFile;
        public final String methodName;
        public final String methodSig;
        public final int line;
        public final List<DebugVariable> variables;
        public final StackTraceElement[] callStack;

        public DebugEvent(String className, String sourceFile, String methodName,
                          String methodSig, int line, List<DebugVariable> variables,
                          StackTraceElement[] callStack) {
            this.className = className;
            this.sourceFile = sourceFile;
            this.methodName = methodName;
            this.methodSig = methodSig;
            this.line = line;
            this.variables = variables;
            this.callStack = callStack;
        }
    }

    private static DebuggerController instance;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<DebugListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean debugging = false;
    private Thread debugThread;
    private Thread monitorThread;
    /** key = 1-індексований рядок; value = умова (порожня = безумовний брейкпоінт).
     *  Note: ConcurrentHashMap does NOT allow null values, so we use "" instead. */
    private final Map<Integer, String> breakpoints = new ConcurrentHashMap<>();

    public static synchronized DebuggerController getInstance() {
        if (instance == null) instance = new DebuggerController();
        return instance;
    }

    private DebuggerController() {}

    public void addListener(DebugListener l) { listeners.add(l); }
    public void removeListener(DebugListener l) { listeners.remove(l); }

    public boolean isDebugging() { return debugging; }

    public void setBreakpoints(Map<Integer, String> bp) {
        breakpoints.clear();
        if (bp != null) {
            for (Map.Entry<Integer, String> e : bp.entrySet()) {
                // ConcurrentHashMap does not allow null values; normalize to ""
                String val = (e.getValue() != null) ? e.getValue() : "";
                breakpoints.put(e.getKey(), val);
            }
        }
        DebugBridge.setBreakpoints(breakpoints);
    }

    public void toggleBreakpoint(int line) {
        if (breakpoints.containsKey(line)) {
            breakpoints.remove(line);
            DebugBridge.removeBreakpoint(line);
        } else {
            breakpoints.put(line, "");
            DebugBridge.addBreakpoint(line, null);
        }
    }

    /** Встановлює/замінює брейкпоінт з умовою (null/empty = безумовний). */
    public void setBreakpoint(int line, String condition) {
        String cond = (condition != null && !condition.trim().isEmpty()) ? condition.trim() : "";
        breakpoints.put(line, cond);
        DebugBridge.addBreakpoint(line, cond.isEmpty() ? null : cond);
    }

    public boolean hasBreakpoint(int line) {
        return breakpoints.containsKey(line);
    }

    public String getBreakpointCondition(int line) {
        return breakpoints.get(line);
    }

    public Map<Integer, String> getBreakpoints() {
        return Collections.unmodifiableMap(breakpoints);
    }

    /** Рядки, де брейкпоінт має непорожню умову (для жовтих крапок у gutter). */
    public java.util.Set<Integer> getConditionalBreakpointLines() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (Map.Entry<Integer, String> e : breakpoints.entrySet()) {
            if (e.getValue() != null && !e.getValue().trim().isEmpty()) out.add(e.getKey());
        }
        return out;
    }

    public void startDebug(String sourceCode, String className, File classDir,
                           File dexDir, File androidJar, String javaTarget,
                           Context context, ClassLoader parentCl, File jniLibsDir) {
        if (debugging) return;
        debugging = true;
        DebugBridge.reset();
        DebugBridge.setBreakpoints(breakpoints);

        notifyStarted();

        monitorThread = new Thread(() -> {
            try {
                while (debugging) {
                    DebugBridge.waitForEvent();
                    if (!debugging) break;

                    String cn = DebugBridge.getCurrentClassName();
                    String sf = DebugBridge.getCurrentSourceFile();
                    String mn = DebugBridge.getCurrentMethod();
                    String ms = DebugBridge.getCurrentMethodSig();
                    int ln = DebugBridge.getCurrentLine();
                    Object[] locals = DebugBridge.getCurrentLocals();
                    String[] names = DebugBridge.getCurrentLocalNames();
                    String[] types = DebugBridge.getCurrentLocalTypes();
                    StackTraceElement[] stack = DebugBridge.getDebugStackTrace();

                    List<DebugVariable> vars = new ArrayList<>();
                    if (names != null && locals != null) {
                        for (int i = 0; i < names.length; i++) {
                            String name = (i < names.length) ? names[i] : "arg" + i;
                            String type = (types != null && i < types.length) ? types[i] : "unknown";
                            Object val = (i < locals.length) ? locals[i] : null;
                            vars.add(new DebugVariable(name, type, val));
                        }
                    }

                    DebugEvent event = new DebugEvent(cn, sf, mn, ms, ln, vars, stack);
                    uiHandler.post(() -> {
                        for (DebugListener l : listeners) {
                            l.onBreakpointHit(event);
                        }
                    });

                    DebugBridge.waitForResume();
                }
            } catch (Exception e) {
                uiHandler.post(() -> {
                    for (DebugListener l : listeners) {
                        l.onDebugError("Debug monitor error: " + e.getMessage());
                    }
                });
            } finally {
                debugging = false;
                uiHandler.post(() -> {
                    for (DebugListener l : listeners) {
                        l.onDebugEnded();
                    }
                });
            }
        }, "DebugMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void stopDebug() {
        DebugBridge.stop();
        debugging = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }

    public void resume() {
        DebugBridge.resume();
    }
    public void stepOver() {
        DebugBridge.stepOver();
    }
    public void stepInto() {
        DebugBridge.stepInto();
    }
    public void stepOut() {
        DebugBridge.stepOut();
    }

    public boolean isPaused() { return DebugBridge.isPaused(); }

    private void notifyStarted() {
        uiHandler.post(() -> {
            for (DebugListener l : listeners) {
                l.onDebugStarted();
            }
        });
    }
}
