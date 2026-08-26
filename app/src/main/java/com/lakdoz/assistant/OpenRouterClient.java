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

public class OpenRouterClient {
    private final SecureSettings settings;
    public OpenRouterClient(Context context){ settings=new SecureSettings(context); }

    public boolean isConfigured(){ String k=settings.getOpenRouterApiKey(); return k!=null&&!k.trim().isEmpty(); }

    public String ask(String prompt,List<HistoryStore.Turn> history,String memory)throws Exception{
        String key=settings.getOpenRouterApiKey();
        if(key==null||key.trim().isEmpty())throw new IllegalStateException("OpenRouter bağlantısı ayarlı değil.");
        JSONObject body=new JSONObject();
        body.put("model","openrouter/free");
        JSONArray messages=new JSONArray();
        StringBuilder sys=new StringBuilder("Sen Lakdoz AI için ikinci görüş üreten güçlü bir yardımcı modelsin. Türkçe cevap ver. Soruyu dikkatle çöz, yazım hatalarını bağlamdan düzelt, güncel olmayan bilgiyi kesinmiş gibi söyleme. Kısa ama gerekçeli ol.");
        if(memory!=null&&!memory.trim().isEmpty())sys.append("\n\nİlgili eski sohbet hafızası:\n").append(memory.trim());
        messages.put(new JSONObject().put("role","system").put("content",sys.toString()));
        int start=Math.max(0,history==null?0:history.size()-6);
        if(history!=null)for(int i=start;i<history.size();i++){
            HistoryStore.Turn t=history.get(i);
            messages.put(new JSONObject().put("role","assistant".equals(t.role)?"assistant":"user").put("content",t.text));
        }
        messages.put(new JSONObject().put("role","user").put("content",prompt));
        body.put("messages",messages);
        body.put("temperature",0.35);
        body.put("max_tokens",900);

        HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(7000);c.setReadTimeout(22000);
        c.setRequestProperty("Authorization","Bearer "+key.trim());
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("X-Title","Lakdoz AI");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);c.getOutputStream().close();
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IllegalStateException("İkinci AI servisi HTTP "+code+": "+readAll(c.getErrorStream()));
        JSONObject root=new JSONObject(readAll(c.getInputStream()));
        JSONArray choices=root.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IllegalStateException("İkinci AI boş cevap verdi.");
        JSONObject msg=choices.getJSONObject(0).optJSONObject("message");
        String out=msg==null?"":msg.optString("content","");
        if(out.trim().isEmpty())throw new IllegalStateException("İkinci AI boş cevap verdi.");
        return out.trim();
    }

    private String readAll(InputStream is)throws Exception{
        if(is==null)return"";BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));
        StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();return sb.toString();
    }
}
