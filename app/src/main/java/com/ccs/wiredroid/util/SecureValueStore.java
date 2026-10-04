package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Keystore-backed string store for API keys and other app secrets. */
public final class SecureValueStore {
    private static final String KEY_ALIAS = "wiredroid_secure_values_v1";
    private static final String PREFIX = "v1:";

    private SecureValueStore() {}

    public static boolean put(Context context, String prefsName, String key, String value) {
        String encoded = encrypt(value == null ? "" : value);
        if (encoded == null) return false;
        context.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putString(key, encoded).apply();
        return true;
    }

    /** Returns {@code null} when the value is absent or cannot be decrypted. */
    public static String get(Context context, String prefsName, String key) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String encoded = prefs.getString(key, null);
        if (encoded == null) return null;
        return decrypt(encoded);
    }

    private static String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[cipher.getIV().length + body.length];
            System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length);
            System.arraycopy(body, 0, combined, cipher.getIV().length, body.length);
            return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decrypt(String encoded) {
        if (!encoded.startsWith(PREFIX)) return null;
        try {
            byte[] combined = Base64.decode(encoded.substring(PREFIX.length()), Base64.NO_WRAP);
            if (combined.length <= 12) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SecretKey getKey() throws Exception {
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
}
