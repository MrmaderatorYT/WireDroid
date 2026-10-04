package com.ccs.wiredroid.ai;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсить markdown з AI-відповіді: кодові блоки з підсвіткою
 * Java, Kotlin, C, C++, Bash, Rust, ASM x86/ARM.
 */
public final class ChatFormatter {

    private static final int COLOR_KEYWORD   = 0xFFCC7832;
    private static final int COLOR_STRING    = 0xFF6A8759;
    private static final int COLOR_NUMBER    = 0xFF6897BB;
    private static final int COLOR_COMMENT   = 0xFF808080;
    private static final int COLOR_TYPE      = 0xFFA9B7C6;
    private static final int COLOR_PREPROC   = 0xFFBBB529;
    private static final int COLOR_CODE_BG   = 0xFF2B2B2B;
    private static final int COLOR_BOLD      = 0xFFFFFFFF;
    private static final int COLOR_HEADER    = 0xFF4A86C8;
    private static final int COLOR_ASM_OP    = 0xFFCC7832;
    private static final int COLOR_ASM_REG   = 0xFF6897BB;
    private static final int COLOR_ASM_LABEL = 0xFFA9B7C6;

    // ── Keyword sets ──────────────────────────────────────────

    private static final String JAVA_KW = "abstract|assert|boolean|break|byte|case|catch|char|class|"
            + "const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|"
            + "if|implements|import|instanceof|int|interface|long|native|new|package|private|"
            + "protected|public|return|short|static|strictfp|super|switch|synchronized|this|"
            + "throw|throws|transient|try|void|volatile|while|true|false|null|var|record|yield|"
            + "sealed|non-sealed|permits|module|opens|provides|requires|to|uses|with|when|exports|transitive";

    private static final String KOTLIN_KW = "abstract|actual|annotation|as|break|by|catch|class|companion|"
            + "const|constructor|crossinline|data|delegate|do|dynamic|else|enum|expect|external|"
            + "false|final|finally|for|fun|get|if|impl|import|in|infix|init|inline|inner|interface|"
            + "internal|is|lateinit|noinline|object|open|operator|out|override|package|private|"
            + "protected|public|reified|return|sealed|set|super|suspend|tailrec|this|throw|true|"
            + "try|typealias|val|var|vararg|when|where";

    private static final String C_KW = "auto|break|case|char|const|continue|default|do|double|else|enum|"
            + "extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|"
            + "static|struct|switch|typedef|union|unsigned|void|volatile|while|_Bool|_Complex|_Imaginary|"
            + "bool|nullptr|static_assert|thread_local|alignas|alignof|_Alignas|_Alignof";

    private static final String CPP_KW = C_KW + "|alignas|alignof|and|and_eq|asm|auto|bitand|bitor|"
            + "class|compl|concept|const_cast|consteval|constexpr|constinit|co_await|co_return|co_yield|"
            + "decltype|delete|dynamic_cast|explicit|export|friend|module|mutable|namespace|new|noexcept|"
            + "not|not_eq|nullptr|operator|or|or_eq|private|protected|public|reinterpret_cast|requires|"
            + "static_assert|static_cast|template|this|thread_local|throw|try|typeid|typename|using|"
            + "virtual|xor|xor_eq|nullptr_t|override|final|override";

    private static final String RUST_KW = "as|async|await|break|const|continue|crate|dyn|else|enum|extern|"
            + "false|fn|for|if|impl|in|let|loop|match|mod|move|mut|pub|ref|return|self|Self|static|"
            + "struct|super|trait|true|type|unsafe|use|where|while|yield|abstract|become|box|do|final|"
            + "macro|override|priv|typeof|unsized|virtual";

    private static final String BASH_KW = "if|then|else|elif|fi|for|while|do|done|case|esac|function|"
            + "return|exit|in|select|until|time|coproc|local|declare|typeset|export|readonly|unset|"
            + "shift|source|trap|eval|exec|set|shopt|true|false|echo|printf|read|test";

