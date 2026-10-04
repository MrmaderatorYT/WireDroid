package com.ccs.wiredroid.util.languages;

import java.util.Locale;

/**
 * Which file names belong to which language.
 *
 * <p>File extensions are matched in a dozen places across the app — the editor,
 * the file tree, search, the AI digest — and each of them grew its own list.
 * The three languages added here answer in one place instead, so a name that is
 * Scala to the editor cannot be something else to search.</p>
 */
public final class LanguageFiles {

    private LanguageFiles() {}

    public static boolean isScala(String name) {
        String lower = lower(name);
        return lower.endsWith(".scala") || lower.endsWith(".sc");
    }

    public static boolean isGroovy(String name) {
        String lower = lower(name);
        // .gradle is Groovy too, but it has a highlighter of its own for the
        // build DSL and keeps it.
        return lower.endsWith(".groovy") || lower.endsWith(".gvy") || lower.endsWith(".gy");
    }

    public static boolean isClojure(String name) {
        String lower = lower(name);
        return lower.endsWith(".clj") || lower.endsWith(".cljs")
                || lower.endsWith(".cljc") || lower.endsWith(".edn");
    }

    /** Any of the languages this class knows about. */
    public static boolean isKnown(String name) {
        return isScala(name) || isGroovy(name) || isClojure(name);
    }

    /** The editor language for a name, or null when it is none of these. */
    public static RuleBasedLanguage languageFor(String name) {
        if (isScala(name)) return new ScalaLanguage();
        if (isGroovy(name)) return new GroovyLanguage();
        if (isClojure(name)) return new ClojureLanguage();
        return null;
    }

    /** The file-tree icon for a name, or null when it is none of these. */
    public static String iconFor(String name) {
        if (isScala(name)) return "🌀";      // spiral, Scala's mark
        if (isGroovy(name)) return "✴";            // star, Groovy's mark
        if (isClojure(name)) return "λ";           // lambda, for the Lisp
        return null;
    }

    /**
     * A starter file for one of these languages, or null for anything else.
     *
     * <p>A new file that opens empty gives no clue what the language expects —
     * where the package goes, what an entry point looks like. The skeleton is
     * the smallest thing that compiles and runs.</p>
     *
     * @param packageName the project's base package, or null when it has none
     */
    public static String starterTemplate(String name, String packageName) {
        return starterTemplate(name, packageName, null);
    }

    /**
     * The starter text for a new file, optionally forcing the runnable form.
     *
     * <p>A name says it well enough inside a project — {@code Main} gets an
     * entry point, {@code Parser} gets an empty class. A scratch has no such
     * name to go on and exists to be run, so it asks for the runnable form
     * whatever it is called.</p>
     *
     * @param runnable true to force an entry point, false to force a plain
     *                 declaration, null to decide from the name
     */
    public static String starterTemplate(String name, String packageName, Boolean runnable) {
        if (name == null) return null;
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        boolean entryPoint = runnable != null ? runnable
                : base.equalsIgnoreCase("main") || base.equalsIgnoreCase("app")
                || base.equalsIgnoreCase("hello");

        if (isScala(name)) {
            StringBuilder sb = new StringBuilder();
            if (packageName != null) sb.append("package ").append(packageName).append("\n\n");
            if (entryPoint) {
                // The braced form rather than Scala 3's indentation syntax: it
                // compiles under both, and the file does not yet know which
                // version the project will use.
                sb.append("object ").append(base).append(" {\n")
                  .append("  def main(args: Array[String]): Unit = {\n")
                  .append("    println(\"Hello from ").append(base).append("\")\n")
                  .append("  }\n}\n");
            } else {
                sb.append("class ").append(base).append(" {\n\n}\n");
            }
            return sb.toString();
        }

        if (isGroovy(name)) {
            StringBuilder sb = new StringBuilder();
            if (packageName != null) sb.append("package ").append(packageName).append("\n\n");
            if (entryPoint) {
                // A Groovy file with statements at the top level is a script,
                // which is how most Groovy is written and run.
                sb.append("println \"Hello from ").append(base).append("\"\n");
            } else {
                sb.append("class ").append(base).append(" {\n\n}\n");
            }
            return sb.toString();
        }

        if (isClojure(name)) {
            String namespace = namespaceOf(base, packageName);
            StringBuilder sb = new StringBuilder();
            sb.append("(ns ").append(namespace).append(")\n\n");
            if (entryPoint) {
                sb.append("(defn -main [& args]\n")
                  .append("  (println \"Hello from ").append(namespace).append("\"))\n");
            } else {
                sb.append("(defn ").append(hyphenated(base)).append(" []\n  nil)\n");
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * The namespace for a Clojure file.
     *
     * <p>Namespaces are hyphenated where the directories that hold them use
     * underscores, so the two spellings have to be converted rather than
     * copied.</p>
     */
    /** The namespace {@code base.clj} under {@code packageName} declares. */
    public static String clojureNamespace(String base, String packageName) {
        return namespaceOf(base, packageName);
    }

    private static String namespaceOf(String base, String packageName) {
        String tail = hyphenated(base);
        if (packageName == null || packageName.isEmpty()) return tail;
        return hyphenated(packageName) + "." + tail;
    }

    private static String hyphenated(String name) {
        return name.replace('_', '-');
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
