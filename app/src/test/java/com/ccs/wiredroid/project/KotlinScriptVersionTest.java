package com.ccs.wiredroid.project;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which Kotlin Gradle DSL a generated script may use.
 *
 * <p>The {@code compilerOptions} block and its {@code JvmTarget} enum arrived
 * with the 2.0 plugin; a script declaring an older Kotlin has to use the string
 * form or it fails to configure.</p>
 */
public class KotlinScriptVersionTest {

    @Test
    public void twoPointOhAndLaterGetTheEnum() {
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl("2.0.21"));
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl("2.1.0"));
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl("10.0.0"));
    }

    @Test
    public void olderKotlinGetsTheStringForm() {
        assertFalse(KotlinProjectFactory.usesCompilerOptionsDsl("1.9.24"));
        assertFalse(KotlinProjectFactory.usesCompilerOptionsDsl("1.8.22"));
    }

    @Test
    public void anythingUnreadableStaysOnTheCurrentDsl() {
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl(null));
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl("main-SNAPSHOT"));
        assertTrue(KotlinProjectFactory.usesCompilerOptionsDsl("x.y.z"));
    }

    @Test
    public void selectableVersionsLeadWithTheBundledOne() {
        assertTrue(KotlinProjectFactory.selectableVersions()
                .get(0).equals(KotlinProjectFactory.KOTLIN_VERSION));
    }
}
