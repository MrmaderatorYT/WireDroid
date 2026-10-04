package com.ccs.wiredroid.project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Selects the execution backend for one project. */
public final class ProjectRuntime {

    private static final String METADATA_DIR = ".wiredroid";
    private static final String METADATA_FILE = "runtime.properties";

    public enum Mode {
        ART("art"),
        JAVA_SE_21("java-se-21");

        public final String id;

        Mode(String id) {
            this.id = id;
        }

        public static Mode fromId(String id) {
            if (id != null) {
                for (Mode mode : values()) {
                    if (mode.id.equalsIgnoreCase(id.trim())) return mode;
                }
            }
            return ART;
        }
    }

    private ProjectRuntime() {}

    /** Projects created before this setting existed remain ART projects. */
    public static Mode resolve(File projectRoot) {
        File file = metadataFile(projectRoot);
        if (file == null || !file.isFile()) return Mode.ART;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                if ("mode".equals(line.substring(0, equals).trim())) {
                    return Mode.fromId(line.substring(equals + 1));
                }
            }
        } catch (Exception ignored) {
            // An unreadable optional metadata file must not make a project unusable.
        }
        return Mode.ART;
    }

    public static boolean isJavaSe(File projectRoot) {
        return resolve(projectRoot) == Mode.JAVA_SE_21;
    }

    public static void set(File projectRoot, Mode mode) throws java.io.IOException {
        if (projectRoot == null) throw new java.io.IOException("Project root is missing");
        File file = metadataFile(projectRoot);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new java.io.IOException("Cannot create " + parent);
        }
        Mode resolved = mode != null ? mode : Mode.ART;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write("mode=");
            writer.write(resolved.id);
            writer.write('\n');
        }
    }

    private static File metadataFile(File projectRoot) {
        return projectRoot == null ? null
                : new File(new File(projectRoot, METADATA_DIR), METADATA_FILE);
    }
}
