package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Moving a package across a project.
 *
 * <p>The failure that matters is a half-move: the {@code package} line is
 * rewritten everywhere, but only some directories follow it. A file whose
 * declared package does not match its directory does not compile, so a rename
 * that misses a source root leaves the project worse than not renaming at
 * all.</p>
 */
public class PackageRenameHelperTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String path, String body) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(tmp.getRoot(), path).toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    public void testSourcesMoveWithTheMainOnes() throws Exception {
        write("src/main/java/com/old/App.java", "package com.old;\npublic class App { }\n");
        write("src/test/java/com/old/AppTest.java",
                "package com.old;\nimport com.old.App;\npublic class AppTest { }\n");

        assertTrue(PackageRenameHelper.renamePackage(tmp.getRoot(), "com.old", "com.new"));

        assertTrue(new File(tmp.getRoot(), "src/main/java/com/new/App.java").isFile());
        assertTrue("the test source must move too",
                new File(tmp.getRoot(), "src/test/java/com/new/AppTest.java").isFile());
        assertFalse(new File(tmp.getRoot(), "src/test/java/com/old").exists());

        assertTrue(read("src/test/java/com/new/AppTest.java").startsWith("package com.new;"));
        assertTrue(read("src/test/java/com/new/AppTest.java").contains("import com.new.App;"));
    }

    @Test
    public void kotlinSourceRootsCountAsWell() throws Exception {
        write("src/main/kotlin/com/old/Main.kt", "package com.old\nclass Main\n");
        assertTrue(PackageRenameHelper.renamePackage(tmp.getRoot(), "com.old", "com.new"));
        assertTrue(new File(tmp.getRoot(), "src/main/kotlin/com/new/Main.kt").isFile());
    }

    @Test
    public void everyDeclaredPackageMatchesItsDirectoryAfterwards() throws Exception {
        write("src/main/java/com/old/App.java", "package com.old;\n");
        write("src/test/java/com/old/AppTest.java", "package com.old;\n");
        PackageRenameHelper.renamePackage(tmp.getRoot(), "com.old", "org.example.app");

        for (String root : new String[]{"src/main/java", "src/test/java"}) {
            File dir = new File(tmp.getRoot(), root + "/org/example/app");
            assertTrue(root + " was not moved", dir.isDirectory());
            for (File f : dir.listFiles()) {
                assertTrue(f.getName() + " declares the wrong package",
                        read(root + "/org/example/app/" + f.getName())
                                .startsWith("package org.example.app"));
            }
        }
    }

    @Test
    public void renamingToTheSameNameIsANoOp() throws Exception {
        write("src/main/java/com/old/App.java", "package com.old;\n");
        assertTrue(PackageRenameHelper.renamePackage(tmp.getRoot(), "com.old", "com.old"));
        assertTrue(new File(tmp.getRoot(), "src/main/java/com/old/App.java").isFile());
    }

    @Test
    public void sourceRootsAreOnlyTheOnesThatExist() throws Exception {
        write("src/main/java/com/old/App.java", "package com.old;\n");
        assertEquals(1, PackageRenameHelper.sourceRoots(tmp.getRoot()).size());
        write("src/test/java/com/old/AppTest.java", "package com.old;\n");
        assertEquals(2, PackageRenameHelper.sourceRoots(tmp.getRoot()).size());
    }
}
