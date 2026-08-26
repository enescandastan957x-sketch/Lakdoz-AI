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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AiClient {
    public interface DeltaListener { void onDelta(String text); }

    private final SecureSettings settings;
    private final HistoryStore memoryStore;

    private static final class ModelCandidate {
        final String name; final boolean stream; final int score;
        ModelCandidate(String name, boolean stream, int score) { this.name=name; this.stream=stream; this.score=score; }
    }

    public AiClient(Context context) {
        settings = new SecureSettings(context);
        memoryStore = new HistoryStore(context);
    }

    public String ask(String prompt, List<HistoryStore.Turn> history) throws Exception { return askStreaming(prompt, history, null); }

    public String askStreaming(String prompt, List<HistoryStore.Turn> history, DeltaListener listener) throws Exception {
        String key = settings.getGeminiApiKey();
        if (key == null || key.trim().isEmpty()) throw new IllegalStateException("Gemini bağlantısı ayarlı değil. Ayarlar bölümünden anahtarını ekle.");
        key = key.trim();
        Exception last = null;
        String cached = cleanModelName(settings.getGeminiModel());
        if (!cached.isEmpty()) {
            try { String answer=streamModel(key,cached,prompt,history,listener); settings.setGeminiModel(cached); return answer; }
            catch(Exception e){last=e;if(!canFallback(e))throw e;}
        }
        List<ModelCandidate> available = discoverModels(key);
        if (available.isEmpty()) { if(last!=null)throw last; throw new IllegalStateException("Bu API anahtarı için uygun Gemini metin modeli bulunamadı."); }
        int tried=0;
        for(ModelCandidate candidate:available){
            if(candidate.name.equals(cached))continue; if(tried>=3)break; tried++;
            try{String answer=candidate.stream?streamModel(key,candidate.name,prompt,history,listener):generateModel(key,candidate.name,prompt,history,listener);settings.setGeminiModel(candidate.name);return answer;}
            catch(Exception e){last=e;if(!canFallback(e))throw e;}
        }
        throw last==null?new IllegalStateException("Gemini şu anda yanıt veremiyor."):last;
    }

    public String testConnection() throws Exception { return askStreaming("Türkçe olarak yalnızca 'Bağlantı başarılı' yaz.", java.util.Collections.emptyList(), null); }

    private List<ModelCandidate> discoverModels(String key) throws Exception {
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key="+URLEncoder.encode(key,"UTF-8");
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(7000);c.setReadTimeout(12000);
        int code=c.getResponseCode();if(code<200||code>=300)throw httpError(c,code,"models.list");
        JSONObject root=new JSONObject(readAll(c.getInputStream()));JSONArray models=root.optJSONArray("models");ArrayList<ModelCandidate> out=new ArrayList<>();if(models==null)return out;
        for(int i=0;i<models.length();i++){
            JSONObject m=models.optJSONObject(i);if(m==null)continue;String name=cleanModelName(m.optString("name",""));if(name.isEmpty()||!name.toLowerCase().contains("gemini"))continue;
            String lower=name.toLowerCase();if(lower.contains("tts")||lower.contains("image")||lower.contains("embedding")||lower.contains("imagen")||lower.contains("veo")||lower.contains("robot")||lower.contains("live")||lower.contains("audio")||lower.contains("computer-use"))continue;
            JSONArray methods=m.optJSONArray("supportedGenerationMethods");boolean generate=false,stream=false;if(methods!=null)for(int j=0;j<methods.length();j++){String method=methods.optString(j,"");if("generateContent".equals(method))generate=true;if("streamGenerateContent".equals(method))stream=true;}
            if(!generate&&!stream)continue;int score=100;if(lower.contains("flash-lite"))score+=1000;else if(lower.contains("flash"))score+=850;else if(lower.contains("pro"))score+=350;if(lower.contains("latest"))score+=90;if(stream)score+=70;if(lower.contains("preview"))score-=40;out.add(new ModelCandidate(name,stream,score));
        }
        Collections.sort(out,new Comparator<ModelCandidate>(){public int compare(ModelCandidate a,ModelCandidate b){int by=Integer.compare(b.score,a.score);return by!=0?by:b.name.compareTo(a.name);}});return out;
    }

    private String streamModel(String key,String model,String prompt,List<HistoryStore.Turn> history,DeltaListener listener)throws Exception{
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+model+":streamGenerateContent?alt=sse&key="+URLEncoder.encode(key,"UTF-8");JSONObject body=buildBody(prompt,history);HttpURLConnection c=openPost(endpoint,body);int code=c.getResponseCode();if(code<200||code>=300)throw httpError(c,code,model);
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));StringBuilder full=new StringBuilder();String line;while((line=br.readLine())!=null){if(!line.startsWith("data:"))continue;String raw=line.substring(5).trim();if(raw.isEmpty()||"[DONE]".equals(raw))continue;String delta=extractText(new JSONObject(raw));if(!delta.isEmpty()){full.append(delta);if(listener!=null)listener.onDelta(delta);}}br.close();if(full.length()==0)throw new IllegalStateException("Gemini boş cevap döndürdü.");return full.toString().trim();
    }

    private String generateModel(String key,String model,String prompt,List<HistoryStore.Turn> history,DeltaListener listener)throws Exception{
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent?key="+URLEncoder.encode(key,"UTF-8");JSONObject body=buildBody(prompt,history);HttpURLConnection c=openPost(endpoint,body);int code=c.getResponseCode();if(code<200||code>=300)throw httpError(c,code,model);String text=extractText(new JSONObject(readAll(c.getInputStream()))).trim();if(text.isEmpty())throw new IllegalStateException("Gemini boş cevap döndürdü.");if(listener!=null)listener.onDelta(text);return text;
    }

    private HttpURLConnection openPost(String endpoint,JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(7000);c.setReadTimeout(22000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);c.getOutputStream().close();return c;}

    private JSONObject buildBody(String prompt,List<HistoryStore.Turn> history)throws Exception{
        JSONObject body=new JSONObject();String memory=memoryStore.buildRelevantMemory(prompt,1800);
        String systemText="Sen Lakdoz adlı hızlı Türkçe kişisel yapay zekâ asistansın. Önce doğrudan cevabı ver. Kısa sorulara kısa cevap ver; kullanıcı ayrıntı isterse ayrıntılandır. Doğal ve samimi konuş. Bilmediğin şeyi uydurma. Telefon komutları Android araçları tarafından ayrıca işlenir.";
        if(!memory.isEmpty()) systemText += "\n\nUZUN SÜRELİ SOHBET HAFIZASI: Aşağıdaki kayıtlar kullanıcının önceki sohbetlerinden otomatik seçildi. Yalnızca soruyla gerçekten ilgiliyse kullan; gereksiz yere bahsetme.\n"+memory;
        JSONObject system=new JSONObject();system.put("parts",new JSONArray().put(new JSONObject().put("text",systemText)));body.put("systemInstruction",system);
        JSONArray contents=new JSONArray();int start=Math.max(0,history.size()-6);for(int i=start;i<history.size();i++){HistoryStore.Turn t=history.get(i);JSONObject item=new JSONObject();item.put("role","assistant".equals(t.role)?"model":"user");item.put("parts",new JSONArray().put(new JSONObject().put("text",t.text)));contents.put(item);}contents.put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",prompt))));body.put("contents",contents);
        JSONObject generation=new JSONObject();generation.put("maxOutputTokens",700);generation.put("temperature",0.55);body.put("generationConfig",generation);return body;
    }

    private String extractText(JSONObject json){StringBuilder out=new StringBuilder();JSONArray candidates=json.optJSONArray("candidates");if(candidates==null||candidates.length()==0)return"";JSONObject first=candidates.optJSONObject(0);if(first==null)return"";JSONObject content=first.optJSONObject("content");if(content==null)return"";JSONArray parts=content.optJSONArray("parts");if(parts==null)return"";for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null)out.append(p.optString("text",""));}return out.toString();}
    private boolean canFallback(Exception e){if(e instanceof SocketTimeoutException)return true;String m=e.getMessage()==null?"":e.getMessage().toLowerCase();return m.contains("http 404")||m.contains("http 408")||m.contains("http 429")||m.contains("http 500")||m.contains("http 502")||m.contains("http 503")||m.contains("http 504")||m.contains("timed out")||m.contains("timeout");}
    private String cleanModelName(String model){if(model==null)return"";String m=model.trim();if(m.startsWith("models/"))m=m.substring("models/".length());return m;}
    private Exception httpError(HttpURLConnection c,int code,String model)throws Exception{String msg=readAll(c.getErrorStream());try{JSONObject error=new JSONObject(msg).optJSONObject("error");if(error!=null)msg=error.optString("message",msg);}catch(Exception ignored){}if(code==401||code==403)return new IllegalStateException("Gemini anahtarı kabul edilmedi. (HTTP "+code+")");if(code==404)return new IllegalStateException("Model bulunamadı: "+model+" (HTTP 404)");if(code==429)return new IllegalStateException("Gemini kullanım sınırına ulaşıldı. (HTTP 429)");if(code==503)return new IllegalStateException("Gemini geçici olarak yoğun. (HTTP 503)");return new IllegalStateException("Gemini servisi HTTP "+code+": "+msg);}
    private String readAll(InputStream is)throws Exception{if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();}
}
