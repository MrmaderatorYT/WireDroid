package com.ccs.wiredroid.editor;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.util.AutoImportHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionItemKind;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Completion for classes that are not imported yet — and the import to go with.
 *
 * <p>Typing {@code Arr} and picking {@code ArrayList} used to leave a red name
 * and a trip to the top of the file. The suggestion now carries its import: the
 * name goes in at the caret, the {@code import java.util.ArrayList;} goes in
 * among the others, and the caret does not move from where the typing was.</p>
 *
 * <p>Only classes the file does not already have are offered. A name that is
 * imported, declared in this file, or in the same package needs no help, and an
 * entry for it would push the useful suggestions further down the list.</p>
 */
public final class ImportingCompletion {

    private static final Pattern IMPORT_LINE =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

    /** Enough of a prefix to be worth suggesting; one letter matches half the JDK. */
    private static final int MIN_PREFIX = 2;

    private ImportingCompletion() {}

    /**
     * Lines scanned for imports and type declarations.
     *
     * <p>Both live at the top of a Java file. Reading only the head keeps this
     * off the whole buffer — it runs on every keystroke, and turning a 5000-line
     * file into a String each time would cost more than the feature is worth.</p>
     */
    private static final int HEAD_LINES = 200;

    /** Publishes importable classes matching the prefix. */
    public static void contribute(@NonNull Content content, @NonNull CharPosition position,
                                  @NonNull String prefix, @NonNull CompletionPublisher publisher) {
        if (prefix.length() < MIN_PREFIX) return;
        if (!Character.isUpperCase(prefix.charAt(0))) return;   // class names only

        String source = head(content);
        List<String> alreadyThere = importedNames(source);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

        List<CompletionItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : AutoImportHelper.commonImports().entrySet()) {
            String simpleName = entry.getKey();
            if (!simpleName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) continue;
            if (alreadyThere.contains(simpleName)) continue;
            if (declaredInFile(source, simpleName)) continue;
            items.add(new ImportItem(simpleName, entry.getValue(), prefix.length()));
            if (items.size() >= 12) break;                       // a menu, not a catalogue
        }
        for (CompletionItem item : items) {
            publisher.addItem(item);
        }
    }

    /** The first lines of the file, where imports and the type declaration are. */
    private static String head(Content content) {
        int limit = Math.min(content.getLineCount(), HEAD_LINES);
        StringBuilder sb = new StringBuilder(limit * 40);
        for (int line = 0; line < limit; line++) {
            sb.append(content.getLineString(line)).append('\n');
            // Once the type itself starts, everything of interest has been seen.
            String trimmed = sb.substring(sb.lastIndexOf("\n",
                    Math.max(0, sb.length() - 2)) + 1).trim();
            if (trimmed.startsWith("class ") || trimmed.startsWith("public ")
                    || trimmed.startsWith("final ") || trimmed.startsWith("abstract ")
                    || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")) {
                break;
            }
        }
        return sb.toString();
    }

    /** Simple names the file can already refer to without a new import. */
    static List<String> importedNames(String source) {
        List<String> names = new ArrayList<>();
        if (source == null) return names;
        Matcher matcher = IMPORT_LINE.matcher(source);
        while (matcher.find()) {
            String imported = matcher.group(2);
            int dot = imported.lastIndexOf('.');
            names.add(dot < 0 ? imported : imported.substring(dot + 1));
        }
        return names;
    }

    /** True when the file declares this type itself, so importing it would clash. */
    static boolean declaredInFile(String source, String simpleName) {
        if (source == null) return false;
        return Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(simpleName) + "\\b")
                .matcher(source).find();
    }

    /**
     * One suggestion: the class name, plus its import when accepted.
     *
     * <p>The import is inserted before the name, so the offset the caret is
     * about to be placed at is still valid when the name goes in — doing it the
     * other way round would put the caret one line too high.</p>
     */
    private static final class ImportItem extends CompletionItem {
        private final String simpleName;
        private final String qualifiedName;
        private final int prefixLen;

        ImportItem(String simpleName, String qualifiedName, int prefixLen) {
            super(simpleName, qualifiedName);
            this.simpleName = simpleName;
            this.qualifiedName = qualifiedName;
            this.prefixLen = prefixLen;
            this.kind = CompletionItemKind.Class;
            // Sorted after anything already in scope: a name the file can
            // already use should win over one that costs an import.
            this.sortText = "z" + simpleName;
        }

        @Override
        public void performCompletion(@NonNull CodeEditor editor, @NonNull Content text,
                                      int line, int column) {
            text.beginBatchEdit();
            try {
                text.replace(line, column - prefixLen, line, column, simpleName);
                insertImport(text);
            } finally {
                text.endBatchEdit();
            }
        }

        /** Puts the import after the last existing one, or after the package line. */
        private void insertImport(Content text) {
            int lastImport = -1;
            int packageLine = -1;
            int limit = Math.min(text.getLineCount(), 200);
            for (int line = 0; line < limit; line++) {
                String content = text.getLineString(line).trim();
                if (content.startsWith("package ")) packageLine = line;
                if (content.startsWith("import ")) lastImport = line;
                // Past the first type declaration there are no imports left to find.
                if (content.startsWith("public ") || content.startsWith("class ")
                        || content.startsWith("final ") || content.startsWith("abstract ")) {
                    break;
                }
            }
            String line = "import " + qualifiedName + ";\n";
            if (lastImport >= 0) {
                text.insert(lastImport + 1, 0, line);
            } else if (packageLine >= 0) {
                text.insert(packageLine + 1, 0, "\n" + line);
            } else {
                text.insert(0, 0, line);
            }
        }
    }
}
