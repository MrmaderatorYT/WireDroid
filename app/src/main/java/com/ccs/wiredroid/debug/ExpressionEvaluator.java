package com.ccs.wiredroid.debug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Evaluates arbitrary expressions against the current debug context.
 * Supports: variable access, field access, method calls, basic arithmetic,
 * string operations, null checks, instanceof, casts.
 */
public class ExpressionEvaluator {

    private final List<DebugVariable> variables;

    public ExpressionEvaluator(List<DebugVariable> variables) {
        this.variables = variables;
    }

    public EvalResult evaluate(String expression) {
        try {
            String expr = expression.trim();
            Object result = evalExpression(expr, null);
            if (result instanceof EvalResult) return (EvalResult) result;
            return new EvalResult(formatValue(result), false);
        } catch (Exception e) {
            return new EvalResult("Error: " + e.getMessage(), true);
        }
    }

    /**
     * Обчислює вираз як boolean у контексті заданих локальних змінних.
     * Для умовних брейплоінтів: будь-яка помилка → false (не паузимо).
     */
    public static boolean evaluateBoolean(String expression, java.util.List<DebugVariable> variables) {
        try {
            ExpressionEvaluator ev = new ExpressionEvaluator(variables);
            Object result = ev.evalExpression(expression.trim(), null);
            if (result instanceof EvalResult) return false; // помилка
            return ev.toBool(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private Object evalExpression(String expr, Object thisObj) {
        // Null literal
        if ("null".equals(expr)) return null;

        // Boolean literals
        if ("true".equals(expr)) return Boolean.TRUE;
        if ("false".equals(expr)) return Boolean.FALSE;

        // Integer literal
        if (expr.matches("-?\\d+")) return Integer.parseInt(expr);

        // Long literal
        if (expr.matches("-?\\d+[lL]")) return Long.parseLong(expr.substring(0, expr.length() - 1));

        // Double literal
        if (expr.matches("-?\\d+\\.\\d+[dD]?")) {
            String s = expr;
            if (s.endsWith("d") || s.endsWith("D")) s = s.substring(0, s.length() - 1);
            return Double.parseDouble(s);
        }

        // Float literal
        if (expr.matches("-?\\d+\\.\\d+[fF]")) {
            return Float.parseFloat(expr.substring(0, expr.length() - 1));
        }

        // String literal
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            return expr.substring(1, expr.length() - 1);
        }

        // Char literal
        if (expr.startsWith("'") && expr.endsWith("'") && expr.length() == 3) {
            return expr.charAt(1);
        }

        // Parenthesized expression
        if (expr.startsWith("(") && findMatchingParen(expr, 0) == expr.length() - 1) {
            return evalExpression(expr.substring(1, expr.length() - 1), thisObj);
        }

        // Binary operations: + - * / % == != < > <= >= && ||
        Object binaryResult = tryBinaryOp(expr, thisObj);
        if (binaryResult != null) {
            if (binaryResult instanceof EvalResult) return binaryResult;
            return binaryResult;
        }

        // Method call: obj.method(args) or method(args)
        if (expr.contains("(") && expr.endsWith(")")) {
            return evalMethodCall(expr, thisObj);
        }

        // Field access: obj.field or ClassName.staticField
        if (expr.contains(".")) {
            return evalFieldAccess(expr, thisObj);
        }

        // Variable lookup
        return evalVariable(expr);
    }

    private Object tryBinaryOp(String expr, Object thisObj) {
        // Find the operator at the lowest precedence level (not inside parens/strings)
        int opPos = findBinaryOp(expr);
        if (opPos < 0) return null;

        String left = expr.substring(0, opPos).trim();
        String right;
        String op;

        // Check for two-char operators
        if (opPos + 1 < expr.length() && isOpChar(expr.charAt(opPos + 1))) {
            op = expr.substring(opPos, opPos + 2);
            right = expr.substring(opPos + 2).trim();
        } else {
            op = String.valueOf(expr.charAt(opPos));
            right = expr.substring(opPos + 1).trim();
        }

        Object leftVal = evalExpression(left, thisObj);
        Object rightVal = evalExpression(right, thisObj);

        if ("+".equals(op)) {
            if (leftVal instanceof String || rightVal instanceof String) {
                return String.valueOf(leftVal) + String.valueOf(rightVal);
            }
            return toDouble(leftVal) + toDouble(rightVal);
        }
        if ("-".equals(op)) return toDouble(leftVal) - toDouble(rightVal);
        if ("*".equals(op)) return toDouble(leftVal) * toDouble(rightVal);
        if ("/".equals(op)) {
            double r = toDouble(rightVal);
            if (r == 0) return new EvalResult("Division by zero", true);
            return toDouble(leftVal) / r;
        }
        if ("%".equals(op)) return toDouble(leftVal) % toDouble(rightVal);
        if ("==".equals(op)) return java.util.Objects.deepEquals(leftVal, rightVal);
        if ("!=".equals(op)) return !java.util.Objects.deepEquals(leftVal, rightVal);
        if (">".equals(op)) return toDouble(leftVal) > toDouble(rightVal);
        if ("<".equals(op)) return toDouble(leftVal) < toDouble(rightVal);
        if (">=".equals(op)) return toDouble(leftVal) >= toDouble(rightVal);
        if ("<=".equals(op)) return toDouble(leftVal) <= toDouble(rightVal);
        if ("&&".equals(op)) return toBool(leftVal) && toBool(rightVal);
        if ("||".equals(op)) return toBool(leftVal) || toBool(rightVal);

        return null;
    }

    private int findBinaryOp(String expr) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        char lastSignificant = 0;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inString) { if (c == '"' && (i == 0 || expr.charAt(i-1) != '\\')) inString = false; continue; }
            if (inChar) { if (c == '\'' && (i == 0 || expr.charAt(i-1) != '\\')) inChar = false; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(' || c == '[') { depth++; continue; }
            if (c == ')' || c == ']') { depth--; continue; }
            if (depth > 0) continue;

            // Only consider operators that aren't part of a number or negative sign
            if (i > 0 && (c == '-' || c == '+') && (Character.isDigit(lastSignificant) || lastSignificant == ')' || lastSignificant == ']' || Character.isLetter(lastSignificant))) {
                // This is a binary operator
                return i;
            }
            if (i > 0 && (c == '*' || c == '/' || c == '%' || c == '=' || c == '!' || c == '<' || c == '>' || c == '&' || c == '|')) {
                return i;
            }

            lastSignificant = c;
        }
        return -1;
    }

    private boolean isOpChar(char c) {
        return c == '=' || c == '&' || c == '|' || c == '!' || c == '<' || c == '>';
    }

    private Object evalMethodCall(String expr, Object thisObj) {
        int parenStart = expr.indexOf('(');
        int parenEnd = findMatchingParen(expr, parenStart);
        if (parenEnd != expr.length() - 1) {
            return new EvalResult("Unexpected tokens after ')'", true);
        }

        String target = expr.substring(0, parenStart).trim();
        String argsStr = expr.substring(parenStart + 1, parenEnd).trim();
        Object[] args = parseArgs(argsStr);

        // static method: ClassName.method(args)
        if (target.contains(".")) {
            int lastDot = target.lastIndexOf('.');
            String className = target.substring(0, lastDot);
            String methodName = target.substring(lastDot + 1);

            // Try to find the class
            Class<?> clazz = findClass(className);
            if (clazz != null) {
                return callStaticMethod(clazz, methodName, args);
            }

            // Or it's obj.method()
            Object obj = evalExpression(className, thisObj);
            if (obj != null) {
                return callInstanceMethod(obj, methodName, args);
            }
            return new EvalResult("Cannot resolve: " + target, true);
        }

        // Try as instance method on 'this' or variables
        if (thisObj != null) {
            try {
                return callInstanceMethod(thisObj, target, args);
            } catch (Exception ignored) {}
        }

        for (DebugVariable v : variables) {
            if (v.getValue() != null && !isPrimitive(v.getType())) {
                try {
                    return callInstanceMethod(v.getValue(), target, args);
                } catch (Exception ignored) {}
            }
        }

        return new EvalResult("Cannot resolve method: " + target, true);
    }

    private Object callInstanceMethod(Object obj, String methodName, Object[] args) {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(obj, args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new RuntimeException("No such method: " + methodName);
    }

    private Object callStaticMethod(Class<?> clazz, String methodName, Object[] args) {
        for (Method m : clazz.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(null, args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new RuntimeException("No such static method: " + methodName);
    }

    private Object evalFieldAccess(String expr, Object thisObj) {
        int lastDot = expr.lastIndexOf('.');
        String target = expr.substring(0, lastDot).trim();
        String fieldName = expr.substring(lastDot + 1).trim();

        Object obj = evalExpression(target, thisObj);
        if (obj == null) return new EvalResult("Null pointer on " + target, true);

        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            return new EvalResult("No field '" + fieldName + "' on " + obj.getClass().getSimpleName(), true);
        }
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Object evalVariable(String name) {
        for (DebugVariable v : variables) {
            if (v.getName().equals(name)) return v.getValue();
        }
        // Check for "this"
        if ("this".equals(name)) {
            for (DebugVariable v : variables) {
                if ("this".equals(v.getName())) return v.getValue();
            }
        }
        throw new RuntimeException("Unknown variable: " + name);
    }

    private Class<?> findClass(String name) {
        // Try primitive wrappers
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("double".equals(name)) return double.class;
        if ("float".equals(name)) return float.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("String".equals(name)) return String.class;

        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            // Try with java.lang prefix
            try {
                return Class.forName("java.lang." + name);
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
    }

    private Object[] parseArgs(String argsStr) {
        if (argsStr.isEmpty()) return new Object[0];
        java.util.List<Object> args = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (inString) { if (c == '"') inString = false; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (depth == 0 && c == ',') {
                args.add(evalExpression(argsStr.substring(start, i).trim(), null));
                start = i + 1;
            }
        }
        args.add(evalExpression(argsStr.substring(start).trim(), null));
        return args.toArray();
    }

    private int findMatchingParen(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            if (s.charAt(i) == ')') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        return 0;
    }

    private boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        return o != null;
    }

    private boolean isPrimitive(String type) {
        return "int".equals(type) || "long".equals(type) || "double".equals(type)
                || "float".equals(type) || "boolean".equals(type) || "byte".equals(type)
                || "short".equals(type) || "char".equals(type);
    }

    private String formatValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + val + "\"";
        if (val instanceof Character) return "'" + val + "'";
        if (val.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(val);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(len, 10); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatValue(java.lang.reflect.Array.get(val, i)));
            }
            if (len > 10) sb.append(", ...");
            sb.append("]");
            return sb.toString();
        }
        return val.toString();
    }

    public static class EvalResult {
        public final String value;
        public final boolean isError;

        public EvalResult(String value, boolean isError) {
            this.value = value;
            this.isError = isError;
        }
    }
}
