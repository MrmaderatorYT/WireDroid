package com.ccs.wiredroid.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Editing the project's own coordinates and nothing else.
 *
 * <p>Every case here is a place a careless replace lands on the wrong element:
 * a dependency of the same name, a parent block that also carries a groupId, a
 * plugin's version.</p>
 */
public class PomCoordinatesTest {

    private static final String POM =
            "<?xml version=\"1.0\"?>\n"
          + "<project>\n"
          + "  <modelVersion>4.0.0</modelVersion>\n"
          + "  <groupId>com.ccs.test</groupId>\n"
          + "  <artifactId>study</artifactId>\n"
          + "  <version>1.0</version>\n"
          + "  <name>Study</name>\n"
          + "  <dependencies>\n"
          + "    <dependency>\n"
          + "      <groupId>junit</groupId>\n"
          + "      <artifactId>study</artifactId>\n"
          + "      <version>4.13.2</version>\n"
          + "    </dependency>\n"
          + "  </dependencies>\n"
          + "</project>\n";

    @Test
    public void readsTheProjectsOwnValues() {
        assertEquals("com.ccs.test", PomCoordinates.get(POM, "groupId"));
        assertEquals("study", PomCoordinates.get(POM, "artifactId"));
        assertEquals("1.0", PomCoordinates.get(POM, "version"));
        assertEquals("Study", PomCoordinates.get(POM, "name"));
    }

    @Test
    public void renamingLeavesADependencyOfTheSameNameAlone() {
        String out = PomCoordinates.set(POM, "artifactId", "school");
        assertEquals("school", PomCoordinates.get(out, "artifactId"));
        assertTrue("the dependency keeps its own artifactId",
                out.contains("<artifactId>study</artifactId>"));
        assertTrue(out.contains("<groupId>junit</groupId>"));
    }

    @Test
    public void versionDoesNotFollowADependencyVersion() {
        String out = PomCoordinates.set(POM, "version", "2.0");
        assertEquals("2.0", PomCoordinates.get(out, "version"));
        assertTrue(out.contains("<version>4.13.2</version>"));
    }

    @Test
    public void aParentBlockIsNotTheProjectsCoordinates() {
        String withParent =
                "<project>\n"
              + "  <parent>\n"
              + "    <groupId>org.springframework.boot</groupId>\n"
              + "    <artifactId>spring-boot-starter-parent</artifactId>\n"
              + "  </parent>\n"
              + "  <groupId>com.mine</groupId>\n"
              + "  <artifactId>app</artifactId>\n"
              + "</project>";
        assertEquals("com.mine", PomCoordinates.get(withParent, "groupId"));
        String out = PomCoordinates.set(withParent, "groupId", "com.yours");
        assertTrue(out.contains("<groupId>org.springframework.boot</groupId>"));
        assertEquals("com.yours", PomCoordinates.get(out, "groupId"));
    }

    @Test
    public void commentsAndFormattingSurvive() {
        String commented = "<project>\n  <!-- <artifactId>old</artifactId> -->\n"
                + "  <artifactId>real</artifactId>\n</project>";
        assertEquals("real", PomCoordinates.get(commented, "artifactId"));
        String out = PomCoordinates.set(commented, "artifactId", "new");
        assertTrue("the comment is not the element", out.contains("<!-- <artifactId>old</artifactId> -->"));
        assertEquals("new", PomCoordinates.get(out, "artifactId"));
    }

    @Test
    public void anAbsentElementIsReportedRatherThanInvented() {
        assertNull(PomCoordinates.get(POM, "packaging"));
        assertNull(PomCoordinates.set(POM, "packaging", "jar"));
    }

    @Test
    public void markupInAValueIsEscaped() {
        String out = PomCoordinates.set(POM, "name", "A & B");
        assertEquals("A &amp; B", PomCoordinates.get(out, "name").trim());
    }
}
