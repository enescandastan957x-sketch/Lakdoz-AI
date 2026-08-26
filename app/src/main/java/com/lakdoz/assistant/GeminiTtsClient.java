package com.lakdoz.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeminiTtsClient {
    private final SecureSettings settings;
    public GeminiTtsClient(Context context) { settings = new SecureSettings(context); }
    public void speak(String text) throws Exception { speak(text, settings.getGeminiVoice(), settings.getGeminiVoiceStyle()); }

    public void speak(String text, String voice, String style) throws Exception {
        if (text == null || text.trim().isEmpty()) return;
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) throw new IllegalStateException("Gemini API anahtarı yok.");
        Exception last = null;
        String[] models = {"gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts"};
        for (String model : models) {
            try {
                AudioData audio = synthesize(key.trim(), model, voice, style, text.trim());
                playBlocking(audio);
                return;
            } catch (Exception e) { last = e; }
        }
        throw last == null ? new IllegalStateException("Gemini ses servisi yanıt vermedi.") : last;
    }

    private AudioData synthesize(String key, String model, String voice, String style, String text) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        JSONObject body = new JSONObject();
        String prompt = (style == null || style.trim().isEmpty() ? "Doğal, sıcak ve akıcı Türkçe konuş." : style.trim()) +
                "\nMetni aynen oku; ek açıklama yapma:\n" + text;
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
        JSONObject voiceConfig = new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice == null ? "Kore" : voice));
        JSONObject speechConfig = new JSONObject().put("voiceConfig", voiceConfig).put("languageCode", "tr-TR");
        body.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig));

        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(25000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", key);
        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(request.length);
        c.getOutputStream().write(request);
        c.getOutputStream().close();

        int code = c.getResponseCode();
        String raw = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) {
            String message = raw;
            try { JSONObject error = new JSONObject(raw).optJSONObject("error"); if (error != null) message = error.optString("message", raw); } catch (Exception ignored) {}
            throw new IllegalStateException("Gemini ses servisi " + model + " HTTP " + code + ": " + message);
        }

        JSONObject root = new JSONObject(raw);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) throw new IllegalStateException("Gemini ses adayı döndürmedi.");
        JSONObject first = candidates.optJSONObject(0);
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) throw new IllegalStateException("Gemini ses içeriği boş.");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            JSONObject inline = part.optJSONObject("inlineData");
            if (inline == null) continue;
            String data = inline.optString("data", "");
            if (data.isEmpty()) continue;
            String mime = inline.optString("mimeType", "audio/L16;rate=24000");
            return new AudioData(Base64.decode(data, Base64.DEFAULT), 24000, 1, mime);
        }
        throw new IllegalStateException("Gemini ses verisi döndürmedi.");
    }

    private void playBlocking(AudioData audio) throws Exception {
        byte[] pcm = audio.bytes;
        int offset = 0;
        if (audio.mime.toLowerCase().contains("wav") || isWav(pcm)) offset = findWavDataOffset(pcm);
        int channelMask = audio.channels > 1 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int payload = Math.max(0, pcm.length - offset);
        int size = Math.max(min, payload);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STATIC).build();
        track.write(pcm, offset, payload);
        track.play();
        int bytesPerFrame = 2 * Math.max(1, audio.channels);
        long frames = Math.max(1, payload / bytesPerFrame);
        long durationMs = Math.max(80, (frames * 1000L) / Math.max(1, audio.sampleRate));
        try { Thread.sleep(durationMs + 70); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { track.stop(); } catch (Exception ignored) {}
        track.release();
    }

    private boolean isWav(byte[] b){return b!=null&&b.length>12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F';}
    private int findWavDataOffset(byte[] b){if(b==null)return 0;for(int i=12;i+8<b.length;i++)if(b[i]=='d'&&b[i+1]=='a'&&b[i+2]=='t'&&b[i+3]=='a')return i+8;return Math.min(44,b.length);}
    private String readAll(InputStream is)throws Exception{if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();}
    private static final class AudioData{final byte[] bytes;final int sampleRate;final int channels;final String mime;AudioData(byte[] bytes,int sampleRate,int channels,String mime){this.bytes=bytes;this.sampleRate=sampleRate;this.channels=channels;this.mime=mime==null?"":mime;}}
}
