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
    private final SecureSettings settings;
    public AiClient(Context context) { settings = new SecureSettings(context); }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception {
        String backend = settings.getBackendUrl();
        if (backend != null && !backend.isEmpty()) return askBackend(backend, prompt, history);
        String key = settings.getGeminiApiKey();
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Gemini bağlantısı ayarlı değil. AI AYARLARI bölümünden Gemini API anahtarını ekle.");
        }
        return askGemini(key, prompt, history);
    }

    public String testConnection() throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.isEmpty()) throw new IllegalStateException("Önce Gemini API anahtarını kaydet.");
        return askGemini(key, "Sadece 'Bağlantı başarılı' yaz.", java.util.Collections.emptyList());
    }

    private String askGemini(String key, String prompt, List<HistoryStore.Turn> history) throws Exception {
        String model = settings.getGeminiModel();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + "/generateContent?key=" + URLEncoder.encode(key, "UTF-8");

        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();

        JSONObject system = new JSONObject();
        system.put("role", "user");
        JSONArray systemParts = new JSONArray();
        systemParts.put(new JSONObject().put("text",
                "Sen Lakdoz adlı Türkçe kişisel yapay zekâ asistansın. Doğal, yararlı ve kısa cevap ver. " +
                "Varsayılan dil Türkçe. Kullanıcı bilgi soruyorsa cevabı doğrudan ver; uygulama açtırma. " +
                "Kesin olmadığın şeyleri kesinmiş gibi söyleme. Telefon komutları uygulamanın yerel araçlarıyla çözülür."));
        system.put("parts", systemParts);
        contents.put(system);

        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            JSONObject c = new JSONObject();
            c.put("role", "assistant".equals(t.role) ? "model" : "user");
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", t.text));
            c.put("parts", parts);
            contents.put(c);
        }

        JSONObject current = new JSONObject();
        current.put("role", "user");
        current.put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
        contents.put(current);
        body.put("contents", contents);

        JSONObject generation = new JSONObject();
        generation.put("temperature", 0.6);
        generation.put("maxOutputTokens", 1024);
        body.put("generationConfig", generation);

        HttpURLConnection c = open(endpoint);
        writeJson(c, body);
        JSONObject json = new JSONObject(readResponse(c));
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
            if (content != null) {
                JSONArray parts = content.optJSONArray("parts");
                if (parts != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part != null) {
                            String text = part.optString("text", "");
                            if (!text.isEmpty()) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(text);
                            }
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
        }
        JSONObject feedback = json.optJSONObject("promptFeedback");
        if (feedback != null) {
            throw new IllegalStateException("Gemini isteği engellendi: " + feedback.toString());
        }
        throw new IllegalStateException("Gemini cevap döndürmedi.");
    }

    private String askBackend(String base, String prompt, List<HistoryStore.Turn> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", prompt);
        JSONArray h = new JSONArray();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            h.put(new JSONObject().put("role", t.role).put("text", t.text));
        }
        body.put("history", h);
        HttpURLConnection c = open(base.replaceAll("/+$", "") + "/chat");
        writeJson(c, body);
        JSONObject json = new JSONObject(readResponse(c));
        String answer = json.optString("answer", "");
        if (answer.isEmpty()) throw new IllegalStateException("Sunucu cevap döndürmedi.");
        return answer;
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
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
        if (is == null) throw new IllegalStateException("AI servisi cevap vermedi: " + code);
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
            throw new IllegalStateException("Gemini servisi " + code + ": " + msg);
        }
        return sb.toString();
    }
}
