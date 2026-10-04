package com.ccs.wiredroid.debug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a local variable during debugging.
 */
public class DebugVariable {
    private final String name;
    private final String type;
    private final Object value;

    public DebugVariable(String name, String type, Object value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public Object getValue() { return value; }

    public String getDisplayValue() {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof char[]) return new String((char[]) value);
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            if (len <= 5) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    Object elem = java.lang.reflect.Array.get(value, i);
                    sb.append(elem == null ? "null" : elem.toString());
                }
                sb.append("]");
                return sb.toString();
            }
            return value.getClass().getComponentType().getSimpleName() + "[" + len + "]";
        }
        String str = value.toString();
        if (str.length() > 200) str = str.substring(0, 200) + "...";
        return str;
    }

    public List<DebugVariable> getFields() {
        List<DebugVariable> fields = new ArrayList<>();
        if (value == null) return fields;
        if (value.getClass().isPrimitive() || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return fields;
        }
        try {
            Class<?> clazz = value.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(value);
                    fields.add(new DebugVariable(f.getName(), f.getType().getSimpleName(), val));
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
        return fields;
    }

    @Override
    public String toString() {
        return type + " " + name + " = " + getDisplayValue();
    }
}