    // ── Patterns ──────────────────────────────────────────────

    private static final Pattern P_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern P_HEADER = Pattern.compile("^#{1,3}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern P_CODE_BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private ChatFormatter() {}

    public static SpannableStringBuilder format(String text, int textColor) {
        if (text == null || text.isEmpty()) return new SpannableStringBuilder();

        SpannableStringBuilder result = new SpannableStringBuilder();
        Matcher m = P_CODE_BLOCK.matcher(text);
        int lastEnd = 0;

        while (m.find()) {
            if (m.start() > lastEnd) {
                appendFormattedText(result, text.substring(lastEnd, m.start()), textColor);
            }
            String lang = m.group(1);
            String code = m.group(2);
            if (code != null && code.endsWith("\n")) code = code.substring(0, code.length() - 1);
            appendCodeBlock(result, code, lang);
            lastEnd = m.end();
        }
        if (lastEnd < text.length()) {
            appendFormattedText(result, text.substring(lastEnd), textColor);
        }
        return result;
    }

    // ── Text formatting ───────────────────────────────────────

    private static void appendFormattedText(SpannableStringBuilder sb, String text, int textColor) {
        Matcher hm = P_HEADER.matcher(text);
        int last = 0;
        while (hm.find()) {
            if (hm.start() > last) appendPlainText(sb, text.substring(last, hm.start()), textColor);
            String ht = hm.group(1);
            int s = sb.length();
            sb.append(ht).append("\n");
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, s + ht.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.2f), s, s + ht.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(COLOR_HEADER), s, s + ht.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = hm.end();
        }
        if (last < text.length()) appendPlainText(sb, text.substring(last), textColor);
    }

