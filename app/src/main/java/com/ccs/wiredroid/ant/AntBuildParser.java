package com.ccs.wiredroid.ant;

import com.ccs.wiredroid.maven.PomModel;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads an Ant {@code build.xml} into the same {@link PomModel} the Maven and
 * Gradle pipelines consume, so an Ant project compiles, runs and packages
 * through the existing on-device toolchain without an Ant runtime.
 *
 * <p>What the script is asked for:</p>
 * <ul>
 *   <li>{@code <project name=…>} — the artifact name</li>
 *   <li>{@code <property name= value=>} and {@code <property file=>}, with
 *       {@code ${…}} interpolation</li>
 *   <li>{@code <javac srcdir= destdir= source= target= release=>} — where the
 *       sources are and which Java level they want</li>
 *   <li>{@code Main-Class} from a {@code <jar>} manifest, or
 *       {@code <java classname=>} from a run target</li>
 *   <li>{@code <fileset dir=>} inside a {@code <path>} — the folders holding
 *       the jars this project depends on</li>
 * </ul>
 *
 * <p>Anything that needs Ant itself to execute — custom {@code <taskdef>}s,
 * {@code <antcall>}, {@code <exec>}, Ivy — is reported through
 * {@link Result#warnings} rather than failing the build, the same way the
 * Gradle parser treats constructs it cannot represent.</p>
 */
public final class AntBuildParser {

    /** Parse outcome: the model plus what could not be represented. */
    public static final class Result {
        public final PomModel pom = new PomModel();
        public final List<String> warnings = new ArrayList<>();
        /** Declared {@code srcdir}, or null when the script names none. */
        public File sourceDir;
        /** Declared {@code destdir}, or null. */
        public File outputDir;
        /** Folders the script scans for jars. */
        public final List<File> libraryDirs = new ArrayList<>();
        /** The build script's own targets, in declaration order. */
        public final List<String> targets = new ArrayList<>();
        /** The target Ant would run when given none. */
        public String defaultTarget;
    }

    /** Tasks that only a real Ant can carry out. */
    private static final String[] NEEDS_ANT = {
            "taskdef", "typedef", "macrodef", "antcall", "subant", "ant",
            "exec", "apply", "scriptdef", "import"
    };

    private AntBuildParser() {}

    public static Result parse(File projectRoot) throws IOException {
        Result result = new Result();
        File build = AntPaths.buildFile(projectRoot);
        if (build == null || !build.isFile()) {
            throw new IOException("No build.xml in "
                    + (projectRoot != null ? projectRoot.getName() : "null"));
        }

        Document document;
        try (InputStream in = new FileInputStream(build)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Ant scripts are plain XML; resolving anything external would reach
            // off the device for a document that is not supposed to need it.
            factory.setNamespaceAware(false);
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot parse build.xml: " + e.getMessage(), e);
        }

        Element project = document.getDocumentElement();
        if (project == null || !"project".equalsIgnoreCase(project.getTagName())) {
            throw new IOException("build.xml has no <project> root");
        }

        String name = attr(project, "name");
        if (name != null && !name.isEmpty()) result.pom.artifactId = name;
        result.defaultTarget = attr(project, "default");
        result.pom.packaging = "jar";

        // Ant properties are immutable: the first setting of a name wins, and
        // later ones are ignored rather than overwriting it. Walking the
        // document in order and refusing to replace reproduces that.
        Map<String, String> properties = new LinkedKeepFirstMap();
        collect(project, projectRoot, properties, result);

        for (Map.Entry<String, String> e : properties.entrySet()) {
            result.pom.properties.put(e.getKey(), expand(e.getValue(), properties));
        }
        String version = result.pom.properties.get("version");
        if (version == null) version = result.pom.properties.get("project.version");
        if (version != null && !version.isEmpty()) result.pom.version = version;

        Set<String> unsupported = new LinkedHashSet<>();
        findUnsupported(project, unsupported);
        for (String task : unsupported) {
            result.warnings.add("<" + task + "> needs a real Ant run and was skipped");
        }
        if (new File(projectRoot, "ivy.xml").isFile()) {
            result.warnings.add("ivy.xml found — Ivy dependencies are not resolved; "
                    + "put the jars in lib/ instead");
        }
        return result;
    }

    /** Walks the document once, in order, gathering everything of interest. */
    private static void collect(Element element, File projectRoot,
                                Map<String, String> properties, Result result) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String tag = child.getTagName().toLowerCase(Locale.ROOT);

            switch (tag) {
                case "property":
                    readProperty(child, projectRoot, properties, result);
                    break;
                case "target": {
                    String targetName = attr(child, "name");
                    if (targetName != null && !targetName.isEmpty()) result.targets.add(targetName);
                    break;
                }
                case "javac":
                    readJavac(child, projectRoot, properties, result);
                    break;
                case "java": {
                    String className = attr(child, "classname");
                    // A jar manifest is the stronger statement, so it is not
                    // overwritten by a <java> task found later.
                    if (className != null && !className.isEmpty() && result.pom.mainClass == null) {
                        result.pom.mainClass = expand(className, properties);
                    }
                    break;
                }
                case "manifest":
                    readManifest(child, properties, result);
                    break;
                case "fileset": {
                    String dir = attr(child, "dir");
                    if (dir != null && !dir.isEmpty()) {
                        File resolved = resolve(projectRoot, expand(dir, properties));
                        if (resolved != null && !result.libraryDirs.contains(resolved)) {
                            result.libraryDirs.add(resolved);
                        }
                    }
                    break;
                }
                default:
                    break;
            }
            collect(child, projectRoot, properties, result);
        }
    }

    private static void readProperty(Element element, File projectRoot,
                                     Map<String, String> properties, Result result) {
        String name = attr(element, "name");
        String value = attr(element, "value");
        if (name != null && value != null) {
            properties.put(name, value);
            return;
        }
        String location = attr(element, "location");
        if (name != null && location != null) {
            properties.put(name, location);
            return;
        }
        String file = attr(element, "file");
        if (file != null) {
            File resolved = resolve(projectRoot, expand(file, properties));
            if (resolved == null || !resolved.isFile()) {
                // A missing property file is how a script says "override these
                // if you like"; Ant carries on and so does this.
                return;
            }
            Properties loaded = new Properties();
            try (InputStream in = new FileInputStream(resolved)) {
                loaded.load(in);
            } catch (IOException e) {
                result.warnings.add("Could not read " + resolved.getName());
                return;
            }
            for (String key : loaded.stringPropertyNames()) {
                properties.put(key, loaded.getProperty(key));
            }
        }
    }

    private static void readJavac(Element element, File projectRoot,
                                  Map<String, String> properties, Result result) {
        String srcdir = attr(element, "srcdir");
        if (srcdir != null && result.sourceDir == null) {
            // srcdir may be a path list; the first entry is the one the
            // compiler here can use, and the rest are reported.
            String[] parts = expand(srcdir, properties).split("[:;,]");
            if (parts.length > 0) result.sourceDir = resolve(projectRoot, parts[0].trim());
            if (parts.length > 1) {
                result.warnings.add("javac srcdir names " + parts.length
                        + " directories; only the first is compiled");
            }
        }
        String destdir = attr(element, "destdir");
        if (destdir != null && result.outputDir == null) {
            result.outputDir = resolve(projectRoot, expand(destdir, properties));
        }

        // The Java level, under the names the rest of the app already reads.
        String release = attr(element, "release");
        String source = attr(element, "source");
        String target = attr(element, "target");
        if (release != null) {
            result.pom.properties.put("maven.compiler.release", expand(release, properties));
        }
        if (source != null) {
            result.pom.properties.put("maven.compiler.source", expand(source, properties));
        }
        if (target != null) {
            result.pom.properties.put("maven.compiler.target", expand(target, properties));
        }
    }

    private static void readManifest(Element manifest, Map<String, String> properties,
                                     Result result) {
        NodeList attributes = manifest.getElementsByTagName("attribute");
        for (int i = 0; i < attributes.getLength(); i++) {
            Element attribute = (Element) attributes.item(i);
            if ("Main-Class".equalsIgnoreCase(attr(attribute, "name"))) {
                String value = attr(attribute, "value");
                if (value != null && !value.isEmpty()) {
                    result.pom.mainClass = expand(value, properties);
                }
            }
        }
    }

    private static void findUnsupported(Element element, Set<String> found) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String tag = child.getTagName().toLowerCase(Locale.ROOT);
            for (String task : NEEDS_ANT) {
                if (tag.equals(task)) found.add(tag);
            }
            if (tag.startsWith("ivy:")) found.add("ivy");
            findUnsupported(child, found);
        }
    }

    /** Replaces {@code ${name}} with what the script set, leaving unknowns alone. */
    static String expand(String raw, Map<String, String> properties) {
        if (raw == null || raw.indexOf('$') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        // Bounded rather than recursive: a script that defines ${a} as ${b} and
        // ${b} as ${a} must not take the parser down with it.
        while (i < raw.length()) {
            int open = raw.indexOf("${", i);
            if (open < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            int close = raw.indexOf('}', open);
            if (close < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            out.append(raw, i, open);
            String key = raw.substring(open + 2, close);
            String value = properties.get(key);
            if (value == null) {
                out.append(raw, open, close + 1);
            } else if (value.contains("${")) {
                out.append(expandOnce(value, properties));
            } else {
                out.append(value);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** One more level of substitution, with no further nesting. */
    private static String expandOnce(String raw, Map<String, String> properties) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int open = raw.indexOf("${", i);
            if (open < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            int close = raw.indexOf('}', open);
            if (close < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            out.append(raw, i, open);
            String value = properties.get(raw.substring(open + 2, close));
            out.append(value != null ? value : raw.substring(open, close + 1));
            i = close + 1;
        }
        return out.toString();
    }

    private static File resolve(File projectRoot, String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        return file.isAbsolute() ? file : new File(projectRoot, path);
    }

    private static String attr(Element element, String name) {
        if (!element.hasAttribute(name)) return null;
        String value = element.getAttribute(name);
        return value == null ? null : value.trim();
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean on) {
        try {
            factory.setFeature(feature, on);
        } catch (Exception ignored) {
            // Not every parser implementation knows every feature; the ones that
            // do not are no less able to read a build file.
        }
    }

    /** A map that keeps the first value put under a name, as Ant properties do. */
    private static final class LinkedKeepFirstMap extends LinkedHashMap<String, String> {
        @Override
        public String put(String key, String value) {
            if (containsKey(key)) return get(key);
            return super.put(key, value);
        }
    }
}
