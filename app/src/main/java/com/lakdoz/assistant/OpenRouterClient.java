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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class OpenRouterClient {
    private static final long MODEL_CACHE_MS=6L*60L*60L*1000L;
    private static volatile long modelCacheAt=0L;
    private static volatile String modelCacheKey="";
    private static volatile ArrayList<String> modelCache=new ArrayList<>();
    private final SecureSettings settings;
    public OpenRouterClient(Context context){ settings=new SecureSettings(context); }

    public boolean isConfigured(){ String k=settings.getOpenRouterApiKey(); return k!=null&&!k.trim().isEmpty(); }

    public String ask(String prompt,List<HistoryStore.Turn> history,String memory)throws Exception {
        List<String> answers=askFreeCandidates(prompt,history,memory);
        if (answers.isEmpty()) throw new IllegalStateException("Ücretsiz AI modellerinden cevap alınamadı.");
        return answers.get(0);
    }

    /** Ücretsiz modelleri mümkün olduğunca dinamik keşfeder ve birkaçını paralel sorgular. */
    public List<String> askFreeCandidates(String prompt,List<HistoryStore.Turn> history,String memory)throws Exception {
        return askFreeCandidates(prompt,history,memory,3);
    }

    public List<String> askFreeCandidates(String prompt,List<HistoryStore.Turn> history,String memory,int maxCandidates)throws Exception {
        String key=settings.getOpenRouterApiKey();
        if(key==null||key.trim().isEmpty())throw new IllegalStateException("OpenRouter bağlantısı ayarlı değil.");
        int limit=Math.max(1,Math.min(3,maxCandidates));
        List<String> models=discoverFreeModels(key);
        if(models.size()>limit)models=new ArrayList<>(models.subList(0,limit));
        if(models.isEmpty())models.add("openrouter/free");
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(limit,models.size()));
        ArrayList<Future<String>> jobs=new ArrayList<>();
        for(String model:models){
            jobs.add(pool.submit(new Callable<String>(){public String call()throws Exception{
                return askWithModel(key,model,prompt,history,memory);
            }}));
        }
        ArrayList<String> answers=new ArrayList<>();
        for(Future<String> job:jobs){
            try{
                String answer=job.get(18,TimeUnit.SECONDS);
                if(answer!=null&&!answer.trim().isEmpty())answers.add(answer.trim());
            }catch(Exception ignored){}
        }
        pool.shutdownNow();
        if(answers.isEmpty()&&!models.contains("openrouter/free")){
            try{answers.add(askWithModel(key,"openrouter/free",prompt,history,memory));}catch(Exception ignored){}
        }
        return answers;
    }

    private List<String> discoverFreeModels(String key)throws Exception {
        String marker=key.length()+":"+(key.length()>4?key.substring(0,4):key);
        long now=System.currentTimeMillis();
        if(marker.equals(modelCacheKey)&&now-modelCacheAt<MODEL_CACHE_MS&&!modelCache.isEmpty())return new ArrayList<>(modelCache);
        HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/models").openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(7000);c.setReadTimeout(12000);
        c.setRequestProperty("Authorization","Bearer "+key.trim());
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IllegalStateException("OpenRouter modelleri HTTP "+code);
        JSONObject root=new JSONObject(readAll(c.getInputStream()));
        JSONArray data=root.optJSONArray("data");
        ArrayList<String> out=new ArrayList<>();
        if(data==null)return out;
        for(int i=0;i<data.length()&&out.size()<3;i++){
            JSONObject model=data.optJSONObject(i);if(model==null)continue;
            String id=model.optString("id","");
            JSONObject pricing=model.optJSONObject("pricing");
            String promptPrice=pricing==null?"":pricing.optString("prompt","");
            String completionPrice=pricing==null?"":pricing.optString("completion","");
            String lower=id.toLowerCase(java.util.Locale.ROOT);
            if(id.isEmpty()||id.equals("openrouter/free"))continue;
            if(lower.contains("embedding")||lower.contains("embed")||lower.contains("whisper")||lower.contains("tts")||lower.contains("audio")||lower.contains("image"))continue;
            if(!isZeroPrice(promptPrice)||!isZeroPrice(completionPrice))continue;
            if(!out.contains(id))out.add(id);
        }
        if(!out.isEmpty()){modelCacheKey=marker;modelCacheAt=now;modelCache=new ArrayList<>(out);}
        return out;
    }

    private boolean isZeroPrice(String price){
        if(price==null)return false;
        try{return Double.parseDouble(price.trim())==0.0d;}catch(Exception ignored){return false;}
    }

    private String askWithModel(String key,String model,String prompt,List<HistoryStore.Turn> history,String memory)throws Exception {
        JSONObject body=new JSONObject();
        body.put("model",model);
        JSONArray messages=new JSONArray();
        StringBuilder sys=new StringBuilder("Sen Lakdoz AI için bağımsız bir yardımcı modelsin. Türkçe cevap ver. Kullanıcının yazım hatalarını, eksik harflerini ve konuşma dili ifadelerini bağlamdan düzelt. Sorunun niyetini değiştirme; emin olmadığın bilgiyi uydurma. Kısa ama gerekçeli bir cevap üret.");
        if(memory!=null&&!memory.trim().isEmpty())sys.append("\n\nİlgili eski sohbet hafızası:\n").append(memory.trim());
        messages.put(new JSONObject().put("role","system").put("content",sys.toString()));
        int start=Math.max(0,history==null?0:history.size()-8);
        if(history!=null)for(int i=start;i<history.size();i++){
            HistoryStore.Turn t=history.get(i);
            messages.put(new JSONObject().put("role","assistant".equals(t.role)?"assistant":"user").put("content",t.text));
        }
        messages.put(new JSONObject().put("role","user").put("content",prompt));
        body.put("messages",messages);
        body.put("temperature",0.25);
        body.put("max_tokens",900);
        HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(7000);c.setReadTimeout(22000);
        c.setRequestProperty("Authorization","Bearer "+key.trim());
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("X-Title","Lakdoz AI");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);c.getOutputStream().close();
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IllegalStateException("Ücretsiz AI servisi HTTP "+code);
        JSONObject root=new JSONObject(readAll(c.getInputStream()));
        JSONArray choices=root.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IllegalStateException("Ücretsiz AI boş cevap verdi.");
        JSONObject msg=choices.getJSONObject(0).optJSONObject("message");
        String out=msg==null?"":msg.optString("content","");
        if(out.trim().isEmpty())throw new IllegalStateException("Ücretsiz AI boş cevap verdi.");
        return out.trim();
    }

    private String readAll(InputStream is)throws Exception{
        if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));
        StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();
    }
}