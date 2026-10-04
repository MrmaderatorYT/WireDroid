package com.ccs.wiredroid.project;

import com.ccs.wiredroid.ui.FileTreeNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class-level dependency graph of a project: one node per source file, one edge
 * per resolvable dependency between two of those files.
 *
 * <p>Dependencies come from {@code import} statements plus same-package
 * references, matched with regexes rather than a real parser — the map only
 * needs to be right about which classes lean on which, not about semantics.
 * Anything that does not resolve to another node in this project (JDK types,
 * libraries, wildcard imports) is dropped: an edge to {@code java.util.List}
 * would tell the reader nothing.</p>
 *
 * <p>The graph is bounded. Beyond {@link #DEFAULT_MAX_NODES} classes the
 * least-connected ones are cut and counted in {@link #hiddenClasses}, so the
 * caller can say so out loud instead of silently drawing a lie.</p>
 */
public final class ProjectMapGraph {

    /** Drawing more than this many labelled circles is mush, not a map. */
    public static final int DEFAULT_MAX_NODES = 220;

    /** A pathological tree must not hold the scan thread forever. */
    private static final int MAX_FILES = 4000;
    /** Only the head of a file matters here; imports live at the top. */
    private static final int MAX_CHARS = 400_000;
    private static final int MAX_TYPE_REFS = 400;
    /** Same-package matching is O(files in package); huge packages skip it. */
    private static final int MAX_PACKAGE_FOR_REFS = 400;

    private static final Pattern PACKAGE_RE =
            Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern IMPORT_RE =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_][A-Za-z0-9_.]*(?:\\.\\*)?)");
    private static final Pattern TYPE_REF_RE =
            Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");

    /** One class: the file it lives in, plus how connected it turned out to be. */
    public static final class Node {
        public final File file;
        public final String fqName;
        public final String simpleName;
        public final String packageName;
        /** Colour bucket — the package segment that distinguishes this module. */
        public final String group;
        public int inDegree;
        public int outDegree;

        Node(File file, String fqName, String simpleName, String packageName, String group) {
            this.file = file;
            this.fqName = fqName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.group = group;
        }

        public int degree() {
            return inDegree + outDegree;
        }
    }

    public final List<Node> nodes;
    public final int[] edgeFrom;
    public final int[] edgeTo;
    /** Classes found before the cap was applied. */
    public final int totalClasses;
    /** Classes dropped by the cap; always reported to the user. */
    public final int hiddenClasses;
    /** Distinct colour buckets, in assignment order. */
    public final List<String> groups;
    /** Node index to index into {@link #groups}. */
    public final int[] groupOf;

    private ProjectMapGraph(List<Node> nodes, int[] edgeFrom, int[] edgeTo,
                            int totalClasses, int hiddenClasses,
                            List<String> groups, int[] groupOf) {
        this.nodes = nodes;
        this.edgeFrom = edgeFrom;
        this.edgeTo = edgeTo;
        this.totalClasses = totalClasses;
        this.hiddenClasses = hiddenClasses;
        this.groups = groups;
        this.groupOf = groupOf;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edgeFrom.length;
    }

    public int maxInDegree() {
        int max = 0;
        for (Node n : nodes) max = Math.max(max, n.inDegree);
        return max;
    }

    /** Classes per colour bucket, indexed like {@link #groups}. */
    public int[] groupSizes() {
        int[] sizes = new int[groups.size()];
        for (int g : groupOf) {
            if (g >= 0 && g < sizes.length) sizes[g]++;
        }
        return sizes;
    }

    public static ProjectMapGraph build(File projectRoot) {
        return build(projectRoot, DEFAULT_MAX_NODES);
    }

    public static ProjectMapGraph build(File projectRoot, int maxNodes) {
        if (projectRoot == null || !projectRoot.isDirectory()) return empty();

        List<Parsed> parsed = new ArrayList<>();
        Map<String, Integer> byFqName = new HashMap<>();
        Map<String, List<Integer>> byPackage = new HashMap<>();

        for (File f : collectSources(projectRoot)) {
            Parsed p = parse(f);
            if (p == null) continue;
            // Two source sets can hold the same fully qualified name; the first
            // one wins rather than the map growing a duplicate node.
            if (byFqName.containsKey(p.fqName)) continue;
            int idx = parsed.size();
            parsed.add(p);
            byFqName.put(p.fqName, idx);
            List<Integer> peers = byPackage.get(p.packageName);
            if (peers == null) {
                peers = new ArrayList<>();
                byPackage.put(p.packageName, peers);
            }
            peers.add(idx);
        }

        if (parsed.isEmpty()) return empty();

        Set<Long> edges = new LinkedHashSet<>();
        for (int i = 0; i < parsed.size(); i++) {
            Parsed p = parsed.get(i);
            for (String imp : p.imports) {
                int target = resolve(byFqName, imp);
                if (target >= 0 && target != i) edges.add(key(i, target));
            }
            // Same-package classes need no import, so they would otherwise look
            // unconnected. Matching capitalised identifiers against the package
            // members is cheap and close enough.
            List<Integer> peers = byPackage.get(p.packageName);
            if (peers != null && peers.size() <= MAX_PACKAGE_FOR_REFS) {
                for (int j : peers) {
                    if (j == i) continue;
                    if (p.typeRefs.contains(parsed.get(j).simpleName)) edges.add(key(i, j));
                }
            }
            p.typeRefs = Collections.emptySet();
        }

        int total = parsed.size();
        int[] degree = new int[total];
        for (long e : edges) {
            degree[from(e)]++;
            degree[to(e)]++;
        }

        int[] keep = selectKept(degree, parsed, Math.max(1, maxNodes));
        int[] remap = new int[total];
        for (int i = 0; i < total; i++) remap[i] = -1;
        for (int newIdx = 0; newIdx < keep.length; newIdx++) remap[keep[newIdx]] = newIdx;

        List<Node> nodes = new ArrayList<>(keep.length);
        List<String> groups = new ArrayList<>();
        Map<String, Integer> groupIndex = new LinkedHashMap<>();
        int[] groupOf = new int[keep.length];

        List<String> keptPackages = new ArrayList<>(keep.length);
        for (int old : keep) keptPackages.add(parsed.get(old).packageName);
        int commonSegments = commonPackageDepth(keptPackages);

        for (int newIdx = 0; newIdx < keep.length; newIdx++) {
            Parsed p = parsed.get(keep[newIdx]);
            String group = groupOf(p.packageName, commonSegments);
            Integer gi = groupIndex.get(group);
            if (gi == null) {
                gi = groups.size();
                groups.add(group);
                groupIndex.put(group, gi);
            }
            groupOf[newIdx] = gi;
            nodes.add(new Node(p.file, p.fqName, p.simpleName, p.packageName, group));
        }

        List<long[]> kept = new ArrayList<>();
        for (long e : edges) {
            int a = remap[from(e)];
            int b = remap[to(e)];
            if (a < 0 || b < 0) continue;
            kept.add(new long[]{a, b});
            nodes.get(a).outDegree++;
            nodes.get(b).inDegree++;
        }

        int[] ef = new int[kept.size()];
        int[] et = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            ef[i] = (int) kept.get(i)[0];
            et[i] = (int) kept.get(i)[1];
        }

        return new ProjectMapGraph(nodes, ef, et, total, total - keep.length, groups, groupOf);
    }

    private static ProjectMapGraph empty() {
        return new ProjectMapGraph(new ArrayList<Node>(), new int[0], new int[0],
                0, 0, new ArrayList<String>(), new int[0]);
    }

    /**
     * Keeps the most-connected classes. Hubs are what a map is read for; the
     * leaves that fall off are the ones nothing points at.
     */
    private static int[] selectKept(int[] degree, List<Parsed> parsed, int maxNodes) {
        int total = degree.length;
        if (total <= maxNodes) {
            int[] all = new int[total];
            for (int i = 0; i < total; i++) all[i] = i;
            return all;
        }
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            if (degree[b] != degree[a]) return degree[b] - degree[a];
            return parsed.get(a).fqName.compareTo(parsed.get(b).fqName);
        });
        int[] keep = new int[maxNodes];
        for (int i = 0; i < maxNodes; i++) keep[i] = order[i];
        // Original order keeps the layout seed stable between runs.
        java.util.Arrays.sort(keep);
        return keep;
    }

    private static long key(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    private static int from(long key) {
        return (int) (key >> 32);
    }

    private static int to(long key) {
        return (int) key;
    }

    /**
     * Resolves an import against the project's own classes, walking up nested
     * and static-member suffixes. Wildcards resolve to nothing on purpose.
     */
    private static int resolve(Map<String, Integer> byFqName, String imported) {
        if (imported.endsWith(".*")) return -1;
        String candidate = imported;
        for (int guard = 0; guard < 3; guard++) {
            Integer idx = byFqName.get(candidate);
            if (idx != null) return idx;
            int dot = candidate.lastIndexOf('.');
            if (dot <= 0) return -1;
            candidate = candidate.substring(0, dot);
        }
        return -1;
    }

    private static List<File> collectSources(File projectRoot) {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        // listIdeaStyleTree already skips build/, target/, out/, bin/ and every
        // dot-directory (.git, .gradle), which is exactly what the map wants.
        for (FileTreeNode node : ProjectScanner.listIdeaStyleTree(projectRoot, null)) {
            if (out.size() >= MAX_FILES) break;
            if (node.directory) continue;
            File f = node.path;
            if (isSource(f)) out.put(f.getAbsolutePath(), f);
        }
        for (File f : ProjectScanner.listJavaSources(projectRoot)) {
            if (out.size() >= MAX_FILES) break;
            if (isSource(f)) out.put(f.getAbsolutePath(), f);
        }
        for (File f : ProjectScanner.listTestSources(projectRoot)) {
            if (out.size() >= MAX_FILES) break;
            if (isSource(f)) out.put(f.getAbsolutePath(), f);
        }
        return new ArrayList<>(out.values());
    }

    private static boolean isSource(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName();
        if (name.equals("package-info.java") || name.equals("module-info.java")) return false;
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private static Parsed parse(File f) {
        String text = read(f);
        if (text == null) return null;

        String simpleName = f.getName();
        int dot = simpleName.lastIndexOf('.');
        if (dot > 0) simpleName = simpleName.substring(0, dot);
        if (simpleName.isEmpty()) return null;

        String pkg = "";
        Matcher pm = PACKAGE_RE.matcher(text);
        if (pm.find()) pkg = pm.group(1);

        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT_RE.matcher(text);
        while (im.find()) imports.add(im.group(1));

        Set<String> refs = new HashSet<>();
        Matcher tm = TYPE_REF_RE.matcher(text);
        while (tm.find() && refs.size() < MAX_TYPE_REFS) refs.add(tm.group(1));
        refs.remove(simpleName);

        String fqName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        return new Parsed(f, fqName, simpleName, pkg, imports, refs);
    }

    private static String read(File f) {
        if (f.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() >= MAX_CHARS) break;
            }
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
        return sb.toString();
    }

    /**
     * Number of leading package segments every class shares. Colouring by the
     * literal top segment would paint an entire {@code com.*} project one
     * colour, so the shared prefix is skipped first.
     */
    private static int commonPackageDepth(List<String> packages) {
        String[] prefix = null;
        for (String pkg : packages) {
            String[] segments = pkg.isEmpty() ? new String[0] : pkg.split("\\.");
            if (prefix == null) {
                prefix = segments;
                continue;
            }
            int limit = Math.min(prefix.length, segments.length);
            int match = 0;
            while (match < limit && prefix[match].equals(segments[match])) match++;
            prefix = java.util.Arrays.copyOf(prefix, match);
            if (match == 0) break;
        }
        return prefix == null ? 0 : prefix.length;
    }

    /** @return the distinguishing segment, or {@code ""} for the shared root */
    private static String groupOf(String pkg, int commonSegments) {
        if (pkg.isEmpty()) return "";
        String[] segments = pkg.split("\\.");
        if (commonSegments >= segments.length) return "";
        return segments[commonSegments];
    }

    private static final class Parsed {
        final File file;
        final String fqName;
        final String simpleName;
        final String packageName;
        final List<String> imports;
        Set<String> typeRefs;

        Parsed(File file, String fqName, String simpleName, String packageName,
               List<String> imports, Set<String> typeRefs) {
            this.file = file;
            this.fqName = fqName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.imports = imports;
            this.typeRefs = typeRefs;
        }
    }
}
