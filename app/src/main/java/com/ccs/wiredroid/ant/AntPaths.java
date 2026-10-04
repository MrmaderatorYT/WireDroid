package com.ccs.wiredroid.ant;

import java.io.File;

/**
 * Locates the files that define an Ant project.
 *
 * <p>Unlike Maven and Gradle, Ant prescribes no layout at all: where the
 * sources are, where the classes go and where the jars live are whatever the
 * build file says. So the conventional answers here are only the fallback for
 * a script that does not say — {@link AntBuildParser} is what actually knows.</p>
 */
public final class AntPaths {

    /** The name Ant looks for when none is given on the command line. */
    public static final String BUILD_FILE = "build.xml";

    private AntPaths() {}

    public static File buildFile(File projectRoot) {
        return projectRoot == null ? null : new File(projectRoot, BUILD_FILE);
    }

    /** {@code build.properties} — loaded by many scripts through {@code <property file=…>}. */
    public static File propertiesFile(File projectRoot) {
        return projectRoot == null ? null : new File(projectRoot, "build.properties");
    }

    /** True when this directory carries an Ant build script. */
    public static boolean isAntProject(File projectRoot) {
        File build = buildFile(projectRoot);
        return build != null && build.isFile();
    }

    /**
     * The directory this project keeps its Java sources in.
     *
     * <p>Read from the script's {@code <javac srcdir=…>} where there is one,
     * because {@code src/} is a convention rather than a rule and a project
     * that puts them elsewhere would otherwise compile nothing at all.</p>
     *
     * @return the directory, or null when this is not an Ant project or the
     *         declared one does not exist
     */
    public static File sourceDir(File projectRoot) {
        if (!isAntProject(projectRoot)) return null;
        try {
            AntBuildParser.Result parsed = AntBuildParser.parse(projectRoot);
            if (parsed.sourceDir != null && parsed.sourceDir.isDirectory()) return parsed.sourceDir;
        } catch (Exception ignored) {
            // An unreadable script still leaves the conventional layout to try.
        }
        File conventional = new File(projectRoot, "src");
        return conventional.isDirectory() ? conventional : null;
    }

    /**
     * Directories the script puts on the classpath, for local jars.
     *
     * <p>Ant has no coordinates — a dependency is a jar sitting in a folder the
     * script names, usually {@code lib/}. Those folders are the only classpath
     * an Ant project has.</p>
     */
    public static java.util.List<File> libraryDirs(File projectRoot) {
        java.util.List<File> out = new java.util.ArrayList<>();
        if (!isAntProject(projectRoot)) return out;
        try {
            AntBuildParser.Result parsed = AntBuildParser.parse(projectRoot);
            out.addAll(parsed.libraryDirs);
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) {
            File conventional = new File(projectRoot, "lib");
            if (conventional.isDirectory()) out.add(conventional);
        }
        return out;
    }
}
