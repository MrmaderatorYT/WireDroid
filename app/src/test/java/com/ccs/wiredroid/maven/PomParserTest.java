package com.ccs.wiredroid.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.StringReader;

/**
 * The pom is how a Maven project tells the app what to compile and what to run.
 * Everything downstream — classpath, target level, the Run button — starts here,
 * and it is plain XML in, model out.
 */
public class PomParserTest {

    private static final String POM =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>com.example.app</groupId>\n"
            + "  <artifactId>demo</artifactId>\n"
            + "  <version>2.5.1</version>\n"
            + "  <packaging>jar</packaging>\n"
            + "  <properties>\n"
            + "    <maven.compiler.source>11</maven.compiler.source>\n"
            + "    <my.lib.version>4.13.2</my.lib.version>\n"
            + "  </properties>\n"
            + "  <dependencies>\n"
            + "    <dependency>\n"
            + "      <groupId>junit</groupId>\n"
            + "      <artifactId>junit</artifactId>\n"
            + "      <version>${my.lib.version}</version>\n"
            + "      <scope>test</scope>\n"
            + "    </dependency>\n"
            + "    <dependency>\n"
            + "      <groupId>com.google.code.gson</groupId>\n"
            + "      <artifactId>gson</artifactId>\n"
            + "      <version>2.10.1</version>\n"
            + "    </dependency>\n"
            + "  </dependencies>\n"
            + "</project>\n";

    private static PomModel parse(String xml) throws Exception {
        return PomParser.parse(new StringReader(xml));
    }

    @Test
    public void readsCoordinates() throws Exception {
        PomModel pom = parse(POM);
        assertEquals("com.example.app", pom.groupId);
        assertEquals("demo", pom.artifactId);
        assertEquals("2.5.1", pom.version);
        assertEquals("jar", pom.packaging);
    }

    @Test
    public void readsProperties() throws Exception {
        assertEquals("11", parse(POM).properties.get("maven.compiler.source"));
    }

    @Test
    public void readsEveryDependency() throws Exception {
        assertEquals(2, parse(POM).dependencies.size());
    }

    @Test
    public void resolvesAPropertyPlaceholder() throws Exception {
        PomModel pom = parse(POM);
        // ${my.lib.version} must become 4.13.2 somewhere between the pom and the
        // classpath, or the dependency cannot be downloaded.
        assertEquals("4.13.2", pom.resolveProperty("${my.lib.version}"));
        assertEquals("2.5.1", pom.resolveProperty("${project.version}"));
        assertEquals("plain", pom.resolveProperty("plain"));
    }

    @Test
    public void aMinimalPomStillYieldsAUsableModel() throws Exception {
        PomModel pom = parse("<project><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version></project>");
        assertEquals("g", pom.groupId);
        assertTrue("packaging should default rather than be null", pom.packaging != null);
    }

    @Test
    public void unknownElementsAreIgnoredNotFatal() throws Exception {
        PomModel pom = parse("<project><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version><somethingNew><nested>x</nested></somethingNew></project>");
        assertEquals("g", pom.groupId);
    }
}
