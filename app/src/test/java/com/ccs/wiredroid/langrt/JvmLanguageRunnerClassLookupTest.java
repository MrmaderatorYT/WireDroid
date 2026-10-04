package com.ccs.wiredroid.langrt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What the Scala runner reads back out of the compiler's output directory.
 *
 * <p>A packaged source puts its classes in a matching directory. Looking only
 * at the top level reported a good compile as having produced nothing, and gave
 * the JVM a class name it could not resolve.</p>
 */
public class JvmLanguageRunnerClassLookupTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = JvmLanguageRunner.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static boolean hasClassFiles(File dir) throws Exception {
        return (Boolean) call("hasClassFiles", new Class<?>[] { File.class }, dir);
    }

    private static String mainClassOf(File source, File out) throws Exception {
        return (String) call("mainClassOf", new Class<?>[] { File.class, File.class }, source, out);
    }

    private File source(String text) throws Exception {
        File file = temp.newFile("Main.scala");
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private File classFile(File out, String path) throws Exception {
        File file = new File(out, path);
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), new byte[] { (byte) 0xCA, (byte) 0xFE });
        return file;
    }

    @Test
    public void findsClassesNestedUnderAPackage() throws Exception {
        File out = temp.newFolder("out");
        assertFalse(hasClassFiles(out));
        classFile(out, "com/ccs/scalatry/Main.class");
        assertTrue(hasClassFiles(out));
    }

    @Test
    public void mainClassCarriesThePackage() throws Exception {
        File out = temp.newFolder("out");
        classFile(out, "com/ccs/scalatry/Main.class");
        File src = source("package com.ccs.scalatry\n\nobject Main {\n"
                + "  def main(args: Array[String]): Unit = println(\"hi\")\n}\n");
        assertEquals("com.ccs.scalatry.Main", mainClassOf(src, out));
    }

    @Test
    public void unpackagedSourceKeepsTheBareName() throws Exception {
        File out = temp.newFolder("out");
        classFile(out, "Main.class");
        assertEquals("Main", mainClassOf(source("object Main {}\n"), out));
    }

    @Test
    public void fallsBackToWhateverCameOut() throws Exception {
        File out = temp.newFolder("out");
        classFile(out, "com/example/Runner.class");
        // A source the patterns cannot read still runs if exactly one plain
        // class was produced.
        assertEquals("com.example.Runner", mainClassOf(source("@main def go = ()\n"), out));
    }

    @Test
    public void syntheticClassesAreNotEntryPoints() throws Exception {
        File out = temp.newFolder("out");
        classFile(out, "com/example/Runner$anon.class");
        assertNull(mainClassOf(source("// nothing\n"), out));
    }
}
