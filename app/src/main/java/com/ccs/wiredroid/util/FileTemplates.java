package com.ccs.wiredroid.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Шаблони файлів для створення нових файлів у проєкті.
 */
public final class FileTemplates {

    public static final String KEY_CLASS = "class";
    public static final String KEY_INTERFACE = "interface";
    public static final String KEY_ENUM = "enum";
    public static final String KEY_ABSTRACT = "abstract";
    public static final String KEY_MAIN = "main";
    public static final String KEY_RECORD = "record";
    public static final String KEY_ANNOTATION = "annotation";
    public static final String KEY_TEST = "test";
    public static final String KEY_BUILDER = "builder";
    public static final String KEY_SINGLETON = "singleton";
    public static final String KEY_FACTORY = "factory";
    public static final String KEY_OBSERVER = "observer";

    private static final Map<String, String[]> templates = new LinkedHashMap<>();

    static {
        templates.put("cpp", new String[]{"C++ · .cpp", com.ccs.wiredroid.wire.WireProject.DEFAULT_CODE});
        templates.put("c", new String[]{"C · .c", "// %s\n"});
        templates.put("h", new String[]{"Header · .h", "#pragma once\n\n// %s\n"});
        templates.put("hpp", new String[]{"C++ header · .hpp", "#pragma once\n\n// %s\n"});
        templates.put("ino", new String[]{"Arduino sketch · .ino", com.ccs.wiredroid.wire.WireProject.DEFAULT_CODE});
    }

    private FileTemplates() {}

    /** Повертає всі шаблони: key → [displayName, templateCode]. */
    public static Map<String, String[]> getAll() {
        return new LinkedHashMap<>(templates);
    }

    /** Повертає шаблон за ключем або null. */
    public static String[] get(String key) {
        return templates.get(key);
    }

    /** Повертає список назв шаблонів. */
    public static String[] getDisplayNames() {
        String[] names = new String[templates.size()];
        int i = 0;
        for (String[] v : templates.values()) {
            names[i++] = v[0];
        }
        return names;
    }

    /** Повертає ключі шаблонів у порядку. */
    public static String[] getKeys() {
        return templates.keySet().toArray(new String[0]);
    }

    /** Форматує шаблон під ім'я класу. */
    public static String format(String templateCode, String className) {
        if (templateCode == null) return "";
        // For singleton/builder/factory — replace %s with className multiple times
        String result = templateCode;
        while (result.contains("%s")) {
            result = result.replaceFirst("%s", className);
        }
        return result;
    }
}
