package com.ccs.wiredroid.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full Maven dependency resolver: direct + transitive dependencies,
 * custom repositories, exclusion handling, SHA1 verification, pom caching.
 */
public final class MavenDependencyResolver {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    /** Maximum recursion depth for transitive resolution */
    private static final int MAX_DEPTH = 5;

    public interface Log {
        void onLine(String line);
    }

    private MavenDependencyResolver() {}

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Resolve all compile-scoped dependencies (direct + transitive).
     */
    public static List<File> resolve(File projectRoot, PomModel pom, Log log) throws IOException {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        com.ccs.wiredroid.profiler.PerformanceMonitor.Timer timer = monitor == null
                ? null : monitor.timer("dependency.resolve.main");
        try {
        File repo = MavenPaths.localRepoDir(projectRoot);
        repo.mkdirs();

        // Build a flat list of all resolved dependencies (deduplicated)
        Set<String> visited = new HashSet<>();
        List<ResolvedDep> all = new ArrayList<>();

        List<PomModel.MavenDependency> directDeps = pom.compileDependencies();
        resolveRecursive(projectRoot, repo, pom, directDeps, all, visited, 0, MAX_DEPTH, log);

        // Download JARs (and verify SHA1) for all resolved deps
        List<File> jars = downloadAll(repo, pom.repositories, all, log);

        // Everything the project carries in libs/ joins them. Done here because
        // this is the one call every compile path already makes, so a jar
        // dropped into the folder is on the classpath for run, debug, tests and
        // packaging alike, without six call sites having to remember.
        List<File> local = com.ccs.wiredroid.project.LocalLibraries.list(projectRoot);
        if (!local.isEmpty() && log != null) {
            log.onLine(local.size() == 1
                    ? "libs/: 1 local jar"
                    : "libs/: " + local.size() + " local jars");
        }
        jars.addAll(local);
        return jars;
        } finally {
            if (timer != null) timer.close();
        }
    }

    /**
     * Resolve only test-scoped dependencies (direct + transitive).
     */
    public static List<File> resolveTestScoped(File projectRoot, PomModel pom, Log log) throws IOException {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        com.ccs.wiredroid.profiler.PerformanceMonitor.Timer timer = monitor == null
                ? null : monitor.timer("dependency.resolve.test");
        try {
        File repo = MavenPaths.localRepoDir(projectRoot);
        repo.mkdirs();

        Set<String> visited = new HashSet<>();
        List<ResolvedDep> all = new ArrayList<>();

        List<PomModel.MavenDependency> testDeps = new ArrayList<>();
        for (PomModel.MavenDependency d : pom.dependencies) {
            String sc = d.scope == null ? "" : d.scope.trim();
            if ("test".equalsIgnoreCase(sc)) testDeps.add(d);
        }
        resolveRecursive(projectRoot, repo, pom, testDeps, all, visited, 0, MAX_DEPTH, log);

        return downloadAll(repo, pom.repositories, all, log);
        } finally {
            if (timer != null) timer.close();
        }
    }

    // ─── Transitive resolution ──────────────────────────────────────────────

