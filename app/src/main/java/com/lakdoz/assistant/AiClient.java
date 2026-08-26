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
import java.util.ArrayList;
import java.util.List;

public class AiClient {
    private final SecureSettings settings;

    public AiClient(Context context) {
        settings = new SecureSettings(context);
    }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("Gemini bağlantısı ayarlı değil. AI AYARLARI bölümünden anahtarını ekle.");
        }
        return askWithFallback(key.trim(), prompt, history);
    }

    public String testConnection() throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("Önce Gemini bağlantısını kaydet.");
        }
        return askWithFallback(key.trim(), "Türkçe olarak yalnızca 'Bağlantı başarılı' yaz.", java.util.Collections.emptyList());
    }

    private String askWithFallback(String key, String prompt, List<HistoryStore.Turn> history) throws Exception {
        ArrayList<String> models = new ArrayList<>();
        addModel(models, settings.getGeminiModel());
        addModel(models, "gemini-flash-latest");
        addModel(models, "gemini-3.7-flash");
        addModel(models, "gemini-3.6-flash");

        Exception last = null;
        for (String model : models) {
            try {
                String answer = askGemini(key, model, prompt, history);
                settings.setGeminiModel(model);
                return answer;
            } catch (IllegalStateException e) {
                last = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!m.contains("HTTP 404")) throw e;
            }
        }
        throw last == null ? new IllegalStateException("Uygun Gemini modeli bulunamadı.") : last;
    }

    private void addModel(ArrayList<String> list, String model) {
        if (model == null) return;
        String m = model.trim();
        if (!m.isEmpty() && !list.contains(m)) list.add(m);
    }

    private String askGemini(String key, String model, String prompt, List<HistoryStore.Turn> history) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model +
                ":generateContent?key=" + URLEncoder.encode(key, "UTF-8");

        JSONObject body = new JSONObject();
        JSONObject systemInstruction = new JSONObject();
        JSONArray systemParts = new JSONArray();
        systemParts.put(new JSONObject().put("text",
                "Sen Lakdoz adlı Türkçe kişisel yapay zekâ asistansın. Kullanıcıyla doğal, samimi ve yararlı konuş. " +
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
        generation.put("maxOutputTokens", 1400);
        body.put("generationConfig", generation);

        HttpURLConnection c = open(endpoint);
        writeJson(c, body);
        JSONObject json = new JSONObject(readResponse(c, model));

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
            }
        }
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

    private String readResponse(HttpURLConnection c, String model) throws Exception {
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
            if (code == 400 || code == 401 || code == 403)
                throw new IllegalStateException("Gemini bağlantısı reddedildi. Anahtarı kontrol et. (HTTP " + code + ")");
            if (code == 404)
                throw new IllegalStateException("Gemini modeli bulunamadı: " + model + " (HTTP 404)");
            if (code == 429)
                throw new IllegalStateException("Gemini kullanım kotasına ulaşıldı. Bir süre sonra tekrar dene. (HTTP 429)");
            throw new IllegalStateException("Gemini servisi HTTP " + code + ": " + msg);
        }
        return sb.toString();
    }
}
