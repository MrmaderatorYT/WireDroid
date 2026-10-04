package com.ccs.wiredroid.analysis;

import android.content.Context;

import com.ccs.wiredroid.R;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findings that get someone breached rather than merely annoyed.
 *
 * <p>Everything here reports {@link ProblemItem.Severity#SECURITY}, which the
 * problems list renders with a shield. The bar for adding a rule is higher than
 * in the style checks: a security warning that cries wolf is worse than no
 * warning at all, because the next real one gets dismissed with the rest. So
 * each rule below tries hard to exclude the shapes that merely <em>look</em>
 * dangerous — a constant name, a placeholder, an already-parameterised query.</p>
 */
final class SecurityRules {

    // ── Hardcoded credentials ───────────────────────────────────────────────

    /**
     * An assignment of a string literal to something named like a credential.
     * Group 1 is the name, group 2 the literal.
     */
    private static final Pattern P_SECRET_ASSIGN = Pattern.compile(
            "(?i)\\b(\\w*(?:password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?key"
                    + "|auth[_-]?token|access[_-]?token|refresh[_-]?token|private[_-]?key"
                    + "|client[_-]?secret|credential)\\w*)\\s*=\\s*\"([^\"]*)\"");

    /** Vendor key formats that are unambiguous wherever they appear. */
    private static final Pattern[] P_KNOWN_KEY_FORMATS = {
            Pattern.compile("\"AKIA[0-9A-Z]{16}\""),                    // AWS access key id
            Pattern.compile("\"gh[pousr]_[A-Za-z0-9]{36,}\""),          // GitHub token
            Pattern.compile("\"sk-[A-Za-z0-9]{32,}\""),                 // OpenAI-style
            Pattern.compile("\"AIza[0-9A-Za-z_-]{35}\""),               // Google API key
            Pattern.compile("\"xox[baprs]-[0-9A-Za-z-]{10,}\""),        // Slack
            Pattern.compile("\"eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\."), // JWT
            // A PEM header in source means the key itself is in the repository.
            // Matched outside quotes too: these usually arrive as a multi-line
            // string or a here-doc-style concatenation rather than one literal.
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    };

    /** A credentials-bearing URL: scheme://user:password@host */
    private static final Pattern P_URL_CREDENTIALS = Pattern.compile(
            "\"[a-zA-Z][a-zA-Z0-9+.-]*://[^\"/\\s:@]+:[^\"/\\s:@]+@");

    /**
     * Literals that are obviously not a real secret. Without this the rule fires
     * on every example, test fixture and empty initialiser in the project, which
     * is exactly how a security check gets switched off.
     */
    // Every literal brace is escaped, closing ones included. Desktop java.util.regex
    // tolerates a bare "}", but Android's regex is ICU, which rejects it outright
    // with a PatternSyntaxException — and since these are static finals, that
    // surfaces as ExceptionInInitializerError the first time a file is analysed.
    private static final Pattern P_PLACEHOLDER = Pattern.compile(
            "(?i)^\\s*$|^(?:x{3,}|\\*{3,}|\\.{3,}|-+)$"
                    + "|^(?:your|my|the)?[_-]?(?:password|secret|api[_-]?key|token|key|value)$"
                    + "|^(?:changeme|placeholder|example|sample|dummy|test|todo|fixme|none|null"
                    + "|unset|undefined|default|redacted|hidden|password|secret|token)$"
                    + "|^\\$\\{.*\\}$|^%[sd]$|^\\{\\{.*\\}\\}$|^<.*>$|^@string/|^R\\.string\\.");

    // ── SQL injection ───────────────────────────────────────────────────────

    /** A SQL keyword inside a string literal — the anchor for the injection check. */
    private static final Pattern P_SQL_LITERAL = Pattern.compile(
            "(?i)\"[^\"]*\\b(?:select\\s|insert\\s+into|update\\s|delete\\s+from|where\\s"
                    + "|order\\s+by|drop\\s+table|union\\s+select)[^\"]*\"");

    /** String concatenation joining a literal to an expression. */
    private static final Pattern P_CONCAT = Pattern.compile("\"\\s*\\+|\\+\\s*\"");

    /** String.format / printf-style building of a statement. */
    private static final Pattern P_SQL_FORMAT = Pattern.compile(
            "(?i)(?:String\\.format|MessageFormat\\.format)\\s*\\(\\s*\"[^\"]*"
                    + "\\b(?:select|insert|update|delete|where)\\b");

    /** Methods that execute a raw statement, as opposed to a prepared one. */
    private static final Pattern P_RAW_EXEC = Pattern.compile(
            "\\.(?:execSQL|rawQuery|createStatement|executeQuery|executeUpdate|execute)\\s*\\(");

    // ── Weak cryptography and transport ─────────────────────────────────────

    private static final Pattern P_WEAK_HASH = Pattern.compile(
            "(?i)MessageDigest\\.getInstance\\s*\\(\\s*\"(MD5|MD2|SHA-?1)\"");
    private static final Pattern P_WEAK_CIPHER = Pattern.compile(
            "(?i)Cipher\\.getInstance\\s*\\(\\s*\"([^\"]*(?:DES|RC2|RC4|Blowfish|/ECB/)[^\"]*)\"");
    private static final Pattern P_INSECURE_RANDOM_SEC = Pattern.compile(
            "(?i)new\\s+Random\\s*\\(|Math\\.random\\s*\\(\\s*\\)");
    private static final Pattern P_TRUST_ALL = Pattern.compile(
            "(?i)(?:checkServerTrusted|checkClientTrusted)\\s*\\([^)]*\\)\\s*\\{\\s*\\}"
                    + "|ALLOW_ALL_HOSTNAME_VERIFIER|setHostnameVerifier\\s*\\(\\s*\\(.*\\)\\s*->\\s*true");
    private static final Pattern P_HTTP_URL = Pattern.compile(
            "\"http://(?!localhost|127\\.0\\.0\\.1|10\\.|192\\.168\\.|0\\.0\\.0\\.0)[^\"]+\"");

    // ── Android-specific ────────────────────────────────────────────────────

    private static final Pattern P_WORLD_MODE = Pattern.compile(
            "MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE");
    private static final Pattern P_WEBVIEW_JS = Pattern.compile(
            "setJavaScriptEnabled\\s*\\(\\s*true\\s*\\)|addJavascriptInterface\\s*\\(");
    private static final Pattern P_EXTERNAL_STORAGE_SECRET = Pattern.compile(
            "(?i)getExternalStorage\\w*\\s*\\(");

    private SecurityRules() {}

    static void analyze(Context ctx, String[] lines, File f, List<ProblemItem> out) {
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();

            // Comment tracking: a commented-out password is not a live finding,
            // and half of what looks like SQL in a codebase lives in javadoc.
            if (inBlockComment) {
                if (line.contains("*/")) inBlockComment = false;
                continue;
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) inBlockComment = true;
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) continue;

            int lineNo = i + 1;
            checkSecrets(ctx, line, f, lineNo, out);
            checkSqlInjection(ctx, line, f, lineNo, out);
            checkCrypto(ctx, line, f, lineNo, out);
            checkAndroid(ctx, line, f, lineNo, out);
        }
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    private static void checkSecrets(Context ctx, String line, File f, int lineNo,
                                     List<ProblemItem> out) {
        // Vendor formats are checked first and suppress the generic rule for the
        // same line: `apiKey = "AKIA…"` matches both, and reporting one line
        // twice makes the list look padded. The vendor message is the more
        // useful of the two anyway — it says revoke the key, not just move it.
        boolean vendorKey = false;
        for (Pattern p : P_KNOWN_KEY_FORMATS) {
            if (p.matcher(line).find()) {
                out.add(item(ctx, R.string.sec_vendor_key, f, lineNo));
                vendorKey = true;
                break;
            }
        }

        if (!vendorKey) {
            Matcher m = P_SECRET_ASSIGN.matcher(line);
            while (m.find()) {
                String value = m.group(2);
                if (isPlaceholder(value)) continue;
                // A very short value is far more likely to be a key name, a
                // column name or a preference key than a real credential.
                if (value.length() < 6) continue;
                out.add(item(ctx, R.string.sec_hardcoded_credential, f, lineNo, m.group(1)));
            }
        }

        if (P_URL_CREDENTIALS.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_url_credentials, f, lineNo));
        }
    }

    private static void checkSqlInjection(Context ctx, String line, File f, int lineNo,
                                          List<ProblemItem> out) {
        boolean hasSqlLiteral = P_SQL_LITERAL.matcher(line).find();

        // The point of the rule: SQL text alone is fine — a fully parameterised
        // PreparedStatement is a SQL literal too. What is dangerous is SQL text
        // being glued to an expression.
        if (hasSqlLiteral && P_CONCAT.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_sql_concat, f, lineNo));
        } else if (P_SQL_FORMAT.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_sql_format, f, lineNo));
        } else if (hasSqlLiteral && P_RAW_EXEC.matcher(line).find()
                && line.contains("+")) {
            out.add(item(ctx, R.string.sec_sql_raw_exec, f, lineNo));
        }
    }

    private static void checkCrypto(Context ctx, String line, File f, int lineNo,
                                    List<ProblemItem> out) {
        Matcher hash = P_WEAK_HASH.matcher(line);
        if (hash.find()) {
            out.add(item(ctx, R.string.sec_weak_hash, f, lineNo, hash.group(1)));
        }
        Matcher cipher = P_WEAK_CIPHER.matcher(line);
        if (cipher.find()) {
            out.add(item(ctx, R.string.sec_weak_cipher, f, lineNo, cipher.group(1)));
        }
        if (P_TRUST_ALL.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_trust_all_certs, f, lineNo));
        }
        if (P_HTTP_URL.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_cleartext_http, f, lineNo));
        }
        // Only flagged where the surrounding line says it is security-relevant;
        // Random() in a game loop is nobody's problem.
        if (P_INSECURE_RANDOM_SEC.matcher(line).find() && looksSecurityRelevant(line)) {
            out.add(item(ctx, R.string.sec_insecure_random, f, lineNo));
        }
    }

    private static void checkAndroid(Context ctx, String line, File f, int lineNo,
                                     List<ProblemItem> out) {
        if (P_WORLD_MODE.matcher(line).find()) {
            out.add(item(ctx, R.string.sec_world_accessible, f, lineNo));
        }
        if (line.contains("addJavascriptInterface(")) {
            out.add(item(ctx, R.string.sec_js_interface, f, lineNo));
        } else if (P_WEBVIEW_JS.matcher(line).find() && line.contains("loadUrl")) {
            out.add(item(ctx, R.string.sec_webview_js, f, lineNo));
        }
        if (P_EXTERNAL_STORAGE_SECRET.matcher(line).find() && looksSecurityRelevant(line)) {
            out.add(item(ctx, R.string.sec_secret_on_external, f, lineNo));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isPlaceholder(String value) {
        return P_PLACEHOLDER.matcher(value.trim()).find();
    }

    /** True when the line mentions something worth protecting. */
    private static boolean looksSecurityRelevant(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("token") || l.contains("password") || l.contains("secret")
                || l.contains("key") || l.contains("nonce") || l.contains("salt")
                || l.contains("session") || l.contains("otp") || l.contains("credential");
    }

    private static ProblemItem item(Context ctx, int res, File f, int line, Object... args) {
        String message;
        if (ctx != null) {
            message = args.length == 0 ? ctx.getString(res) : ctx.getString(res, args);
        } else {
            // Same fallback contract as the other rule classes: no context means
            // no resources, and an empty message beats a crash.
            message = "";
        }
        return new ProblemItem(ProblemItem.Severity.SECURITY, message, f, line);
    }
}
