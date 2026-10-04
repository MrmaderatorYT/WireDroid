package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The templates the New Project screen offers, the fields each one actually
 * consumes, and the tree each one writes.
 *
 * <p>Only combinations a factory can really produce appear here: Java+Maven,
 * Java+Gradle, Kotlin+Gradle, Bytecode, Playground and Scratch. Kotlin+Maven has no
 * factory, so {@link Template#mavenAllowed} is false for Kotlin and the screen
 * greys that segment rather than offering a build it cannot generate.</p>
 *
 * <p>The preview lists live here, next to the applicability rules and one file
 * away from the {@code create} calls in {@link ProjectCreator}, because a
 * preview that drifts from the factories is worse than no preview. Preview
 * entries are file paths, not prose, so they are literals rather than
 * resources; every label the user reads is a string resource.</p>
 */
public final class ProjectTemplates {

    /** The fixed directory {@link PlaygroundProjectFactory} always writes to. */
    public static final String PLAYGROUND_DIR = "Playground";

    /** How the scratch directory is shown in the preview; the real path is absolute. */
    public static final String SCRATCH_DIR = "scratches";

    /** MavenProjectFactory.normalizeGroupId's own test, applied to every template. */
    private static final String GROUP_ID_RE =
            "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$";

    private ProjectTemplates() {}

    /** One chip of the template rail. */
    public enum Template {

        JAVA(R.string.np_template_java, R.string.np_caption_java,
                true, true, true, true, true, true),

        KOTLIN(R.string.np_template_kotlin, R.string.np_caption_kotlin,
                true, true, false, true, true, true),

        SCALA(R.string.np_template_scala, R.string.np_caption_scala,
                true, false, false, false, true, true),

        GROOVY(R.string.np_template_groovy, R.string.np_caption_groovy,
                true, false, false, false, true, true),

        CLOJURE(R.string.np_template_clojure, R.string.np_caption_clojure,
                true, false, false, false, true, true),

        BYTECODE(R.string.np_template_bytecode, R.string.np_caption_bytecode,
                true, false, false, false, false, true),

        PLAYGROUND(R.string.np_template_playground, R.string.np_caption_playground,
                false, false, false, false, false, false),

        /**
         * One file, no project at all.
         *
         * <p>Every flag is false because none of the project fields apply — the
         * screen shows a language pair and a file name of its own instead, and
         * the Create button writes through {@code ScratchManager} rather than
         * {@link ProjectCreator}.</p>
         */
        SCRATCH(R.string.np_template_scratch, R.string.np_caption_scratch,
                false, false, false, false, false, false),

        SAMPLES(R.string.np_template_samples, R.string.np_caption_samples,
                true, false, false, true, false, true),

        CIRCUIT(R.string.np_template_circuit, R.string.np_caption_circuit,
                true, false, false, false, false, true);

        public final int labelRes;
        public final int captionRes;
        /** Whether the factory takes a project name at all. */
        public final boolean takesName;
        /** Whether Maven | Gradle is a real choice, so the row is shown. */
        public final boolean hasBuildSystem;
        /** Whether the Maven segment is selectable. Kotlin is Gradle-only. */
        public final boolean mavenAllowed;
        public final boolean takesJdk;
        /** groupId, plus artifactId when the Maven segment is selected. */
        public final boolean takesCoordinates;
        public final boolean takesGit;

        Template(int labelRes, int captionRes, boolean takesName, boolean hasBuildSystem,
                 boolean mavenAllowed, boolean takesJdk, boolean takesCoordinates,
                 boolean takesGit) {
            this.labelRes = labelRes;
            this.captionRes = captionRes;
            this.takesName = takesName;
            this.hasBuildSystem = hasBuildSystem;
            this.mavenAllowed = mavenAllowed;
            this.takesJdk = takesJdk;
            this.takesCoordinates = takesCoordinates;
            this.takesGit = takesGit;
        }

        public String label(Context context) {
            return context.getString(labelRes);
        }

        /** One line describing the template; the language ones name their version. */
        public String caption(Context context) {
            return caption(context, null);
        }

        /**
         * The one-line description, naming {@code version} where the template
         * has a language version to name.
         *
         * <p>Passing null falls back to the bundled version — what the template
         * is created against when nobody has chosen otherwise.</p>
         */
        public String caption(Context context, String version) {
            if (version != null && !version.trim().isEmpty()
                    && (this == KOTLIN || jvmLanguage() != null)) {
                return context.getString(captionRes, version.trim());
            }
            if (this == KOTLIN) {
                return context.getString(captionRes, KotlinProjectFactory.KOTLIN_VERSION);
            }
            com.ccs.wiredroid.langrt.JvmLanguage language = jvmLanguage();
            if (language != null) {
                return context.getString(captionRes,
                        com.ccs.wiredroid.langrt.LanguageRuntimes.bundledVersion(context, language));
            }
            return context.getString(captionRes);
        }

        /**
         * The bundled JVM language this template creates a project for, or null.
         *
         * <p>Used to decide whether the screen shows a language-version picker
         * and which runtime the project is written against.</p>
         */
        public com.ccs.wiredroid.langrt.JvmLanguage jvmLanguage() {
            switch (this) {
                case SCALA:   return com.ccs.wiredroid.langrt.JvmLanguage.SCALA;
                case GROOVY:  return com.ccs.wiredroid.langrt.JvmLanguage.GROOVY;
                case CLOJURE: return com.ccs.wiredroid.langrt.JvmLanguage.CLOJURE;
                default:      return null;
            }
        }
    }

    /** The template at {@code ordinal}, or {@link Template#JAVA} when out of range. */
    public static Template byOrdinal(int ordinal) {
        Template[] all = Template.values();
        return Template.CIRCUIT;
    }

    // ── Names and coordinates ─────────────────────────────────────────────

    /**
     * The directory name a factory derives from the typed name — the same
     * {@code [^a-zA-Z0-9_-]} rule all four name-taking factories apply, so the
     * path the screen shows is the path that gets written.
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** The groupId every factory falls back to for an empty value. */
    public static String defaultGroupId(String safeName) {
        String base = safeName == null ? "" : safeName;
        if (base.isEmpty()) base = "project";
        return "com.ccs." + base.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /** MavenProjectFactory.sanitizeArtifactId, so the field shows what it will get. */
    public static String sanitizeArtifactId(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("[^a-zA-Z0-9_.-]", "-").toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a typed groupId is a dotted package. Maven silently substitutes a
     * fallback for a bad one; the Gradle and Kotlin factories do not check at
     * all and would write it verbatim into {@code group = '…'} and into a
     * {@code package} statement, so the screen checks for all three.
     */
    public static boolean isValidGroupId(String raw) {
        if (raw == null) return false;
        String g = raw.trim().replaceAll("\\s+", "");
        return g.matches(GROUP_ID_RE);
    }

    /** The groupId the factory will actually use for this name and field value. */
    public static String effectiveGroupId(String typed, String safeName) {
        if (typed != null && !typed.trim().isEmpty() && isValidGroupId(typed)) {
            return typed.trim().replaceAll("\\s+", "");
        }
        return defaultGroupId(safeName);
    }

    // ── Preview ───────────────────────────────────────────────────────────

    /**
     * The files and directories the selected combination will write, project
     * root first and everything else indented one level.
     *
     * @param maven     whether the Maven segment is selected; ignored unless the
     *                  template has a build-system choice
     * @param gitignore true when the screen will add a {@code .gitignore} the
     *                  template does not write itself
     */
    public static List<String> previewLines(Template template, boolean maven, String safeName,
                                            String groupId, boolean gitignore) {
        return previewLines(template, maven ? BuildSystem.Kind.MAVEN : BuildSystem.Kind.GRADLE,
                safeName, groupId, gitignore);
    }

    public static List<String> previewLines(Template template, BuildSystem.Kind buildSystem,
                                            String safeName, String groupId, boolean gitignore) {
        boolean maven = buildSystem == BuildSystem.Kind.MAVEN;
        boolean ant = buildSystem == BuildSystem.Kind.ANT;
        List<String> out = new ArrayList<>();
        String name = (safeName == null || safeName.isEmpty()) ? "project" : safeName;
        String pkg = effectiveGroupId(groupId, name).replace('.', '/');

        switch (template) {
            case CIRCUIT:
                out.add(name + "/");
                out.add("  circuit.wire.json");
                out.add("  sketches/uno.cpp");
                out.add("  README.md");
                out.add("  .gitignore");
                break;
            case SCALA:
            case GROOVY:
            case CLOJURE: {
                com.ccs.wiredroid.langrt.JvmLanguage jvm = template.jvmLanguage();
                String entry = jvm == com.ccs.wiredroid.langrt.JvmLanguage.SCALA ? "Main.scala"
                        : jvm == com.ccs.wiredroid.langrt.JvmLanguage.GROOVY ? "main.groovy"
                        : "main.clj";
                out.add(name + "/");
                out.add("  build.gradle");
                out.add("  settings.gradle");
                out.add("  src/main/" + jvm.id + "/" + pkg + "/" + entry);
                out.add("  src/main/resources/");
                out.add("  .gitignore");
                break;
            }
            case JAVA:
                out.add(name + "/");
                if (ant) {
                    // Ant's own layout, not Maven's: the generated build.xml
                    // declares src/ and lib/, and showing a tree the script does
                    // not describe would be a preview of a different project.
                    out.add("  build.xml");
                    out.add("  build.properties");
                    out.add("  src/" + pkg + "/App.java");
                    out.add("  test/" + pkg + "/AppTest.java");
                    out.add("  lib/");
                    out.add("  build/classes/");
                    out.add("  .gitignore");
                } else if (maven) {
                    out.add("  pom.xml");
                    out.add("  src/main/java/" + pkg + "/App.java");
                    out.add("  src/main/resources/application.properties");
                    out.add("  src/main/resources/META-INF/");
                    out.add("  src/test/java/" + pkg + "/AppTest.java");
                    out.add("  src/test/resources/.gitkeep");
                    out.add("  target/classes/");
                    out.add("  target/test-classes/");
                    if (gitignore) out.add("  .gitignore");
                } else {
                    out.add("  build.gradle");
                    out.add("  settings.gradle");
                    out.add("  gradle.properties");
                    out.add("  src/main/java/" + pkg + "/App.java");
                    out.add("  src/main/resources/");
                    out.add("  src/test/java/" + pkg + "/AppTest.java");
                    out.add("  src/test/resources/");
                    out.add("  .gitignore");
                }
                break;

            case KOTLIN:
                out.add(name + "/");
                out.add("  build.gradle");
                out.add("  settings.gradle");
                out.add("  gradle.properties");
                out.add("  src/main/kotlin/" + pkg + "/Main.kt");
                out.add("  src/main/resources/");
                out.add("  src/test/kotlin/" + pkg + "/MainTest.kt");
                out.add("  src/test/resources/");
                out.add("  .gitignore");
                break;

            case BYTECODE:
                out.add(name + "/");
                out.add("  src/HelloWorld.asm");
                out.add("  out/");
                if (gitignore) out.add("  .gitignore");
                break;

            case SAMPLES:
                out.add(name + "/");
                out.add("  pom.xml");
                out.add("  src/main/java/com/example/sample/Main.java");
                out.add("  src/main/resources/");
                out.add("  target/classes/");
                if (gitignore) out.add("  .gitignore");
                break;

            case SCRATCH:
                // Filled in by the screen, which knows the language and the
                // resolved file name; listed here so the switch stays total.
                out.add(SCRATCH_DIR + "/");
                out.add("  " + name);
                break;

            case PLAYGROUND:
            default:
                out.add(PLAYGROUND_DIR + "/");
                out.add("  pom.xml");
                out.add("  src/main/java/com/playground/Main.java");
                out.add("  src/main/java/com/playground/Calculator.java");
                out.add("  src/main/java/com/playground/Box.java");
                out.add("  src/main/java/com/playground/HelloWorld.kt");
                out.add("  src/main/java/com/playground/DataModel.kt");
                out.add("  src/main/resources/");
                out.add("  target/classes/");
                out.add("  hello.html");
                out.add("  style.css");
                out.add("  app.js");
                out.add("  demo.http");
                out.add("  sample.sql");
                out.add("  sample.db");
                out.add("  logo.svg");
                out.add("  proguard-mapping.txt");
                out.add("  notes.md");
                out.add("  README.md");
                break;
        }
        return out;
    }
}
