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
    private static final class ModelCandidate { final String name; final boolean stream; final int score; ModelCandidate(String n,boolean s,int sc){name=n;stream=s;score=sc;} }
    public AiClient(Context context){settings=new SecureSettings(context);}
    public String ask(String prompt,List<HistoryStore.Turn> history)throws Exception{return askStreaming(prompt,history,"",null);}
    public String askStreaming(String prompt,List<HistoryStore.Turn> history,DeltaListener listener)throws Exception{return askStreaming(prompt,history,"",listener);}
    public String askStreaming(String prompt,List<HistoryStore.Turn> history,String memory,DeltaListener listener)throws Exception{
        String key=settings.getGeminiApiKey(); if(key==null||key.trim().isEmpty())throw new IllegalStateException("Lakdoz AI bağlantısı ayarlı değil. Ayarlar bölümünden bağlantı anahtarını ekle."); key=key.trim();
        Exception last=null; String cached=cleanModelName(settings.getGeminiModel());
        if(!cached.isEmpty()){try{String a=streamModel(key,cached,prompt,history,memory,listener);settings.setGeminiModel(cached);return a;}catch(Exception e){last=e;if(isModelNotFound(e))settings.setGeminiModel("");if(!canFallback(e))throw e;}}
        List<ModelCandidate> available;
        try{available=discoverModels(key);}catch(Exception e){last=e;available=new ArrayList<>();}
        addKnownFallbacks(available);
        if(available.isEmpty()){if(last!=null&&canFallback(last))throw new IllegalStateException("Lakdoz AI modeli güncelleniyor. Lütfen tekrar dene.");throw new IllegalStateException("Lakdoz AI için uygun bağlantı bulunamadı.");}
        int tried=0; for(ModelCandidate c:available){if(c.name.equals(cached))continue;if(tried++>=3)break;try{String a=c.stream?streamModel(key,c.name,prompt,history,memory,listener):generateModel(key,c.name,prompt,history,memory,listener);settings.setGeminiModel(c.name);return a;}catch(Exception e){last=e;if(!canFallback(e))throw e;}}
        throw new IllegalStateException("Lakdoz AI şu anda yanıt veremiyor. Lütfen tekrar dene.");
    }
    private void addKnownFallbacks(List<ModelCandidate> models){String[] names={"gemini-3.6-flash","gemini-3.5-flash","gemini-3.1-flash-lite","gemini-3-flash-preview"};for(int i=names.length-1;i>=0;i--){String name=names[i];boolean found=false;for(ModelCandidate c:models)if(c.name.equals(name)){found=true;break;}if(!found)models.add(0,new ModelCandidate(name,false,10000-i));}}
    private boolean isModelNotFound(Exception e){String m=e.getMessage()==null?"":e.getMessage().toLowerCase();return m.contains("http 404")||m.contains("model bulunamadı")||m.contains("not found");}
    public String testConnection()throws Exception{return askStreaming("Türkçe olarak yalnızca 'Bağlantı başarılı' yaz.",Collections.emptyList(),"",null);}
    private List<ModelCandidate> discoverModels(String key)throws Exception{
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key="+URLEncoder.encode(key,"UTF-8");HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(7000);c.setReadTimeout(12000);int code=c.getResponseCode();if(code<200||code>=300)throw httpError(c,code,"models.list");
        JSONObject root=new JSONObject(readAll(c.getInputStream()));JSONArray models=root.optJSONArray("models");ArrayList<ModelCandidate> out=new ArrayList<>();if(models==null)return out;
        for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m==null)continue;String name=cleanModelName(m.optString("name",""));if(name.isEmpty()||!name.toLowerCase().contains("gemini"))continue;String lower=name.toLowerCase();if(lower.contains("tts")||lower.contains("image")||lower.contains("embedding")||lower.contains("imagen")||lower.contains("veo")||lower.contains("live")||lower.contains("audio")||lower.contains("computer-use"))continue;JSONArray methods=m.optJSONArray("supportedGenerationMethods");boolean generate=false,stream=false;if(methods!=null)for(int j=0;j<methods.length();j++){String method=methods.optString(j,"");if("generateContent".equals(method))generate=true;if("streamGenerateContent".equals(method))stream=true;}if(!generate&&!stream)continue;int score=100;if(lower.contains("flash-lite"))score+=1000;else if(lower.contains("flash"))score+=850;else if(lower.contains("pro"))score+=350;if(lower.contains("latest"))score+=90;if(stream)score+=70;if(lower.contains("preview"))score-=40;out.add(new ModelCandidate(name,stream,score));}
        Collections.sort(out,new Comparator<ModelCandidate>(){public int compare(ModelCandidate a,ModelCandidate b){int s=Integer.compare(b.score,a.score);return s!=0?s:b.name.compareTo(a.name);}});return out;
    }
    private String streamModel(String key,String model,String prompt,List<HistoryStore.Turn> history,String memory,DeltaListener listener)throws Exception{
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+model+":streamGenerateContent?alt=sse&key="+URLEncoder.encode(key,"UTF-8");
        boolean grounded=shouldUseWeb(prompt);
        HttpURLConnection c=openPost(endpoint,buildBody(prompt,history,memory,grounded));int code=c.getResponseCode();
        if(code==400&&grounded){c.disconnect();c=openPost(endpoint,buildBody(prompt,history,memory,false));code=c.getResponseCode();}
        if(code<200||code>=300)throw httpError(c,code,model);BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));StringBuilder full=new StringBuilder();String line;while((line=br.readLine())!=null){if(!line.startsWith("data:"))continue;String raw=line.substring(5).trim();if(raw.isEmpty()||"[DONE]".equals(raw))continue;String d=extractText(new JSONObject(raw));if(!d.isEmpty()){full.append(d);if(listener!=null)listener.onDelta(d);}}br.close();if(full.length()==0)throw new IllegalStateException("Lakdoz boş cevap döndürdü.");return full.toString().trim();
    }
    private String generateModel(String key,String model,String prompt,List<HistoryStore.Turn> history,String memory,DeltaListener listener)throws Exception{
        String endpoint="https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent?key="+URLEncoder.encode(key,"UTF-8");
        boolean grounded=shouldUseWeb(prompt);
        HttpURLConnection c=openPost(endpoint,buildBody(prompt,history,memory,grounded));int code=c.getResponseCode();
        if(code==400&&grounded){c.disconnect();c=openPost(endpoint,buildBody(prompt,history,memory,false));code=c.getResponseCode();}
        if(code<200||code>=300)throw httpError(c,code,model);String text=extractText(new JSONObject(readAll(c.getInputStream()))).trim();if(text.isEmpty())throw new IllegalStateException("Lakdoz boş cevap döndürdü.");if(listener!=null)listener.onDelta(text);return text;
    }
    private HttpURLConnection openPost(String endpoint,JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(7000);c.setReadTimeout(22000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);c.getOutputStream().close();return c;}
    private JSONObject buildBody(String prompt,List<HistoryStore.Turn> history,String memory,boolean grounded)throws Exception{
        JSONObject body=new JSONObject();StringBuilder sys=new StringBuilder("Sen Lakdoz AI adlı dikkatli Türkçe kişisel asistansın. Kullanıcı sık yazım hatası yapabilir; kelimeleri birebir okumak yerine cümle ve konuşma bağlamından niyeti çıkar. Eksik harfleri, yanlış tuşları, fonetik yazımları ve konuşma dili kısaltmalarını sessizce düzelt. Takip sorularında önceki kişi, yer, ürün, konu ve zamanı koru; yeni konuya geçildiyse eski konuyu zorla sürdürme. Kullanıcı açıkça değiştirmediyse bağlamı değiştirme. Emin olmadığın yeni bir kişi, şehir, ürün veya olay uydurma. Önce gerçek niyeti belirle, sonra cevap ver. Mesaj iki farklı şekilde anlaşılabiliyorsa kısa bir netleştirme sorusu sor; düşük güvenle rastgele tahmin yapma. Bilmediğin veya güncel olmayan şeyi uydurma. Canlı bir araçtan doğrulanmış veri geldiyse onu önceliklendir. Sağlık, hukuk, para ve güvenlik konularında kesin hüküm yerine sınırlarını ve riskleri açıkça belirt. Kullanıcının seçtiği cevap biçimine uy.");
        String mode=settings.getResponseMode();
        if("Kısa".equalsIgnoreCase(mode))sys.append(" Kısa modu: en fazla 3 cümleyle yanıtla.");
        else if("Detaylı".equalsIgnoreCase(mode))sys.append(" Detaylı modu: gerekli arka planı, adımları ve uyarıları anlaşılır biçimde ekle.");
        else if("Madde madde".equalsIgnoreCase(mode))sys.append(" Madde modu: cevabı okunabilir maddeler halinde düzenle.");
        if(memory!=null&&!memory.trim().isEmpty())sys.append("\n\nAşağıdaki bölüm kullanıcının ÖNCEKİ SOHBETLERİNDEN cihazdaki ilgili hafızadır. Gerektiğinde doğal biçimde kullan; kullanıcı sormadıysa gereksiz yere tekrar etme. Çelişki varsa en yeni kullanıcı mesajını önceliklendir.\n--- HAFIZA ---\n").append(memory.trim()).append("\n--- HAFIZA SONU ---");
        body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",sys.toString()))));JSONArray contents=new JSONArray();int historySize=history==null?0:history.size();int start=Math.max(0,historySize-16);if(history!=null)for(int i=start;i<history.size();i++){HistoryStore.Turn t=history.get(i);if(t==null||t.text==null||t.text.trim().isEmpty())continue;contents.put(new JSONObject().put("role","assistant".equals(t.role)?"model":"user").put("parts",new JSONArray().put(new JSONObject().put("text",t.text))));}contents.put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",prompt))));body.put("contents",contents);body.put("generationConfig",new JSONObject().put("maxOutputTokens",800).put("temperature",0.30));if(grounded)body.put("tools",new JSONArray().put(new JSONObject().put("google_search",new JSONObject())));return body;
    }
    private boolean shouldUseWeb(String prompt){
        String p=prompt==null?"":prompt.toLowerCase(new java.util.Locale("tr","TR"));
        return p.contains("internetten")||p.contains("internete")||p.contains("güncel")||p.contains("bugün")||p.contains("şu an")||
                p.contains("son haber")||p.contains("haberler")||p.contains("fiyat")||p.contains("kim kazandı")||p.contains("son durum")||
                p.contains("araştır")||p.contains("webde")||p.contains("internette");
    }
    private String extractText(JSONObject json){StringBuilder out=new StringBuilder();JSONArray candidates=json.optJSONArray("candidates");if(candidates==null||candidates.length()==0)return"";JSONObject first=candidates.optJSONObject(0);if(first==null)return"";JSONObject content=first.optJSONObject("content");if(content==null)return"";JSONArray parts=content.optJSONArray("parts");if(parts==null)return"";for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null)out.append(p.optString("text",""));}return out.toString();}
    private boolean canFallback(Exception e){if(e instanceof SocketTimeoutException)return true;String m=e.getMessage()==null?"":e.getMessage().toLowerCase();return m.contains("http 404")||m.contains("http 408")||m.contains("http 429")||m.contains("http 500")||m.contains("http 502")||m.contains("http 503")||m.contains("http 504")||m.contains("timed out")||m.contains("timeout");}
    private String cleanModelName(String model){if(model==null)return"";String m=model.trim();if(m.startsWith("models/"))m=m.substring(7);return m;}
    private Exception httpError(HttpURLConnection c,int code,String model)throws Exception{String msg=readAll(c.getErrorStream());try{JSONObject error=new JSONObject(msg).optJSONObject("error");if(error!=null)msg=error.optString("message",msg);}catch(Exception ignored){}if(code==401||code==403)return new IllegalStateException("AI bağlantı anahtarı kabul edilmedi. (HTTP "+code+")");if(code==404)return new IllegalStateException("Model bulunamadı: "+model+" (HTTP 404)");if(code==429)return new IllegalStateException("AI kullanım sınırına ulaşıldı. (HTTP 429)");if(code==503)return new IllegalStateException("Lakdoz AI geçici olarak yoğun. (HTTP 503)");return new IllegalStateException("Lakdoz AI servisi HTTP "+code+": "+msg);}
    private String readAll(InputStream is)throws Exception{if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();}
}

