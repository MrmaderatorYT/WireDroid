package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.util.AppPreferences;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.git.GitManager;
import com.ccs.wiredroid.maven.MavenProjectFactory;
import com.ccs.wiredroid.tools.bytecode.BytecodeProjectFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * One place that turns a filled-in New Project form into a project on disk.
 *
 * <p>The screen holds only UI: it builds a {@link Request} and calls
 * {@link #create}, which picks the factory for the chosen combination and runs
 * the post-creation steps — the per-project level record and the optional git
 * repository — that no factory owns. Everything here does file I/O and must be
 * called off the main thread.</p>
 */
public final class ProjectCreator {

    /** What the user chose. Fields not applicable to the template are ignored. */
    public static final class Request {
        public ProjectTemplates.Template template = ProjectTemplates.Template.CIRCUIT;
        public String name = "";
        public String sampleId = "hello_world";
        public String wireExampleId;
        /** True for Maven, false for Gradle. Only read when the template has both. */
        /**
         * Which build script the project gets.
         *
         * <p>Was a boolean while there were two choices; Ant makes a third, and
         * a boolean cannot say "neither".</p>
         */
        public BuildSystem.Kind buildSystem = BuildSystem.Kind.MAVEN;
        /**
         * Version of the project's language, for the templates that have one.
         *
         * <p>Written into the generated build script, so a build elsewhere uses
         * the version the project was created against.</p>
         */
        public String languageVersion;
        /** Release code, e.g. {@code "1.8"} or {@code "21"}; null uses the global default. */
        public String javaTarget;
        /** ART by default; Java SE currently uses the fixed JDK 21 runtime. */
        public ProjectRuntime.Mode runtimeMode = ProjectRuntime.Mode.ART;
        /** Typed groupId, or null/empty to let the factory derive one. */
        public String groupId;
        /** Typed artifactId; only Maven takes one. */
        public String artifactId;
        public boolean initGit;
        /** Compile this project's .c/.cpp sources. */
        public boolean nativeEnabled;
        /** {@link AppPreferences#NATIVE_TCC} or {@link AppPreferences#NATIVE_NDK}. */
        public String nativeBackend = AppPreferences.NATIVE_TCC;
    }

    /** Where the project landed, plus anything non-fatal that went wrong. */
    public static final class Result {
        public final File root;
        /** Localized note about a step that failed without failing creation, or null. */
        public final String warning;

        Result(File root, String warning) {
            this.root = root;
            this.warning = warning;
        }
    }

    /**
     * Written before the first commit for templates that ship no ignore file of
     * their own — the Maven and bytecode factories write none, and committing
     * {@code target/} as the baseline is not useful.
     */
    private static final String DEFAULT_GITIGNORE =
            "target/\n"
            + "out/\n"
            + "build/\n"
            + ".gradle/\n"
            + ".wiredroid/\n"
            + "*.class\n"
            + "*.jar\n";

    private ProjectCreator() {}

    /**
     * Creates the project and returns its root.
     *
     * @throws IOException              when the directory already exists, which is
     *                                  how every factory reports a collision
     * @throws IllegalArgumentException on an empty name
     */
    public static Result create(Context context, Request request) throws Exception {
        if (request.template != ProjectTemplates.Template.CIRCUIT) throw new IllegalArgumentException("WireDroid створює лише embedded-проєкти");
        File root = request.wireExampleId == null
                ? com.ccs.wiredroid.wire.WireProject.create(context, request.name)
                : com.ccs.wiredroid.wire.WireExamples.create(context, request.wireExampleId, request.name);

        String warning = null;
        if (request.initGit && request.template.takesGit) {
            try {
                File ignore = new File(root, ".gitignore");
                if (!ignore.isFile()) writeUtf8(ignore, DEFAULT_GITIGNORE);
                GitManager.init(root);
                GitManager.addAll(root);
                GitManager.commit(root,
                        context.getString(R.string.np_git_initial_commit), null, null);
            } catch (Exception e) {
                // The project exists and is usable; only the repository failed.
                warning = context.getString(R.string.np_git_failed, describe(e));
            }
        }
        return new Result(root, warning);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
