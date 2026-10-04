package com.ccs.wiredroid.langrt;

import android.content.Context;
import android.content.res.AssetManager;

import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.util.AppPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Gets a language's jars onto disk so the embedded JVM can be pointed at them.
 *
 * <p>The version that ships with the app is unpacked from assets, so a device
 * that has never seen a network can compile and run all three languages. Any
 * other version the user picks is fetched from Maven Central once and cached
 * beside it — the version choice would otherwise be a list with one usable
 * entry.</p>
 */
public final class LanguageRuntimes {

    /** Where unpacked and downloaded runtimes live, per language and version. */
    private static final String CACHE_DIR = "language-runtimes";

    /** Reports progress and lets a long fetch say what it is doing. */
    public interface Log {
        void onLine(String message);
    }

    private LanguageRuntimes() {}

    /**
     * The versions offered for a language, bundled one first.
     *
     * <p>A short list rather than everything ever published: each non-bundled
     * choice is a download, and a list of forty releases would mostly be ways
     * to wait.</p>
     */
    public static List<String> selectableVersions(Context context, JvmLanguage language) {
        List<String> out = new ArrayList<>();
        out.add(bundledVersion(context, language));
        String[] extra;
        switch (language) {
            case SCALA:   extra = new String[]{ "3.5.2", "3.4.3", "3.3.3" }; break;
            case GROOVY:  extra = new String[]{ "4.0.22", "4.0.15", "3.0.22" }; break;
            default:      extra = new String[]{ "1.11.1", "1.10.3" }; break;
        }
        for (String version : extra) {
            if (!out.contains(version)) out.add(version);
        }
        return out;
    }

    /** The version that ships inside the app, as written by the build. */
    public static String bundledVersion(Context context, JvmLanguage language) {
        switch (language) {
            case SCALA:   return com.ccs.wiredroid.BuildConfig.BUNDLED_SCALA_VERSION;
            case GROOVY:  return com.ccs.wiredroid.BuildConfig.BUNDLED_GROOVY_VERSION;
            default:      return com.ccs.wiredroid.BuildConfig.BUNDLED_CLOJURE_VERSION;
        }
    }

    /** The version the user chose, defaulting to the bundled one. */
    public static String selectedVersion(Context context, JvmLanguage language) {
        String chosen = new AppPreferences(context).getLanguageVersion(language.id);
        return chosen == null || chosen.trim().isEmpty()
                ? bundledVersion(context, language) : chosen.trim();
    }

    /**
     * The version a project declares for a language, or null when it says nothing.
     *
     * <p>Read from the project's own build file, because that is where the
     * answer belongs: a Groovy 3 project and a Groovy 4 project can sit side by
     * side, and a single app-wide setting cannot be right for both. Creating one
     * project used to change which runtime every other project ran on.</p>
     */
    public static String projectVersion(File projectRoot, JvmLanguage language) {
        if (projectRoot == null) return null;
        for (String name : new String[]{ "build.gradle", "build.gradle.kts", "pom.xml" }) {
            String version = versionIn(new File(projectRoot, name), language);
            if (version != null) return version;
        }
        return null;
    }

    /** Artifact ids that carry the language version, most specific first. */
    private static String[] artifactNames(JvmLanguage language) {
        switch (language) {
            case SCALA:  return new String[]{ "scala3-library_3", "scala3-compiler_3",
                                              "scala-library", "scala-compiler" };
            case GROOVY: return new String[]{ "groovy-all", "groovy" };
            default:     return new String[]{ "clojure" };
        }
    }

    /**
     * The version this build file declares, in Gradle or Maven shape.
     *
     * <p>Matches {@code group:artifact:version} and the Maven element trio, so
     * one reader covers both without pulling in a build-file parser for a
     * question this narrow.</p>
     */
    private static String versionIn(File buildFile, JvmLanguage language) {
        if (buildFile == null || !buildFile.isFile() || buildFile.length() > 512 * 1024) {
            return null;
        }
        String text = readAll(buildFile);
        if (text == null) return null;
        for (String artifact : artifactNames(language)) {
            java.util.regex.Matcher gradle = java.util.regex.Pattern.compile(
                    "[\\w.\\-]+:" + java.util.regex.Pattern.quote(artifact)
                            + ":([0-9][\\w.\\-]*)").matcher(text);
            if (gradle.find()) return gradle.group(1);
            java.util.regex.Matcher maven = java.util.regex.Pattern.compile(
                    "<artifactId>\\s*" + java.util.regex.Pattern.quote(artifact)
                            + "\\s*</artifactId>\\s*<version>\\s*([0-9][\\w.\\-]*)\\s*</version>")
                    .matcher(text);
            if (maven.find()) return maven.group(1);
        }
        return null;
    }

