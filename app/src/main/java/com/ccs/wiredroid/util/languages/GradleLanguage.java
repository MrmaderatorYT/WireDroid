package com.ccs.wiredroid.util.languages;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.QuickQuoteHandler;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Gradle build-script highlighting for both DSLs — Groovy ({@code build.gradle})
 * and Kotlin ({@code build.gradle.kts}).
 *
 * <p>Beyond plain keyword matching this separates the pieces that matter when
 * reading a build file: DSL block names, dependency configurations, dependency
 * coordinates, method calls, annotations, and {@code ${…}} interpolation inside
 * GStrings.</p>
 */
public class GradleLanguage implements Language {

    private final GradleAnalyzeManager manager;
    private final IdentifierAutoComplete autoComplete;

    /** Top-level DSL blocks — highlighted as keywords in bold. */
    private static final String[] DSL_BLOCKS = {
        "plugins", "dependencies", "repositories", "buildscript", "allprojects", "subprojects",
        "android", "defaultConfig", "buildTypes", "compileOptions", "kotlinOptions", "sourceSets",
        "signingConfigs", "publishing", "application", "java", "kotlin", "tasks", "test",
        "testOptions", "packagingOptions", "lint", "lintOptions", "flavorDimensions",
        "productFlavors", "ext", "configurations", "artifacts", "jar", "wrapper", "settings",
        "pluginManagement", "dependencyResolutionManagement", "versionCatalogs", "toolchain",
        "manifestPlaceholders", "bundle", "composeOptions", "dataBinding", "viewBinding"
    };

    /** Dependency configurations — highlighted distinctly from other identifiers. */
    private static final String[] CONFIGURATIONS = {
        "implementation", "api", "compileOnly", "compileOnlyApi", "runtimeOnly", "annotationProcessor",
        "testImplementation", "testApi", "testCompileOnly", "testRuntimeOnly", "testAnnotationProcessor",
        "androidTestImplementation", "androidTestCompileOnly", "androidTestRuntimeOnly",
        "debugImplementation", "releaseImplementation", "kapt", "ksp", "classpath",
        "compile", "testCompile", "provided", "runtime", "modules"
    };

    /** Language keywords shared by Groovy and Kotlin. */
    private static final String[] LANGUAGE_KEYWORDS = {
        "def", "val", "var", "fun", "class", "interface", "enum", "object", "package", "import",
        "extends", "implements", "return", "new", "this", "super", "if", "else", "for", "while",
        "do", "switch", "when", "case", "default", "break", "continue", "throw", "try", "catch",
        "finally", "void", "int", "long", "float", "double", "boolean", "char", "byte", "short",
        "String", "as", "is", "in", "by", "it", "private", "public", "internal", "protected",
        "static", "final", "abstract", "open", "override", "inline", "operator", "lazy"
    };

    /** Well-known DSL properties and helper calls. */
    private static final String[] DSL_PROPERTIES = {
        "group", "version", "description", "mainClass", "mainClassName", "sourceCompatibility",
        "targetCompatibility", "jvmTarget", "compileSdk", "compileSdkVersion", "targetSdk",
        "targetSdkVersion", "minSdk", "minSdkVersion", "applicationId", "namespace", "versionCode",
        "versionName", "testInstrumentationRunner", "minifyEnabled", "shrinkResources",
        "proguardFiles", "consumerProguardFiles", "archivesBaseName", "rootProject", "project",
        "mavenCentral", "google", "mavenLocal", "gradlePluginPortal", "jcenter", "maven", "url",
        "uri", "id", "apply", "plugin", "task", "register", "named", "exclude", "transitive",
        "platform", "enforcedPlatform", "files", "fileTree", "gradleApi", "localGroovy",
        "sourceSets", "srcDir", "srcDirs", "useJUnitPlatform", "withSourcesJar", "withJavadocJar"
    };

    private static final String[] LITERAL_WORDS = { "true", "false", "null", "None" };

    private static final String[] COMPLETION_KEYWORDS = concat(
            DSL_BLOCKS, CONFIGURATIONS, LANGUAGE_KEYWORDS, DSL_PROPERTIES, LITERAL_WORDS);

    public GradleLanguage() {
        manager = new GradleAnalyzeManager();
        autoComplete = new IdentifierAutoComplete(COMPLETION_KEYWORDS);
    }

    private static String[] concat(String[]... groups) {
        int total = 0;
        for (String[] g : groups) total += g.length;
        String[] out = new String[total];
        int at = 0;
        for (String[] g : groups) {
            System.arraycopy(g, 0, out, at, g.length);
            at += g.length;
        }
        return out;
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return manager;
    }

    @Nullable
    @Override
    public QuickQuoteHandler getQuickQuoteHandler() {
        return null;
    }

