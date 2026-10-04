package com.ccs.wiredroid.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Швидкий високоякісний підсвічувач синтаксису для блоків коду у Markdown Reader та AI Chat.
 *
 * Підтримувані мови:
 * - Java, Kotlin, Groovy, Gradle
 * - C, C++, Objective-C, C# (.NET)
 * - Python, JavaScript, TypeScript
 * - Rust, Go, Swift, Dart (Flutter), PHP
 * - XML, HTML, SVG, JSON, YAML, TOML, Properties, INI
 * - SQL (MySQL, PostgreSQL, SQLite, Oracle, H2)
 * - Bash, Shell, Zsh, Batch, PowerShell
 * - Assembly (x86/x64, ARM), JVM Bytecode, Javap
 * - Diff / Patch
 */
public final class CodeSnippetHighlighter {

    private CodeSnippetHighlighter() {}

    // ── Dark Theme Palette ────────────────────────────────────────────────
    private static final int C_KEYWORD_DARK    = 0xFFCC7832; // Orange / Amber
    private static final int C_STRING_DARK     = 0xFF6A8759; // Forest Green
    private static final int C_COMMENT_DARK    = 0xFF808080; // Muted Grey
    private static final int C_NUMBER_DARK     = 0xFF6897BB; // Cyan / Light Blue
    private static final int C_ANNOTATION_DARK = 0xFFBBB529; // Gold / Yellow
    private static final int C_TYPE_DARK       = 0xFFFFC66D; // Warm Yellow
    private static final int C_TAG_DARK        = 0xFFE8BF6A; // XML Tag
    private static final int C_ATTR_DARK       = 0xFF9876AA; // XML Attribute / Purple
    private static final int C_VAR_DARK        = 0xFF9876AA; // Variable / Identifier
    private static final int C_DIFF_ADD_DARK   = 0xFF6A8759; // Diff +
    private static final int C_DIFF_DEL_DARK   = 0xFFCF6679; // Diff -
    private static final int C_DIFF_HDR_DARK   = 0xFF6897BB; // Diff @@

    // ── Light Theme Palette ───────────────────────────────────────────────
    private static final int C_KEYWORD_LIGHT    = 0xFF0033B3; // IntelliJ Blue
    private static final int C_STRING_LIGHT     = 0xFF067D17; // Emerald Green
    private static final int C_COMMENT_LIGHT    = 0xFF8C8C8C; // Grey
    private static final int C_NUMBER_LIGHT     = 0xFF1750EB; // Royal Blue
    private static final int C_ANNOTATION_LIGHT = 0xFF9E880D; // Gold / Olive
    private static final int C_TYPE_LIGHT       = 0xFF871094; // Purple
    private static final int C_TAG_LIGHT        = 0xFF0033B3; // XML Tag Blue
    private static final int C_ATTR_LIGHT       = 0xFF1750EB; // XML Attr Blue
    private static final int C_VAR_LIGHT        = 0xFF871094; // Variable / Identifier
    private static final int C_DIFF_ADD_LIGHT   = 0xFF1A7F37; // Diff +
    private static final int C_DIFF_DEL_DARK_L  = 0xFFCF222E; // Diff -
    private static final int C_DIFF_HDR_LIGHT   = 0xFF0969DA; // Diff @@

    // ── Keywords ──────────────────────────────────────────────────────────
    private static final String JAVA_KEYWORDS = "\\b(abstract|assert|boolean|break|byte|case|catch|char|class|" +
            "const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|" +
            "import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|" +
            "static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|" +
            "record|sealed|permits|non-sealed|yield|var|val|fun|null|true|false)\\b";

    private static final String KOTLIN_KEYWORDS = "\\b(package|import|class|interface|object|fun|val|var|" +
            "typealias|constructor|init|this|super|is|as|when|try|catch|finally|for|do|while|if|else|return|" +
            "throw|break|continue|companion|data|inline|sealed|open|override|abstract|private|protected|" +
            "public|internal|lateinit|suspend|coroutine|null|true|false|it|by|lazy|get|set|field|reified|" +
            "crossinline|noinline|tailrec|operator|infix|external|expect|actual)\\b";

