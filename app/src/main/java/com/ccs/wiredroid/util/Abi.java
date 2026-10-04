package com.ccs.wiredroid.util;

import android.os.Process;

/**
 * Which word size this process is running at.
 *
 * <p>Screens that are expensive to build have two paths: the original one, which
 * builds everything before the first frame, and a staged one, which puts the
 * visible part up first and fills in the rest over the following frames. This
 * decides which path is taken.</p>
 *
 * <p>The staged path is limited to 32-bit because that is where the cost was
 * measured — {@code SettingsActivity} took 2099 ms to appear on a 32-bit debug
 * build and 288 ms once staged. No equivalent measurement exists for a 64-bit
 * process, so those keep the behaviour they always had.</p>
 *
 * <p>Note this is the <em>process</em> word size, not the device's: a 64-bit
 * phone still reports {@code true} here if the app happens to be running in a
 * 32-bit process.</p>
 */
public final class Abi {

    /** Fixed for the life of the process, so it is worth asking only once. */
    private static final boolean IS_32_BIT = !Process.is64Bit();

    private Abi() {
    }

    /** Whether this process is 32-bit, and so takes the staged build path. */
    public static boolean is32Bit() {
        return IS_32_BIT;
    }
}
