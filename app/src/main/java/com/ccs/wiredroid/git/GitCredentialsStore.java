package com.ccs.wiredroid.git;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.eclipse.jgit.transport.URIish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Зберігає Git-токени per-host у SharedPreferences із ключем Android Keystore.
 * Старі значення XOR-формату розшифровуються один раз і мігрують у AES-GCM.
 */
public final class GitCredentialsStore {

    private static final String PREFS = "com.ccs.wiredroid.git";
    private static final String KEY_AUTHOR_NAME  = "author_name";
    private static final String KEY_AUTHOR_EMAIL = "author_email";
    private static final String KEY_ALIAS = "wiredroid_git_credentials_v2";
    private static final String AES_PREFIX = "v2:";

    // Salt для обфускації — стабільний на пристрої.
    private final byte[] salt;
    private final SharedPreferences prefs;

    public GitCredentialsStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String installId = ctx.getPackageName() + ":wiredroid-git-creds-v1";
        this.salt = sha256(installId.getBytes(StandardCharsets.UTF_8));
    }

    public void saveAuthor(String name, String email) {
        prefs.edit()
                .putString(KEY_AUTHOR_NAME, name == null ? "" : name)
                .putString(KEY_AUTHOR_EMAIL, email == null ? "" : email)
                .apply();
    }

    public String authorName()  { return prefs.getString(KEY_AUTHOR_NAME, ""); }
    public String authorEmail() { return prefs.getString(KEY_AUTHOR_EMAIL, ""); }

    public void save(String url, String username, String token) {
        String host = hostOf(url);
        String encrypted = encrypt(token);
        if (encrypted == null) return;
        prefs.edit()
                .putString("u_" + host, username == null ? "" : username)
                .putString("t_" + host, encrypted)
                .apply();
    }

    public String username(String url) {
        return prefs.getString("u_" + hostOf(url), "");
    }

    public String token(String url) {
        String key = "t_" + hostOf(url);
        String stored = prefs.getString(key, "");
        String value = decrypt(stored);
        // Migrate the deterministic XOR format on first read.
        if (!stored.isEmpty() && !stored.startsWith(AES_PREFIX) && !value.isEmpty()) {
            String migrated = encrypt(value);
            if (migrated != null) prefs.edit().putString(key, migrated).apply();
        }
        return value;
    }

    public boolean hasCredentials(String url) {
        String h = hostOf(url);
        return prefs.contains("u_" + h) && prefs.contains("t_" + h);
    }

    public void clear(String url) {
        String h = hostOf(url);
        prefs.edit().remove("u_" + h).remove("t_" + h).apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }

    // ── Helpers ───────────────────────────────────────────────

    private static String hostOf(String url) {
        if (url == null) return "default";
        try {
            URIish u = new URIish(url.trim());
            String host = u.getHost();
            return host == null || host.isEmpty() ? "default" : host;
        } catch (Exception e) {
            return "default";
        }
    }

    private String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[cipher.getIV().length + encrypted.length];
            System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length);
            System.arraycopy(encrypted, 0, combined, cipher.getIV().length, encrypted.length);
            return AES_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decrypt(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        if (b64.startsWith(AES_PREFIX)) {
            try {
                byte[] combined = Base64.decode(b64.substring(AES_PREFIX.length()), Base64.NO_WRAP);
                if (combined.length <= 12) return "";
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(),
                        new GCMParameterSpec(128, combined, 0, 12));
                return new String(cipher.doFinal(combined, 12, combined.length - 12),
                        StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
        // Legacy v1 deterministic XOR format; token() migrates it after reading.
        try {
            byte[] data = Base64.decode(b64, Base64.NO_WRAP);
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ salt[i % salt.length]);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            return new byte[]{1, 2, 3, 4};
        }
    }
}