    private static void appendPlainText(SpannableStringBuilder sb, String text, int textColor) {
        Matcher bm = P_BOLD.matcher(text);
        int last = 0;
        while (bm.find()) {
            if (bm.start() > last) {
                int s = sb.length();
                sb.append(text.substring(last, bm.start()));
                sb.setSpan(new ForegroundColorSpan(textColor), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            String bt = bm.group(1);
            int s = sb.length();
            sb.append(bt);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, s + bt.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(COLOR_BOLD), s, s + bt.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = bm.end();
        }
        if (last < text.length()) {
            int s = sb.length();
            sb.append(text.substring(last));
            sb.setSpan(new ForegroundColorSpan(textColor), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Code block highlighting ───────────────────────────────

    private static void appendCodeBlock(SpannableStringBuilder sb, String code, String lang) {
        int start = sb.length();
        sb.append(code).append("\n");

        sb.setSpan(new LeadingMarginSpan.Standard(dp(16), dp(16)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Підсвітка залежно від мови
        String l = lang == null ? "" : lang.toLowerCase(java.util.Locale.US).trim();
        switch (l) {
            case "kotlin": case "kt":
                highlightWithKeywords(sb, start, sb.length(), KOTLIN_KW, "//", "/*");
                break;
            case "c":
                highlightWithKeywords(sb, start, sb.length(), C_KW, "//", "/*");
                highlightPreprocessor(sb, start, sb.length());
                break;
            case "cpp": case "c++": case "cc": case "cxx":
                highlightWithKeywords(sb, start, sb.length(), CPP_KW, "//", "/*");
                highlightPreprocessor(sb, start, sb.length());
                break;
            case "rust": case "rs":
                highlightWithKeywords(sb, start, sb.length(), RUST_KW, "//", "/*");
                break;
            case "bash": case "sh": case "shell": case "zsh":
                highlightBash(sb, start, sb.length());
                break;
            case "asm": case "x86":
                highlightAsmX86(sb, start, sb.length());
                break;
            case "arm": case "aarch64":
                highlightAsmArm(sb, start, sb.length());
                break;
            default:
                highlightWithKeywords(sb, start, sb.length(), JAVA_KW, "//", "/*");
                break;
        }
    }

    // ── Generic keyword highlighter ───────────────────────────

    private static void highlightWithKeywords(SpannableStringBuilder sb, int cs, int ce,
                                               String keywords, String slComment, String mlComment) {
        String code = sb.subSequence(cs, ce).toString();

        // Keywords
        Matcher km = Pattern.compile("\\b(" + keywords + ")\\b").matcher(code);
        while (km.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_KEYWORD), cs + km.start(), cs + km.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings
        Matcher sm = Pattern.compile("\"([^\"]*)\"").matcher(code);
        while (sm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + sm.start(), cs + sm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Chars
        Matcher cm = Pattern.compile("'.'").matcher(code);
        while (cm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + cm.start(), cs + cm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Numbers
        Matcher nm = Pattern.compile("\\b(\\d+[\\.\\d]*[fFdDlLuU]*)\\b").matcher(code);
        while (nm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), cs + nm.start(), cs + nm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Single-line comments
        if (slComment != null) {
            Matcher clm = Pattern.compile(Pattern.quote(slComment) + "(.+)$", Pattern.MULTILINE).matcher(code);
            while (clm.find()) {
                sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), cs + clm.start(), cs + clm.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // Multi-line comments
        if (mlComment != null) {
            String endComment = mlComment.equals("//") ? null : "\\*/";
            if (endComment != null) {
                Matcher mlm = Pattern.compile(Pattern.quote(mlComment) + "(.+?)" + endComment, Pattern.DOTALL).matcher(code);
                while (mlm.find()) {
                    sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), cs + mlm.start(), cs + mlm.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        // Types (common)
        Matcher tm = Pattern.compile("\\b(String|Integer|Long|Double|Float|Boolean|Object|List|Map|Set|"
                + "ArrayList|HashMap|Thread|Exception|StringBuilder|System|File|InputStream|OutputStream|"
                + "int|long|short|byte|char|float|double|boolean|void|size_t|int8_t|int16_t|int32_t|int64_t|"
                + "uint8_t|uint16_t|uint32_t|uint64_t|char8_t|char16_t|char32_t|wchar_t|"
                + "Vec|Box|Rc|Arc|String|Option|Result|HashMap|HashSet|BTreeMap|Cow|Pin|"
                + "bool|str|Self|usize|isize|crate|super|self)\\b").matcher(code);
        while (tm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_TYPE), cs + tm.start(), cs + tm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── C/C++ preprocessor ────────────────────────────────────

    private static void highlightPreprocessor(SpannableStringBuilder sb, int cs, int ce) {
        String code = sb.subSequence(cs, ce).toString();
        Matcher pm = Pattern.compile("^\\s*#\\s*\\w+.*$", Pattern.MULTILINE).matcher(code);
        while (pm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_PREPROC), cs + pm.start(), cs + pm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Bash ──────────────────────────────────────────────────

    private static void highlightBash(SpannableStringBuilder sb, int cs, int ce) {
        String code = sb.subSequence(cs, ce).toString();

        // Keywords
        Matcher km = Pattern.compile("\\b(" + BASH_KW + ")\\b").matcher(code);
        while (km.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_KEYWORD), cs + km.start(), cs + km.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings double
        Matcher sd = Pattern.compile("\"([^\"]*)\"").matcher(code);
        while (sd.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + sd.start(), cs + sd.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings single
        Matcher ss = Pattern.compile("'([^']*)'").matcher(code);
        while (ss.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + ss.start(), cs + ss.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Variables $var, ${var}
        Matcher vm = Pattern.compile("\\$\\{?[\\w]+\\}?").matcher(code);
        while (vm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), cs + vm.start(), cs + vm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Comments
        Matcher cm = Pattern.compile("#(.+)$", Pattern.MULTILINE).matcher(code);
        while (cm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), cs + cm.start(), cs + cm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Numbers
        Matcher nm = Pattern.compile("\\b(\\d+)\\b").matcher(code);
        while (nm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), cs + nm.start(), cs + nm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Flags -x, --option
        Matcher fm = Pattern.compile("(\\s)(--?[\\w-]+)").matcher(code);
        while (fm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_TYPE), cs + fm.start() + 1, cs + fm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── ASM x86 ──────────────────────────────────────────────

    private static void highlightAsmX86(SpannableStringBuilder sb, int cs, int ce) {
        String code = sb.subSequence(cs, ce).toString();

        // Instructions
        String x86ops = "mov|movb|movw|movl|movq|push|pop|pushq|popq|"
                + "add|sub|imul|idiv|mul|div|inc|dec|neg|not|and|or|xor|shl|shr|sal|sar|rol|ror|"
                + "cmp|test|lea|"
                + "jmp|je|jne|jz|jnz|jg|jge|jl|jle|ja|jae|jb|jbe|jo|jno|js|jns|loop|loope|loopne|"
                + "call|ret|retn|leave|enter|"
                + "nop|int|syscall|sysenter|cpuid|rdtsc|"
                + "cmove|cmovne|cmovg|cmovge|cmovl|cmovle|cmova|cmovae|cmovb|cmovbe|"
                + "sete|setne|setg|setge|setl|setle|seta|setae|setb|setbe|"
                + "pushf|popf|cli|sti|cld|std|"
                + "movsx|movzx|cbw|cwde|cdqe|cqo|cdq|cwd|"
                + "bsf|bsr|bt|bts|btr|btc|bswap|"
                + "xchg|bswap|cmpxchg|cmpxchg8b";

        Matcher im = Pattern.compile("\\b(" + x86ops + ")\\b").matcher(code);
        while (im.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_OP), cs + im.start(), cs + im.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Registers
        String x86regs = "rax|rbx|rcx|rdx|rsi|rdi|rbp|rsp|r8|r9|r10|r11|r12|r13|r14|r15|"
                + "eax|ebx|ecx|edx|esi|edi|ebp|esp|r8d|r9d|r10d|r11d|r12d|r13d|r14d|r15d|"
                + "ax|bx|cx|dx|si|di|bp|sp|r8w|r9w|r10w|r11w|r12w|r13w|r14w|r15w|"
                + "al|ah|bl|bh|cl|ch|dl|dh|sil|dil|bpl|spl|r8b|r9b|r10b|r11b|r12b|r13b|r14b|r15b|"
                + "cs|ds|ss|es|fs|gs|rflags|eflags|flags|rip|eip|ip|"
                + "xmm0|xmm1|xmm2|xmm3|xmm4|xmm5|xmm6|xmm7|xmm8|xmm9|xmm10|xmm11|xmm12|xmm13|xmm14|xmm15|"
                + "ymm0|ymm1|ymm2|ymm3|ymm4|ymm5|ymm6|ymm7";

        Matcher rm = Pattern.compile("\\b(" + x86regs + ")\\b").matcher(code);
        while (rm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_REG), cs + rm.start(), cs + rm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Labels
        Matcher lm = Pattern.compile("^\\s*([\\w.]+):", Pattern.MULTILINE).matcher(code);
        while (lm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_LABEL), cs + lm.start(), cs + lm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Directives
        Matcher dm = Pattern.compile("(\\.\\w+)").matcher(code);
        while (dm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_PREPROC), cs + dm.start(), cs + dm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Comments
        Matcher cm = Pattern.compile("[#;](.+)$", Pattern.MULTILINE).matcher(code);
        while (cm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), cs + cm.start(), cs + cm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings
        Matcher sm = Pattern.compile("\"([^\"]*)\"").matcher(code);
        while (sm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + sm.start(), cs + sm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Numbers (hex 0x, decimal, octal)
        Matcher nm = Pattern.compile("\\b(0x[0-9a-fA-F]+|0b[01]+|\\d+[hH]?|[0-9a-fA-F]+h)\\b").matcher(code);
        while (nm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), cs + nm.start(), cs + nm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── ASM ARM ───────────────────────────────────────────────

    private static void highlightAsmArm(SpannableStringBuilder sb, int cs, int ce) {
        String code = sb.subSequence(cs, ce).toString();

        // Instructions
        String armOps = "mov|mvn|add|adc|sub|sbc|rsb|rsc|"
                + "and|orr|eor|bic|tst|teq|cmp|cmn|"
                + "ldr|str|ldrb|strb|ldrh|strh|ldm|stm|"
                + "push|pop|"
                + "b|bl|bx|blx|bxlr|ret|"
                + "beq|bne|bgt|bge|blt|ble|bhi|bhs|blo|bls|bvs|bvc|bmi|bpl|bal|"
                + "mul|mla|mls|umull|smull|umlal|smlal|"
                + "lsl|lsr|asr|ror|rrx|"
                + "mrs|msr|cpsid|cpsie|wfi|wfe|dmb|dsb|isb|"
                + "svc|swi|bkpt|nop|"
                + "clz|rev|rev16|revsh|"
                + "uxtb|uxth|uxtw|sxtb|sxth|sxtw|"
                + "it|itt|ite|itte|ittt|ittte";

        Matcher im = Pattern.compile("\\b(" + armOps + ")\\b").matcher(code);
        while (im.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_OP), cs + im.start(), cs + im.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Registers
        String armRegs = "r0|r1|r2|r3|r4|r5|r6|r7|r8|r9|r10|r11|r12|"
                + "sp|lr|pc|cpsr|spsr|fp|ip|"
                + "x0|x1|x2|x3|x4|x5|x6|x7|x8|x9|x10|x11|x12|x13|x14|x15|"
                + "x16|x17|x18|x19|x20|x21|x22|x23|x24|x25|x26|x27|x28|x29|x30|"
                + "w0|w1|w2|w3|w4|w5|w6|w7|w8|w9|w10|w11|w12|w13|w14|w15|"
                + "w16|w17|w18|w19|w20|w21|w22|w23|w24|w25|w26|w27|w28|w29|w30|wzr|xzr|"
                + "q0|q1|q2|q3|q4|q5|q6|q7|q8|q9|q10|q11|q12|q13|q14|q15|"
                + "d0|d1|d2|d3|d4|d5|d6|d7|d8|d9|d10|d11|d12|d13|d14|d15|"
                + "s0|s1|s2|s3|s4|s5|s6|s7";

        Matcher rm = Pattern.compile("\\b(" + armRegs + ")\\b").matcher(code);
        while (rm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_REG), cs + rm.start(), cs + rm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Labels
        Matcher lm = Pattern.compile("^\\s*([\\w.]+):", Pattern.MULTILINE).matcher(code);
        while (lm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_ASM_LABEL), cs + lm.start(), cs + lm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Directives
        Matcher dm = Pattern.compile("(\\.\\w+|\\b\\w+\\s*:)").matcher(code);
        while (dm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_PREPROC), cs + dm.start(), cs + dm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Comments
        Matcher cm = Pattern.compile("[@/](.+)$", Pattern.MULTILINE).matcher(code);
        while (cm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), cs + cm.start(), cs + cm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Strings
        Matcher sm = Pattern.compile("\"([^\"]*)\"").matcher(code);
        while (sm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_STRING), cs + sm.start(), cs + sm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Numbers
        Matcher nm = Pattern.compile("\\b(0x[0-9a-fA-F]+|0b[01]+|#\\d+|\\d+)\\b").matcher(code);
        while (nm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), cs + nm.start(), cs + nm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Condition suffixes (eq, ne, gt, ...)
        Matcher cfm = Pattern.compile("\\b(moveq|movne|addgt|suble|ldrhi|strlo|beq|bne|bgt|ble|blt|bge)\\b").matcher(code);
        while (cfm.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_KEYWORD), cs + cfm.start(), cs + cfm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int dp(int v) {
        return v * 3;
    }

    private static class Locale {
        static java.util.Locale US = java.util.Locale.US;
    }
}
