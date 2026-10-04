package com.ccs.wiredroid.project;

import static org.junit.Assert.assertTrue;

import com.ccs.wiredroid.langrt.JvmLanguage;

import org.junit.Test;

/**
 * The Gradle script written into a Scala, Groovy or Clojure project.
 *
 * <p>The app never runs Gradle, so nothing here is exercised on device — this
 * script exists for the desktop that opens the project next, and a mistake in
 * it only shows up there.</p>
 */
public class JvmLanguageBuildScriptTest {

    private static String script(JvmLanguage language) {
        return JvmLanguageProjectFactory.buildScript(language, "com.ccs.demo", "9.9.9",
                "src/main/" + language.id);
    }

    @Test
    public void eachLanguageDeclaresItsOwnLibrary() {
        assertTrue(script(JvmLanguage.SCALA)
                .contains("org.scala-lang:scala3-library_3:9.9.9"));
        assertTrue(script(JvmLanguage.GROOVY).contains("org.apache.groovy:groovy:9.9.9"));
        assertTrue(script(JvmLanguage.CLOJURE).contains("org.clojure:clojure:9.9.9"));
    }

    @Test
    public void everyScriptNamesSomethingToStart() {
        // The application plugin is applied to all three; without a mainClass
        // `gradle run` fails on a project the app itself runs.
        for (JvmLanguage language : JvmLanguage.values()) {
            String text = script(language);
            assertTrue(language + " has no mainClass", text.contains("mainClass = '"));
        }
    }

    @Test
    public void scalaStartsTheObjectTheStarterDefines() {
        assertTrue(script(JvmLanguage.SCALA).contains("mainClass = 'com.ccs.demo.Main'"));
    }

    @Test
    public void groovyStartsTheScriptsOwnClass() {
        assertTrue(script(JvmLanguage.GROOVY).contains("mainClass = 'com.ccs.demo.main'"));
    }

    @Test
    public void clojureGoesThroughItsLauncherWithTheNamespace() {
        String text = script(JvmLanguage.CLOJURE);
        assertTrue(text.contains("mainClass = 'clojure.main'"));
        assertTrue(text.contains("args = ['-m', 'com.ccs.demo.main']"));
    }

    @Test
    public void groovyThreeKeepsItsOldGroupId() {
        // Groovy moved to org.apache.groovy in 4.0; asking for 3.x there is a
        // dependency that does not exist.
        String old = JvmLanguageProjectFactory.buildScript(JvmLanguage.GROOVY, "com.ccs.demo",
                "3.0.22", "src/main/groovy");
        assertTrue(old.contains("org.codehaus.groovy:groovy:3.0.22"));
        assertTrue(script(JvmLanguage.GROOVY).contains("org.apache.groovy:groovy:9.9.9"));
    }

    @Test
    public void sourcesAreWhereTheLanguageExpectsThem() {
        assertTrue(script(JvmLanguage.SCALA).contains("scala { srcDirs = ['src/main/scala'] }"));
        assertTrue(script(JvmLanguage.GROOVY)
                .contains("groovy { srcDirs = ['src/main/groovy'] }"));
        assertTrue(script(JvmLanguage.CLOJURE)
                .contains("resources { srcDirs = ['src/main/clojure'] }"));
    }
}
