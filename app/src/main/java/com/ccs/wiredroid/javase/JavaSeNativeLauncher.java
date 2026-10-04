package com.ccs.wiredroid.javase;

final class JavaSeNativeLauncher {

    static {
        System.loadLibrary("java_se_launcher");
    }

    private JavaSeNativeLauncher() {}

    static native int launch(String runtimeHome, String workingDirectory,
                             String[] arguments, String[] environment,
                             String outputPath);

    /**
     * Writes into the running program's stdin.
     *
     * <p>Called from a different thread than {@link #launch}, which is blocked
     * for the whole run.</p>
     *
     * @return bytes written, or -1 when no program is running
     */
    static native int writeStdin(byte[] data);

    /** Reports end of input to the running program. */
    static native void closeStdin();
}
