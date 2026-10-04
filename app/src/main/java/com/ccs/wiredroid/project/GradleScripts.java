package com.ccs.wiredroid.project;

import java.util.ArrayList;
import java.util.List;

/**
 * The two Groovy-DSL blocks an imported project needs and a generated one does
 * not: source roots that are not {@code src/main/java}, and jars checked into
 * the repository.
 *
 * <p>Shared by {@link GradleProjectFactory} and {@link KotlinProjectFactory} so
 * the Java and Kotlin scripts spell them identically.</p>
 */
final class GradleScripts {

    private GradleScripts() {}

    /**
     * A {@code sourceSets} block naming explicit roots.
     *
     * @param language {@code java} or {@code kotlin} — the inner block name. The
     *                 Kotlin plugin's {@code kotlin} source set compiles Java
     *                 too, so a Kotlin project needs only the one block.
     * @return the block, or {@code null} when both root lists are empty and the
     *         conventional layout applies
     */
    static String sourceSetsBlock(String language, List<String> mainDirs, List<String> testDirs) {
        List<String> main = sanitize(mainDirs);
        List<String> test = sanitize(testDirs);
        if (main.isEmpty() && test.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("sourceSets {\n");
        if (!main.isEmpty()) {
            // No resources block: these roots hold sources, and pointing
            // resources at them would package every .java file into the jar.
            sb.append("    main {\n")
              .append("        ").append(language).append(" { srcDirs = ")
              .append(list(main)).append(" }\n")
              .append("    }\n");
        }
        if (!test.isEmpty()) {
            sb.append("    test {\n")
              .append("        ").append(language).append(" { srcDirs = ")
              .append(list(test)).append(" }\n")
              .append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * {@code implementation files(…)} lines for jars that live in the repository.
     *
     * <p>Named individually rather than as a {@code fileTree}: the Eclipse
     * {@code .classpath} says which jars are on the classpath, and a folder
     * often holds more than that — sources jars, an old version kept around.</p>
     */
    static String fileDependencies(List<String> jars) {
        List<String> paths = sanitize(jars);
        if (paths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("    implementation files('").append(path).append("')\n");
        }
        return sb.toString();
    }

    /** {@code ['a', 'b']} */
    private static String list(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(values.get(i)).append('\'');
        }
        return sb.append(']').toString();
    }

    /**
     * Drops anything that cannot go into a single-quoted Groovy string or that
     * would climb out of the project. A path with a quote or a {@code ..} in it
     * is a broken build script, not a useful one.
     */
    private static List<String> sanitize(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String value : values) {
            if (value == null) continue;
            String v = value.trim().replace('\\', '/');
            while (v.startsWith("./")) v = v.substring(2);
            if (v.isEmpty() || v.equals(".")) {
                // The project directory itself is a legitimate source root for a
                // repository of loose files.
                if (!out.contains(".")) out.add(".");
                continue;
            }
            if (v.contains("'") || v.contains("$")) continue;
            if (v.startsWith("/") || v.contains("..")) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }
}
