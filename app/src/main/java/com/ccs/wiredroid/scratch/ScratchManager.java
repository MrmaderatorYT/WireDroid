package com.ccs.wiredroid.scratch;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Manages standalone scratch files (Java & Kotlin) created outside of any project.
 */
public final class ScratchManager {

    private static final String SCRATCH_DIR_NAME = "scratches";

    private ScratchManager() {}

    /**
     * Where scratches live, without creating anything.
     *
     * <p>Separate from {@link #getScratchDir} so a read-only question — is this
     * file a scratch? — cannot leave a directory behind as a side effect.</p>
     */
    @NonNull
    private static File scratchDirPath(@NonNull File baseDir) {
        return new File(baseDir, SCRATCH_DIR_NAME);
    }

    @NonNull
    public static File getScratchDir(@NonNull Context context) {
        return getScratchDir(context.getFilesDir());
    }

    @NonNull
    public static File getScratchDir(@NonNull File baseDir) {
        File dir = scratchDirPath(baseDir);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static boolean isScratchFile(@NonNull Context context, @Nullable File file) {
        return isScratchFile(context.getFilesDir(), file);
    }

    public static boolean isScratchFile(@NonNull File baseDir, @Nullable File file) {
        if (file == null) return false;
        // The separator matters: a bare prefix test also claimed neighbours like
        // ".../scratches_backup/Old.java", which are ordinary project files.
        String dirPath = scratchDirPath(baseDir).getAbsolutePath() + File.separator;
        return file.getAbsolutePath().startsWith(dirPath);
    }

    @NonNull
    public static List<File> listScratches(@NonNull Context context) {
        return listScratches(context.getFilesDir());
    }

    @NonNull
    public static List<File> listScratches(@NonNull File baseDir) {
        File dir = getScratchDir(baseDir);
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".java") || lower.endsWith(".kt");
        });
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>(files.length);
        Collections.addAll(list, files);
        list.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return list;
    }

    /**
     * The file name a create call would actually use, without creating anything.
     *
     * <p>Shared with {@link #createJavaScratch} and {@link #createKotlinScratch}
     * rather than reimplemented, so a preview of the name cannot drift from the
     * name that gets written — including the {@code _1} suffix a collision
     * adds.</p>
     */
    @NonNull
    public static String resolveFileName(@NonNull Context context, @Nullable String requestedName,
                                         boolean kotlin) {
        return resolveFileName(context.getFilesDir(), requestedName, kotlin);
    }

    @NonNull
    public static String resolveFileName(@NonNull File baseDir, @Nullable String requestedName,
                                         boolean kotlin) {
        return resolveFileName(baseDir, requestedName,
                kotlin ? ScratchLanguage.KOTLIN : ScratchLanguage.JAVA);
    }

    @NonNull
    public static String resolveFileName(@NonNull Context context, @Nullable String requestedName,
                                         @NonNull ScratchLanguage language) {
        return resolveFileName(context.getFilesDir(), requestedName, language);
    }

    @NonNull
    public static String resolveFileName(@NonNull File baseDir, @Nullable String requestedName,
                                         @NonNull ScratchLanguage language) {
        String desired = sanitizeIdentifier(
                stripExtension(requestedName, language.extension), language.fallbackName);
        // scratchDirPath, not getScratchDir: asking what a name would be must not
        // leave a directory behind.
        return findNextAvailableName(scratchDirPath(baseDir), desired, language.extension);
    }

    /** Writes a scratch in the given language and returns it. */
    @NonNull
    public static File create(@NonNull Context context, @Nullable String requestedName,
                              @NonNull ScratchLanguage language) throws IOException {
        return create(context.getFilesDir(), requestedName, language);
    }

    @NonNull
    public static File create(@NonNull File baseDir, @Nullable String requestedName,
                              @NonNull ScratchLanguage language) throws IOException {
        File dir = getScratchDir(baseDir);
        String fileName = resolveFileName(baseDir, requestedName, language);
        String baseName = fileName.substring(0, fileName.length() - language.extension.length());
        File file = new File(dir, fileName);
        writeFile(file, language.template(baseName));
        return file;
    }

    @NonNull
    public static File createJavaScratch(@NonNull Context context, @Nullable String requestedName) throws IOException {
        return createJavaScratch(context.getFilesDir(), requestedName);
    }

    @NonNull
    public static File createJavaScratch(@NonNull File baseDir, @Nullable String requestedName) throws IOException {
        File dir = getScratchDir(baseDir);

        // Numbering applies to a name the user typed too, not only to a blank
        // one. Reusing the name outright reopened the same File and truncated
        // it, so asking for "Algo" twice destroyed the first Algo.
        String fileName = resolveFileName(baseDir, requestedName, false);
        String className = fileName.substring(0, fileName.length() - ".java".length());

        File file = new File(dir, fileName);
        String content = "public class " + className + " {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello from WireDroid Scratchpad!\");\n"
                + "    }\n"
                + "}\n";

        writeFile(file, content);
        return file;
    }

    @NonNull
    public static File createKotlinScratch(@NonNull Context context, @Nullable String requestedName) throws IOException {
        return createKotlinScratch(context.getFilesDir(), requestedName);
    }

    @NonNull
    public static File createKotlinScratch(@NonNull File baseDir, @Nullable String requestedName) throws IOException {
        File dir = getScratchDir(baseDir);

        // Sanitised for the same reason as Java, plus one of its own: an
        // unfiltered name carrying a separator would have written outside the
        // scratch directory.
        String fileName = resolveFileName(baseDir, requestedName, true);

        File file = new File(dir, fileName);
        String content = "fun main() {\n"
                + "    println(\"Hello from WireDroid Scratchpad!\")\n"
                + "}\n";

        writeFile(file, content);
        return file;
    }

    public static boolean deleteScratch(@Nullable File file) {
        if (file == null || !file.exists()) return false;
        return file.delete();
    }

    private static String stripExtension(@Nullable String requestedName, String extension) {
        String name = requestedName != null ? requestedName.trim() : "";
        if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return name.substring(0, name.length() - extension.length());
        }
        return name;
    }

    private static String findNextAvailableName(File dir, String prefix, String extension) {
        File candidate = new File(dir, prefix + extension);
        if (!candidate.exists()) return prefix + extension;

        int index = 1;
        while (true) {
            candidate = new File(dir, prefix + "_" + index + extension);
            if (!candidate.exists()) {
                return prefix + "_" + index + extension;
            }
            index++;
        }
    }

    private static String sanitizeIdentifier(String name, String fallback) {
        if (name.isEmpty()) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                if (Character.isJavaIdentifierStart(c)) {
                    sb.append(c);
                } else {
                    sb.append('_');
                    if (Character.isJavaIdentifierPart(c)) {
                        sb.append(c);
                    }
                }
            } else {
                if (Character.isJavaIdentifierPart(c)) {
                    sb.append(c);
                } else {
                    sb.append('_');
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : fallback;
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
