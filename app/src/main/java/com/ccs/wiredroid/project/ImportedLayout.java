package com.ccs.wiredroid.project;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a freshly cloned directory turned out to be.
 *
 * <p>Purely a description — nothing here has been written to disk yet. The
 * decision of what, if anything, to generate belongs to
 * {@link ImportedProjectConfigurator}, and the decision of what to say about it
 * belongs to the UI.</p>
 */
public final class ImportedLayout {

    public enum Kind {
        /** {@code pom.xml} at the root. */
        MAVEN,
        /** {@code build.gradle}, {@code build.gradle.kts} or {@code settings.gradle}. */
        GRADLE,
        /** {@code .project} + {@code .classpath}, no build script. */
        ECLIPSE,
        /** Sources with no build system at all. */
        PLAIN_SOURCES,
        /** Nothing recognisable — no build script and no sources. */
        UNKNOWN
    }

    /** One buildable directory inside a multi-module repository. */
    public static final class Module {
        /** Path as the build declares it, e.g. {@code core/api} or {@code :app}. */
        public final String name;
        public final File dir;
        public final Kind kind;

        Module(String name, File dir, Kind kind) {
            this.name = name;
            this.dir = dir;
            this.kind = kind;
        }
    }

    public final File root;
    public final Kind kind;
    /** True when the project's sources are Kotlin rather than (only) Java. */
    public final boolean kotlin;
    /** Main source roots relative to {@link #root}; empty means the conventional layout. */
    public final List<String> mainSourceRoots = new ArrayList<>();
    /** Test source roots relative to {@link #root}. */
    public final List<String> testSourceRoots = new ArrayList<>();
    /** Jar paths relative to {@link #root}, from an Eclipse {@code .classpath}. */
    public final List<String> libJars = new ArrayList<>();
    /** Sub-projects to choose between; empty for a single-module repository. */
    public final List<Module> modules = new ArrayList<>();
    /** Package inferred from the source tree, or {@code null}. */
    public String packageName;
    /** Fully qualified class with a {@code main}, or {@code null}. */
    public String mainClass;
    /** Anything the detector noticed but could not act on. */
    public final List<String> warnings = new ArrayList<>();

    ImportedLayout(File root, Kind kind, boolean kotlin) {
        this.root = root;
        this.kind = kind;
        this.kotlin = kotlin;
    }

    public boolean isMultiModule() {
        return modules.size() > 1;
    }

    /** True when the project already carries a build script the app can drive. */
    public boolean isBuildable() {
        return kind == Kind.MAVEN || kind == Kind.GRADLE;
    }
}
