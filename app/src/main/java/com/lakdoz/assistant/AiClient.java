package com.lakdoz.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AiClient {
    private static final String MODEL = "gemini-2.5-flash";
    private final SecureSettings settings;

    public AiClient(Context context) {
        settings = new SecureSettings(context);
    }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API anahtarı ayarlı değil. AI AYARLARI bölümünden anahtarını ekle.");
        }
        return askGemini(key.trim(), prompt, history);
    }

    public String testConnection() throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("Önce Gemini API anahtarını kaydet.");
        }
        return askGemini(key.trim(), "Türkçe olarak sadece 'Bağlantı başarılı' yaz.", java.util.Collections.emptyList());
    }

    private String askGemini(String key, String prompt, List<HistoryStore.Turn> history) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL +
                ":generateContent?key=" + URLEncoder.encode(key, "UTF-8");

        JSONObject body = new JSONObject();

        JSONObject systemInstruction = new JSONObject();
        JSONArray systemParts = new JSONArray();
        systemParts.put(new JSONObject().put("text",
                "Sen Lakdoz adlı Türkçe kişisel yapay zekâ asistansın. Kullanıcıyla doğal ve samimi konuş. " +
                "Varsayılan dil Türkçe. Bilgi sorularında doğrudan cevap ver. Gereksiz yere web sayfası veya uygulama açtırma. " +
                "Kesin olmadığın bilgiyi kesinmiş gibi söyleme. Kısa sorularda kısa, ayrıntı istenirse ayrıntılı cevap ver. " +
                "Telefon komutları uygulamanın yerel Android araçları tarafından ayrıca işlenir."));
        systemInstruction.put("parts", systemParts);
        body.put("systemInstruction", systemInstruction);

        JSONArray contents = new JSONArray();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            JSONObject c = new JSONObject();
            c.put("role", "assistant".equals(t.role) ? "model" : "user");
            c.put("parts", new JSONArray().put(new JSONObject().put("text", t.text)));
            contents.put(c);
        }

        JSONObject current = new JSONObject();
        current.put("role", "user");
        current.put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
        contents.put(current);
        body.put("contents", contents);

        JSONObject generation = new JSONObject();
        generation.put("temperature", 0.65);
        generation.put("maxOutputTokens", 1200);
        body.put("generationConfig", generation);

        HttpURLConnection c = open(endpoint);
        writeJson(c, body);
        JSONObject json = new JSONObject(readResponse(c));

        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            JSONObject first = candidates.optJSONObject(0);
            if (first != null) {
                JSONObject content = first.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject part = parts.optJSONObject(i);
                            if (part == null) continue;
                            String text = part.optString("text", "");
                            if (!text.isEmpty()) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(text);
                            }
                        }
                        if (sb.length() > 0) return sb.toString();
                    }
                }
                String finish = first.optString("finishReason", "");
                if (!finish.isEmpty()) throw new IllegalStateException("Gemini yanıtı tamamlayamadı: " + finish);
            }
        }

        JSONObject feedback = json.optJSONObject("promptFeedback");
        if (feedback != null) throw new IllegalStateException("Gemini isteği engellendi: " + feedback.toString());
        throw new IllegalStateException("Gemini boş cevap döndürdü.");
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return c;
    }

    private void writeJson(HttpURLConnection c, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        c.getOutputStream().write(bytes);
        c.getOutputStream().close();
    }

    private String readResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (is == null) throw new IllegalStateException("Gemini cevap vermedi: HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        if (code < 200 || code >= 300) {
            String msg = sb.toString();
            try {
                JSONObject root = new JSONObject(msg);
                JSONObject error = root.optJSONObject("error");
                if (error != null) msg = error.optString("message", msg);
            } catch (Exception ignored) {}

            if (code == 400 || code == 403) {
                throw new IllegalStateException("Gemini anahtarı geçersiz veya bu API için yetkili değil. Yeni bir Google AI Studio anahtarı oluştur. (HTTP " + code + ")");
            }
            if (code == 404) {
                throw new IllegalStateException("Gemini modeli bulunamadı. Lakdoz gemini-2.5-flash kullanıyor. (HTTP 404)");
            }
            if (code == 429) {
                throw new IllegalStateException("Gemini ücretsiz kullanım kotasına ulaşıldı. Bir süre sonra tekrar dene. (HTTP 429)");
            }
            throw new IllegalStateException("Gemini servisi HTTP " + code + ": " + msg);
        }
        return sb.toString();
    }
}
