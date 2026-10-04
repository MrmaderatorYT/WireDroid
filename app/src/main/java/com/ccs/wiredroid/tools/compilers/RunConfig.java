package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.ccs.wiredroid.util.AppPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the user typed into "Program Arguments &amp; Environment", turned into
 * something a {@code main(String[])} can actually receive.
 *
 * <p>Both halves are parsed the way a shell would be expected to parse them,
 * because that is what the hint text in the dialog promises.</p>
 */
public final class RunConfig {

    private static final String[] NO_ARGS = new String[0];

    public final String[] args;
    public final Map<String, String> env;

    private RunConfig(String[] args, Map<String, String> env) {
        this.args = args;
        this.env = env;
    }

    public static RunConfig from(Context context) {
        try {
            AppPreferences prefs = new AppPreferences(context);
            return new RunConfig(parseArgs(prefs.getProgramArgs()), parseEnv(prefs.getEnvVars()));
        } catch (Throwable t) {
            // A malformed configuration must never be the reason a program refuses
            // to start; running with none is the better failure.
            Log.w("WireDroidRun", "Falling back to an empty run configuration", t);
            return new RunConfig(NO_ARGS, new LinkedHashMap<>());
        }
    }

    /** For callers that only need the arguments, with no preferences involved. */
    public static RunConfig none() {
        return new RunConfig(NO_ARGS, new LinkedHashMap<>());
    }

    // ── Arguments ───────────────────────────────────────────────────────────

    /**
     * Splits on whitespace, keeping quoted runs together, so that
     * {@code arg1 "hello world"} arrives as two arguments rather than three.
     *
     * <p>Inside double quotes a backslash escapes the next character; inside
     * single quotes it does not, matching POSIX shells. An unterminated quote
     * closes at the end of the line instead of being reported — the user is
     * typing into a one-line field, not writing a script.</p>
     */
    public static String[] parseArgs(String raw) {
        if (raw == null) return NO_ARGS;
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean started = false;
        char quote = 0;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"' && i + 1 < raw.length()) {
                    current.append(raw.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                started = true;                       // "" is a real, empty argument
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    out.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else if (c == '\\' && i + 1 < raw.length()) {
                current.append(raw.charAt(++i));
                started = true;
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started) out.add(current.toString());
        return out.toArray(new String[0]);
    }

    // ── Environment ─────────────────────────────────────────────────────────

    /**
     * Reads {@code KEY=VALUE} pairs separated by newlines or commas.
     *
     * <p>A comma only separates when what follows looks like the start of the
     * next assignment, so a value may itself contain commas — {@code PATHS=a,b}
     * is one variable, not a variable and a broken fragment.</p>
     */
    public static Map<String, String> parseEnv(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\\R")) {
            for (String entry : line.split(",(?=\\s*[A-Za-z_][A-Za-z0-9_]*\\s*=)")) {
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String key = entry.substring(0, eq).trim();
                String value = entry.substring(eq + 1).trim();
                if (value.length() >= 2 && value.charAt(0) == value.charAt(value.length() - 1)
                        && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Publishes the variables to the process so {@code System.getenv} sees them,
     * and hands back the undo.
     *
     * <p>The program runs inside the IDE's own process, so there is one
     * environment shared with the editor — the values are therefore removed
     * again when the run ends rather than left behind to affect the next one.
     * Anything that cannot be set is skipped: a rejected variable is worth a log
     * line, not a failed run.</p>
     */
    public Runnable applyEnv() {
        if (env.isEmpty()) return () -> { };
        Map<String, String> previous = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            try {
                String old = System.getenv(e.getKey());
                Os.setenv(e.getKey(), e.getValue(), true);
                if (old != null) previous.put(e.getKey(), old);
                else added.add(e.getKey());
            } catch (Throwable t) {
                Log.w("WireDroidRun", "Could not set " + e.getKey(), t);
            }
        }
        return () -> {
            for (Map.Entry<String, String> e : previous.entrySet()) {
                try {
                    Os.setenv(e.getKey(), e.getValue(), true);
                } catch (Throwable ignored) {
                }
            }
            for (String key : added) {
                try {
                    Os.unsetenv(key);
                } catch (Throwable ignored) {
                }
            }
        };
    }

    /** One-line summary for the console banner, or empty when nothing is configured. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (args.length > 0) {
            sb.append("args: ");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(args[i].contains(" ") ? '"' + args[i] + '"' : args[i]);
            }
        }
        if (!env.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("env: ").append(String.join(", ", env.keySet()));
        }
        return sb.toString();
    }
}
