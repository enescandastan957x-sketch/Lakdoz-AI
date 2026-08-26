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
    private static final String ALIAS = "lakdoz_gemini_key_v2";
    private final SharedPreferences prefs;

    public SecureSettings(Context context) {
        prefs = context.getSharedPreferences("lakdoz_secure", Context.MODE_PRIVATE);
    }

    public void setGeminiApiKey(String value) throws Exception { saveEncrypted("gemini_key", value); }
    public String getGeminiApiKey() { return loadEncrypted("gemini_key"); }
    public void clearGeminiApiKey() { prefs.edit().remove("gemini_key_ct").remove("gemini_key_iv").apply(); }

    public void setGeminiModel(String model) { prefs.edit().putString("gemini_model", model == null ? "" : model.trim()).apply(); }
    public String getGeminiModel() {
        String model = prefs.getString("gemini_model", "");
        return model == null ? "" : model.trim();
    }

    public void setUseGeminiVoice(boolean enabled) { prefs.edit().putBoolean("use_gemini_voice", enabled).apply(); }
    public boolean useGeminiVoice() { return prefs.getBoolean("use_gemini_voice", true); }
    public void setGeminiVoice(String name) { prefs.edit().putString("gemini_voice", name == null ? "Kore" : name).apply(); }
    public String getGeminiVoice() {
        String v = prefs.getString("gemini_voice", "Kore");
        return v == null || v.trim().isEmpty() ? "Kore" : v.trim();
    }
    public void setGeminiVoiceStyle(String style) { prefs.edit().putString("gemini_voice_style", style == null ? "" : style).apply(); }
    public String getGeminiVoiceStyle() { return prefs.getString("gemini_voice_style", "Doğal, sıcak ve akıcı bir Türkçe ile konuş."); }

    public void setSoundEnabled(boolean enabled) { prefs.edit().putBoolean("sound_enabled", enabled).apply(); }
    public boolean isSoundEnabled() { return prefs.getBoolean("sound_enabled", true); }

    public void setVoiceName(String name) { prefs.edit().putString("voice_name", name == null ? "" : name).apply(); }
    public String getVoiceName() { return prefs.getString("voice_name", ""); }
    public void setSpeechRate(float value) { prefs.edit().putFloat("speech_rate", value).apply(); }
    public float getSpeechRate() { return prefs.getFloat("speech_rate", 1.03f); }
    public void setSpeechPitch(float value) { prefs.edit().putFloat("speech_pitch", value).apply(); }
    public float getSpeechPitch() { return prefs.getFloat("speech_pitch", 1.0f); }

    private void saveEncrypted(String prefix, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove(prefix + "_ct").remove(prefix + "_iv").apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString(prefix + "_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(prefix + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }

    private String loadEncrypted(String prefix) {
        try {
            String ctS = prefs.getString(prefix + "_ct", "");
            String ivS = prefs.getString(prefix + "_iv", "");
            if (ctS == null || ivS == null || ctS.isEmpty() || ivS.isEmpty()) return "";
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(ivS, Base64.NO_WRAP)));
            byte[] pt = cipher.doFinal(Base64.decode(ctS, Base64.NO_WRAP));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
}
