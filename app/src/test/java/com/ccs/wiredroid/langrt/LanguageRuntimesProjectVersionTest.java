package com.ccs.wiredroid.langrt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reading a language version out of a project's own build file.
 *
 * <p>This is what makes two projects on different Groovy versions able to sit
 * side by side; the app-wide setting cannot be right for both.</p>
 */
public class LanguageRuntimesProjectVersionTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File projectWith(String fileName, String content) throws Exception {
        File root = temp.newFolder();
        Files.write(new File(root, fileName).toPath(), content.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void readsScalaFromGradle() throws Exception {
        File root = projectWith("build.gradle",
                "dependencies {\n"
                        + "    implementation 'org.scala-lang:scala3-library_3:3.4.3'\n"
                        + "}\n");
        assertEquals("3.4.3", LanguageRuntimes.projectVersion(root, JvmLanguage.SCALA));
    }

    @Test
    public void readsGroovyUnderEitherGroupId() throws Exception {
        File modern = projectWith("build.gradle",
                "implementation 'org.apache.groovy:groovy:4.0.24'\n");
        assertEquals("4.0.24", LanguageRuntimes.projectVersion(modern, JvmLanguage.GROOVY));
        // Groovy 3 and earlier live under org.codehaus.groovy.
        File legacy = projectWith("build.gradle",
                "implementation 'org.codehaus.groovy:groovy:3.0.22'\n");
        assertEquals("3.0.22", LanguageRuntimes.projectVersion(legacy, JvmLanguage.GROOVY));
    }

    @Test
    public void readsClojureFromKotlinScript() throws Exception {
        File root = projectWith("build.gradle.kts",
                "dependencies {\n    implementation(\"org.clojure:clojure:1.11.4\")\n}\n");
        assertEquals("1.11.4", LanguageRuntimes.projectVersion(root, JvmLanguage.CLOJURE));
    }

    @Test
    public void readsMavenCoordinates() throws Exception {
        File root = projectWith("pom.xml",
                "<project><dependencies><dependency>"
                        + "<groupId>org.apache.groovy</groupId>"
                        + "<artifactId>groovy</artifactId>"
                        + "<version>4.0.21</version>"
                        + "</dependency></dependencies></project>");
        assertEquals("4.0.21", LanguageRuntimes.projectVersion(root, JvmLanguage.GROOVY));
    }

    @Test
    public void anotherLanguagesDependencyIsNotAnAnswer() throws Exception {
        File root = projectWith("build.gradle",
                "implementation 'org.clojure:clojure:1.11.4'\n");
        assertNull(LanguageRuntimes.projectVersion(root, JvmLanguage.GROOVY));
        assertNull(LanguageRuntimes.projectVersion(root, JvmLanguage.SCALA));
    }

    @Test
    public void aProjectWithNoBuildFileSaysNothing() throws Exception {
        assertNull(LanguageRuntimes.projectVersion(temp.newFolder(), JvmLanguage.SCALA));
        assertNull(LanguageRuntimes.projectVersion(null, JvmLanguage.SCALA));
    }

    @Test
    public void aVersionRangeOrPropertyIsNotMistakenForOne() throws Exception {
        // A property reference has no digits to read, and guessing would put a
        // literal "${groovyVersion}" on the classpath.
        File root = projectWith("build.gradle",
                "implementation \"org.apache.groovy:groovy:${groovyVersion}\"\n");
        assertNull(LanguageRuntimes.projectVersion(root, JvmLanguage.GROOVY));
    }
}
