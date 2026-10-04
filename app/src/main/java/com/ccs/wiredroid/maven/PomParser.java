package com.ccs.wiredroid.maven;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Full pom.xml parser: coordinates, properties, dependencies (with scope, exclusions),
 * parent POM, repositories, profiles, dependencyManagement.
 */
public final class PomParser {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private PomParser() {}

    // ─── Public API ─────────────────────────────────────────────────────────

    public static PomModel parse(File pomFile) throws IOException, XmlPullParserException {
        try (FileReader reader = new FileReader(pomFile)) {
            return parse(reader);
        }
    }

    public static PomModel parse(Reader reader) throws XmlPullParserException, IOException {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(reader);

        PomModel model = new PomModel();

        // State machine flags
        boolean inProperties = false;
        boolean inDependency = false;
        boolean inParent = false;
        boolean inRepositories = false;
        boolean inRepository = false;
        boolean inDepMgmt = false;
        boolean inProfiles = false;
        boolean inProfile = false;
        boolean inExclusions = false;
        boolean inProfileProps = false;

        int propDepth = -1;
        int profilePropDepth = -1;

        PomModel.MavenDependency cur = null;
        PomModel.MavenRepository curRepo = null;
        PomModel.MavenProfile curProfile = null;
        PomModel.MavenDependency curExcl = null;

        String textTarget = null;

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = localName(p);
                int depth = p.getDepth();

                switch (name) {
                    case "parent":
                        inParent = true;
                        break;
                    case "properties":
                        if (inProfile) {
                            inProfileProps = true;
                            profilePropDepth = depth;
                        } else {
                            inProperties = true;
                            propDepth = depth;
                        }
                        textTarget = null;
                        break;
                    case "dependency":
                        inDependency = true;
                        cur = new PomModel.MavenDependency();
                        textTarget = null;
                        break;
                    case "repositories":
                        inRepositories = true;
                        break;
                    case "repository":
                        if (inRepositories) {
                            inRepository = true;
                            curRepo = new PomModel.MavenRepository();
                        }
                        break;
                    case "profiles":
                        inProfiles = true;
                        break;
                    case "profile":
                        if (inProfiles) {
                            inProfile = true;
                            curProfile = new PomModel.MavenProfile();
                        }
                        break;
                    case "dependencyManagement":
                        inDepMgmt = true;
                        break;
                    case "exclusions":
                        inExclusions = true;
                        break;
                    case "exclusion":
                        if (inExclusions && cur != null) {
                            curExcl = new PomModel.MavenDependency();
                        }
                        break;

                    // ── Dependency fields ──
                    // The three coordinate tags mean different things depending
                    // on what encloses them. Inside <parent> they are the parent's,
                    // and the text handler below routes them there — which it never
                    // got to do while this skipped them, leaving parentGroupId null
                    // and the parent pom unfetchable. A dependency whose version
                    // lives in a parent property then kept the literal ${...} and
                    // was downloaded from a URL that answered 404.
                    case "groupId":
                        if (inExclusions && curExcl != null) textTarget = "xg";
                        else if (inDependency && cur != null) textTarget = "dg";
                        else if (inRepository && curRepo != null) textTarget = "rg";
                        else if (inParent) textTarget = "g";
                        else if (!inProperties && !inDepMgmt && !inProfileProps && depth == 2) textTarget = "g";
                        else textTarget = null;
                        break;
                    case "artifactId":
                        if (inExclusions && curExcl != null) textTarget = "xa";
                        else if (inDependency && cur != null) textTarget = "da";
                        else if (inParent) textTarget = "a";
                        else if (!inProperties && !inDepMgmt && !inProfileProps && depth == 2) textTarget = "a";
                        else textTarget = null;
                        break;
                    case "version":
                        if (inExclusions && curExcl != null) { textTarget = null; break; }
                        if (inDependency && cur != null) textTarget = "dv";
                        else if (inRepository && curRepo != null) textTarget = null;
                        else if (inParent) textTarget = "v";
                        else if (!inProperties && !inDepMgmt && !inProfileProps && depth == 2) textTarget = "v";
                        else textTarget = null;
                        break;
                    case "scope":
                        if (inDependency && cur != null) textTarget = "ds";
                        else textTarget = null;
                        break;
                    case "classifier":
                        if (inDependency && cur != null) textTarget = "dc";
                        else textTarget = null;
                        break;
                    case "type":
                        if (inDependency && cur != null) textTarget = "dt";
                        else textTarget = null;
                        break;
                    case "optional":
                        if (inDependency && cur != null) textTarget = "do";
                        else textTarget = null;
                        break;
                    case "packaging":
                        if (!inProperties && !inParent && !inDepMgmt && !inProfileProps && depth == 2) textTarget = "pkg";
                        else textTarget = null;
                        break;

                    // ── Repository fields ──
                    case "url":
                        if (inRepository && curRepo != null) textTarget = "ru";
                        else textTarget = null;
                        break;
                    case "id":
                        if (inRepository && curRepo != null) {
                            textTarget = "ri";
                        } else if (inProfile && curProfile != null) {
                            textTarget = "pi";
                        } else {
                            textTarget = null;
                        }
                        break;

                    // ── Profile fields ──
                    case "activation":
                        // just enter activation context — handled by child elements
                        break;
                    case "activeByDefault":
                        textTarget = "pad";
                        break;
                    case "jdk":
                        if (inProfile) textTarget = "paj";
                        else textTarget = null;
                        break;

                    default:
                        // Unknown tag — could be a property name inside <properties>
                        if (inProperties && propDepth >= 0 && depth == propDepth + 1) {
                            textTarget = "prop:" + name;
                        } else if (inProfileProps && profilePropDepth >= 0 && depth == profilePropDepth + 1) {
                            textTarget = "pprop:" + name;
                        } else {
                            textTarget = null;
                        }
                        break;
                }

            } else if (event == XmlPullParser.TEXT) {
                String text = p.getText();
                if (text == null || textTarget == null) {
                    event = p.next();
                    continue;
                }
                text = text.trim();
                if (text.isEmpty()) {
                    event = p.next();
                    continue;
                }

                // ── Root project coordinates ──
                if ("g".equals(textTarget)) {
                    if (inParent) model.parentGroupId = text;
                    else model.groupId = text;
                }
                else if ("a".equals(textTarget)) {
                    if (inParent) model.parentArtifactId = text;
                    else model.artifactId = text;
                }
                else if ("v".equals(textTarget)) {
                    if (inParent) model.parentVersion = text;
                    else model.version = text;
                }
                else if ("pkg".equals(textTarget)) model.packaging = text;

                // ── Properties ──
                else if (textTarget != null && textTarget.startsWith("prop:")) {
                    model.properties.put(textTarget.substring(5), text);
                }
                else if (textTarget != null && textTarget.startsWith("pprop:")) {
                    if (curProfile != null) {
                        curProfile.properties.put(textTarget.substring(6), text);
                    }
                }

                // ── Dependency fields ──
                else if ("dg".equals(textTarget)) cur.groupId = text;
                else if ("da".equals(textTarget)) cur.artifactId = text;
                else if ("dv".equals(textTarget)) cur.version = text;
                else if ("ds".equals(textTarget)) cur.scope = text;
                else if ("dc".equals(textTarget)) cur.classifier = text;
                else if ("dt".equals(textTarget)) cur.type = text;
                else if ("do".equals(textTarget)) cur.optional = "true".equalsIgnoreCase(text);

                // ── Exclusion fields ──
                else if ("xg".equals(textTarget) && curExcl != null) curExcl.groupId = text;
                else if ("xa".equals(textTarget) && curExcl != null) curExcl.artifactId = text;

                // ── Repository fields ──
                else if ("rg".equals(textTarget) && curRepo != null) curRepo.id = text;
                else if ("ru".equals(textTarget) && curRepo != null) curRepo.url = text;
                else if ("ri".equals(textTarget) && curRepo != null) curRepo.id = text;

                // ── Profile fields ──
                else if ("pi".equals(textTarget) && curProfile != null) curProfile.id = text;
                else if ("pad".equals(textTarget) && curProfile != null) curProfile.activeByDefault = "true".equalsIgnoreCase(text);
                else if ("paj".equals(textTarget) && curProfile != null) curProfile.activationJdk = text;

            } else if (event == XmlPullParser.END_TAG) {
                String name = localName(p);

                switch (name) {
                    case "parent":
                        inParent = false;
                        textTarget = null;
                        break;
                    case "properties":
                        inProperties = false;
                        inProfileProps = false;
                        propDepth = -1;
                        profilePropDepth = -1;
                        textTarget = null;
                        break;
                    case "dependency":
                        if (cur != null && cur.groupId != null && cur.artifactId != null) {
                            if (inDepMgmt) {
                                model.dependencyManagement.add(cur);
                            } else if (inProfile && curProfile != null) {
                                curProfile.dependencies.add(cur);
                            } else {
                                model.dependencies.add(cur);
                            }
                        }
                        inDependency = false;
                        cur = null;
                        textTarget = null;
                        break;
                    case "repository":
                        if (curRepo != null && curRepo.url != null) {
                            model.repositories.add(curRepo);
                        }
                        inRepository = false;
                        curRepo = null;
                        textTarget = null;
                        break;
                    case "repositories":
                        inRepositories = false;
                        textTarget = null;
                        break;
                    case "profile":
                        if (curProfile != null) model.profiles.add(curProfile);
                        inProfile = false;
                        curProfile = null;
                        textTarget = null;
                        break;
                    case "profiles":
                        inProfiles = false;
                        textTarget = null;
                        break;
                    case "dependencyManagement":
                        inDepMgmt = false;
                        textTarget = null;
                        break;
                    case "exclusion":
                        if (curExcl != null && cur != null) {
                            cur.exclusions.add(curExcl);
                        }
                        curExcl = null;
                        textTarget = null;
                        break;
                    case "exclusions":
                        inExclusions = false;
                        textTarget = null;
                        break;
                    default:
                        textTarget = null;
                        break;
                }
            }
            event = p.next();
        }

        // ── Post-processing ─────────────────────────────────────────────────

        // Inherit missing groupId/version from parent
        model.inheritFromParent();

        // Resolve mainClass property
        String mc = model.properties.get("mainClass");
        if (mc != null) model.mainClass = model.resolveProperty(mc);

        // Resolve ${} in dependencies
        for (PomModel.MavenDependency d : model.dependencies) {
            d.groupId = model.resolveProperty(d.groupId);
            d.artifactId = model.resolveProperty(d.artifactId);
            d.version = model.resolveProperty(d.version);
        }
        // Resolve ${} in dependencyManagement
        for (PomModel.MavenDependency d : model.dependencyManagement) {
            d.groupId = model.resolveProperty(d.groupId);
            d.artifactId = model.resolveProperty(d.artifactId);
            d.version = model.resolveProperty(d.version);
        }
        // Resolve ${} in model coordinates
        model.version = model.resolveProperty(model.version);
        model.groupId = model.resolveProperty(model.groupId);
        model.artifactId = model.resolveProperty(model.artifactId);

        // Activate profiles and merge their properties/dependencies
        for (PomModel.MavenProfile prof : model.activeProfiles()) {
            model.mergeParentProperties(prof.properties);
            // Merge profile dependencies (only if not already present)
            for (PomModel.MavenDependency pd : prof.dependencies) {
                pd.groupId = model.resolveProperty(pd.groupId);
                pd.artifactId = model.resolveProperty(pd.artifactId);
                pd.version = model.resolveProperty(pd.version);
                boolean exists = false;
                for (PomModel.MavenDependency existing : model.dependencies) {
                    if (existing.key().equals(pd.key())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) model.dependencies.add(pd);
            }
        }

        return model;
    }

    /**
     * Download and parse a parent POM from Maven Central.
     * Returns null if not found or on error.
     */
    public static PomModel parseRemoteParent(String groupId, String artifactId, String version) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + ".pom";
        String urlStr = MAVEN_CENTRAL + path;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            try (InputStream is = c.getInputStream()) {
                return parse(new java.io.InputStreamReader(is, "UTF-8"));
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Download and parse a dependency's POM from a list of repositories (including Central).
     * Returns null if not found.
     */
    public static PomModel parseRemoteDependencyPom(List<PomModel.MavenRepository> repos,
                                                     String groupId, String artifactId,
                                                     String version) {
        String fileName = artifactId + "-" + version + ".pom";
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName;

        // Try custom repos first, then Central
        List<String> urls = new ArrayList<>();
        for (PomModel.MavenRepository repo : repos) {
            urls.add(repo.url + "/" + path);
        }
        urls.add(MAVEN_CENTRAL + path);

        for (String urlStr : urls) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) {
                    c.disconnect();
                    continue;
                }
                try (InputStream is = c.getInputStream()) {
                    return parse(new java.io.InputStreamReader(is, "UTF-8"));
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String localName(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return "";
        int c = n.lastIndexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }
}
