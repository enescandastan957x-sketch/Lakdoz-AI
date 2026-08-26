package com.lakdoz.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AiClient {
    private final SecureSettings settings;
    public AiClient(Context context) { settings = new SecureSettings(context); }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception {
        String backend = settings.getBackendUrl();
        if (!backend.isEmpty()) return askBackend(backend, prompt, history);
        String key = settings.getApiKey();
        if (key.isEmpty()) throw new IllegalStateException("AI bağlantısı ayarlı değil. AI AYARLARI bölümünden API anahtarı veya güvenli sunucu adresi ekle.");
        return askOpenAI(key, prompt, history);
    }

    private String askOpenAI(String key, String prompt, List<HistoryStore.Turn> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", settings.getModel());
        body.put("instructions",
                "Sen Lakdoz adlı Türkçe kişisel telefon yapay zekâ asistanısın. " +
                "Kullanıcıya doğrudan, doğal ve yararlı cevap ver. Varsayılan dil Türkçe. " +
                "Güncel bilgi gerekiyorsa web aramasını kullan. Kullanıcı yalnızca bilgi istiyorsa başka uygulama açtırma. " +
                "Kesin olmadığın şeyi kesinmiş gibi söyleme. Kısa sorularda kısa, ayrıntı istenirse ayrıntılı cevap ver.");
        JSONArray input = new JSONArray();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant".equals(t.role) ? "assistant" : "user");
            msg.put("content", t.text);
            input.put(msg);
        }
        JSONObject current = new JSONObject();
        current.put("role", "user");
        current.put("content", prompt);
        input.put(current);
        body.put("input", input);
        body.put("store", false);
        JSONObject reasoning = new JSONObject();
        reasoning.put("effort", "low");
        body.put("reasoning", reasoning);
        JSONObject text = new JSONObject();
        text.put("verbosity", "medium");
        body.put("text", text);
        if (settings.isWebEnabled()) {
            JSONArray tools = new JSONArray();
            JSONObject web = new JSONObject();
            web.put("type", "web_search");
            tools.put(web);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpURLConnection c = open("https://api.openai.com/v1/responses");
        c.setRequestProperty("Authorization", "Bearer " + key);
        writeJson(c, body);
        String raw = readResponse(c);
        JSONObject json = new JSONObject(raw);
        String direct = json.optString("output_text", "");
        if (!direct.isEmpty()) return direct;
        JSONArray output = json.optJSONArray("output");
        if (output != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null || !"message".equals(item.optString("type"))) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null && "output_text".equals(part.optString("type"))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(part.optString("text"));
                    }
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        throw new IllegalStateException("Model cevap döndürmedi.");
    }

    private String askBackend(String base, String prompt, List<HistoryStore.Turn> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", prompt);
        JSONArray h = new JSONArray();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            HistoryStore.Turn t = history.get(i);
            JSONObject o = new JSONObject();
            o.put("role", t.role);
            o.put("text", t.text);
            h.put(o);
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
                JSONObject error = new JSONObject(msg).optJSONObject("error");
                if (error != null) msg = error.optString("message", msg);
            } catch (Exception ignored) {}
            throw new IllegalStateException("AI servisi " + code + ": " + msg);
        }
        return sb.toString();
    }
}
