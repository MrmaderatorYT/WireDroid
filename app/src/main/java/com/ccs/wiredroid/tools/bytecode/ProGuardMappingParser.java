package com.ccs.wiredroid.tools.bytecode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * ProGuard/R8 mapping file parser.
 * Reads mapping.txt and builds reverse maps for deobfuscation.
 *
 * Mapping file format:
 * <pre>
 * android.support.v4.app.ActivityCompat -> a.b.c:
 *     void someMethod() -> a
 *     java.lang.String anotherMethod(int) -> b
 *     static final java.lang.String FIELD -> c
 * </pre>
 */
public final class ProGuardMappingParser {

    private final Map<String, String> classMapping = new HashMap<>();       // obfuscated -> original
    private final Map<String, String> methodMapping = new HashMap<>();      // "owner.obfName:desc" -> originalName
    private final Map<String, String> fieldMapping = new HashMap<>();       // "owner.obfName" -> originalName
    private final Map<String, String> packageMapping = new HashMap<>();     // obfuscated package -> original

    /** Parsed class mappings: original -> obfuscated */
    private final Map<String, String> reverseClassMapping = new HashMap<>();

    private int classCount;
    private int methodCount;
    private int fieldCount;

    /**
     * Parse a mapping file from a File.
     */
    public void parse(File mappingFile) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(mappingFile))) {
            parse(br);
        }
    }

    /**
     * Parse a mapping file from an InputStream.
     */
    public void parse(InputStream mappingStream) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(mappingStream))) {
            parse(br);
        }
    }

    private void parse(BufferedReader reader) throws IOException {
        String currentOriginalClass = null;
        String currentObfuscatedClass = null;
        String line;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Class mapping line: "original -> obfuscated:"
            if (line.endsWith(":")) {
                line = line.substring(0, line.length() - 1).trim();
                int arrow = line.indexOf(" -> ");
                if (arrow > 0) {
                    currentOriginalClass = line.substring(0, arrow).trim();
                    currentObfuscatedClass = line.substring(arrow + 4).trim();
                    classMapping.put(currentObfuscatedClass, currentOriginalClass);
                    reverseClassMapping.put(currentOriginalClass, currentObfuscatedClass);
                    classCount++;
                }
                continue;
            }

            // Member mapping line: "returnType originalName(params) -> obfuscatedName"
            if (currentOriginalClass != null && currentObfuscatedClass != null) {
                int arrow = line.indexOf(" -> ");
                if (arrow > 0) {
                    String memberPart = line.substring(0, arrow).trim();
                    String obfuscatedName = line.substring(arrow + 4).trim();

                    if (memberPart.contains("(")) {
                        // Method mapping
                        int space = memberPart.lastIndexOf(' ');
                        String methodName;
                        String desc;
                        if (space > 0) {
                            methodName = memberPart.substring(space + 1);
                            // Extract just the method name without params
                            int paren = methodName.indexOf('(');
                            if (paren > 0) methodName = methodName.substring(0, paren);
                            desc = memberPart.substring(space);
                        } else {
                            int paren = memberPart.indexOf('(');
                            methodName = paren > 0 ? memberPart.substring(0, paren) : memberPart;
                            desc = memberPart.substring(paren);
                        }

                        String key = currentObfuscatedClass + "." + methodName + desc;
                        methodMapping.put(key, currentOriginalClass + "." + methodName);
                        methodCount++;
                    } else {
                        // Field mapping
                        String key = currentObfuscatedClass + "." + memberPart;
                        fieldMapping.put(key, currentOriginalClass + "." + memberPart);
                        fieldCount++;
                    }
                }
            }
        }
    }

    /**
     * Get original class name from obfuscated name.
     * @param obfuscatedClass e.g. "a.b.c"
     * @return original class name or null
     */
    public String deobfuscateClass(String obfuscatedClass) {
        if (obfuscatedClass == null) return null;
        String result = classMapping.get(obfuscatedClass);
        if (result != null) return result;
        // Try without package
        int lastDot = obfuscatedClass.lastIndexOf('.');
        if (lastDot > 0) {
            return classMapping.get(obfuscatedClass.substring(lastDot + 1));
        }
        return null;
    }

    /**
     * Get original method name.
     * @param obfuscatedOwner obfuscated class name
     * @param obfuscatedMethod obfuscated method name
     * @param desc method descriptor
     * @return original method name or null
     */
    public String deobfuscateMethod(String obfuscatedOwner, String obfuscatedMethod, String desc) {
        if (obfuscatedOwner == null || obfuscatedMethod == null) return null;

        // First try to get the full original class name
        String originalClass = deobfuscateClass(obfuscatedOwner);
        if (originalClass == null) originalClass = obfuscatedOwner;

        String key = obfuscatedOwner + "." + obfuscatedMethod + desc;
        String result = methodMapping.get(key);
        if (result != null) {
            // Extract just the method name from "com.example.OriginalClass.originalMethod"
            int lastDot = result.lastIndexOf('.');
            return lastDot > 0 ? result.substring(lastDot + 1) : result;
        }
        return null;
    }

    /**
     * Get original field name.
     */
    public String deobfuscateField(String obfuscatedOwner, String obfuscatedField) {
        if (obfuscatedOwner == null || obfuscatedField == null) return null;

        String key = obfuscatedOwner + "." + obfuscatedField;
        String result = fieldMapping.get(key);
        if (result != null) {
            int lastDot = result.lastIndexOf('.');
            return lastDot > 0 ? result.substring(lastDot + 1) : result;
        }
        return null;
    }

    /**
     * Get full original class name from obfuscated name.
     */
    public String getOriginalClassName(String obfuscatedClass) {
        return deobfuscateClass(obfuscatedClass);
    }

    /**
     * Get full original method signature.
     */
    public String getOriginalMethodSignature(String obfuscatedOwner, String obfuscatedMethod, String desc) {
        String originalClass = deobfuscateClass(obfuscatedOwner);
        if (originalClass == null) originalClass = obfuscatedOwner;

        String key = obfuscatedOwner + "." + obfuscatedMethod + desc;
        String result = methodMapping.get(key);
        if (result != null) return result;

        return originalClass + "." + obfuscatedMethod;
    }

    /**
     * Check if this mapping has any data.
     */
    public boolean isEmpty() {
        return classMapping.isEmpty();
    }

    public int getClassCount() { return classCount; }
    public int getMethodCount() { return methodCount; }
    public int getFieldCount() { return fieldCount; }

    public Map<String, String> getClassMapping() { return classMapping; }
    public Map<String, String> getReverseClassMapping() { return reverseClassMapping; }
}
