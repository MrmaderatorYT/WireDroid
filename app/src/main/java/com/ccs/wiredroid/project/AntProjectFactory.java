package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lays down a new Ant project: a {@code build.xml} and the layout it declares.
 *
 * <p>The conventional Ant layout is used — {@code src/}, {@code lib/},
 * {@code build/} — rather than Maven's {@code src/main/java}. Ant prescribes
 * nothing, so the script is the only thing that says where the sources are, and
 * generating the Maven layout here would make the script's {@code srcdir} a
 * decoration that no longer describes the project.</p>
 */
public final class AntProjectFactory {

    private AntProjectFactory() {}

    /**
     * @param javaTarget the level the generated {@code <javac>} declares, e.g.
     *                   {@code "17"}; null falls back to the global setting
     */
    public static File create(Context context, String projectName, String groupId,
                              String javaTarget) throws IOException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String safe = projectName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        File root = MavenPaths.projectDir(context, safe);
        if (root.exists()) {
            throw new IOException("Project already exists: " + safe);
        }

        String gid = (groupId != null && !groupId.trim().isEmpty())
                ? groupId.trim().replaceAll("\\s+", "")
                : "com.ccs." + safe.toLowerCase().replace('-', '_');
        String level = (javaTarget == null || javaTarget.trim().isEmpty())
                ? ProjectJdk.defaultForNewProject(context) : javaTarget.trim();

        File srcPkg = new File(root, "src/" + gid.replace('.', File.separatorChar));
        File testPkg = new File(root, "test/" + gid.replace('.', File.separatorChar));
        srcPkg.mkdirs();
        testPkg.mkdirs();
        // Empty but present: it is where a jar goes, and an Ant project with no
        // lib folder gives no hint that this is how dependencies arrive.
        new File(root, "lib").mkdirs();

        writeUtf8(new File(root, "build.xml"), buildScript(safe, gid, level));
        writeUtf8(new File(root, "build.properties"),
                "# Overrides for build.xml. Ant keeps the first value a name is\n"
                + "# given, so anything set here wins over the script's defaults.\n"
                + "version=1.0\n");

        writeUtf8(new File(srcPkg, "App.java"),
                "package " + gid + ";\n"
                + "\n"
                + "public class App {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello from \" + App.class.getPackage().getName());\n"
                + "    }\n"
                + "}\n");

        writeUtf8(new File(testPkg, "AppTest.java"),
                "package " + gid + ";\n"
                + "\n"
                + "import org.junit.Test;\n"
                + "import static org.junit.Assert.*;\n"
                + "\n"
                + "public class AppTest {\n"
                + "    @Test\n"
                + "    public void smoke() {\n"
                + "        assertTrue(true);\n"
                + "    }\n"
                + "}\n");

        writeUtf8(new File(root, ".gitignore"),
                "build/\n"
                + "target/\n"
                + ".wiredroid/\n"
                + "*.class\n"
                + "*.jar\n");

        return root;
    }

    /**
     * The {@code build.xml} this app writes.
     *
     * <p>A complete script rather than a stub: it declares the properties the
     * targets use, a classpath over {@code lib/}, and the four targets an Ant
     * project is expected to have. The app runs its own pipeline rather than
     * these targets, but the script has to be a real one — it is what the
     * project is, and what another machine will build with.</p>
     */
    public static String buildScript(String projectName, String groupId, String javaLevel) {
        String mainClass = groupId + ".App";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<project name=\"" + projectName + "\" default=\"jar\" basedir=\".\">\n"
             + "\n"
             + "    <property file=\"build.properties\"/>\n"
             + "    <property name=\"src.dir\" value=\"src\"/>\n"
             + "    <property name=\"test.dir\" value=\"test\"/>\n"
             + "    <property name=\"lib.dir\" value=\"lib\"/>\n"
             + "    <property name=\"build.dir\" value=\"build\"/>\n"
             + "    <property name=\"classes.dir\" value=\"${build.dir}/classes\"/>\n"
             + "    <property name=\"main.class\" value=\"" + mainClass + "\"/>\n"
             + "\n"
             + "    <path id=\"classpath\">\n"
             + "        <fileset dir=\"${lib.dir}\" includes=\"**/*.jar\"/>\n"
             + "    </path>\n"
             + "\n"
             + "    <target name=\"clean\">\n"
             + "        <delete dir=\"${build.dir}\"/>\n"
             + "    </target>\n"
             + "\n"
             + "    <target name=\"compile\">\n"
             + "        <mkdir dir=\"${classes.dir}\"/>\n"
             + "        <javac srcdir=\"${src.dir}\" destdir=\"${classes.dir}\"\n"
             + "               source=\"" + javaLevel + "\" target=\"" + javaLevel + "\"\n"
             + "               includeantruntime=\"false\" encoding=\"UTF-8\">\n"
             + "            <classpath refid=\"classpath\"/>\n"
             + "        </javac>\n"
             + "    </target>\n"
             + "\n"
             + "    <target name=\"jar\" depends=\"compile\">\n"
             + "        <jar destfile=\"${build.dir}/" + projectName + ".jar\"\n"
             + "             basedir=\"${classes.dir}\">\n"
             + "            <manifest>\n"
             + "                <attribute name=\"Main-Class\" value=\"${main.class}\"/>\n"
             + "            </manifest>\n"
             + "        </jar>\n"
             + "    </target>\n"
             + "\n"
             + "    <target name=\"run\" depends=\"jar\">\n"
             + "        <java classname=\"${main.class}\" fork=\"true\">\n"
             + "            <classpath>\n"
             + "                <path refid=\"classpath\"/>\n"
             + "                <pathelement location=\"${classes.dir}\"/>\n"
             + "            </classpath>\n"
             + "        </java>\n"
             + "    </target>\n"
             + "\n"
             + "</project>\n";
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
