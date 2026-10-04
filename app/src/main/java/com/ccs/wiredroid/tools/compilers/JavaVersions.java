package com.ccs.wiredroid.tools.compilers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every Java release the target-version setting offers, and what the bundled
 * toolchain can really do with each one.
 *
 * <p>Two independent ceilings apply. The batch compiler (ECJ 3.46) accepts
 * {@code -source} from 1.8 up to 26; anything older was dropped upstream in
 * ECJ 3.39. The dexer (D8 8.13) reads class files up to major version 69,
 * which is Java 25. The usable window is therefore <b>1.8 … 25</b>, and the
 * releases outside it are still listed — a project may legitimately declare
 * {@code <source>1.4</source>} — but they compile at the nearest level that
 * works. {@link #effective(String)} performs that mapping; call it wherever a
 * level is handed to a compiler, never {@code getJavaTarget()} directly.</p>
 */
public final class JavaVersions {

    /** Oldest {@code -source} the bundled ECJ still accepts. */
    public static final String MIN_COMPILABLE = "1.8";

    /** Newest release D8 can dex (class file major 69). */
    public static final String MAX_COMPILABLE = "25";

    /** One selectable release. */
    public static final class Release {
        /** Value stored in preferences and passed to ECJ, e.g. {@code "1.4"} or {@code "17"}. */
        public final String code;
        /** Feature number: 1.4 → 4, 8 → 8, 21 → 21. */
        public final int feature;
        /** Marketing name, e.g. {@code "Java 1.4"} or {@code "Java 21"}. */
        public final String label;

        Release(String code, int feature, String label) {
            this.code = code;
            this.feature = feature;
            this.label = label;
        }

        /** True when the toolchain compiles this level as-is. */
        public boolean isCompilable() {
            return code.equals(effective(code));
        }
    }

    private static final List<Release> RELEASES;

    static {
        List<Release> out = new ArrayList<>();
        // 1.0 … 1.4 kept their "1.x" name in the wild and in build files.
        for (int i = 0; i <= 4; i++) {
            out.add(new Release("1." + i, i, "Java 1." + i));
        }
        // 5 … 8 answer to both spellings; ECJ wants the "1.x" form.
        for (int i = 5; i <= 8; i++) {
            out.add(new Release("1." + i, i, "Java " + i));
        }
        // 9 and up dropped the "1." prefix entirely.
        for (int i = 9; i <= 25; i++) {
            out.add(new Release(String.valueOf(i), i, "Java " + i));
        }
        RELEASES = Collections.unmodifiableList(out);
    }

    private JavaVersions() {}

    /** All releases, oldest first. */
    public static List<Release> all() {
        return RELEASES;
    }

    /** Looks up a release by its stored code, or {@code null}. */
    public static Release byCode(String code) {
        String normalized = normalize(code);
        for (Release r : RELEASES) {
            if (r.code.equals(normalized)) return r;
        }
        return null;
    }

    /**
     * Feature number of a level written in any of the accepted spellings
     * ({@code "1.8"}, {@code "8"}, {@code "8.0"}), or {@code -1}.
     */
    public static int feature(String code) {
        if (code == null) return -1;
        String s = code.trim();
        if (s.isEmpty()) return -1;
        if (s.startsWith("1.")) s = s.substring(2);
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Rewrites {@code "8"} as {@code "1.8"} so codes compare by equality. */
    public static String normalize(String code) {
        int f = feature(code);
        if (f < 0) return MIN_COMPILABLE;
        return f <= 8 ? "1." + f : String.valueOf(f);
    }

    /**
     * The level actually handed to the compiler: the argument itself when the
     * toolchain supports it, otherwise the nearest level that works.
     */
    public static String effective(String code) {
        int f = feature(code);
        if (f < 0) return MIN_COMPILABLE;
        if (f < feature(MIN_COMPILABLE)) return MIN_COMPILABLE;
        if (f > feature(MAX_COMPILABLE)) return MAX_COMPILABLE;
        return normalize(code);
    }

    /** True when {@code code} compiles at exactly the level requested. */
    public static boolean isCompilable(String code) {
        return normalize(code).equals(effective(code));
    }

    /**
     * Gradle {@code JavaVersion} constant for a level, e.g. {@code VERSION_1_8}
     * or {@code VERSION_21}. Uses the effective level so generated build files
     * agree with what the on-device compiler produces.
     */
    public static String gradleConstant(String code) {
        String eff = effective(code);
        int f = feature(eff);
        return f <= 10 ? "VERSION_1_" + f : "VERSION_" + f;
    }

    /**
     * Kotlin {@code JvmTarget} enum names to try, best first. The bundled
     * Kotlin compiler tops out below the Java ceiling, so callers walk the list
     * until {@link Enum#valueOf} stops throwing.
     */
    public static List<String> kotlinJvmTargets(String code) {
        List<String> names = new ArrayList<>();
        for (int f = feature(effective(code)); f >= 8; f--) {
            names.add(f == 8 ? "JVM_1_8" : "JVM_" + f);
        }
        return names;
    }
}
