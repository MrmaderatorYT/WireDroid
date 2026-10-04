package com.ccs.wiredroid.project;

import com.ccs.wiredroid.ant.AntBuildParser;
import com.ccs.wiredroid.ant.AntPaths;
import com.ccs.wiredroid.gradle.GradleBuildParser;
import com.ccs.wiredroid.gradle.GradlePaths;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.maven.PomParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells the supported build systems apart and produces the single
 * {@link PomModel} the compile/run pipeline consumes for any of them.
 */
public final class BuildSystem {

    public enum Kind {
        /** {@code pom.xml} present. */
        MAVEN,
        /** {@code build.gradle} or {@code build.gradle.kts} present. */
        GRADLE,
        /** {@code build.xml} present. */
        ANT,
        /** Loose sources with no build script. */
        NONE
    }

    /** A resolved build model plus anything the parser could not represent. */
    public static final class Model {
        public final Kind kind;
        public final PomModel pom;
        public final List<String> warnings;

        Model(Kind kind, PomModel pom, List<String> warnings) {
            this.kind = kind;
            this.pom = pom;
            this.warnings = warnings;
        }
    }

    private BuildSystem() {}

    /**
     * Maven wins when a directory somehow carries several build scripts.
     *
     * <p>Ant is asked last on purpose: a project migrated to Maven or Gradle
     * often keeps its old {@code build.xml} around, and the newer script is the
     * one being maintained.</p>
     */
    public static Kind detect(File projectRoot) {
        if (projectRoot == null) return Kind.NONE;
        if (MavenPaths.pomFile(projectRoot).isFile()) return Kind.MAVEN;
        if (GradlePaths.isGradleProject(projectRoot)) return Kind.GRADLE;
        if (AntPaths.isAntProject(projectRoot)) return Kind.ANT;
        return Kind.NONE;
    }

    /** True when the project can be built — either build system counts. */
    public static boolean isBuildable(File projectRoot) {
        return detect(projectRoot) != Kind.NONE;
    }

    /** Human-readable name for console output and dialogs. */
    public static String displayName(Kind kind) {
        switch (kind) {
            case MAVEN:  return "Maven";
            case GRADLE: return "Gradle";
            case ANT:    return "Ant";
            default:     return "—";
        }
    }

    /** The build script driving this project, or {@code null} when there is none. */
    public static File buildScript(File projectRoot) {
        switch (detect(projectRoot)) {
            case MAVEN:  return MavenPaths.pomFile(projectRoot);
            case GRADLE: return GradlePaths.buildFile(projectRoot);
            case ANT:    return AntPaths.buildFile(projectRoot);
            default:     return null;
        }
    }

    /**
     * Reads the project's build model.
     *
     * @throws IOException when the project has no build script, or it is unreadable
     */
    public static Model model(File projectRoot) throws IOException {
        Kind kind = detect(projectRoot);
        switch (kind) {
            case MAVEN:
                try {
                    return new Model(kind, PomParser.parse(MavenPaths.pomFile(projectRoot)),
                            new ArrayList<>());
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    // Malformed XML — surface it as an IO failure so callers that
                    // already handle unreadable build scripts keep working.
                    throw new IOException("Cannot parse pom.xml: " + e.getMessage(), e);
                }
            case GRADLE: {
                GradleBuildParser.Result r = GradleBuildParser.parse(projectRoot);
                return new Model(kind, r.pom, r.warnings);
            }
            case ANT: {
                AntBuildParser.Result r = AntBuildParser.parse(projectRoot);
                return new Model(kind, r.pom, r.warnings);
            }
            default:
                throw new IOException("No pom.xml, build.gradle or build.xml in "
                        + (projectRoot != null ? projectRoot.getName() : "null"));
        }
    }
}
