package com.fnvideo.app;

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

/** Auth stays on this device; Android Keystore key is never exported. */
public final class SessionStore {
    private static final String ALIAS = "fnvideo.session.v1";
    private static final String ACCOUNT_KEY = "account_id";

    private SessionStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    public static String base(Context context) {
        return prefs(context).getString("base", "http://nas.example.test:5666");
    }

    /**
     * Returns the stable login identity used for local watch-state isolation.
     * This is deliberately the username, never the encrypted session token.
     * An empty result means an old session has no known account and the UI
     * must request a fresh login before opening account-scoped watch state.
     */
    public static String accountId(Context context) {
        try {
            String value = prefs(context).getString(ACCOUNT_KEY, "");
            return value == null ? "" : value.trim();
        } catch (ClassCastException invalidValue) {
            return "";
        }
    }

    /** Remembers a username without persisting a password or authentication token. */
    public static void rememberAccountId(Context context, String accountId) {
        String value = accountId == null ? "" : accountId.trim();
        SharedPreferences.Editor editor = prefs(context).edit();
        if (value.isEmpty()) editor.remove(ACCOUNT_KEY);
        else editor.putString(ACCOUNT_KEY, value);
        editor.apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    /**
     * Compatibility overload for non-login callers. It clears any stale
     * identity; login callers must use the four-argument overload so a new
     * token and username are committed together.
     */
    public static void save(Context context, String base, String token) {
        save(context, base, token, "");
    }

    /** Saves a token and its stable username identity in one preference update. */
    public static void save(Context context, String base, String token, String accountId) {
        String origin = ServerAddress.normalize(base);
        if (token == null) throw new IllegalArgumentException("Missing session token");
        String identity = accountId == null ? "" : accountId.trim();
        if (token.isEmpty()) {
            SharedPreferences.Editor editor = prefs(context).edit()
                    .putString("base", origin).remove("token").remove("iv")
                    .remove(ACCOUNT_KEY);
            editor.apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String encrypted = Base64.encodeToString(
                    cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            SharedPreferences.Editor editor = prefs(context).edit()
                    .putString("base", origin).putString("token", encrypted)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            if (identity.isEmpty()) editor.remove(ACCOUNT_KEY);
            else editor.putString(ACCOUNT_KEY, identity);
            editor.apply();
        } catch (Exception error) {
            throw new IllegalStateException("无法安全保存登录，请重试", error);
        }
    }

    /** A settings change must never pair a different origin with the old credentials. */
    public static String changeServer(Context context, String requestedBase) {
        String origin = ServerAddress.normalize(requestedBase);
        String retained = ServerAddress.retainedToken(base(context), origin, token(context));
        save(context, origin, retained, ServerAddress.sameOrigin(base(context), origin)
                ? accountId(context) : "");
        return retained;
    }

    public static String token(Context context) {
        try {
            String encrypted = prefs(context).getString("token", "");
            if (encrypted.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(prefs(context).getString("iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            clear(context);
            return "";
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove("token").remove("iv").remove(ACCOUNT_KEY).apply();
    }

    /** Recover from an invalid legacy address instead of crashing on every launch. */
    public static void reset(Context context) {
        prefs(context).edit().remove("base").remove("token").remove("iv")
                .remove(ACCOUNT_KEY).apply();
    }
}
