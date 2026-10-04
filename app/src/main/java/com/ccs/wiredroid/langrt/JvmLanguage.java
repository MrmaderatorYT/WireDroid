package com.ccs.wiredroid.langrt;

import java.util.Locale;

/**
 * A JVM language the app can run, and how to run it.
 *
 * <p>All three execute on the embedded Java SE runtime rather than on ART: they
 * are ordinary JVM compilers that reach for APIs Android does not have, and
 * dexing a Scala compiler to run it on the device's own runtime is neither
 * feasible nor useful when a real JVM is already bundled.</p>
 */
public enum JvmLanguage {

    /**
     * Groovy runs a script file directly — no separate compile step, which is
     * how most Groovy is written.
     */
    GROOVY("groovy", "groovy.ui.GroovyMain", false),

    /** Clojure likewise: {@code clojure.main -M file.clj} runs the file. */
    CLOJURE("clojure", "clojure.main", false),

    /**
     * Scala has to be compiled first. There is no script runner that avoids
     * it — the compiler produces classes, and those are what run.
     */
    SCALA("scala", "dotty.tools.dotc.Main", true);

    /** Asset folder and cache folder name. */
    public final String id;
    /** Class the JVM is told to start. */
    public final String mainClass;
    /** Whether running means compiling first. */
    public final boolean compiles;

    JvmLanguage(String id, String mainClass, boolean compiles) {
        this.id = id;
        this.mainClass = mainClass;
        this.compiles = compiles;
    }

    /** The language a file name belongs to, or null. */
    public static JvmLanguage of(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".groovy") || lower.endsWith(".gvy") || lower.endsWith(".gy")) {
            return GROOVY;
        }
        if (lower.endsWith(".clj") || lower.endsWith(".cljc")) return CLOJURE;
        if (lower.endsWith(".scala") || lower.endsWith(".sc")) return SCALA;
        // .cljs is ClojureScript — it compiles to JavaScript, and running it
        // needs a JS engine rather than this JVM.
        return null;
    }

    /** How it reads in the console and in settings. */
    public String displayName() {
        switch (this) {
            case GROOVY:  return "Groovy";
            case CLOJURE: return "Clojure";
            default:      return "Scala";
        }
    }
}
