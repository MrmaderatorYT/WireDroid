package com.ccs.wiredroid.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PomModel {

    public String groupId = "com.ccs";
    public String artifactId = "app";
    public String version = "1.0-SNAPSHOT";
    public String packaging = "jar";
    /** Повне ім'я головного класу з pom.properties або exec-maven-plugin */
    public String mainClass;

    /** Parent POM coordinates */
    public String parentGroupId;
    public String parentArtifactId;
    public String parentVersion;

    public final Map<String, String> properties = new LinkedHashMap<>();
    public final List<MavenDependency> dependencies = new ArrayList<>();
    public final List<MavenRepository> repositories = new ArrayList<>();
    public final List<MavenDependency> dependencyManagement = new ArrayList<>();
    public final List<MavenProfile> profiles = new ArrayList<>();

    /**
     * Resolves a property placeholder like ${java.version} or a plain string.
     * Supports built-in project.* properties: ${project.version}, ${project.groupId},
     * ${project.artifactId}, ${project.basedir}, ${project.build.sourceEncoding}.
     */
    public String resolveProperty(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("${") && s.endsWith("}")) {
            String key = s.substring(2, s.length() - 1);

            // Built-in project.* properties
            switch (key) {
                case "project.version":
                    return version != null ? version : s;
                case "project.groupId":
                    return groupId != null ? groupId : s;
                case "project.artifactId":
                    return artifactId != null ? artifactId : s;
                // A BOM refers to its own parent this way, and jackson-databind
                // pins jackson-bom with it. Answerable now that the parent
                // coordinates are actually read out of the pom.
                case "project.parent.version":
                    return parentVersion != null ? parentVersion : s;
                case "project.parent.groupId":
                    return parentGroupId != null ? parentGroupId : s;
                case "project.parent.artifactId":
                    return parentArtifactId != null ? parentArtifactId : s;
                case "project.basedir":
                    return ".";
                case "project.build.sourceEncoding":
                case "project.encoding":
                    return properties.get("project.build.sourceEncoding") != null
                            ? properties.get("project.build.sourceEncoding")
                            : "UTF-8";
            }

            // User-defined properties
            String v = properties.get(key);
            if (v != null) {
                // Recursive resolution: ${${x}} chains
                if (v.startsWith("${") && v.endsWith("}")) {
                    return resolveProperty(v);
                }
                return v;
            }
            return s;
        }
        return s;
    }

    /** Inherit missing coordinates from parent (if parent is set). */
    public void inheritFromParent() {
        if (groupId == null || groupId.isEmpty()) groupId = parentGroupId;
        if (version == null || version.isEmpty()) version = parentVersion;
    }

    /** Merge a parent POM's properties into this model (parent wins on conflict). */
    public void mergeParentProperties(Map<String, String> parentProps) {
        if (parentProps == null) return;
        // Parent properties become defaults; child overrides win
        Map<String, String> merged = new LinkedHashMap<>(parentProps);
        merged.putAll(properties);
        properties.clear();
        properties.putAll(merged);
    }

    /**
     * Re-resolves {@code ${...}} in the dependency lists.
     *
     * <p>Parsing resolves them once, against whatever properties the POM itself
     * declared. A dependency that takes its version from a parent — the usual
     * shape of a BOM — has none of them at that moment, and its version is left
     * as the literal text {@code ${jackson.version.core}}, which then goes into
     * a download URL and comes back 404. Call this after merging a parent's
     * properties in, and the versions resolve.</p>
     *
     * <p>Idempotent: anything already resolved has no placeholder left to
     * substitute, and anything still unknown is left as it was.</p>
     */
    public void applyProperties() {
        for (MavenDependency d : dependencies) {
            d.groupId = resolveProperty(d.groupId);
            d.artifactId = resolveProperty(d.artifactId);
            d.version = resolveProperty(d.version);
        }
        for (MavenDependency d : dependencyManagement) {
            d.groupId = resolveProperty(d.groupId);
            d.artifactId = resolveProperty(d.artifactId);
            d.version = resolveProperty(d.version);
        }
    }

    /** Activate profiles based on JDK and activeByDefault flag. */
    public List<MavenProfile> activeProfiles() {
        List<MavenProfile> active = new ArrayList<>();
        String jdk = System.getProperty("java.specification.version");
        if (jdk == null) jdk = System.getProperty("java.version");
        if (jdk != null && jdk.startsWith("1.")) jdk = jdk.substring(2);
        for (MavenProfile p : profiles) {
            if (p.activeByDefault) {
                active.add(p);
                continue;
            }
            if (p.activationJdk != null && !p.activationJdk.isEmpty()) {
                try {
                    if (jdk != null && jdk.startsWith(p.activationJdk)) {
                        active.add(p);
                        continue;
                    }
                } catch (Exception ignored) {}
            }
            // If no activation criteria, activate by default
            if (p.activationJdk == null && p.activationOs == null && p.activeByDefault) {
                active.add(p);
            }
        }
        return active;
    }

    public List<MavenDependency> compileDependencies() {
        List<MavenDependency> out = new ArrayList<>();
        for (MavenDependency d : dependencies) {
            if (d.isForCompile()) out.add(d);
        }
        return out;
    }

    public List<MavenDependency> testDependencies() {
        List<MavenDependency> out = new ArrayList<>();
        for (MavenDependency d : dependencies) {
            if (d.isForTest()) out.add(d);
        }
        return out;
    }

    // ─── Nested model classes ───────────────────────────────────────────────

    public static class MavenDependency {
        public String groupId;
        public String artifactId;
        public String version;
        /** test, provided, runtime, compile (default) */
        public String scope;
        /** Optional classifier (e.g. "sources", "javadoc") */
        public String classifier;
        /** Optional type (e.g. "jar", "war", "aar") */
        public String type;
        /** Whether this dependency is optional */
        public boolean optional;
        /** Exclusions — do not pull these transitive deps */
        public final List<MavenDependency> exclusions = new ArrayList<>();

        public boolean isForCompile() {
            String s = scope == null ? "compile" : scope.trim();
            return "compile".equalsIgnoreCase(s) || "runtime".equalsIgnoreCase(s) || s.isEmpty();
        }

        public boolean isForTest() {
            String s = scope == null ? "compile" : scope.trim();
            return "test".equalsIgnoreCase(s) || "compile".equalsIgnoreCase(s)
                    || "runtime".equalsIgnoreCase(s) || s.isEmpty();
        }

        /** Key for deduplication: groupId:artifactId */
        public String key() {
            return (groupId != null ? groupId : "") + ":" + (artifactId != null ? artifactId : "");
        }

        /** Check if a given artifact is excluded by this dependency's exclusions */
        public boolean isExcluded(String gid, String aid) {
            for (MavenDependency ex : exclusions) {
                boolean matchG = "*".equals(ex.groupId) || ex.groupId.equals(gid);
                boolean matchA = "*".equals(ex.artifactId) || ex.artifactId.equals(aid);
                if (matchG && matchA) return true;
            }
            return false;
        }
    }

    public static class MavenRepository {
        public String id;
        public String url;

        /** Build URL path for an artifact: baseUrl/groupId/path/artifactId/version/file */
        public String artifactUrl(String gid, String aid, String ver, String fileName) {
            return url + "/" + gid.replace('.', '/') + "/" + aid + "/" + ver + "/" + fileName;
        }
    }

    public static class MavenProfile {
        public String id;
        public boolean activeByDefault;
        public String activationJdk;
        public String activationOs;
        public final Map<String, String> properties = new LinkedHashMap<>();
        public final List<MavenDependency> dependencies = new ArrayList<>();
    }
}
