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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class GeminiTtsClient {
    private final SecureSettings settings;
    private static final String[] MODELS = {"gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts"};

    public GeminiTtsClient(Context context) { settings = new SecureSettings(context); }
    public void speak(String text) throws Exception { speak(text, settings.getGeminiVoice(), settings.getGeminiVoiceStyle()); }
    public void speak(String text, String voice, String style) throws Exception {
        if (text == null || text.trim().isEmpty()) return;
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) throw new IllegalStateException("Gemini API anahtarı yok.");
        Exception last = null;
        for (String model : MODELS) {
            try { play(synthesize(key.trim(), model, voice, style, text.trim())); return; }
            catch (Exception e) { last = e; String m=e.getMessage()==null?"":e.getMessage(); if(!m.contains("HTTP 404")&&!m.contains("HTTP 429")&&!m.contains("HTTP 503")) throw e; }
        }
        throw last == null ? new IllegalStateException("Gemini ses modeli bulunamadı.") : last;
    }

    private AudioData synthesize(String key, String model, String voice, String style, String text) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + URLEncoder.encode(key,"UTF-8");
        JSONObject body = new JSONObject();
        String prompt = (style == null || style.trim().isEmpty() ? "Doğal, sıcak ve akıcı bir Türkçe ile konuş." : style.trim()) + "\nMetni aynen ve doğal Türkçe ile seslendir:\n" + text;
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
        JSONObject speech = new JSONObject();
        speech.put("languageCode", "tr-TR");
        speech.put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice == null || voice.trim().isEmpty() ? "Kore" : voice.trim())));
        JSONObject generation = new JSONObject();
        generation.put("responseModalities", new JSONArray().put("AUDIO"));
        generation.put("speechConfig", speech);
        body.put("generationConfig", generation);

        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(45000); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        byte[] request=body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(request.length); c.getOutputStream().write(request); c.getOutputStream().close();
        int code=c.getResponseCode(); String raw=readAll(code>=200&&code<300?c.getInputStream():c.getErrorStream());
        if(code<200||code>=300){String message=raw;try{JSONObject error=new JSONObject(raw).optJSONObject("error");if(error!=null)message=error.optString("message",raw);}catch(Exception ignored){}throw new IllegalStateException("Gemini ses servisi HTTP "+code+": "+message);}
        JSONObject root=new JSONObject(raw); JSONArray candidates=root.optJSONArray("candidates");
        if(candidates==null||candidates.length()==0)throw new IllegalStateException("Gemini ses adayı döndürmedi.");
        JSONObject first=candidates.optJSONObject(0); if(first==null)throw new IllegalStateException("Gemini ses adayı boş.");
        JSONObject content=first.optJSONObject("content"); if(content==null)throw new IllegalStateException("Gemini ses içeriği yok.");
        JSONArray parts=content.optJSONArray("parts"); if(parts==null)throw new IllegalStateException("Gemini ses parçası yok.");
        JSONObject inline=null; for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null&&p.optJSONObject("inlineData")!=null){inline=p.optJSONObject("inlineData");break;}}
        if(inline==null)throw new IllegalStateException("Gemini ses verisi döndürmedi.");
        String data=inline.optString("data",""); if(data.isEmpty())throw new IllegalStateException("Gemini ses verisi boş.");
        String mime=inline.optString("mimeType","audio/L16;codec=pcm;rate=24000"); int sampleRate=parseRate(mime); return new AudioData(Base64.decode(data,Base64.DEFAULT),sampleRate,1,mime);
    }

    private int parseRate(String mime){try{int i=mime.indexOf("rate=");if(i>=0){String s=mime.substring(i+5).replaceAll("[^0-9].*$","");if(!s.isEmpty())return Integer.parseInt(s);}}catch(Exception ignored){}return 24000;}
    private void play(AudioData audio){byte[] pcm=audio.bytes;int offset=0;if(audio.mime.toLowerCase().contains("wav")||isWav(pcm))offset=findWavDataOffset(pcm);int channelMask=audio.channels>1?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO;int min=AudioTrack.getMinBufferSize(audio.sampleRate,channelMask,AudioFormat.ENCODING_PCM_16BIT);int payload=Math.max(0,pcm.length-offset);int size=Math.max(min,payload);AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(channelMask).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STATIC).build();track.write(pcm,offset,payload);int frames=Math.max(1,payload/(2*Math.max(1,audio.channels)));track.setNotificationMarkerPosition(frames);track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener(){public void onMarkerReached(AudioTrack t){try{t.stop();}catch(Exception ignored){}t.release();}public void onPeriodicNotification(AudioTrack t){}});track.play();}
    private boolean isWav(byte[] b){return b!=null&&b.length>12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F';}
    private int findWavDataOffset(byte[] b){if(b==null)return 0;for(int i=12;i+8<b.length;i++)if(b[i]=='d'&&b[i+1]=='a'&&b[i+2]=='t'&&b[i+3]=='a')return i+8;return Math.min(44,b.length);}
    private String readAll(InputStream is)throws Exception{if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();}
    private static final class AudioData{final byte[] bytes;final int sampleRate;final int channels;final String mime;AudioData(byte[] b,int r,int c,String m){bytes=b;sampleRate=r;channels=c;mime=m==null?"":m;}}
}
