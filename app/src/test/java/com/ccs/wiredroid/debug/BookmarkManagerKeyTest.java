package com.ccs.wiredroid.debug;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which stored bookmark keys name a file that can actually be opened.
 *
 * <p>Bookmarks live in SharedPreferences under {@code bm_<absolute path>}, and
 * {@code loadAll} trusts whatever it finds there. A leftover from an older build
 * — {@code bm_-1305596005}, a hash code rather than a path — surfaced in the
 * panel as "★ -1305596005:13", a row that names no file and goes nowhere when
 * tapped. Preferences outlive app versions, so the load path has to be the thing
 * that refuses them.</p>
 */
public class BookmarkManagerKeyTest {

    @Test
    public void anAbsolutePathIsUsable() {
        assertTrue(BookmarkManager.isUsableKey(
                "/storage/emulated/0/Documents/WireDroid/demo/src/main/java/App.java"));
        assertTrue(BookmarkManager.isUsableKey("/a.java"));
    }

    @Test
    public void theHashCodeLeftoverIsRefused() {
        assertFalse("this is the actual value found on a device",
                BookmarkManager.isUsableKey("-1305596005"));
        assertFalse(BookmarkManager.isUsableKey("1305596005"));
    }

    @Test
    public void relativePathsAreRefused() {
        // Nothing writes these, so a relative path can only be corruption or a
        // leftover, and it cannot be resolved without knowing the old project.
        assertFalse(BookmarkManager.isUsableKey("src/main/java/App.java"));
        assertFalse(BookmarkManager.isUsableKey("App.java"));
    }

    @Test
    public void emptyAndDegenerateKeysAreRefused() {
        assertFalse(BookmarkManager.isUsableKey(null));
        assertFalse(BookmarkManager.isUsableKey(""));
        assertFalse("a bare root names no file", BookmarkManager.isUsableKey("/"));
        assertFalse("a directory holds no line", BookmarkManager.isUsableKey("/some/dir/"));
    }

    @Test
    public void aPathWithSpacesOrUnicodeIsStillUsable() {
        assertTrue(BookmarkManager.isUsableKey("/storage/My Projects/файл.java"));
    }
}
