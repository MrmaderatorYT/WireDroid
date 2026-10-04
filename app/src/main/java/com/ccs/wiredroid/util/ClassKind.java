package com.ccs.wiredroid.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.ccs.wiredroid.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * What kind of type a source file declares, for the icon shown beside it.
 *
 * <p>Answering this means reading the file, which the file tree and the tab
 * strip cannot do while binding a row — a cold scroll would be one disk read
 * per visible line on the main thread. So the answer is cached by path and
 * mtime, and a miss is resolved on a background thread that calls back when
 * the row can be refreshed. Callers get {@code null} until then and draw what
 * they drew before, which is why a missing icon is never worse than the old
 * behaviour.</p>
 */
public enum ClassKind {

    CLASS     (R.drawable.ic_kind_class),
    INTERFACE (R.drawable.ic_kind_interface),
    ABSTRACT  (R.drawable.ic_kind_abstract),
    FINAL     (R.drawable.ic_kind_final),
    ENUM      (R.drawable.ic_kind_enum),
    TEST      (R.drawable.ic_kind_test),
    RECORD    (R.drawable.ic_kind_record),
    EXCEPTION (R.drawable.ic_kind_exception),
    ANNOTATION(R.drawable.ic_kind_annotation);

    private final int badge;

    ClassKind(int badge) {
        this.badge = badge;
    }

    /** The language a source file is written in, for the corner wedge. */
    enum Lang {
        JAVA(R.drawable.ic_lang_java),
        KOTLIN(R.drawable.ic_lang_kotlin),
        SCALA(R.drawable.ic_lang_scala),
        GROOVY(R.drawable.ic_lang_groovy),
        CLOJURE(R.drawable.ic_lang_clojure);

        final int wedge;

        Lang(int wedge) {
            this.wedge = wedge;
        }
    }

    /**
     * The icon for this kind on a file of a given language.
     *
     * <p>Composed rather than drawn: five languages times nine kinds is
     * forty-five combinations, and each one as its own file would be
     * forty-five to regenerate every time a letter or a colour changed. The
     * badge says what the type is and the wedge says what language it is in,
     * and they are layered here.</p>
     */
    public Drawable icon(Context context, File file) {
        Drawable badgeLayer = ContextCompat.getDrawable(context, badge);
        Drawable wedgeLayer = ContextCompat.getDrawable(context,
                langOf(file == null ? "" : file.getName()).wedge);
        if (badgeLayer == null) return wedgeLayer;
        if (wedgeLayer == null) return badgeLayer;
        return new LayerDrawable(new Drawable[]{ badgeLayer, wedgeLayer });
    }

    // ── lookup ────────────────────────────────────────────────────────────

    /** Callback for a classification that had to go to disk. */
    public interface Ready {
        void onKind(ClassKind kind);
    }

    private static final class Entry {
        final long modified;
        final long length;
        final ClassKind kind;   // null means "read it, declares nothing we mark"
        Entry(long modified, long length, ClassKind kind) {
            this.modified = modified;
            this.length = length;
            this.kind = kind;
        }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "class-kind");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /**
     * Created on first use, not in a static initialiser.
     *
     * <p>Touching {@link Looper} at class-load time throws on a plain JVM, which
     * would take the unit tests for {@link #classify} down with it — and those
     * are exactly the part worth testing off-device.</p>
     */
    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static Handler main;

    /** Only the head of a file is read; a declaration below this is not one. */
    private static final int HEAD_CHARS = 16 * 1024;

    /**
     * The known kind, or null when it has not been read yet.
     *
     * <p>{@code onReady} fires on the main thread only when a disk read was
     * needed and it found something — so a caller can refresh just that row
     * without a redundant pass for files it already had.</p>
     */
    public static ClassKind of(File file, Ready onReady) {
        if (file == null || !isSource(file.getName())) return null;
        String key = file.getAbsolutePath();
        long modified = file.lastModified();
        long length = file.length();

        Entry hit = CACHE.get(key);
        if (hit != null && hit.modified == modified && hit.length == length) return hit.kind;

        IO.execute(() -> {
            ClassKind kind = read(file);
            CACHE.put(key, new Entry(modified, length, kind));
            if (onReady != null) main().post(() -> onReady.onKind(kind));
        });
        return hit != null ? hit.kind : null;   // stale beats blank while re-reading
    }

    /** Drops everything cached; for when files change outside the editor. */
    public static void invalidate() {
        CACHE.clear();
    }

    /** Drops one file, so the next lookup re-reads it. */
    public static void invalidate(File file) {
        if (file != null) CACHE.remove(file.getAbsolutePath());
    }

    // ── classification ────────────────────────────────────────────────────

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT  = Pattern.compile("//[^\\n]*");
    private static final Pattern STRING_LIT    = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");

    private static ClassKind read(File file) {
        String head = head(file);
        if (head == null) return null;
        String src = STRING_LIT.matcher(
                LINE_COMMENT.matcher(
                        BLOCK_COMMENT.matcher(head).replaceAll(" ")).replaceAll(" ")
        ).replaceAll("\"\"");
        return classify(file.getName(), src);
    }

