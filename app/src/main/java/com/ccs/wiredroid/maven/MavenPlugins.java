package com.ccs.wiredroid.maven;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * The build plugins declared in a pom, for display only.
 *
 * <p>Read here rather than in {@link PomParser} on purpose. That parser feeds
 * compilation, dependency resolution and packaging; every project in the app
 * depends on it being right. Plugins are needed by one branch of one tree, so
 * they are read separately and a failure costs an empty list rather than a
 * project that will not build.</p>
 */
public final class MavenPlugins {

    public static final class Plugin {
        public final String groupId;
        public final String artifactId;
        public final String version;

        Plugin(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        /** {@code artifactId:version}, or just the artifact when the version is inherited. */
        public String display() {
            if (version == null || version.isEmpty()) return artifactId;
            return artifactId + ":" + version;
        }
    }

    private MavenPlugins() {
    }

    public static List<Plugin> of(File projectRoot) {
        List<Plugin> out = new ArrayList<>();
        File pom = new File(projectRoot, "pom.xml");
        if (!pom.isFile()) return out;
        try (Reader r = new FileReader(pom)) {
            read(r, out);
        } catch (Throwable ignored) {
            // A malformed pom is already reported by the build model; here it
            // simply means there is nothing to list.
        }
        return out;
    }

    private static void read(Reader reader, List<Plugin> out) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser p = f.newPullParser();
        p.setInput(reader);

        // Only <plugin> elements inside <plugins> count; a <dependency> nested in
        // a plugin configuration must not be mistaken for one.
        boolean inPlugins = false;
        boolean inPlugin = false;
        String groupId = null, artifactId = null, version = null;
        String tag = null;

        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("plugins".equals(tag)) inPlugins = true;
                else if (inPlugins && "plugin".equals(tag)) {
                    inPlugin = true;
                    groupId = artifactId = version = null;
                }
            } else if (e == XmlPullParser.TEXT && inPlugin && tag != null) {
                String text = p.getText();
                if (text == null || text.trim().isEmpty()) continue;
                switch (tag) {
                    case "groupId":    groupId = text.trim();    break;
                    case "artifactId": artifactId = text.trim(); break;
                    case "version":    version = text.trim();    break;
                    default: break;
                }
            } else if (e == XmlPullParser.END_TAG) {
                String end = p.getName();
                if ("plugin".equals(end) && inPlugin) {
                    inPlugin = false;
                    if (artifactId != null) {
                        out.add(new Plugin(
                                groupId == null ? "org.apache.maven.plugins" : groupId,
                                artifactId, version));
                    }
                } else if ("plugins".equals(end)) {
                    inPlugins = false;
                }
                tag = null;
            }
        }
    }
}