    private static String readAll(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The version to run a file with: the project's, else the app-wide choice.
     *
     * <p>A scratch belongs to no project, and that is when the setting in
     * Settings is the right answer.</p>
     */
    public static String versionFor(Context context, JvmLanguage language, File projectRoot) {
        String declared = projectVersion(projectRoot, language);
        return declared != null ? declared : selectedVersion(context, language);
    }

    /** True when the chosen version is the one inside the app — no network needed. */
    public static boolean isBundled(Context context, JvmLanguage language, String version) {
        return bundledVersion(context, language).equals(version);
    }

    /**
     * The jars for a language, unpacking or downloading them if needed.
     *
     * <p>Blocking, and meant to be called off the main thread.</p>
     *
     * @throws IOException when a version that is not bundled cannot be fetched
     */
    public static List<File> ensure(Context context, JvmLanguage language, String version,
                                    Log log) throws IOException {
        File dir = versionDir(context, language, version);
        List<File> cached = jarsIn(dir);
        if (!cached.isEmpty()) return cached;

        dir.mkdirs();
        if (isBundled(context, language, version)) {
            unpackFromAssets(context, language, dir, log);
        } else {
            download(context, language, version, dir, log);
        }
        List<File> jars = jarsIn(dir);
        if (jars.isEmpty()) {
            throw new IOException(language.displayName() + " " + version + ": no jars available");
        }
        return jars;
    }

    private static File versionDir(Context context, JvmLanguage language, String version) {
        return new File(new File(context.getFilesDir(), CACHE_DIR), language.id + "-" + version);
    }

    /** Sorted, so the classpath is the same on every run. */
    private static List<File> jarsIn(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }

    private static void unpackFromAssets(Context context, JvmLanguage language, File dir, Log log)
            throws IOException {
        AssetManager assets = context.getAssets();
        String assetDir = "languages/" + language.id;
        String[] names = assets.list(assetDir);
        if (names == null || names.length == 0) {
            throw new IOException("No bundled runtime for " + language.displayName());
        }
        if (log != null) {
            log.onLine(language.displayName() + ": unpacking " + names.length + " bundled jars");
        }
        for (String name : names) {
            if (!name.endsWith(".jar")) continue;
            File out = new File(dir, name);
            try (InputStream in = assets.open(assetDir + "/" + name);
                 OutputStream os = new FileOutputStream(out)) {
                copy(in, os);
            }
            // A jar the class loader is going to open must not stay writable on
            // newer Android releases, the same rule the dex loader follows.
            out.setReadOnly();
        }
    }

    /**
     * Fetches a version that is not the bundled one.
     *
     * <p>Only the language's own artifacts are downloaded, not a full dependency
     * closure: the bundled runtime's supporting jars — Scala's 2.13 library,
     * Clojure's spec — are copied alongside, because those change far less often
     * than the language artifact itself.</p>
     */
    private static void download(Context context, JvmLanguage language, String version,
                                 File dir, Log log) throws IOException {
        // Start from the bundled set so the supporting jars are present.
        unpackFromAssets(context, language, dir, log);

        String[] coordinates = artifacts(language, version);
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split(":");
            String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                    + "/" + parts[1] + "-" + parts[2] + ".jar";
            String url = "https://repo1.maven.org/maven2/" + path;
            File target = new File(dir, parts[1] + "-" + parts[2] + ".jar");
            if (log != null) log.onLine("Downloading " + parts[1] + " " + parts[2]);
            if (!ProjectCompiler.downloadFile(url, target, 30000, 120000)) {
                throw new IOException("Could not download " + coordinate);
            }
            target.setReadOnly();
            // The bundled copy of the same artifact would otherwise stay on the
            // classpath beside the requested one.
            removeOtherVersions(dir, parts[1], target);
        }
    }

    /**
     * Where a given Groovy is published.
     *
     * <p>Groovy moved from {@code org.codehaus.groovy} to
     * {@code org.apache.groovy} in 4.0, so one coordinate cannot fetch both —
     * asking for 3.x under the new group is a 404.</p>
     */
    public static String groovyGroupId(String version) {
        return majorOf(version) >= 4 ? "org.apache.groovy" : "org.codehaus.groovy";
    }

    /** The leading number of a version, or 0 when there is none to read. */
    private static int majorOf(String version) {
        if (version == null) return 0;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        try {
            return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The artifacts that carry the language version itself. */
    private static String[] artifacts(JvmLanguage language, String version) {
        switch (language) {
            case SCALA:
                return new String[]{
                        "org.scala-lang:scala3-compiler_3:" + version,
                        "org.scala-lang:scala3-library_3:" + version,
                        "org.scala-lang:tasty-core_3:" + version,
                };
            case GROOVY:
                return new String[]{ groovyGroupId(version) + ":groovy:" + version };
            default:
                return new String[]{ "org.clojure:clojure:" + version };
        }
    }

    private static void removeOtherVersions(File dir, String artifact, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.equals(keep)) continue;
            String name = f.getName();
            if (name.startsWith(artifact + "-") && name.endsWith(".jar")) {
                f.delete();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
}
