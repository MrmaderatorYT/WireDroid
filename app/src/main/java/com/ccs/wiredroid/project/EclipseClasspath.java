package com.ccs.wiredroid.project;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of an Eclipse {@code .classpath} that survive the trip to Gradle.
 *
 * <pre>
 *   &lt;classpathentry kind="src" path="src"/&gt;
 *   &lt;classpathentry kind="src" path="test" test="true"/&gt;
 *   &lt;classpathentry kind="lib" path="lib/guava.jar"/&gt;
 *   &lt;classpathentry kind="con" path="…JRE_CONTAINER"/&gt;
 * </pre>
 *
 * <p>Only {@code src} and {@code lib} carry information a build script can
 * reproduce. A {@code con} entry names an Eclipse container (the JRE, or a
 * plugin's own classpath) that has no Gradle equivalent, and a {@code src}
 * entry whose path starts with {@code /} points at a different project in the
 * same Eclipse workspace — neither is in the clone, so both are recorded as
 * warnings rather than silently dropped.</p>
 */
public final class EclipseClasspath {

    /** Source roots relative to the project, in declaration order. */
    public final List<String> sourceRoots = new ArrayList<>();
    /** Source roots Eclipse marked as tests. */
    public final List<String> testSourceRoots = new ArrayList<>();
    /** Jar paths relative to the project. */
    public final List<String> libs = new ArrayList<>();
    /** Entries that could not be carried over, phrased for the user. */
    public final List<String> unsupported = new ArrayList<>();

    private EclipseClasspath() {}

    /** True when the directory carries both files Eclipse needs to open a project. */
    public static boolean isEclipseProject(File root) {
        return root != null
                && new File(root, ".project").isFile()
                && new File(root, ".classpath").isFile();
    }

    public static File classpathFile(File root) {
        return new File(root, ".classpath");
    }

    /**
     * Reads {@code .classpath}. A malformed file yields whatever was read before
     * the error rather than an exception: a partially recovered source root is
     * still better than treating the project as unknown.
     */
    public static EclipseClasspath parse(File classpathFile) {
        EclipseClasspath out = new EclipseClasspath();
        if (classpathFile == null || !classpathFile.isFile()) return out;
        try (Reader reader = new InputStreamReader(
                new FileInputStream(classpathFile), StandardCharsets.UTF_8)) {
            out.read(reader);
        } catch (Exception ignored) {
            // Keep the partial result; the caller falls back to a plain-source layout.
        }
        return out;
    }

    private void read(Reader reader) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(reader);

        for (int event = parser.getEventType();
             event != XmlPullParser.END_DOCUMENT;
             event = parser.next()) {
            if (event != XmlPullParser.START_TAG) continue;
            if (!"classpathentry".equalsIgnoreCase(parser.getName())) continue;

            String kind = attr(parser, "kind");
            String path = attr(parser, "path");
            boolean test = "true".equalsIgnoreCase(attr(parser, "test"));
            if (kind == null || path == null) continue;
            path = path.trim();
            if (path.isEmpty()) continue;
            kind = kind.trim().toLowerCase(Locale.ROOT);

            switch (kind) {
                case "src":
                    if (path.startsWith("/")) {
                        // A workspace-relative reference to a sibling project.
                        unsupported.add("src " + path);
                    } else if (test || looksLikeTests(path)) {
                        addOnce(testSourceRoots, normalize(path));
                    } else {
                        addOnce(sourceRoots, normalize(path));
                    }
                    break;
                case "lib":
                    if (path.startsWith("/")) {
                        unsupported.add("lib " + path);
                    } else {
                        addOnce(libs, normalize(path));
                    }
                    break;
                case "con":
                    if (!path.contains("JRE_CONTAINER")) unsupported.add("con " + path);
                    break;
                case "var":
                    unsupported.add("var " + path);
                    break;
                default:
                    // "output" is Eclipse's bin/ directory — Gradle picks its own.
                    break;
            }
        }
    }

    /** True when nothing usable was found and the caller should look elsewhere. */
    public boolean isEmpty() {
        return sourceRoots.isEmpty() && testSourceRoots.isEmpty() && libs.isEmpty();
    }

    private static boolean looksLikeTests(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.equals("test") || p.equals("tests")
                || p.startsWith("test/") || p.startsWith("tests/")
                || p.startsWith("src/test");
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static void addOnce(List<String> list, String value) {
        if (!value.isEmpty() && !list.contains(value)) list.add(value);
    }

    private static String attr(XmlPullParser parser, String name) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equalsIgnoreCase(parser.getAttributeName(i))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }
}
