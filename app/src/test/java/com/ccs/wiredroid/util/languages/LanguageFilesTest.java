package com.ccs.wiredroid.util.languages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which names belong to which language, and what a new file of each starts as. */
public class LanguageFilesTest {

    @Test
    public void extensionsAreRecognised() {
        assertTrue(LanguageFiles.isScala("Main.scala"));
        assertTrue(LanguageFiles.isScala("script.sc"));
        assertTrue(LanguageFiles.isGroovy("Build.groovy"));
        assertTrue(LanguageFiles.isGroovy("thing.gvy"));
        assertTrue(LanguageFiles.isClojure("core.clj"));
        assertTrue(LanguageFiles.isClojure("app.cljs"));
        assertTrue(LanguageFiles.isClojure("shared.cljc"));
        assertTrue(LanguageFiles.isClojure("config.edn"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertTrue(LanguageFiles.isScala("MAIN.SCALA"));
        assertTrue(LanguageFiles.isClojure("Core.CLJ"));
    }

    @Test
    public void aBuildScriptStaysWithTheGradleHighlighter() {
        // .gradle is Groovy, but it has a highlighter of its own for the build
        // DSL — claiming it here would take that away.
        assertFalse(LanguageFiles.isGroovy("build.gradle"));
        assertFalse(LanguageFiles.isKnown("build.gradle"));
    }

    @Test
    public void javaAndKotlinAreNotClaimed() {
        assertFalse(LanguageFiles.isKnown("App.java"));
        assertFalse(LanguageFiles.isKnown("App.kt"));
        assertNull(LanguageFiles.languageFor("App.java"));
    }

    @Test
    public void eachExtensionGetsItsOwnLanguage() {
        assertTrue(LanguageFiles.languageFor("A.scala") instanceof ScalaLanguage);
        assertTrue(LanguageFiles.languageFor("A.groovy") instanceof GroovyLanguage);
        assertTrue(LanguageFiles.languageFor("a.clj") instanceof ClojureLanguage);
    }

    @Test
    public void scalaEntryPointCompilesAsWritten() {
        String template = LanguageFiles.starterTemplate("Main.scala", "com.example");
        assertTrue(template.startsWith("package com.example\n"));
        assertTrue(template.contains("object Main {"));
        assertTrue(template.contains("def main(args: Array[String]): Unit"));
    }

    @Test
    public void scalaNonEntryPointIsAClass() {
        String template = LanguageFiles.starterTemplate("Zoo.scala", null);
        assertFalse("no package line when the project has none", template.contains("package"));
        assertTrue(template.contains("class Zoo {"));
    }

    @Test
    public void groovyEntryPointIsAScript() {
        String template = LanguageFiles.starterTemplate("Main.groovy", "com.example");
        assertTrue(template.contains("println"));
        assertFalse("a script has no class wrapper", template.contains("class Main"));
    }

    @Test
    public void clojureNamespaceIsHyphenatedNotUnderscored() {
        // Namespaces use hyphens where the directories holding them use
        // underscores; copying the package across unchanged would give a
        // namespace that does not match its own file.
        String template = LanguageFiles.starterTemplate("core.clj", "my_app.tools");
        assertTrue(template.startsWith("(ns my-app.tools.core)"));
        assertTrue(template.contains("(defn core []"));
    }

    @Test
    public void clojureEntryPointIsDashMain() {
        String template = LanguageFiles.starterTemplate("main.clj", "app");
        assertTrue(template.contains("(defn -main [& args]"));
    }

    @Test
    public void nothingIsInventedForOtherFiles() {
        assertNull(LanguageFiles.starterTemplate("notes.txt", "com.example"));
        assertNull(LanguageFiles.starterTemplate(null, null));
    }

    @Test
    public void iconsAreDistinct() {
        assertEquals(3, new java.util.HashSet<>(java.util.Arrays.asList(
                LanguageFiles.iconFor("a.scala"),
                LanguageFiles.iconFor("a.groovy"),
                LanguageFiles.iconFor("a.clj"))).size());
        assertNull(LanguageFiles.iconFor("a.java"));
    }
}
