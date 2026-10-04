package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.tools.compilers.JavaVersions;
import com.ccs.wiredroid.util.AppPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Java release one project targets.
 *
 * <p>The level belongs in the project's own build file: that is the file a
 * desktop build obeys and the one the user can read. Preferences hold only two
 * things — the global default a new project starts from, and a per-project
 * fallback for trees that declare nothing at all (a bytecode scratch project, a
 * bare source folder). Resolution therefore consults the build file first, so a
 * pom edited by hand beats anything this class wrote earlier; the alternative
 * would have the IDE quietly disagreeing with the build.</p>
 *
 * <p>Project creation and Project Structure both go through here, and both draw
 * their selectable releases from {@link JavaVersions#all()}, so the two surfaces
 * cannot drift apart.</p>
 */
public final class ProjectJdk {

    /** Per-project fallback, keyed by absolute project path. */
    private static final String K_PREFIX = "project_java_target:";

    /** Build files are small; anything larger is not one and is not parsed. */
    private static final long MAX_BUILD_FILE = 2L * 1024 * 1024;

    private static final String[] GRADLE_FILES = {"build.gradle", "build.gradle.kts"};

    // ── pom.xml, properties form ──────────────────────────────────────────
    private static final Pattern POM_PROP_RELEASE =
            Pattern.compile("<maven\\.compiler\\.release>([^<]*)</maven\\.compiler\\.release>");
    private static final Pattern POM_PROP_SOURCE =
            Pattern.compile("<maven\\.compiler\\.source>([^<]*)</maven\\.compiler\\.source>");
    private static final Pattern POM_PROP_TARGET =
            Pattern.compile("<maven\\.compiler\\.target>([^<]*)</maven\\.compiler\\.target>");

    // ── pom.xml, maven-compiler-plugin configuration form ─────────────────
    private static final Pattern POM_PLUGIN_RELEASE = Pattern.compile("<release>([^<]*)</release>");
    private static final Pattern POM_PLUGIN_SOURCE  = Pattern.compile("<source>([^<]*)</source>");
    private static final Pattern POM_PLUGIN_TARGET  = Pattern.compile("<target>([^<]*)</target>");

    // ── Gradle ────────────────────────────────────────────────────────────
    private static final Pattern GRADLE_SOURCE_CONST =
            Pattern.compile("sourceCompatibility\\s*=\\s*JavaVersion\\.VERSION_[0-9_]+");
    private static final Pattern GRADLE_TARGET_CONST =
            Pattern.compile("targetCompatibility\\s*=\\s*JavaVersion\\.VERSION_[0-9_]+");
    private static final Pattern GRADLE_CONST_READ =
            Pattern.compile("(?:source|target)Compatibility\\s*=\\s*JavaVersion\\.VERSION_([0-9_]+)");
    /** Quotes are captured so the rewrite keeps whichever style the script used. */
    private static final Pattern GRADLE_SOURCE_PLAIN =
            Pattern.compile("(sourceCompatibility\\s*=\\s*)(['\"]?)([0-9][0-9.]*)(\\2)");
    private static final Pattern GRADLE_TARGET_PLAIN =
            Pattern.compile("(targetCompatibility\\s*=\\s*)(['\"]?)([0-9][0-9.]*)(\\2)");
    private static final Pattern GRADLE_PLAIN_READ =
            Pattern.compile("(?:source|target)Compatibility\\s*=\\s*['\"]?([0-9][0-9.]*)['\"]?");
    private static final Pattern KOTLIN_JVM_TARGET = Pattern.compile("JvmTarget\\.JVM_[0-9_]+");

    // Live problems re-enter resolve() on every analysis pass, so the build
    // file is read once per modification rather than once per keystroke.
    private static final Object LOCK = new Object();
    private static String cachedRoot;
    private static long cachedStamp;
    private static String cachedCode;
    private static boolean cacheValid;

    private ProjectJdk() {}

    // ── Reading ───────────────────────────────────────────────────────────

    /** The global default a newly created project starts from. */
    public static String defaultForNewProject(Context context) {
        try {
            return JavaVersions.normalize(new AppPreferences(context).getJavaTarget());
        } catch (Exception e) {
            return AppPreferences.JAVA_8;
        }
    }

    /**
     * The release {@code projectRoot} targets: what its build file declares,
     * else what was stored for it, else the global default.
     */
    public static String resolve(Context context, File projectRoot) {
        if (projectRoot != null) {
            String declared = declaredCached(projectRoot);
            if (declared != null) return declared;
            try {
                String stored = new AppPreferences(context).raw()
                        .getString(K_PREFIX + projectRoot.getAbsolutePath(), null);
                if (stored != null) return JavaVersions.normalize(stored);
            } catch (Exception ignored) {
                // Fall through to the global default.
            }
        }
        return defaultForNewProject(context);
    }

    /** {@link #resolve} for whichever project is currently open. */
    public static String forOpenProject(Context context) {
        String root;
        try {
            root = new AppPreferences(context).getProjectRoot();
        } catch (Exception e) {
            root = null;
        }
        return resolve(context, root == null ? null : new File(root));
    }

    /**
     * The release the project's build file names, or {@code null} when it names
     * none — an unresolved {@code ${property}} placeholder counts as none.
     */
    public static String declaredIn(File projectRoot) {
        if (projectRoot == null) return null;

        String pom = readUtf8(new File(projectRoot, "pom.xml"));
        if (pom != null) {
            String level = firstValidLevel(pom, POM_PROP_RELEASE, POM_PROP_TARGET, POM_PROP_SOURCE);
            // <source>/<target> are shared with several other plugins, so they
            // only count in a pom that configures the compiler at all.
            if (level == null && pom.contains("maven-compiler-plugin")) {
                level = firstValidLevel(pom, POM_PLUGIN_RELEASE, POM_PLUGIN_TARGET, POM_PLUGIN_SOURCE);
            }
            if (level != null) return JavaVersions.normalize(level);
        }

        for (String name : GRADLE_FILES) {
            String script = readUtf8(new File(projectRoot, name));
            if (script == null) continue;
            Matcher m = GRADLE_CONST_READ.matcher(script);
            if (m.find()) {
                String suffix = m.group(1);
                String code = suffix.startsWith("1_") ? "1." + suffix.substring(2) : suffix;
                if (JavaVersions.feature(code) >= 0) return JavaVersions.normalize(code);
            }
            String plain = firstValidLevel(script, GRADLE_PLAIN_READ);
            if (plain != null) return JavaVersions.normalize(plain);
        }
        return null;
    }

    // ── Writing ───────────────────────────────────────────────────────────

    /**
     * Points {@code projectRoot} at {@code code}, rewriting its build files.
     *
     * @return true when a build file was updated; false means only the stored
     *         fallback changed, which still drives the on-device compiler
     */
    public static boolean set(Context context, File projectRoot, String code) {
        if (projectRoot == null) return false;
        String level = JavaVersions.normalize(code);
        try {
            new AppPreferences(context).raw().edit()
                    .putString(K_PREFIX + projectRoot.getAbsolutePath(), level)
                    .apply();
        } catch (Exception ignored) {
            // A missing preference only costs the fallback, not the rewrite.
        }
        boolean written = writeIntoBuildFiles(projectRoot, level);
        invalidate();
        return written;
    }

    /** Drops the cached build-file reading; call after editing a build file. */
    public static void invalidate() {
        synchronized (LOCK) {
            cacheValid = false;
            cachedRoot = null;
            cachedCode = null;
        }
    }

    /**
     * The compiler properties a freshly generated pom should carry.
     *
     * <p>{@code release} is the honest spelling from 9 onwards — it pins the API
     * as well as the bytecode — but it did not exist before then, so older
     * levels still get the {@code source}/{@code target} pair.</p>
     *
     * @param indent leading whitespace for each line, e.g. eight spaces
     */
    public static String pomCompilerProperties(String code, String indent) {
        String pad = indent == null ? "" : indent;
        String level = JavaVersions.normalize(code);
        int feature = JavaVersions.feature(level);
        if (feature >= 9) {
            return pad + "<maven.compiler.release>" + feature + "</maven.compiler.release>\n";
        }
        return pad + "<maven.compiler.source>" + level + "</maven.compiler.source>\n"
             + pad + "<maven.compiler.target>" + level + "</maven.compiler.target>\n";
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private static boolean writeIntoBuildFiles(File root, String level) {
        boolean any = false;

        File pom = new File(root, "pom.xml");
        String pomText = readUtf8(pom);
        if (pomText != null) {
            String updated = rewritePom(pomText, level);
            if (updated != null && !updated.equals(pomText) && writeUtf8(pom, updated)) any = true;
        }

        for (String name : GRADLE_FILES) {
            File file = new File(root, name);
            String text = readUtf8(file);
            if (text == null) continue;
            String updated = rewriteGradle(text, level);
            if (!updated.equals(text) && writeUtf8(file, updated)) any = true;
        }
        return any;
    }

    /**
     * A pom already declaring a level keeps the spelling it chose and only has
     * its number changed — the least surprising edit to someone else's file.
     * Only a pom declaring nothing gets the generated form.
     *
     * @return the rewritten xml, or {@code null} when there was nowhere to put it
     */
    static String rewritePom(String xml, String code) {
        String level = JavaVersions.normalize(code);
        String numeric = String.valueOf(JavaVersions.feature(level));
        String out = xml;
        boolean touched = false;

        if (POM_PROP_RELEASE.matcher(out).find()) {
            out = replaceAll(out, POM_PROP_RELEASE,
                    "<maven.compiler.release>" + numeric + "</maven.compiler.release>");
            touched = true;
        }
        if (POM_PROP_SOURCE.matcher(out).find()) {
            out = replaceAll(out, POM_PROP_SOURCE,
                    "<maven.compiler.source>" + level + "</maven.compiler.source>");
            touched = true;
        }
        if (POM_PROP_TARGET.matcher(out).find()) {
            out = replaceAll(out, POM_PROP_TARGET,
                    "<maven.compiler.target>" + level + "</maven.compiler.target>");
            touched = true;
        }

        if (out.contains("maven-compiler-plugin")) {
            if (POM_PLUGIN_RELEASE.matcher(out).find()) {
                out = replaceAll(out, POM_PLUGIN_RELEASE, "<release>" + numeric + "</release>");
                touched = true;
            }
            if (POM_PLUGIN_SOURCE.matcher(out).find()) {
                out = replaceAll(out, POM_PLUGIN_SOURCE, "<source>" + level + "</source>");
                touched = true;
            }
            if (POM_PLUGIN_TARGET.matcher(out).find()) {
                out = replaceAll(out, POM_PLUGIN_TARGET, "<target>" + level + "</target>");
                touched = true;
            }
        }

        return touched ? out : insertCompilerProperties(out, level);
    }

    private static String insertCompilerProperties(String xml, String level) {
        String block = pomCompilerProperties(level, "        ");

        int close = xml.indexOf("</properties>");
        if (close >= 0) {
            int lineStart = xml.lastIndexOf('\n', close) + 1;
            return xml.substring(0, lineStart) + block + xml.substring(lineStart);
        }

        int anchor = firstIndexOf(xml, "<dependencies>", "<build>", "</project>");
        if (anchor < 0) return null;
        int lineStart = xml.lastIndexOf('\n', anchor) + 1;
        return xml.substring(0, lineStart)
                + "    <properties>\n" + block + "    </properties>\n\n"
                + xml.substring(lineStart);
    }

    static String rewriteGradle(String script, String code) {
        String level = JavaVersions.normalize(code);
        String constant = JavaVersions.gradleConstant(level);
        String out = script;
        out = replaceAll(out, GRADLE_SOURCE_CONST, "sourceCompatibility = JavaVersion." + constant);
        out = replaceAll(out, GRADLE_TARGET_CONST, "targetCompatibility = JavaVersion." + constant);
        out = replaceLevel(out, GRADLE_SOURCE_PLAIN, level);
        out = replaceLevel(out, GRADLE_TARGET_PLAIN, level);
        // The Kotlin plugin fails the build when compileJava and compileKotlin
        // name different bytecode levels, so the JVM target moves with them.
        out = replaceAll(out, KOTLIN_JVM_TARGET, "JvmTarget.JVM_" + kotlinTargetSuffix(level));
        return out;
    }

    private static String kotlinTargetSuffix(String code) {
        List<String> candidates = JavaVersions.kotlinJvmTargets(code);
        String name = candidates.isEmpty() ? "JVM_1_8" : candidates.get(0);
        return name.substring("JVM_".length());
    }

    private static String declaredCached(File root) {
        String path = root.getAbsolutePath();
        long stamp = stampOf(root);
        synchronized (LOCK) {
            if (cacheValid && path.equals(cachedRoot) && stamp == cachedStamp) return cachedCode;
        }
        String declared = declaredIn(root);
        synchronized (LOCK) {
            cachedRoot = path;
            cachedStamp = stamp;
            cachedCode = declared;
            cacheValid = true;
        }
        return declared;
    }

    private static long stampOf(File root) {
        long stamp = new File(root, "pom.xml").lastModified();
        for (String name : GRADLE_FILES) {
            stamp = stamp * 31 + new File(root, name).lastModified();
        }
        return stamp;
    }

    private static String firstValidLevel(String text, Pattern... patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String raw = m.group(1);
                if (raw == null) continue;
                raw = raw.trim();
                if (JavaVersions.feature(raw) >= 0) return raw;
            }
        }
        return null;
    }

    private static int firstIndexOf(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int at = text.indexOf(needle);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static String replaceAll(String text, Pattern pattern, String replacement) {
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /** Rebuilds each match from its own groups so the quoting style survives. */
    private static String replaceLevel(String text, Pattern pattern, String level) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String quote = m.group(2) == null ? "" : m.group(2);
            m.appendReplacement(sb,
                    Matcher.quoteReplacement(m.group(1) + quote + level + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String readUtf8(File file) {
        if (file == null || !file.isFile() || file.length() > MAX_BUILD_FILE) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean writeUtf8(File file, String content) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
