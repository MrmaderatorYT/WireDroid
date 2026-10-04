package com.ccs.wiredroid.tools.compilers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Kotlin language versions the bundled compiler will accept.
 *
 * <p>Not a choice of compiler: one is built into the app, and it is the only
 * one on the device. What it will do is compile <em>as</em> an older language
 * version — the same thing {@code -language-version} does on the command line —
 * which is what a project pinned to an older Kotlin needs.</p>
 *
 * <p>A compiler cannot be asked for a version newer than itself, so the list
 * stops at the bundled one.</p>
 */
public final class KotlinVersions {

    /** The version the bundled compiler is, and therefore the ceiling. */
    public static final String BUNDLED = "2.0";

    /** Empty means "whatever the compiler does by default", i.e. {@link #BUNDLED}. */
    public static final String DEFAULT = "";

    private static final List<String> SELECTABLE =
            Arrays.asList(DEFAULT, "2.0", "1.9", "1.8", "1.7");

    private KotlinVersions() {}

    /** Versions offered in the settings, default first. */
    public static List<String> selectable() {
        return new ArrayList<>(SELECTABLE);
    }

    /** True when nothing should be passed to the compiler. */
    public static boolean isDefault(String version) {
        return version == null || version.trim().isEmpty() || BUNDLED.equals(version.trim());
    }

    /**
     * The {@code LanguageVersion} enum constant name for a version.
     *
     * <p>{@code "1.9"} is spelled {@code KOTLIN_1_9} in the compiler's own
     * enum, which is what has to be looked up reflectively.</p>
     *
     * @return the constant name, or null when the version is not one we offer
     */
    public static String enumName(String version) {
        if (version == null) return null;
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return null;
        if (!SELECTABLE.contains(trimmed)) return null;
        return "KOTLIN_" + trimmed.replace('.', '_');
    }

    /** How a version reads in the settings list. */
    public static String label(String version, String defaultLabel) {
        return isDefault(version) && (version == null || version.trim().isEmpty())
                ? defaultLabel
                : "Kotlin " + version;
    }
}