    private static final String PYTHON_KEYWORDS = "\\b(and|as|assert|async|await|break|class|continue|def|" +
            "del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|" +
            "or|pass|raise|return|True|try|while|with|yield|self|match|case)\\b";

    private static final String JS_KEYWORDS = "\\b(break|case|catch|class|const|continue|debugger|default|" +
            "delete|do|else|export|extends|finally|for|function|if|import|in|instanceof|new|return|super|" +
            "switch|this|throw|try|typeof|var|void|while|with|yield|let|static|async|await|null|true|false|" +
            "undefined|interface|type|enum|implements|declare|readonly|as|from|of)\\b";

    private static final String SQL_KEYWORDS = "\\b(SELECT|FROM|WHERE|INSERT|INTO|UPDATE|DELETE|CREATE|" +
            "TABLE|DROP|ALTER|INDEX|VIEW|JOIN|INNER|LEFT|RIGHT|FULL|OUTER|CROSS|ON|GROUP|BY|ORDER|HAVING|LIMIT|" +
            "OFFSET|UNION|ALL|DISTINCT|AS|AND|OR|NOT|IN|EXISTS|LIKE|ILIKE|BETWEEN|IS|NULL|PRIMARY|KEY|FOREIGN|" +
            "REFERENCES|CONSTRAINT|DEFAULT|CHECK|UNIQUE|VALUES|SET|COUNT|SUM|AVG|MIN|MAX|CASE|WHEN|THEN|" +
            "ELSE|END|DATABASE|SCHEMA|TRANSACTION|COMMIT|ROLLBACK|BEGIN|GRANT|REVOKE|TRIGGER|PROCEDURE|FUNCTION|" +
            "EXEC|EXECUTE|IF|EXPLAIN|ANALYZE|CASCADE|PRAGMA|AUTOINCREMENT|BOOLEAN|INTEGER|VARCHAR|TEXT|BLOB)\\b";

    private static final String CPP_KEYWORDS = "\\b(auto|break|case|char|const|continue|default|do|double|" +
            "else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|" +
            "static|struct|switch|typedef|union|unsigned|void|volatile|while|class|namespace|template|typename|" +
            "public|private|protected|virtual|friend|delete|new|this|operator|nullptr|true|false|bool|constexpr|" +
            "consteval|constinit|concept|requires|decltype|noexcept|static_assert|explicit|export|mutable|" +
            "using|override|final|try|catch|throw)\\b";

    private static final String CSHARP_KEYWORDS = "\\b(abstract|as|base|bool|break|byte|case|catch|char|" +
            "checked|class|const|continue|decimal|default|delegate|do|double|else|enum|event|explicit|extern|" +
            "false|finally|fixed|float|for|foreach|goto|if|implicit|in|int|interface|internal|is|lock|long|" +
            "namespace|new|null|object|operator|out|override|params|private|protected|public|readonly|ref|" +
            "return|sbyte|sealed|short|sizeof|stackalloc|static|string|struct|switch|this|throw|true|try|" +
            "typeof|uint|ulong|unchecked|unsafe|ushort|using|virtual|void|volatile|while|async|await|var|" +
            "record|init|get|set|value|yield)\\b";

    private static final String RUST_KEYWORDS = "\\b(as|async|await|break|const|continue|crate|dyn|else|" +
            "enum|extern|false|fn|for|if|impl|in|let|loop|match|mod|move|mut|pub|ref|return|self|Self|static|" +
            "struct|super|trait|true|type|unsafe|use|where|while|yield|macro_rules)\\b";

