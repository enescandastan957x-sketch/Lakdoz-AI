package com.lakdoz.assistant;

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

public class SecureSettings {
    private static final String ALIAS = "lakdoz_ai_key_v1";
    private final SharedPreferences prefs;

    public SecureSettings(Context context) {
        prefs = context.getSharedPreferences("lakdoz_secure", Context.MODE_PRIVATE);
    }

    public void setApiKey(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove("api_key_ct").remove("api_key_iv").apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString("api_key_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString("api_key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public String getApiKey() {
        try {
            String ctS = prefs.getString("api_key_ct", "");
            String ivS = prefs.getString("api_key_iv", "");
            if (ctS.isEmpty() || ivS.isEmpty()) return "";
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(ivS, Base64.NO_WRAP)));
            byte[] pt = cipher.doFinal(Base64.decode(ctS, Base64.NO_WRAP));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public void setModel(String model) { prefs.edit().putString("model", model).apply(); }
    public String getModel() { return prefs.getString("model", "gpt-5.6-luna"); }
    public void setWebEnabled(boolean enabled) { prefs.edit().putBoolean("web", enabled).apply(); }
    public boolean isWebEnabled() { return prefs.getBoolean("web", true); }
    public void setBackendUrl(String url) { prefs.edit().putString("backend", url == null ? "" : url.trim()).apply(); }
    public String getBackendUrl() { return prefs.getString("backend", ""); }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
