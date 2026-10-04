package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.langrt.JvmLanguage;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.util.languages.LanguageFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lays down a Scala, Groovy or Clojure project.
 *
 * <p>One factory for the three, because they differ only in where the sources
 * live, what the starter file says and what the build script declares — and the
 * first two are already answered elsewhere.</p>
 *
 * <p>A Gradle script is written even though the app never runs Gradle: it is
 * what makes the folder a project anywhere else, and the app compiles these
 * languages file by file regardless of what the script says.</p>
 */
public final class JvmLanguageProjectFactory {

    private JvmLanguageProjectFactory() {}

    public static File create(Context context, JvmLanguage language, String projectName,
                              String groupId, String languageVersion) throws IOException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String safe = projectName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        File root = MavenPaths.projectDir(context, safe);
        if (root.exists()) {
            throw new IOException("Project already exists: " + safe);
        }

        String gid = (groupId != null && !groupId.trim().isEmpty())
                ? groupId.trim().replaceAll("\\s+", "")
                : "com.ccs." + safe.toLowerCase().replace('-', '_');

        // Each language keeps sources under its own name, which is the layout
        // its own build tools expect.
        String sourceRoot = "src/main/" + language.id;
        File packageDir = new File(root, sourceRoot + "/" + gid.replace('.', File.separatorChar));
        packageDir.mkdirs();
        new File(root, "src/main/resources").mkdirs();

        String entryName = entryFileName(language);
        writeUtf8(new File(packageDir, entryName),
                LanguageFiles.starterTemplate(entryName, gid));

        // A caller that named no version gets the one the app ships, so the
        // script never declares a dependency on "null".
        String version = (languageVersion == null || languageVersion.trim().isEmpty())
                ? com.ccs.wiredroid.langrt.LanguageRuntimes.bundledVersion(context, language)
                : languageVersion.trim();
        writeUtf8(new File(root, "build.gradle"),
                buildScript(language, gid, version, sourceRoot));
        writeUtf8(new File(root, "settings.gradle"),
                "rootProject.name = '" + safe + "'\n");
        writeUtf8(new File(root, ".gitignore"),
                "build/\ntarget/\n.gradle/\n.wiredroid/\n*.class\n*.jar\n");
        return root;
    }

    /** {@code Main.scala}, {@code main.groovy}, {@code main.clj}. */
    private static String entryFileName(JvmLanguage language) {
        switch (language) {
            case SCALA:  return "Main.scala";
            case GROOVY: return "main.groovy";
            default:     return "main.clj";
        }
    }

    /**
     * A Gradle script naming the language and its version.
     *
     * <p>The version is the one the project was created with, so a build on a
     * desktop uses the same one the app ran it with.</p>
     */
    public static String buildScript(JvmLanguage language, String groupId, String version,
                                     String sourceRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        switch (language) {
            case SCALA:  sb.append("    id 'scala'\n"); break;
            case GROOVY: sb.append("    id 'groovy'\n"); break;
            default:     sb.append("    id 'java'\n"); break;
        }
        sb.append("    id 'application'\n}\n\n");
        sb.append("group = '").append(groupId).append("'\n");
        sb.append("version = '1.0-SNAPSHOT'\n\n");
        sb.append("repositories {\n    mavenCentral()\n}\n\n");
        sb.append("dependencies {\n");
        switch (language) {
            case SCALA:
                sb.append("    implementation 'org.scala-lang:scala3-library_3:")
                  .append(version).append("'\n");
                break;
            case GROOVY:
                // Same split the downloader honours: Groovy 3 and earlier are
                // published under org.codehaus.groovy.
                sb.append("    implementation '")
                  .append(com.ccs.wiredroid.langrt.LanguageRuntimes.groovyGroupId(version))
                  .append(":groovy:").append(version).append("'\n");
                break;
            default:
                sb.append("    implementation 'org.clojure:clojure:")
                  .append(version).append("'\n");
                break;
        }
        sb.append("}\n\n");
        sb.append("sourceSets {\n    main {\n");
        switch (language) {
            case SCALA:  sb.append("        scala { srcDirs = ['").append(sourceRoot).append("'] }\n"); break;
            case GROOVY: sb.append("        groovy { srcDirs = ['").append(sourceRoot).append("'] }\n"); break;
            default:     sb.append("        resources { srcDirs = ['").append(sourceRoot).append("'] }\n"); break;
        }
        sb.append("    }\n}\n");
        // The application plugin needs to be told what to start, or `gradle
        // run` fails on a project the app itself runs perfectly well.
        sb.append("\napplication {\n");
        switch (language) {
            case SCALA:
                sb.append("    mainClass = '").append(groupId).append(".Main'\n}\n");
                break;
            case GROOVY:
                // A Groovy script compiles to a class named after its file.
                sb.append("    mainClass = '").append(groupId).append(".main'\n}\n");
                break;
            default:
                // Clojure starts through its own launcher, pointed at the
                // namespace holding -main.
                sb.append("    mainClass = 'clojure.main'\n}\n\n")
                  .append("run {\n    args = ['-m', '")
                  .append(LanguageFiles.clojureNamespace("main", groupId))
                  .append("']\n}\n");
                break;
        }
        return sb.toString();
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }
}
