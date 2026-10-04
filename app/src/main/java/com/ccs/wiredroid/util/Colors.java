package com.ccs.wiredroid.util;

/**
 * Colour arithmetic shared by everything that tints itself from a theme.
 *
 * <p>{@code blend} had been copy-pasted into nine classes — identical every
 * time, which is exactly how one copy quietly drifts from the rest.</p>
 */
public final class Colors {

    private Colors() {}

    /**
     * Mixes two opaque colours.
     *
     * @param t 0 returns {@code a}, 1 returns {@code b}
     * @return the mix, always fully opaque
     */
    public static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
