package com.ccs.wiredroid.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class PackageRenameHelper {

    private PackageRenameHelper() {}

    public static boolean renamePackage(File projectRoot, String oldPackage, String newPackage) {
        if (projectRoot == null || !projectRoot.exists() || oldPackage == null || newPackage == null) {
            return false;
        }

        oldPackage = oldPackage.trim();
        newPackage = newPackage.trim();
        if (oldPackage.equals(newPackage)) return true;

        // Every source root that holds the package, not just the first one
        // found. Only main sources used to be moved, while the pass below
        // rewrote the `package` line in every file — so a test class ended up
        // declaring a package its directory no longer matched, which does not
        // compile. A rename has to move the tests with the code.
        String relative = oldPackage.replace('.', '/');
        boolean movedAny = false;
        for (File srcRoot : sourceRoots(projectRoot)) {
            File oldDir = new File(srcRoot, relative);
            if (!oldDir.isDirectory()) continue;
            File newDir = new File(srcRoot, newPackage.replace('.', '/'));
            newDir.mkdirs();
            moveDirectory(oldDir, newDir);
            deleteEmptyParents(oldDir, srcRoot);
            movedAny = true;
        }
        if (!movedAny) {
            // A flat project with no recognised layout: the package directory,
            // if there is one, sits directly under the project root.
            File oldDir = new File(projectRoot, relative);
            if (oldDir.isDirectory()) {
                File newDir = new File(projectRoot, newPackage.replace('.', '/'));
                newDir.mkdirs();
                moveDirectory(oldDir, newDir);
                deleteEmptyParents(oldDir, projectRoot);
            }
        }

        List<File> allFiles = new ArrayList<>();
        collectFiles(projectRoot, allFiles);

        for (File f : allFiles) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml") || name.endsWith(".gradle")) {
                updateFileReferences(f, oldPackage, newPackage);
            }
        }
        return true;
    }

    /** The source roots a project may keep code in, in the order they are tried. */
    static java.util.List<File> sourceRoots(File projectRoot) {
        String[] candidates = {
                "src/main/java", "src/test/java",
                "src/main/kotlin", "src/test/kotlin",
                "app/src/main/java", "app/src/test/java",
                "app/src/main/kotlin", "app/src/test/kotlin",
        };
        List<File> roots = new ArrayList<>();
        for (String candidate : candidates) {
            File dir = new File(projectRoot, candidate);
            if (dir.isDirectory()) roots.add(dir);
        }
        return roots;
    }

    private static void moveDirectory(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    moveDirectory(child, new File(target, child.getName()));
                }
            }
            source.delete();
        } else {
            source.renameTo(target);
        }
    }

    private static void deleteEmptyParents(File dir, File rootLimit) {
        File curr = dir;
        while (curr != null && !curr.equals(rootLimit) && curr.exists() && curr.isDirectory()) {
            File[] files = curr.listFiles();
            if (files == null || files.length == 0) {
                curr.delete();
                curr = curr.getParentFile();
            } else {
                break;
            }
        }
    }

    private static void collectFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            String name = dir.getName();
            if (name.equals(".git") || name.equals(".gradle") || name.equals("build") || name.equals(".idea")) return;
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) collectFiles(child, result);
            }
        } else {
            result.add(dir);
        }
    }

    private static void updateFileReferences(File file, String oldPkg, String newPkg) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);

            String updated = content.replace("package " + oldPkg, "package " + newPkg)
                                    .replace("import " + oldPkg + ".", "import " + newPkg + ".")
                                    .replace("package=\"" + oldPkg + "\"", "package=\"" + newPkg + "\"");

            if (!updated.equals(content)) {
                Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {}
    }
}
