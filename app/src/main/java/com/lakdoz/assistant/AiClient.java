package com.lakdoz.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AiClient {
    public interface DeltaListener { void onDelta(String text); }

    private final SecureSettings settings;

    public AiClient(Context context) {
        settings = new SecureSettings(context);
    }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception {
        return askStreaming(prompt, history, null);
    }

    public String askStreaming(String prompt, List<HistoryStore.Turn> history, DeltaListener listener) throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty())
            throw new IllegalStateException("Gemini bağlantısı ayarlı değil. Ayarlar bölümünden anahtarını ekle.");

        String[] models = {"gemini-2.5-flash-lite", "gemini-2.5-flash"};
        Exception last = null;
        for (String model : models) {
            try {
                String answer = streamModel(key.trim(), model, prompt, history, listener);
                settings.setGeminiModel(model);
                return answer;
            } catch (Exception e) {
                last = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!(e instanceof SocketTimeoutException) &&
                        !m.contains("HTTP 408") && !m.contains("HTTP 429") &&
                        !m.contains("HTTP 500") && !m.contains("HTTP 502") &&
                        !m.contains("HTTP 503") && !m.contains("HTTP 504") &&
                        !m.contains("HTTP 404") && !m.toLowerCase().contains("timed out")) {
                    throw e;
                }
            }
        }
        throw last == null ? new IllegalStateException("Gemini şu anda yanıt veremiyor.") : last;
    }

    public String testConnection() throws Exception {
        return askStreaming("Türkçe olarak yalnızca 'Bağlantı başarılı' yaz.", java.util.Collections.emptyList(), null);
    }

    private String streamModel(String key, String model, String prompt, List<HistoryStore.Turn> history, DeltaListener listener) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model +
                ":streamGenerateContent?alt=sse&key=" + URLEncoder.encode(key, "UTF-8");

        JSONObject body = buildBody(prompt, history);
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(22000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        c.getOutputStream().write(bytes);
        c.getOutputStream().close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw httpError(c, code, model);

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder full = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String raw = line.substring(5).trim();
            if (raw.isEmpty() || "[DONE]".equals(raw)) continue;
            String delta = extractText(new JSONObject(raw));
            if (!delta.isEmpty()) {
                full.append(delta);
                if (listener != null) listener.onDelta(delta);
            }
        }
        br.close();
        if (full.length() == 0) throw new IllegalStateException("Gemini boş cevap döndürdü.");
        return full.toString().trim();
    }

    private JSONObject buildBody(String prompt, List<HistoryStore.Turn> history) throws Exception {
        JSONObject body = new JSONObject();
        JSONObject system = new JSONObject();
        system.put("parts", new JSONArray().put(new JSONObject().put("text",
                "Sen Lakdoz adlı hızlı Türkçe kişisel yapay zekâ asistansın. Önce doğrudan cevabı ver. " +
                "Kısa sorulara kısa cevap ver; kullanıcı ayrıntı isterse ayrıntılandır. Doğal ve samimi konuş. " +
                "Bilmediğin şeyi uydurma. Telefon komutları Android araçları tarafından ayrıca işlenir.")));
        body.put("systemInstruction", system);

        JSONArray contents = new JSONArray();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            JSONObject c = new JSONObject();
            c.put("role", "assistant".equals(t.role) ? "model" : "user");
            c.put("parts", new JSONArray().put(new JSONObject().put("text", t.text)));
            contents.put(c);
        }
        contents.put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
        body.put("contents", contents);

        JSONObject generation = new JSONObject();
        generation.put("maxOutputTokens", 700);
        generation.put("temperature", 0.55);
        body.put("generationConfig", generation);
        return body;
    }

    private String extractText(JSONObject json) {
        StringBuilder out = new StringBuilder();
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null) out.append(p.optString("text", ""));
        }
        return out.toString();
    }

    private Exception httpError(HttpURLConnection c, int code, String model) throws Exception {
        InputStream is = c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        String msg = sb.toString();
        try {
            JSONObject error = new JSONObject(msg).optJSONObject("error");
            if (error != null) msg = error.optString("message", msg);
        } catch (Exception ignored) {}
        if (code == 401 || code == 403) return new IllegalStateException("Gemini anahtarı kabul edilmedi. (HTTP " + code + ")");
        if (code == 404) return new IllegalStateException("Model bulunamadı: " + model + " (HTTP 404)");
        if (code == 429) return new IllegalStateException("Gemini kullanım sınırına ulaşıldı. (HTTP 429)");
        if (code == 503) return new IllegalStateException("Gemini geçici olarak yoğun. (HTTP 503)");
        return new IllegalStateException("Gemini servisi HTTP " + code + ": " + msg);
    }
}
