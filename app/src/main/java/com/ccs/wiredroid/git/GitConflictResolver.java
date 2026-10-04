package com.ccs.wiredroid.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;

/** Reads the three unmerged index stages and writes the user's resolved result. */
public final class GitConflictResolver {
    public static final class ConflictFile {
        public final String path;
        public final String base;
        public final String current;
        public final String incoming;

        ConflictFile(String path, String base, String current, String incoming) {
            this.path = path;
            this.base = base;
            this.current = current;
            this.incoming = incoming;
        }
    }

    private GitConflictResolver() {}

    public static ConflictFile read(File projectDir, String path) throws Exception {
        try (Git git = Git.open(projectDir)) {
            Repository repository = git.getRepository();
            DirCache cache = repository.readDirCache();
            return new ConflictFile(path,
                    readStage(repository, cache, path, 1),
                    readStage(repository, cache, path, 2),
                    readStage(repository, cache, path, 3));
        }
    }

    private static String readStage(Repository repository, DirCache cache,
                                    String path, int stage) throws Exception {
        DirCacheEntry entry = null;
        int start = cache.findEntry(path);
        if (start >= 0) {
            int end = cache.nextEntry(start);
            for (int i = start; i < end; i++) {
                DirCacheEntry candidate = cache.getEntry(i);
                if (candidate.getStage() == stage) { entry = candidate; break; }
            }
        }
        if (entry == null || entry.getObjectId() == null) return "";
        ObjectLoader loader = repository.open(entry.getObjectId());
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }

    public static void resolve(File projectDir, String path, String result) throws Exception {
        File target = new File(projectDir, path);
        File canonicalRoot = projectDir.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        String rootPath = canonicalRoot.getPath() + File.separator;
        if (!canonicalTarget.getPath().startsWith(rootPath)) {
            throw new SecurityException("Conflict path escapes project: " + path);
        }
        File parent = canonicalTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(canonicalTarget)) {
            output.write(result.getBytes(StandardCharsets.UTF_8));
        }
        try (Git git = Git.open(projectDir)) {
            git.add().addFilepattern(path.replace(File.separatorChar, '/')).call();
        }
    }
}
