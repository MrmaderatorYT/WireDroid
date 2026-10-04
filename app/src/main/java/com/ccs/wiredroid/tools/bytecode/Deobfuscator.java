package com.ccs.wiredroid.tools.bytecode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Deobfuscator: applies ProGuard/R8 mapping to BytecodeModel for display.
 * Replaces obfuscated names with original names in the UI.
 */
public final class Deobfuscator {

    private final ProGuardMappingParser mapping;
    private boolean enabled = true;

    public Deobfuscator() {
        this.mapping = new ProGuardMappingParser();
    }

    /**
     * Load a ProGuard/R8 mapping file.
     */
    public void loadMapping(File mappingFile) throws IOException {
        mapping.parse(mappingFile);
    }

    /**
     * Load a ProGuard/R8 mapping from an InputStream.
     */
    public void loadMapping(InputStream stream) throws IOException {
        mapping.parse(stream);
    }

    /**
     * Get the original class name for display.
     */
    public String className(String obfuscated) {
        if (!enabled || mapping.isEmpty() || obfuscated == null) return obfuscated;
        String original = mapping.deobfuscateClass(obfuscated);
        return original != null ? original : obfuscated;
    }

    /**
     * Get the original method name for display.
     */
    public String methodName(String owner, String obfuscated, String desc) {
        if (!enabled || mapping.isEmpty() || obfuscated == null) return obfuscated;
        String original = mapping.deobfuscateMethod(owner, obfuscated, desc);
        return original != null ? original : obfuscated;
    }

    /**
     * Get the original field name for display.
     */
    public String fieldName(String owner, String obfuscated) {
        if (!enabled || mapping.isEmpty() || obfuscated == null) return obfuscated;
        String original = mapping.deobfuscateField(owner, obfuscated);
        return original != null ? original : obfuscated;
    }

    /**
     * Get the original class name from obfuscated name.
     */
    public String getOriginalClassName(String obfuscated) {
        return mapping.getOriginalClassName(obfuscated);
    }

    /**
     * Check if a mapping is loaded.
     */
    public boolean hasMapping() {
        return !mapping.isEmpty();
    }

    /**
     * Enable/disable deobfuscation.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ProGuardMappingParser getMapping() {
        return mapping;
    }

    /**
     * Get statistics about the loaded mapping.
     */
    public String getStats() {
        if (mapping.isEmpty()) return "No mapping loaded";
        return String.format("Classes: %d, Methods: %d, Fields: %d",
                mapping.getClassCount(), mapping.getMethodCount(), mapping.getFieldCount());
    }
}
