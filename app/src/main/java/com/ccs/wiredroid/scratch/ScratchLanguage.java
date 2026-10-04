package com.ccs.wiredroid.scratch;

import com.ccs.wiredroid.util.languages.LanguageFiles;

/**
 * A language a scratch file can be written in.
 *
 * <p>Was a boolean while there were two. Each entry carries the three things
 * that actually differ — the extension, the name an untitled scratch falls back
 * to, and the starter text — so adding a language is one constant rather than
 * another branch in five places.</p>
 */
public enum ScratchLanguage {

    JAVA("Java", ".java", "Scratch"),
    KOTLIN("Kotlin", ".kt", "scratch"),
    SCALA("Scala", ".scala", "Scratch"),
    GROOVY("Groovy", ".groovy", "scratch"),
    CLOJURE("Clojure", ".clj", "scratch");

    public final String displayName;
    public final String extension;
    /** Used when the user types no name; capitalised where the file is a class. */
    public final String fallbackName;

    ScratchLanguage(String displayName, String extension, String fallbackName) {
        this.displayName = displayName;
        this.extension = extension;
        this.fallbackName = fallbackName;
    }

    /** The starting content for a scratch with this base name. */
    public String template(String baseName) {
        switch (this) {
            case JAVA:
                return "public class " + baseName + " {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"Hello from WireDroid Scratchpad!\");\n"
                        + "    }\n"
                        + "}\n";
            case KOTLIN:
                return "fun main() {\n"
                        + "    println(\"Hello from WireDroid Scratchpad!\")\n"
                        + "}\n";
            default:
                // No package, and always the runnable form: a scratch is a
                // file you press Run on, whatever it ended up being called.
                String starter =
                        LanguageFiles.starterTemplate(baseName + extension, null, true);
                return starter != null ? starter : "";
        }
    }

    /** The language for a file name, defaulting to Java. */
    public static ScratchLanguage of(String fileName) {
        if (fileName == null) return JAVA;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (ScratchLanguage language : values()) {
            if (lower.endsWith(language.extension)) return language;
        }
        return JAVA;
    }
}
