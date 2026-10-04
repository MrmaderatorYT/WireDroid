package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.gradle.GradlePaths;
import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gives an imported project the build script it was missing.
 *
 * <p>A Maven or Gradle repository is left exactly as it was cloned: it already
 * describes itself, and rewriting someone's build script is both unnecessary
 * and destructive. Only the two cases that cannot be built as they stand get a
 * generated script:</p>
 *
 * <ul>
 *   <li><b>Eclipse</b> — the {@code .classpath} knows the source roots and the
 *       jars, but nothing outside Eclipse reads it.</li>
 *   <li><b>Plain sources</b> — no build system at all.</li>
 * </ul>
 *
 * <p>The generated script is Gradle rather than Maven. Both Eclipse cases that
 * matter here — more than one source root, and jars checked into the repository
 * — are one line each in Gradle, while in Maven they need the build-helper
 * plugin and {@code system}-scoped dependencies with absolute paths, which is a
 * pom no one would want to inherit.</p>
 *
 * <p>Nothing existing is ever overwritten.</p>
 */
public final class ImportedProjectConfigurator {

    /** What was written, if anything. */
    public static final class Outcome {
        /** File names created, relative to the project. Empty when nothing was needed. */
        public final List<String> generated = new ArrayList<>();
        /** Things worth telling the user, as raw {@code .classpath} entries. */
        public final List<String> warnings = new ArrayList<>();

        public boolean wroteAnything() {
            return !generated.isEmpty();
        }
    }

    private ImportedProjectConfigurator() {}

    /**
     * @param layout the detection result for the directory being opened
     * @return what was generated; never {@code null}
     * @throws IOException if a build script could not be written
     */
    public static Outcome configure(Context context, ImportedLayout layout) throws IOException {
        Outcome outcome = new Outcome();
        if (layout == null) return outcome;
        if (com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION) {
            if (!com.ccs.wiredroid.wire.WireProject.isCircuit(layout.root)) {
                com.ccs.wiredroid.wire.WireProject.initializeImported(layout.root);
                outcome.generated.add(com.ccs.wiredroid.wire.WireProject.SCENE);
            }
            return outcome;
        }
        outcome.warnings.addAll(layout.warnings);

        if (layout.kind != ImportedLayout.Kind.ECLIPSE
                && layout.kind != ImportedLayout.Kind.PLAIN_SOURCES) {
            return outcome;
        }

        File root = layout.root;
        // Detection already ruled these out, but a module chosen from a
        // multi-module repository is a different directory than the one detected.
        //
        // Ant belongs in this list: a project that builds with build.xml already
        // has a build system, and writing a build.gradle beside it would quietly
        // change which script the app reads and which sources it compiles.
        if (MavenPaths.pomFile(root).isFile()
                || GradlePaths.isGradleProject(root)
                || com.ccs.wiredroid.ant.AntPaths.isAntProject(root)) {
            return outcome;
        }

        String group = groupId(layout);
        String script = layout.kotlin
                ? KotlinProjectFactory.buildScript(context, group, layout.mainClass,
                        layout.mainSourceRoots, layout.testSourceRoots, layout.libJars)
                : GradleProjectFactory.buildScript(context, group, layout.mainClass,
                        layout.mainSourceRoots, layout.testSourceRoots, layout.libJars);

        File buildFile = new File(root, "build.gradle");
        if (!buildFile.exists()) {
            writeUtf8(buildFile, script);
            outcome.generated.add(buildFile.getName());
        }

        if (GradlePaths.settingsFile(root) == null) {
            File settings = new File(root, "settings.gradle");
            writeUtf8(settings, "rootProject.name = '" + gradleName(root) + "'\n");
            outcome.generated.add(settings.getName());
        }

        return outcome;
    }

    /**
     * The Gradle {@code group}: the package the sources already declare, so the
     * generated coordinates match the code rather than contradicting it.
     */
    private static String groupId(ImportedLayout layout) {
        String pkg = layout.packageName;
        if (pkg != null) {
            pkg = pkg.trim();
            if (pkg.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")) {
                return pkg;
            }
        }
        return "com.ccs." + safeName(layout.root).toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String gradleName(File root) {
        return safeName(root);
    }

    private static String safeName(File root) {
        String name = root == null ? "" : root.getName();
        name = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        return name.isEmpty() ? "project" : name;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