    /**
     * Decides the kind from a file's name and its comment-stripped source.
     *
     * <p>Package-visible and free of file access so the ordering below can be
     * tested directly — the order is the part that is easy to get wrong, since
     * most files match more than one rule.</p>
     */
    static ClassKind classify(String fileName, String src) {
        Lang lang = langOf(fileName);

        // Clojure declares no classes at all — a file is a namespace of
        // functions. Only the three forms that do name a type are marked, and
        // anything else keeps the plain file icon rather than being called a
        // class it is not.
        if (lang == Lang.CLOJURE) {
            if (isTestName(fileName) || contains(src, "\\(deftest\\b")) return TEST;
            if (contains(src, "\\(defprotocol\\b")) return INTERFACE;
            if (contains(src, "\\(defrecord\\b"))   return RECORD;
            if (contains(src, "\\(deftype\\b"))     return CLASS;
            return null;
        }

        boolean kotlin = lang == Lang.KOTLIN;
        boolean scala = lang == Lang.SCALA;

        // Most specific declaration first: a thing that is an annotation or an
        // enum is also, textually, a "class", so those have to be asked first.
        if (kotlin) {
            if (contains(src, "annotation\\s+class")) return ANNOTATION;
            if (contains(src, "enum\\s+class"))       return ENUM;
        } else if (scala) {
            // Scala 3 spells it "enum X"; Scala 2 had no enums of its own.
            if (contains(src, "\\benum\\s+\\w"))            return ENUM;
            // A case class is a data carrier, which is what a record is.
            if (contains(src, "\\bcase\\s+class\\s+\\w"))   return RECORD;
        } else {
            if (contains(src, "@interface\\s+\\w"))              return ANNOTATION;
            if (contains(src, "\\benum\\s+\\w+\\s*[{<a-zA-Z]"))  return ENUM;
            if (contains(src, "\\brecord\\s+\\w+\\s*[(<]"))      return RECORD;
        }
        if (kotlin && contains(src, "\\bdata\\s+class\\b"))      return RECORD;
        // Scala and Groovy both say "trait" for what Java calls an interface.
        if (contains(src, "\\btrait\\s+\\w"))                  return INTERFACE;
        if (contains(src, "\\b(fun\\s+)?interface\\s+\\w"))      return INTERFACE;

        // A test is a class first and a test second, but the fact that matters
        // when scanning a tree is that it is a test.
        if (isTestName(fileName) || contains(src, "@Test\\b")
                || contains(src, "\\borg\\.junit\\b") || contains(src, "\\bkotlin\\.test\\b")
                || contains(src, "\\borg\\.scalatest\\b") || contains(src, "\\bspock\\.lang\\b")) {
            return TEST;
        }
        if (isThrowableName(fileName)
                || contains(src, "\\bextends\\s+\\w*(Exception|Error|Throwable)\\b")
                || contains(src, ":\\s*\\w*(Exception|Error|Throwable)\\s*\\(")) {
            return EXCEPTION;
        }
        if (contains(src, "\\babstract\\s+class\\b")) return ABSTRACT;

        // Java and Groovy have to say "final"; Kotlin and Scala classes are
        // final unless opened, so marking every one of them would colour a
        // whole tree one shade and say nothing. Only an explicitly sealed-off
        // class is marked in those two.
        if (!kotlin && !scala && contains(src, "\\bfinal\\s+class\\b")) return FINAL;
        if ((kotlin || scala) && contains(src, "\\bsealed\\s+(abstract\\s+)?(class|trait|interface)\\b")) {
            return FINAL;
        }

        // Anything left that still declares a type is a plain class. This is
        // last on purpose: every rule above also matches the word "class", so
        // asking this first would answer for all of them.
        if (contains(src, "\\bclass\\s+\\w")) return CLASS;
        if ((kotlin || scala) && contains(src, "\\bobject\\s+\\w")) return CLASS;
        return null;
    }

    private static boolean contains(String src, String regex) {
        return Pattern.compile(regex).matcher(src).find();
    }

    private static boolean isTestName(String name) {
        String base = stripExtension(name);
        return base.endsWith("Test") || base.endsWith("Tests")
                || base.endsWith("Spec") || base.startsWith("Test") || base.endsWith("IT");
    }

    private static boolean isThrowableName(String name) {
        String base = stripExtension(name);
        return base.endsWith("Exception") || base.endsWith("Error") || base.endsWith("Throwable");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static boolean isKotlin(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".kt") || lower.endsWith(".kts");
    }

    /** The language a file name says it is written in; Java when unrecognised. */
    static Lang langOf(String name) {
        if (isKotlin(name)) return Lang.KOTLIN;
        if (com.ccs.wiredroid.util.languages.LanguageFiles.isScala(name)) return Lang.SCALA;
        if (com.ccs.wiredroid.util.languages.LanguageFiles.isGroovy(name)) return Lang.GROOVY;
        if (com.ccs.wiredroid.util.languages.LanguageFiles.isClojure(name)) return Lang.CLOJURE;
        return Lang.JAVA;
    }

    /** Whether a name is a source file this can say anything about at all. */
    public static boolean isSource(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || isKotlin(lower)
                || com.ccs.wiredroid.util.languages.LanguageFiles.isKnown(lower);
    }

    private static String head(File file) {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buf = new char[HEAD_CHARS];
            int n = 0;
            while (n < buf.length) {
                int got = r.read(buf, n, buf.length - n);
                if (got < 0) break;
                n += got;
            }
            return new String(buf, 0, Math.max(n, 0));
        } catch (Exception e) {
            return null;
        }
    }
}
