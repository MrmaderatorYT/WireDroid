package com.ccs.wiredroid.debug;

/**
 * A watch expression that is evaluated at each breakpoint.
 */
public class WatchExpression {
    private final String expression;
    private String lastResult;
    private boolean isError;

    public WatchExpression(String expression) {
        this.expression = expression;
        this.lastResult = "";
        this.isError = false;
    }

    public String getExpression() { return expression; }
    public String getLastResult() { return lastResult; }
    public boolean isError() { return isError; }

    public void setResult(String result, boolean error) {
        this.lastResult = result;
        this.isError = error;
    }

    /**
     * Evaluate the watch expression against current local variables.
     * Simple expression evaluator: supports field access, method calls on locals.
     */
    public String evaluate(DebuggerController.DebugEvent event) {
        try {
            if (event == null || event.variables == null) {
                setResult("No context", true);
                return lastResult;
            }

            String expr = expression.trim();

            // Direct variable lookup
            for (DebugVariable v : event.variables) {
                if (v.getName().equals(expr)) {
                    setResult(v.getDisplayValue(), false);
                    return lastResult;
                }
            }

            // Try simple field access: obj.field
            int dotIdx = expr.indexOf('.');
            if (dotIdx > 0) {
                String objName = expr.substring(0, dotIdx);
                String fieldName = expr.substring(dotIdx + 1);
                for (DebugVariable v : event.variables) {
                    if (v.getName().equals(objName) && v.getValue() != null) {
                        Object obj = v.getValue();
                        try {
                            java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            setResult(formatValue(val), false);
                            return lastResult;
                        } catch (NoSuchFieldException e) {
                            // Try getter
                            String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                            try {
                                java.lang.reflect.Method m = obj.getClass().getMethod(getter);
                                Object val = m.invoke(obj);
                                setResult(formatValue(val), false);
                                return lastResult;
                            } catch (Exception ex) {
                                setResult("No field '" + fieldName + "' on " + obj.getClass().getSimpleName(), true);
                                return lastResult;
                            }
                        }
                    }
                }
            }

            // Try as a method call: method()
            if (expr.endsWith("()")) {
                String methodName = expr.substring(0, expr.length() - 2);
                // Look for the object in variables (this, or first non-primitive)
                for (DebugVariable v : event.variables) {
                    if (v.getValue() != null && !isPrimitive(v.getType())) {
                        try {
                            java.lang.reflect.Method m = v.getValue().getClass().getMethod(methodName);
                            Object val = m.invoke(v.getValue());
                            setResult(formatValue(val), false);
                            return lastResult;
                        } catch (Exception ignored) {}
                    }
                }
            }

            setResult("Cannot evaluate: " + expr, true);
            return lastResult;
        } catch (Exception e) {
            setResult("Error: " + e.getMessage(), true);
            return lastResult;
        }
    }

    private static String formatValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + val + "\"";
        return val.toString();
    }

    private static boolean isPrimitive(String type) {
        return "int".equals(type) || "long".equals(type) || "double".equals(type)
                || "float".equals(type) || "boolean".equals(type) || "byte".equals(type)
                || "short".equals(type) || "char".equals(type);
    }
}
