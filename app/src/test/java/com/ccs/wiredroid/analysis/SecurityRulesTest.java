package com.ccs.wiredroid.analysis;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule set that looks for credentials committed into source.
 *
 * <p>Two ways for it to be useless, and both need guarding. Missing a real key
 * is the obvious one. Firing on ordinary code is the quieter one: a security
 * level that cries wolf gets ignored, and then it may as well not exist — which
 * matters more here than elsewhere, because the panel routinely holds tens of
 * thousands of findings and this is the one that must stand out.</p>
 */
public class SecurityRulesTest {

    private static List<ProblemItem> scan(String source) {
        List<ProblemItem> out = new ArrayList<>();
        SecurityRules.analyze(null, source.split("\n", -1), new File("T.java"), out);
        return out;
    }

    private static boolean flagged(String source) {
        for (ProblemItem p : scan(source)) {
            if (p.severity == ProblemItem.Severity.SECURITY) return true;
        }
        return false;
    }

    @Test
    public void findsAnAwsKey() {
        assertTrue(flagged("String k = \"AKIAIOSFODNN7EXAMPLE\";"));
    }

    @Test
    public void findsAGoogleApiKey() {
        assertTrue(flagged("private static final String K = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\";"));
    }

    @Test
    public void findsAPrivateKeyBlock() {
        assertTrue(flagged("String pem = \"-----BEGIN RSA PRIVATE KEY-----\";"));
    }

    @Test
    public void findsAHardcodedPassword() {
        assertTrue(flagged("conn.setPassword(\"hunter2hunter2\");")
                || flagged("String password = \"hunter2hunter2\";"));
    }

    @Test
    public void ordinaryCodeIsNotFlagged() {
        // Everything here is the kind of line a real file is full of. A hit on any
        // of them is a false positive, and false positives are how this rule set
        // gets switched off.
        String[] innocent = {
                "int total = items.size() * 2;",
                "String name = user.getName();",
                "if (path.endsWith(\".java\")) return true;",
                "Log.d(TAG, \"loaded \" + count + \" files\");",
                "private static final String TAG = \"MainActivity\";",
                "String url = \"https://github.com/MrmaderatorYT\";",
                "sb.append(\"public class \").append(className).append(\" {\");",
                "// password handling is delegated to the keystore",
        };
        for (String line : innocent) {
            assertTrue("false positive on: " + line, !flagged(line));
        }
    }

    @Test
    public void emptyAndBlankInputIsSafe() {
        scan("");
        scan("\n\n   \n");
    }

    @Test
    public void aVeryLongLineDoesNotHang() {
        StringBuilder sb = new StringBuilder("String s = \"");
        for (int i = 0; i < 20000; i++) sb.append('x');
        sb.append("\";");
        scan(sb.toString());
    }
}
