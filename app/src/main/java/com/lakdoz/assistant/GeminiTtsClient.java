package com.lakdoz.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
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

    public GeminiTtsClient(Context context) {
        settings = new SecureSettings(context);
    }

    public void speak(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return;
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) throw new IllegalStateException("Gemini API anahtarı yok.");

        AudioData audio = synthesize(key.trim(), settings.getGeminiVoice(), settings.getGeminiVoiceStyle(), text.trim());
        play(audio);
    }

    private AudioData synthesize(String key, String voice, String style, String text) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/interactions";
        JSONObject body = new JSONObject();
        body.put("model", "gemini-2.5-flash-preview-tts");
        String prompt = (style == null || style.trim().isEmpty() ? "Doğal ve akıcı bir Türkçe ile konuş." : style.trim())
                + "\n\nMetni aynen seslendir:\n" + text;
        body.put("input", prompt);
        body.put("response_format", new JSONObject().put("type", "audio"));
        JSONObject generation = new JSONObject();
        generation.put("speech_config", new JSONArray().put(new JSONObject().put("voice", voice)));
        body.put("generation_config", generation);

        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
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
            try {
                JSONObject error = new JSONObject(raw).optJSONObject("error");
                if (error != null) message = error.optString("message", raw);
            } catch (Exception ignored) {}
            throw new IllegalStateException("Gemini ses servisi HTTP " + code + ": " + message);
        }

        JSONObject root = new JSONObject(raw);
        JSONObject audio = root.optJSONObject("output_audio");
        if (audio == null) audio = findAudio(root);
        if (audio == null) throw new IllegalStateException("Gemini ses verisi döndürmedi.");

        String data = audio.optString("data", "");
        if (data.isEmpty()) throw new IllegalStateException("Gemini ses verisi boş.");
        int sampleRate = audio.optInt("sample_rate", 24000);
        int channels = audio.optInt("channels", 1);
        String mime = audio.optString("mime_type", "audio/l16");
        return new AudioData(Base64.decode(data, Base64.DEFAULT), sampleRate, channels, mime);
    }

    private JSONObject findAudio(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if ("audio".equals(o.optString("type")) && !o.optString("data", "").isEmpty()) return o;
            if (!o.optString("data", "").isEmpty() && o.has("sample_rate")) return o;
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Object child = o.opt(names.optString(i));
                    JSONObject found = findAudio(child);
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findAudio(a.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void play(AudioData audio) {
        byte[] pcm = audio.bytes;
        int offset = 0;
        if (audio.mime.contains("wav") || isWav(pcm)) offset = findWavDataOffset(pcm);
        int channelMask = audio.channels > 1 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, pcm.length - offset);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(pcm, offset, pcm.length - offset);
        track.setNotificationMarkerPosition(Math.max(1, (pcm.length - offset) / 2));
        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            public void onMarkerReached(AudioTrack t) { t.stop(); t.release(); }
            public void onPeriodicNotification(AudioTrack t) {}
        });
        track.play();
    }

    private boolean isWav(byte[] b) {
        return b != null && b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
    }

    private int findWavDataOffset(byte[] b) {
        if (b == null) return 0;
        for (int i = 12; i + 8 < b.length; i++) {
            if (b[i] == 'd' && b[i+1] == 'a' && b[i+2] == 't' && b[i+3] == 'a') return i + 8;
        }
        return Math.min(44, b.length);
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static final class AudioData {
        final byte[] bytes;
        final int sampleRate;
        final int channels;
        final String mime;
        AudioData(byte[] bytes, int sampleRate, int channels, String mime) {
            this.bytes = bytes; this.sampleRate = sampleRate; this.channels = channels; this.mime = mime == null ? "" : mime;
        }
    }
}