    private static final String GO_KEYWORDS = "\\b(break|case|chan|const|continue|default|defer|else|" +
            "fallthrough|for|func|go|goto|if|import|interface|map|package|range|return|select|struct|switch|" +
            "type|var|true|false|nil|iota|make|new|len|cap|append|copy|close|delete|panic|recover)\\b";

    private static final String SWIFT_KEYWORDS = "\\b(associatedtype|class|deinit|enum|extension|fileprivate|" +
            "func|import|init|inout|internal|let|open|operator|private|protocol|public|rethrows|static|struct|" +
            "subscript|typealias|var|break|case|continue|default|defer|do|else|fallthrough|for|guard|if|in|" +
            "repeat|return|switch|where|while|as|Any|catch|false|is|nil|super|self|Self|throw|throws|true|try|some|async|await)\\b";

    private static final String DART_KEYWORDS = "\\b(abstract|as|assert|async|await|break|case|catch|class|" +
            "const|continue|covariant|default|deferred|do|dynamic|else|enum|export|extends|extension|external|" +
            "factory|false|final|finally|for|Function|get|hide|if|implements|import|in|interface|is|late|library|" +
            "mixin|new|null|of|on|operator|part|required|rethrow|return|set|show|static|super|switch|sync|this|" +
            "throw|true|try|typedef|var|void|while|with|yield)\\b";

    private static final String PHP_KEYWORDS = "\\b(abstract|and|array|as|break|callable|case|catch|class|" +
            "clone|const|continue|declare|default|die|do|echo|else|elseif|empty|enddeclare|endfor|endforeach|" +
            "endif|endswitch|endwhile|eval|exit|extends|final|finally|fn|for|foreach|function|global|goto|if|" +
            "implements|include|include_once|instanceof|insteadof|interface|isset|list|match|namespace|new|" +
            "or|print|private|protected|public|readonly|require|require_once|return|static|switch|throw|trait|" +
            "try|unset|use|var|while|xor|yield|null|true|false)\\b";

    private static final String BASH_KEYWORDS = "\\b(if|then|else|elif|fi|case|esac|for|select|while|until|" +
            "do|done|in|function|time|export|source|alias|echo|cd|ls|mkdir|rm|cp|mv|chmod|chown|grep|cat|" +
            "curl|wget|git|gradle|mvn|sudo|apt|brew|find|sed|awk|ssh|kill|exit|return|source|set|unset|local)\\b";

    private static final String BYTECODE_OPCODES = "\\b(aload|astore|iload|istore|lload|lstore|fload|fstore|" +
            "dload|dstore|iaload|laload|faload|daload|aaload|baload|caload|saload|iastore|lastore|fastore|" +
            "dastore|aastore|bastore|castore|sastore|pop|pop2|dup|dup_x1|dup_x2|dup2|dup2_x1|dup2_x2|swap|" +
            "iadd|ladd|fadd|dadd|isub|lsub|fsub|dsub|imul|lmul|fmul|dmul|idiv|ldiv|fdiv|ddiv|irem|lrem|frem|" +
            "drem|ineg|lneg|fneg|dneg|ishl|lshl|ishr|lshr|iushr|lushr|iand|land|ior|lor|ixor|lxor|iinc|" +
            "i2l|i2f|i2d|l2i|l2f|l2d|f2i|f2l|f2d|d2i|d2l|d2f|i2b|i2c|i2s|lcmp|fcmpl|fcmpg|dcmpl|dcmpg|" +
            "ifeq|ifne|iflt|ifge|ifgt|ifle|if_icmpeq|if_icmpne|if_icmplt|if_icmpge|if_icmpgt|if_icmple|" +
            "if_acmpeq|if_acmpne|goto|jsr|ret|tableswitch|lookupswitch|ireturn|lreturn|freturn|dreturn|" +
            "areturn|return|getstatic|putstatic|getfield|putfield|invokevirtual|invokespecial|invokestatic|" +
            "invokeinterface|invokedynamic|new|newarray|anewarray|arraylength|athrow|checkcast|instanceof|" +
            "monitorenter|monitorexit|wide|multianewarray|ifnull|ifnonnull|goto_w|jsr_w|ldc|ldc_w|ldc2_w|" +
            "bipush|sipush|nop|mov|push|pop|call|ret|jmp|add|sub|mul|div|ldr|str|bx|bl|svc)\\b";

