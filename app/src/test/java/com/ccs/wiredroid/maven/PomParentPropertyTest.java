package com.ccs.wiredroid.maven;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A dependency version that lives in a parent POM.
 *
 * <p>The shape that made real builds fail: {@code maven-core} asks for
 * {@code aether-impl} at version {@code ${aetherVersion}}, and the property is
 * declared not in its own pom but in the parent. Parsing substitutes once,
 * before the parent has been fetched, so the version stayed as the literal text
 * and went into a download URL that answered 404.</p>
 */
public class PomParentPropertyTest {

    /** Shaped after org.apache.maven:maven-core:3.0.5. */
    private static final String CHILD =
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <parent>\n"
            + "    <groupId>org.apache.maven</groupId>\n"
            + "    <artifactId>maven</artifactId>\n"
            + "    <version>3.0.5</version>\n"
            + "  </parent>\n"
            + "  <artifactId>maven-core</artifactId>\n"
            + "  <dependencies>\n"
            + "    <dependency>\n"
            + "      <groupId>org.sonatype.aether</groupId>\n"
            + "      <artifactId>aether-impl</artifactId>\n"
            + "      <version>${aetherVersion}</version>\n"
            + "    </dependency>\n"
            + "  </dependencies>\n"
            + "</project>\n";

    private static PomModel child() throws Exception {
        return PomParser.parse(new StringReader(CHILD));
    }

    @Test
    public void theParentCoordinatesAreReadAtAll() throws Exception {
        // Without these the parent pom cannot even be fetched.
        PomModel pom = child();
        assertEquals("org.apache.maven", pom.parentGroupId);
        assertEquals("maven", pom.parentArtifactId);
        assertEquals("3.0.5", pom.parentVersion);
    }

    @Test
    public void unresolvedBeforeTheParentIsKnown() throws Exception {
        // Not a wish, just the starting position this test exists to change.
        assertEquals("${aetherVersion}", child().dependencies.get(0).version);
    }

    @Test
    public void resolvesOnceTheParentPropertiesAreMerged() throws Exception {
        PomModel pom = child();
        Map<String, String> parentProps = new LinkedHashMap<>();
        parentProps.put("aetherVersion", "1.13.1");
        pom.mergeParentProperties(parentProps);
        pom.applyProperties();
        assertEquals("1.13.1", pom.dependencies.get(0).version);
    }

    @Test
    public void applyingTwiceChangesNothing() throws Exception {
        PomModel pom = child();
        Map<String, String> parentProps = new LinkedHashMap<>();
        parentProps.put("aetherVersion", "1.13.1");
        pom.mergeParentProperties(parentProps);
        pom.applyProperties();
        pom.applyProperties();
        assertEquals("1.13.1", pom.dependencies.get(0).version);
    }

    @Test
    public void theParentVersionIsAProperty() throws Exception {
        // How a BOM pins itself: jackson-databind asks for jackson-bom at
        // ${project.parent.version}.
        String pom =
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <parent>\n"
                + "    <groupId>com.fasterxml.jackson</groupId>\n"
                + "    <artifactId>jackson-base</artifactId>\n"
                + "    <version>2.13.2</version>\n"
                + "  </parent>\n"
                + "  <artifactId>jackson-databind</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>com.fasterxml.jackson</groupId>\n"
                + "      <artifactId>jackson-bom</artifactId>\n"
                + "      <version>${project.parent.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        PomModel model = PomParser.parse(new StringReader(pom));
        model.applyProperties();
        assertEquals("2.13.2", model.dependencies.get(0).version);
    }

    @Test
    public void anUnknownPropertyIsLeftAlone() throws Exception {
        // Better a visible ${...} in the log than a wrong version silently used.
        PomModel pom = child();
        pom.applyProperties();
        assertEquals("${aetherVersion}", pom.dependencies.get(0).version);
    }
}
