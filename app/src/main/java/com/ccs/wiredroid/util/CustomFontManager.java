package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Downloads and validates a user-provided font without blocking the UI thread. */
public final class CustomFontManager {

    private static final String DIRECTORY_NAME = "fonts";
    private static final String FONT_FILE_NAME = "custom_font.ttf";
    private static final int MAX_REDIRECTS = 4;
    private static final long MAX_FONT_BYTES = 16L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private CustomFontManager() {}

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(File file);
        void onError(String message);
    }

    public static File getFontFile(Context context) {
        return new File(getFontDirectory(context), FONT_FILE_NAME);
    }

    public static boolean isInstalled(Context context, String path) {
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path);
        return file.isFile() && file.length() > 0;
    }

    public static void clearManagedFont(Context context) {
        File file = getFontFile(context);
        if (file.exists()) file.delete();
    }

    public static void download(Context context, String rawUrl, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File temp = null;
            try {
                File directory = getFontDirectory(appContext);
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Cannot create the font directory");
                }
                temp = File.createTempFile("custom-font-", ".part", directory);
                downloadToTemp(rawUrl, temp, percent -> main.post(() -> callback.onProgress(percent)));

                File destination = installValidated(appContext, temp);
                temp = null;
                main.post(() -> callback.onSuccess(destination));
            } catch (Exception e) {
                if (temp != null) temp.delete();
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = "Unknown download error";
                String finalMessage = message;
                main.post(() -> callback.onError(finalMessage));
            }
        }, "custom-font-download").start();
    }

    /** Imports a font selected through the Android document picker. */
    public static void importLocal(Context context, Uri uri, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File temp = null;
            try {
                if (uri == null) throw new IOException("No font file was selected");
                File directory = getFontDirectory(appContext);
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Cannot create the font directory");
                }
                temp = File.createTempFile("custom-font-", ".part", directory);
                ContentResolver resolver = appContext.getContentResolver();
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) throw new IOException("Cannot read the selected file");
                    copyWithLimit(input, temp, -1, percent -> main.post(() -> callback.onProgress(percent)));
                }
                File destination = installValidated(appContext, temp);
                temp = null;
                main.post(() -> callback.onSuccess(destination));
            } catch (Exception e) {
                if (temp != null) temp.delete();
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = "Unknown import error";
                String finalMessage = message;
                main.post(() -> callback.onError(finalMessage));
            }
        }, "custom-font-import").start();
    }

    private interface Progress { void update(int percent); }

    private static File getFontDirectory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY_NAME);
    }

    private static void downloadToTemp(String rawUrl, File destination, Progress progress)
            throws IOException {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IOException("Font URL is empty");
        }

        URL url = httpsUrl(rawUrl.trim());
        HttpURLConnection connection = null;
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "font/ttf, font/otf, font/collection, application/octet-stream, */*");

                int response = connection.getResponseCode();
                if (response >= 300 && response < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("The font server returned an invalid redirect");
                    }
                    url = httpsUrl(new URL(url, location).toString());
                    continue;
                }
                if (response < 200 || response >= 300) {
                    throw new IOException("Font server returned HTTP " + response);
                }

                long expected = connection.getContentLengthLong();
                if (expected > MAX_FONT_BYTES) {
                    throw new IOException(String.format(Locale.US,
                            "Font is larger than %d MB", MAX_FONT_BYTES / (1024 * 1024)));
                }

                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    copyWithLimit(input, destination, expected, progress);
                }
                progress.update(100);
                return;
            }
            throw new IOException("Too many redirects");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void copyWithLimit(InputStream input, File destination, long expected,
                                      Progress progress) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int lastPercent = -1;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FONT_BYTES) {
                    throw new IOException("Font is larger than 16 MB");
                }
                output.write(buffer, 0, count);
                if (expected > 0) {
                    int percent = (int) Math.min(99, total * 100 / expected);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.update(percent);
                    }
                }
            }
        }
    }

    private static File installValidated(Context context, File temp) throws IOException {
        // Typeface parsing is the final format check. This rejects HTML error
        // pages and archives that happened to be returned with a 200 status.
        Typeface.createFromFile(temp);
        File destination = getFontFile(context);
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Cannot replace the existing custom font");
        }
        if (!temp.renameTo(destination)) {
            throw new IOException("Cannot store the custom font");
        }
        return destination;
    }

    private static URL httpsUrl(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTPS font links are supported");
        }
        return url;
    }
}