    @NonNull
    public static SpannableStringBuilder highlight(@NonNull String code, @Nullable String language,
                                                  boolean dark, @Nullable Typeface mono) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        if (mono != null) {
            sb.setSpan(new CustomTypefaceSpan(mono), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        String lang = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";

        switch (lang) {
            case "json":
            case "jsonc":
                highlightJson(sb, dark);
                break;
            case "xml":
            case "html":
            case "xhtml":
            case "svg":
            case "manifest":
            case "xaml":
                highlightXml(sb, dark);
                break;
            case "sql":
            case "mysql":
            case "postgresql":
            case "postgres":
            case "sqlite":
            case "plsql":
            case "h2":
                highlightSql(sb, dark);
                break;
            case "py":
            case "python":
                highlightPython(sb, dark);
                break;
            case "js":
            case "javascript":
            case "jsx":
            case "ts":
            case "typescript":
            case "tsx":
            case "mjs":
            case "cjs":
                highlightJs(sb, dark);
                break;
            case "c":
            case "cpp":
            case "c++":
            case "cc":
            case "cxx":
            case "h":
            case "hpp":
            case "objc":
                highlightCpp(sb, dark);
                break;
            case "cs":
            case "csharp":
            case "dotnet":
                highlightCSharp(sb, dark);
                break;
            case "rs":
            case "rust":
                highlightRust(sb, dark);
                break;
            case "go":
            case "golang":
                highlightGo(sb, dark);
                break;
            case "swift":
                highlightSwift(sb, dark);
                break;
            case "dart":
            case "flutter":
                highlightDart(sb, dark);
                break;
            case "php":
                highlightPhp(sb, dark);
                break;
            case "css":
            case "scss":
            case "sass":
            case "less":
                highlightCss(sb, dark);
                break;
            case "sh":
            case "bash":
            case "shell":
            case "zsh":
            case "cmd":
            case "bat":
            case "powershell":
            case "ps1":
                highlightBash(sb, dark);
                break;
            case "yaml":
            case "yml":
            case "properties":
            case "ini":
            case "toml":
            case "env":
            case "conf":
                highlightPropertiesAndYaml(sb, dark);
                break;
            case "asm":
            case "s":
            case "nasm":
            case "arm":
            case "bytecode":
            case "jvm":
            case "javap":
            case "disasm":
                highlightBytecodeAndAsm(sb, dark);
                break;
            case "diff":
            case "patch":
                highlightDiff(sb, dark);
                break;
            case "kt":
            case "kotlin":
            case "kts":
                highlightKotlin(sb, dark);
                break;
            case "gradle":
            case "groovy":
                highlightGroovy(sb, dark);
                break;
            case "plantuml":
            case "puml":
            case "uml":
                com.ccs.wiredroid.uml.PlantUmlHighlighter.highlight(sb, dark);
                break;
            default:
                // Default: Java highlighting
                highlightJava(sb, dark);
                break;
        }

        return sb;
    }

    // ── Highlighters by Language ──────────────────────────────────────────

    private static void highlightJava(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b(0x[0-9a-fA-F]+|[0-9]+(\\.[0-9]+)?([fFdDlL])?)\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(JAVA_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightKotlin(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b(0x[0-9a-fA-F]+|[0-9]+(\\.[0-9]+)?([fFL])?)\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(KOTLIN_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightGroovy(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b(0x[0-9a-fA-F]+|[0-9]+(\\.[0-9]+)?([fFdDlL])?)\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(JAVA_KEYWORDS + "|\\b(def|as|in|plugins|dependencies|implementation|testImplementation|android|defaultConfig|buildTypes|repositories)\\b"),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightPython(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(PYTHON_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("#.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightJs(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("`[\\s\\S]*?`|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile(JS_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightCpp(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?([fFlLuU]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("#[a-zA-Z_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(CPP_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightCSharp(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("@\"(\"\"|[^\"])*\"|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?([fFdDmMlL]?)\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+|\\[[A-Za-z0-9_]+\\]"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(CSHARP_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightRust(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("r#\"[\\s\\S]*?\"#|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?([uif][0-9]+|usize|isize)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("#!?\\[[^\\]]+\\]"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(RUST_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightGo(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("`[\\s\\S]*?`|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile(GO_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightSwift(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(\\\\.|[^\"])*\""),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(SWIFT_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightDart(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("@[A-Za-z0-9_]+"),
                dark ? C_ANNOTATION_DARK : C_ANNOTATION_LIGHT);
        applyPattern(sb, text, Pattern.compile(DART_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        applyComments(sb, text, dark);
    }

    private static void highlightPhp(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*"),
                dark ? C_VAR_DARK : C_VAR_LIGHT);
        applyPattern(sb, text, Pattern.compile(PHP_KEYWORDS, Pattern.CASE_INSENSITIVE),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyComments(sb, text, dark);
        applyPattern(sb, text, Pattern.compile("#.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightCss(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Strings
        applyPattern(sb, text, Pattern.compile("\"[^\"]*\"|'[^']*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        // Properties
        applyPattern(sb, text, Pattern.compile("(?<=\\{|;)[\\s]*[a-zA-Z-]+(?=\\s*:)"),
                dark ? C_ATTR_DARK : C_ATTR_LIGHT);
        // Units / Colors
        applyPattern(sb, text, Pattern.compile("#[0-9a-fA-F]{3,8}|\\b[0-9]+(px|em|rem|%|vh|vw|pt|s|ms)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        // Selectors
        applyPattern(sb, text, Pattern.compile("[.#][a-zA-Z0-9_-]+"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT);
        // Comments /* ... */
        applyPattern(sb, text, Pattern.compile("/\\*[\\s\\S]*?\\*/"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightSql(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("'(\\\\.|[^'])*'|\"(\\\\.|[^\"])*\"|`[^`]*`"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\b[0-9]+(\\.[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        applyPattern(sb, text, Pattern.compile(SQL_KEYWORDS, Pattern.CASE_INSENSITIVE),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("--.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
        applyPattern(sb, text, Pattern.compile("/\\*[\\s\\S]*?\\*/"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightBash(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        applyPattern(sb, text, Pattern.compile("\\$[A-Za-z0-9_{}]+"),
                dark ? C_VAR_DARK : C_VAR_LIGHT);
        applyPattern(sb, text, Pattern.compile(BASH_KEYWORDS),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        applyPattern(sb, text, Pattern.compile("#.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightPropertiesAndYaml(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Strings
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        // Keys: start of line or space + key: or key=
        applyPattern(sb, text, Pattern.compile("^[ \\t]*[a-zA-Z0-9_.-]+(?=\\s*[:=])", Pattern.MULTILINE),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT, true);
        // Booleans & Numbers
        applyPattern(sb, text, Pattern.compile("\\b(true|false|yes|no|null|~|[0-9]+(\\.[0-9]+)?)\\b", Pattern.CASE_INSENSITIVE),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        // Comments
        applyPattern(sb, text, Pattern.compile("[#!].*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightBytecodeAndAsm(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Strings
        applyPattern(sb, text, Pattern.compile("\"(\\\\.|[^\"])*\""),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        // Hex / Numbers / Constants
        applyPattern(sb, text, Pattern.compile("#[0-9]+|\\b0x[0-9a-fA-F]+\\b|\\b[0-9]+\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
        // Labels: name: or line 0012:
        applyPattern(sb, text, Pattern.compile("^[ \\t]*[a-zA-Z0-9_.$-]+:", Pattern.MULTILINE),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT, true);
        // Opcodes
        applyPattern(sb, text, Pattern.compile(BYTECODE_OPCODES, Pattern.CASE_INSENSITIVE),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        // Comments
        applyPattern(sb, text, Pattern.compile("//.*|;.*|#.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightDiff(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Added lines: + ...
        applyPattern(sb, text, Pattern.compile("^[+].*", Pattern.MULTILINE),
                dark ? C_DIFF_ADD_DARK : C_DIFF_ADD_LIGHT);
        // Deleted lines: - ...
        applyPattern(sb, text, Pattern.compile("^[-].*", Pattern.MULTILINE),
                dark ? C_DIFF_DEL_DARK : C_DIFF_DEL_DARK_L);
        // Header / chunk lines: @@ ... @@
        applyPattern(sb, text, Pattern.compile("^@@.*@@", Pattern.MULTILINE),
                dark ? C_DIFF_HDR_DARK : C_DIFF_HDR_LIGHT, true);
        // Diff command line
        applyPattern(sb, text, Pattern.compile("^diff --git.*", Pattern.MULTILINE),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
    }

    private static void highlightXml(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Attribute values: "..." or '...'
        applyPattern(sb, text, Pattern.compile("\"[^\"]*\"|'[^']*'"),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        // Attribute names: name=
        applyPattern(sb, text, Pattern.compile("\\b[a-zA-Z0-9_:-]+(?=\\=)"),
                dark ? C_ATTR_DARK : C_ATTR_LIGHT);
        // Tags: <tag ... </tag>
        applyPattern(sb, text, Pattern.compile("</?[a-zA-Z0-9_:-]+|/?>"),
                dark ? C_TAG_DARK : C_TAG_LIGHT, true);
        // Comments <!-- ... -->
        applyPattern(sb, text, Pattern.compile("<!--[\\s\\S]*?-->"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void highlightJson(SpannableStringBuilder sb, boolean dark) {
        String text = sb.toString();
        // Property keys: "key":
        applyPattern(sb, text, Pattern.compile("\"[^\"]*\"(?=\\s*:)"),
                dark ? C_TYPE_DARK : C_TYPE_LIGHT, true);
        // String values
        applyPattern(sb, text, Pattern.compile(":\\s*\"[^\"]*\""),
                dark ? C_STRING_DARK : C_STRING_LIGHT);
        // Booleans, Null
        applyPattern(sb, text, Pattern.compile("\\b(true|false|null)\\b"),
                dark ? C_KEYWORD_DARK : C_KEYWORD_LIGHT, true);
        // Numbers
        applyPattern(sb, text, Pattern.compile("\\b-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?\\b"),
                dark ? C_NUMBER_DARK : C_NUMBER_LIGHT);
    }

    // ── Helper Pattern Matchers ───────────────────────────────────────────

    private static void applyComments(SpannableStringBuilder sb, String text, boolean dark) {
        applyPattern(sb, text, Pattern.compile("//.*"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
        applyPattern(sb, text, Pattern.compile("/\\*[\\s\\S]*?\\*/"),
                dark ? C_COMMENT_DARK : C_COMMENT_LIGHT, false, true);
    }

    private static void applyPattern(SpannableStringBuilder sb, String text, Pattern pattern, int color) {
        applyPattern(sb, text, pattern, color, false, false);
    }

    private static void applyPattern(SpannableStringBuilder sb, String text, Pattern pattern, int color, boolean bold) {
        applyPattern(sb, text, pattern, color, bold, false);
    }

    private static void applyPattern(SpannableStringBuilder sb, String text, Pattern pattern,
                                     int color, boolean bold, boolean italic) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (italic) {
                sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