    @Override
    public void destroy() {
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content, @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher, @NonNull Bundle extraArguments) {
        String prefix = io.github.rosemoe.sora.lang.completion.CompletionHelper.computePrefix(
                content, position, Character::isJavaIdentifierPart);
        autoComplete.requireAutoComplete(content, position, prefix, publisher, null);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference text, int line, int column) {
        // Indent one level after an opening brace, like the Java language does.
        try {
            String lineText = text.getLine(line);
            int limit = Math.min(column, lineText.length());
            int depth = 0;
            for (int i = 0; i < limit; i++) {
                char c = lineText.charAt(i);
                if (c == '{' || c == '(' || c == '[') depth++;
                else if (c == '}' || c == ')' || c == ']') depth--;
            }
            return Math.max(0, depth) * 4;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @NonNull
    @Override
    public Formatter getFormatter() {
        return EmptyLanguage.EmptyFormatter.INSTANCE;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newlineHandlers;
    }

    private static class GradleAnalyzeManager extends SimpleAnalyzeManager<Object> {

        private static final Set<String> BLOCKS = setOf(DSL_BLOCKS);
        private static final Set<String> CONFIGS = setOf(CONFIGURATIONS);
        private static final Set<String> KEYWORDS = setOf(LANGUAGE_KEYWORDS);
        private static final Set<String> PROPERTIES = setOf(DSL_PROPERTIES);
        private static final Set<String> LITERALS = setOf(LITERAL_WORDS);

        private static Set<String> setOf(String[] values) {
            return new HashSet<>(Arrays.asList(values));
        }

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int line = 0;
            int lineStartIdx = 0;
            int len = text.length();

            long styleNormal    = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
            long styleBlock     = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
            long styleKeyword   = TextStyle.makeStyle(EditorColorScheme.KEYWORD);
            long styleConfig    = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME, 0, true, false, false);
            long styleProperty  = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME);
            long styleFunction  = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME);
            long styleLiteral   = TextStyle.makeStyle(EditorColorScheme.LITERAL);
            long styleCoordinate = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_VALUE);
            long styleComment   = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
            long styleOperator  = TextStyle.makeStyle(EditorColorScheme.OPERATOR);
            long styleAnnotation = TextStyle.makeStyle(EditorColorScheme.ANNOTATION);
            long styleInterp    = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR, 0, true, false, false);

            boolean inBlockComment = false;

            for (int i = 0; i < len; ) {
                if (delegate.isCancelled()) {
                    return null;
                }

                char c = text.charAt(i);

                if (c == '\n') {
                    builder.determine(line);
                    line++;
                    lineStartIdx = i + 1;
                    i++;
                    continue;
                }

                int col = i - lineStartIdx;

                if (inBlockComment) {
                    builder.addIfNeeded(line, col, styleComment);
                    if (c == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }

                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                    inBlockComment = true;
                    builder.addIfNeeded(line, col, styleComment);
                    i += 2;
                    continue;
                }

                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                    builder.addIfNeeded(line, col, styleComment);
                    while (i < len && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }

                if (c == '#' && col == 0) {
                    // Shebang or a properties-style comment pasted into the script.
                    builder.addIfNeeded(line, col, styleComment);
                    while (i < len && text.charAt(i) != '\n') i++;
                    continue;
                }

                if (c == '"' || c == '\'') {
                    int consumed = highlightString(text, i, len, line, col, lineStartIdx, builder,
                            styleLiteral, styleCoordinate, styleInterp, styleOperator);
                    // A multi-line string moves us onto later lines; recompute state.
                    int end = i + consumed;
                    for (int k = i; k < end && k < len; k++) {
                        if (text.charAt(k) == '\n') {
                            builder.determine(line);
                            line++;
                            lineStartIdx = k + 1;
                        }
                    }
                    i = end;
                    continue;
                }

                if (c == '@' && i + 1 < len && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                    builder.addIfNeeded(line, col, styleAnnotation);
                    i++;
                    while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                    continue;
                }

                if (Character.isWhitespace(c)) {
                    builder.addIfNeeded(line, col, styleNormal);
                    i++;
                    continue;
                }

                if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    i++;
                    while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    String word = text.substring(start, i);

                    long style;
                    if (LITERALS.contains(word)) {
                        style = styleLiteral;
                    } else if (CONFIGS.contains(word)) {
                        style = styleConfig;
                    } else if (BLOCKS.contains(word) && followedByBrace(text, i, len)) {
                        style = styleBlock;
                    } else if (KEYWORDS.contains(word)) {
                        style = styleKeyword;
                    } else if (BLOCKS.contains(word) || PROPERTIES.contains(word)) {
                        style = styleProperty;
                    } else if (followedByParen(text, i, len)) {
                        style = styleFunction;
                    } else {
                        style = styleNormal;
                    }
                    builder.addIfNeeded(line, col, style);
                    continue;
                }

                if (Character.isDigit(c)) {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && (Character.isLetterOrDigit(text.charAt(i))
                            || text.charAt(i) == '.' || text.charAt(i) == '_')) {
                        i++;
                    }
                    continue;
                }

                builder.addIfNeeded(line, col, styleOperator);
                i++;
            }

            builder.determine(line);
            return new Styles(builder.build());
        }

        /**
         * Highlights a string literal starting at {@code start}, marking
         * {@code ${…}} interpolation separately and using a distinct colour for
         * dependency coordinates ({@code group:artifact:version}).
         *
         * @return the number of characters consumed, including both quotes
         */
        private int highlightString(StringBuilder text, int start, int len, int line, int col,
                                    int lineStartIdx, MappedSpans.Builder builder,
                                    long styleLiteral, long styleCoordinate,
                                    long styleInterp, long styleOperator) {
            char quote = text.charAt(start);
            // Triple-quoted strings span lines and ignore single closing quotes.
            boolean triple = start + 2 < len
                    && text.charAt(start + 1) == quote && text.charAt(start + 2) == quote;
            int bodyStart = start + (triple ? 3 : 1);

            int end = bodyStart;
            while (end < len) {
                char c = text.charAt(end);
                if (c == '\\') { end += 2; continue; }
                if (triple) {
                    if (c == quote && end + 2 < len
                            && text.charAt(end + 1) == quote && text.charAt(end + 2) == quote) {
                        end += 3;
                        break;
                    }
                } else {
                    if (c == '\n') break;          // unterminated — stop at the line end
                    if (c == quote) { end++; break; }
                }
                end++;
            }
            if (end > len) end = len;

            String body = text.substring(bodyStart, Math.max(bodyStart, Math.min(end, len)));
            boolean coordinate = looksLikeCoordinate(body);
            long base = coordinate ? styleCoordinate : styleLiteral;
            builder.addIfNeeded(line, col, base);

            // Mark interpolation. Only single-line spans get their own span
            // because the builder needs ascending columns within one line.
            int currentLine = line;
            int currentLineStart = lineStartIdx;
            for (int k = bodyStart; k < end && k < len; k++) {
                char c = text.charAt(k);
                if (c == '\n') {
                    currentLine++;
                    currentLineStart = k + 1;
                    continue;
                }
                if (c != '$') continue;
                boolean braced = k + 1 < len && text.charAt(k + 1) == '{';
                if (!braced && (k + 1 >= len || !Character.isJavaIdentifierStart(text.charAt(k + 1)))) {
                    continue;
                }
                builder.addIfNeeded(currentLine, k - currentLineStart, styleInterp);
                int close = k + 1;
                if (braced) {
                    int depth = 0;
                    while (close < end && close < len) {
                        char cc = text.charAt(close);
                        if (cc == '{') depth++;
                        else if (cc == '}') { depth--; if (depth == 0) { close++; break; } }
                        else if (cc == '\n') break;
                        close++;
                    }
                } else {
                    close = k + 1;
                    while (close < end && close < len
                            && Character.isJavaIdentifierPart(text.charAt(close))) {
                        close++;
                    }
                }
                if (close < end && close < len) {
                    builder.addIfNeeded(currentLine, close - currentLineStart, base);
                }
                k = close - 1;
            }
            return end - start;
        }

        /** True for {@code group:artifact} or {@code group:artifact:version}. */
        private static boolean looksLikeCoordinate(String s) {
            if (s.isEmpty() || s.indexOf(':') < 0) return false;
            if (s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0) return false;
            if (s.contains("://")) return false;   // a repository URL, not a coordinate
            String[] parts = s.split(":", -1);
            if (parts.length < 2 || parts.length > 4) return false;
            return !parts[0].isEmpty() && !parts[1].isEmpty();
        }

        /** True when the next non-space character opens a block. */
        private static boolean followedByBrace(StringBuilder text, int from, int len) {
            for (int i = from; i < len; i++) {
                char c = text.charAt(i);
                if (c == '{') return true;
                if (c == ' ' || c == '\t') continue;
                if (c == '(') continue;            // `tasks.register("x") {`
                if (c == ')') continue;
                return false;
            }
            return false;
        }

        /** True when the identifier is immediately called. */
        private static boolean followedByParen(StringBuilder text, int from, int len) {
            for (int i = from; i < len; i++) {
                char c = text.charAt(i);
                if (c == '(') return true;
                if (c == ' ' || c == '\t') continue;
                return false;
            }
            return false;
        }
    }

    /** Enter between a bracket pair opens the block. */
    private final NewlineHandler[] newlineHandlers =
            { new BracePairNewlineHandler(useTab()) };
}