    /**
     * Recursively resolve dependencies.
     * @param visited keys in format "groupId:artifactId" — prevents infinite recursion
     */
    private static void resolveRecursive(File projectRoot, File repo, PomModel pom,
                                          List<PomModel.MavenDependency> deps,
                                          List<ResolvedDep> result,
                                          Set<String> visited,
                                          int depth, int maxDepth,
                                          Log log) {
        if (depth >= maxDepth) return;

        for (PomModel.MavenDependency d : deps) {
            if (d.version == null || d.version.isEmpty()) {
                // Try to fill version from dependencyManagement
                String managedVersion = findManagedVersion(pom, d.groupId, d.artifactId);
                if (managedVersion != null) {
                    d.version = managedVersion;
                } else {
                    if (log != null) log.onLine("Skip (no version): " + d.key());
                    continue;
                }
            }

            String key = d.key();
            if (visited.contains(key)) continue;
            visited.add(key);

            result.add(new ResolvedDep(d.groupId, d.artifactId, d.version));

            if (log != null) log.onLine("Resolving: " + d.groupId + ":" + d.artifactId + ":" + d.version);

            // Download and parse the dependency's own POM to find transitive deps
            File depPomFile = cacheDependencyPom(repo, d.groupId, d.artifactId, d.version, pom.repositories, log);
            if (depPomFile != null && depPomFile.exists()) {
                try {
                    PomModel depPom = PomParser.parse(depPomFile);
                    // Walk the whole parent chain, not one step of it: a version
                    // like ${jackson.version.core} is declared in a BOM two or
                    // three POMs up, and stopping at the immediate parent leaves
                    // the placeholder in the download URL — which 404s.
                    PomModel ancestor = depPom;
                    for (int up = 0; up < MAX_PARENT_DEPTH && ancestor.parentGroupId != null; up++) {
                        PomModel parentPom = PomParser.parseRemoteParent(
                                ancestor.parentGroupId, ancestor.parentArtifactId,
                                ancestor.parentVersion);
                        if (parentPom == null) break;
                        depPom.mergeParentProperties(parentPom.properties);
                        ancestor = parentPom;
                    }
                    // Parsing already substituted once, before any of those
                    // properties existed, so the versions have to be resolved
                    // again now that they do.
                    depPom.applyProperties();
                    if (log != null) {
                        // Says which property could not be found, rather than
                        // letting a literal ${...} reach a download URL and come
                        // back as an unexplained 404.
                        for (PomModel.MavenDependency t : depPom.compileDependencies()) {
                            if (t.version != null && t.version.contains("${")) {
                                log.onLine("Unresolved property in " + key + " → "
                                        + t.groupId + ":" + t.artifactId + ":" + t.version);
                            }
                        }
                    }

                    List<PomModel.MavenDependency> transitive = depPom.compileDependencies();

                    // Apply this dependency's exclusions
                    transitive = filterExclusions(transitive, d.exclusions);

                    resolveRecursive(projectRoot, repo, pom, transitive, result, visited,
                            depth + 1, maxDepth, log);
                } catch (Exception e) {
                    if (log != null) log.onLine("Warning: could not parse POM for " + key + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Find a managed version in dependencyManagement for the given artifact.
     */
    private static String findManagedVersion(PomModel pom, String groupId, String artifactId) {
        for (PomModel.MavenDependency dm : pom.dependencyManagement) {
            if (groupId.equals(dm.groupId) && artifactId.equals(dm.artifactId) && dm.version != null) {
                return pom.resolveProperty(dm.version);
            }
        }
        return null;
    }

    /**
     * Remove transitive dependencies that match any exclusion.
     */
    /** Parent chains are short; this only stops a cycle from spinning. */
    private static final int MAX_PARENT_DEPTH = 8;

    private static List<PomModel.MavenDependency> filterExclusions(
            List<PomModel.MavenDependency> deps, List<PomModel.MavenDependency> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return deps;
        List<PomModel.MavenDependency> filtered = new ArrayList<>();
        outer:
        for (PomModel.MavenDependency d : deps) {
            for (PomModel.MavenDependency ex : exclusions) {
                boolean matchG = "*".equals(ex.groupId) || ex.groupId.equals(d.groupId);
                boolean matchA = "*".equals(ex.artifactId) || ex.artifactId.equals(d.artifactId);
                if (matchG && matchA) continue outer;
            }
            filtered.add(d);
        }
        return filtered;
    }

    // ─── POM caching ────────────────────────────────────────────────────────

    /**
     * Download and cache a dependency's POM file.
     * Returns the cached File, or null on failure.
     */
    private static File cacheDependencyPom(File localRepoRoot, String groupId, String artifactId,
                                            String version,
                                            List<PomModel.MavenRepository> customRepos,
                                            Log log) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + ".pom";

        File destDir = new File(localRepoRoot, groupId.replace('.', '/') + "/" + artifactId + "/" + version);
        destDir.mkdirs();
        File dest = new File(destDir, artifactId + "-" + version + ".pom");
        if (dest.exists() && dest.length() > 0) return dest;

        // Build URL list: custom repos first, then Central
        List<String> urls = new ArrayList<>();
        for (PomModel.MavenRepository repo : customRepos) {
            urls.add(repo.url + "/" + path);
        }
        urls.add(CENTRAL + path);

        for (String urlStr : urls) {
            try {
                if (log != null) log.onLine("GET pom: " + urlStr);
                File downloaded = downloadToFile(urlStr, dest, 15000, 20000);
                if (downloaded != null) return downloaded;
            } catch (IOException ignored) {}
        }

        if ("org.jetbrains.kotlin".equals(groupId) && "kotlin-stdlib".equals(artifactId)) {
            String fallbackPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "  <groupId>org.jetbrains.kotlin</groupId>\n" +
                    "  <artifactId>kotlin-stdlib</artifactId>\n" +
                    "  <version>" + version + "</version>\n" +
                    "</project>";
            try {
                java.nio.file.Files.write(dest.toPath(), fallbackPom.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return dest;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─── JAR downloading with SHA1 verification ────────────────────────────

    /**
     * Download all resolved JARs, verifying SHA1 checksums.
     */
    private static List<File> downloadAll(File localRepoRoot,
                                          List<PomModel.MavenRepository> customRepos,
                                          List<ResolvedDep> deps,
                                          Log log) throws IOException {
        List<File> jars = new ArrayList<>();
        for (ResolvedDep d : deps) {
            File jar = downloadJarWithVerify(localRepoRoot, customRepos, d, log);
            if (jar != null && jar.exists()) jars.add(jar);
        }
        return jars;
    }

    private static File downloadJarWithVerify(File localRepoRoot,
                                               List<PomModel.MavenRepository> customRepos,
                                               ResolvedDep d, Log log) {
        String jarFileName = d.artifactId + "-" + d.version + ".jar";
        String path = d.groupId.replace('.', '/') + "/" + d.artifactId + "/" + d.version + "/" + jarFileName;

        File destDir = new File(localRepoRoot, d.groupId.replace('.', '/') + "/" + d.artifactId + "/" + d.version);
        destDir.mkdirs();
        File dest = new File(destDir, jarFileName);

        // Already cached
        if (dest.exists() && dest.length() > 0) {
            if (log != null) log.onLine("Cached: " + jarFileName);
            return dest;
        }

        // Build URL list: custom repos first, then Central
        List<String> urls = new ArrayList<>();
        for (PomModel.MavenRepository repo : customRepos) {
            urls.add(repo.url + "/" + path);
        }
        urls.add(CENTRAL + path);

        for (String urlStr : urls) {
            try {
                if (log != null) log.onLine("GET " + urlStr);
                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(60000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) {
                    if (log != null) log.onLine("HTTP " + code + " for " + d.artifactId);
                    c.disconnect();
                    continue;
                }

                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }

                // Verify SHA1
                verifySha1(dest, urls, log);

                if (log != null) log.onLine("Saved " + jarFileName);
                return dest;
            } catch (IOException e) {
                if (log != null) log.onLine("Failed " + d.artifactId + ": " + e.getMessage());
            }
        }

        if ("org.jetbrains.kotlin".equals(d.groupId) && "kotlin-stdlib".equals(d.artifactId)) {
            File cachedStdlib = com.ccs.wiredroid.tools.compilers.KotlinCompiler.ensureKotlinStdlib(destDir);
            if (cachedStdlib != null && cachedStdlib.exists() && cachedStdlib.length() > 0) {
                try {
                    if (!dest.getAbsolutePath().equals(cachedStdlib.getAbsolutePath())) {
                        java.nio.file.Files.copy(cachedStdlib.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (dest.exists() && dest.length() > 0) {
                        if (log != null) log.onLine("Using bundled " + jarFileName);
                        return dest;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Try to verify SHA1 checksum of a downloaded JAR.
     * Downloads .sha1 file and compares. Logs warning on mismatch but does NOT delete.
     */
    private static void verifySha1(File jarFile, List<String> baseUrls, Log log) {
        try {
            String sha1Url = jarFile.getName().replace(".jar", ".jar.sha1");
            // Build sha1 URLs from the same base URLs used for the JAR
            for (String baseUrl : baseUrls) {
                String shaUrlStr = baseUrl.replace(".jar", ".jar.sha1");
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(shaUrlStr).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestMethod("GET");
                    if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                    String expectedSha1;
                    try (InputStream is = c.getInputStream()) {
                        byte[] data = new byte[64];
                        int len = is.read(data);
                        expectedSha1 = new String(data, 0, len, "UTF-8").trim();
                    } finally {
                        c.disconnect();
                    }
                    String actualSha1 = sha1Hex(jarFile);
                    if (actualSha1 != null && actualSha1.equalsIgnoreCase(expectedSha1)) {
                        if (log != null) log.onLine("  SHA1 verified: " + jarFile.getName());
                        return;
                    } else {
                        if (log != null) log.onLine("  SHA1 MISMATCH for " + jarFile.getName() + " (expected " + expectedSha1 + ")");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            // Could not download .sha1 — skip verification silently
        } catch (Exception ignored) {}
    }

    private static String sha1Hex(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            try (FileInputStream fis = new FileInputStream(file)) {
                int n;
                while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private static File downloadToFile(String urlStr, File dest, int connectTimeout, int readTimeout)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            return null;
        }
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return dest;
    }

    /** Internal representation of a resolved dependency (deduplicated). */
    private static class ResolvedDep {
        final String groupId, artifactId, version;
        ResolvedDep(String g, String a, String v) { groupId = g; artifactId = a; version = v; }
    }
}
