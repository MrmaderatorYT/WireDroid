package com.ccs.wiredroid.util;

import com.ccs.wiredroid.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What WireDroid is built on.
 *
 * <p>Kept as data next to the code rather than as prose in a layout, because it
 * has to stay in step with {@code app/build.gradle}: every entry here matches a
 * dependency declared there, and each licence is the one the artifact itself
 * declares in its POM rather than a guess. When a dependency is added, bumped
 * or dropped, this list changes with it.</p>
 *
 * <p>Names, versions and licence identifiers are deliberately not translated —
 * they are proper nouns and legal identifiers. Only the "what it is for" line
 * is a string resource.</p>
 */
public final class Credits {

    /** One bundled component. */
    public static final class Entry {
        /** Display name, as the project calls itself. */
        public final String name;
        /** Version shipped in the APK. */
        public final String version;
        /** Licence exactly as the artifact declares it. */
        public final String license;
        /** Localised description of what WireDroid uses it for. */
        public final int purposeRes;
        /** Project home page, or {@code null}. */
        public final String url;

        Entry(String name, String version, String license, int purposeRes, String url) {
            this.name = name;
            this.version = version;
            this.license = license;
            this.purposeRes = purposeRes;
            this.url = url;
        }
    }

    /** A titled run of entries. */
    public static final class Group {
        public final int titleRes;
        public final List<Entry> entries;

        Group(int titleRes, Entry... entries) {
            this.titleRes = titleRes;
            this.entries = Collections.unmodifiableList(Arrays.asList(entries));
        }
    }

    private Credits() {}

    /** Everything bundled, grouped by the job it does. */
    public static List<Group> groups() {
        List<Group> groups = new ArrayList<>();

        groups.add(new Group(R.string.credits_group_compiler,
                new Entry("Eclipse Compiler for Java (ECJ)", "3.46.0", "EPL-2.0",
                        R.string.credits_use_ecj,
                        "https://www.eclipse.org/jdt/core/"),
                new Entry("OpenJDK Mobile runtime (Pojav build)", "21.0.1",
                        "GPL-2.0 with Classpath Exception",
                        R.string.credits_use_openjdk_mobile,
                        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch"),
                new Entry("R8 / D8", "8.13.19", "BSD-3-Clause",
                        R.string.credits_use_r8,
                        "https://r8.googlesource.com/r8"),
                new Entry("Kotlin compiler (embeddable)", "2.0.21", "Apache-2.0",
                        R.string.credits_use_kotlin,
                        "https://kotlinlang.org"),
                new Entry("desugar_jdk_libs", "2.0.4", "GPL-2.0 with Classpath Exception",
                        R.string.credits_use_desugar,
                        "https://github.com/google/desugar_jdk_libs"),
                new Entry("ASM", "9.6", "BSD-3-Clause",
                        R.string.credits_use_asm,
                        "https://asm.ow2.io")));

        groups.add(new Group(R.string.credits_group_editor,
                new Entry("sora-editor", "0.23.6", "LGPL-2.1",
                        R.string.credits_use_sora,
                        "https://github.com/Rosemoe/sora-editor")));

        groups.add(new Group(R.string.credits_group_network,
                new Entry("Eclipse JGit", "5.13.5", "Eclipse Distribution License (New BSD)",
                        R.string.credits_use_jgit,
                        "https://www.eclipse.org/jgit/"),
                new Entry("OkHttp", "4.12.0", "Apache-2.0",
                        R.string.credits_use_okhttp,
                        "https://square.github.io/okhttp/"),
                new Entry("SLF4J", "1.7.36", "MIT",
                        R.string.credits_use_slf4j,
                        "https://www.slf4j.org")));

        groups.add(new Group(R.string.credits_group_formats,
                new Entry("junrar", "7.5.5", "UnRar License",
                        R.string.credits_use_junrar,
                        "https://github.com/junrar/junrar"),
                new Entry("Apache Commons Compress", "1.27.1", "Apache-2.0",
                        R.string.credits_use_compress,
                        "https://commons.apache.org/proper/commons-compress/"),
                new Entry("XZ for Java", "1.10", "Public Domain",
                        R.string.credits_use_xz,
                        "https://tukaani.org/xz/java.html"),
                new Entry("AndroidSVG", "1.4", "Apache-2.0",
                        R.string.credits_use_androidsvg,
                        "https://github.com/BigBadaboom/androidsvg"),
                new Entry("org.json", "20231013", "Public Domain",
                        R.string.credits_use_json,
                        "https://github.com/stleary/JSON-java"),
                new Entry("StAX API", "1.0-2", "CDDL-1.0 / GPL-2.0",
                        R.string.credits_use_stax,
                        "https://mvnrepository.com/artifact/javax.xml.stream/stax-api"),
                new Entry("Woodstox stax2-api", "4.2.2", "BSD-2-Clause",
                        R.string.credits_use_stax2,
                        "https://github.com/FasterXML/stax2-api")));

        groups.add(new Group(R.string.credits_group_platform,
                new Entry("Vosk", "0.3.75", "Apache-2.0",
                        R.string.credits_use_vosk,
                        "https://alphacephei.com/vosk/"),
                new Entry("AndroidX + Material Components", "—", "Apache-2.0",
                        R.string.credits_use_androidx,
                        "https://developer.android.com/jetpack/androidx"),
                new Entry("Android SDK platform (android.jar)", "API 36",
                        "Android Software Development Kit License",
                        R.string.credits_use_android_jar,
                        "https://developer.android.com/studio/terms")));

        return Collections.unmodifiableList(groups);
    }

    /**
     * Notes about Java itself, as string resources.
     *
     * <p>Worth stating plainly: WireDroid is not a JDK, is not affiliated with
     * Oracle, and "Java" here names a language and a class-file format rather
     * than a product.</p>
     */
    public static int[] javaNotes() {
        return new int[]{
                R.string.credits_java_trademark,
                R.string.credits_java_runtime_modes,
                R.string.credits_java_android_libs,
                R.string.credits_java_target_meaning,
                R.string.credits_java_build_files
        };
    }
}
