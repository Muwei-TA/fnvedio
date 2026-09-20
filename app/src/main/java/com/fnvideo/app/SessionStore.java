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
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("session", Context.MODE_PRIVATE); }
    public static String base(Context c) { return prefs(c).getString("base", "http://nas.example.test:5666"); }
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS, null);
    }
    public static void save(Context c, String base, String token) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            String enc = Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            prefs(c).edit().putString("base", base).putString("token", enc)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
        } catch (Exception e) { throw new IllegalStateException("无法安全保存登录，请重试", e); }
    }
    public static String token(Context c) {
        try {
            String token = prefs(c).getString("token", ""); if (token.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs(c).getString("iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { clear(c); return ""; }
    }
    public static void clear(Context c) { prefs(c).edit().remove("token").remove("iv").apply(); }
}
