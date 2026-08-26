package com.lakdoz.assistant;

import android.content.Context;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SmartAiOrchestrator {
    private final Context context;
    private final SecureSettings settings;
    public SmartAiOrchestrator(Context context){this.context=context.getApplicationContext();this.settings=new SecureSettings(this.context);}

    public String ask(String prompt,List<HistoryStore.Turn> history,String memory,AiClient.DeltaListener listener)throws Exception{
        if(!shouldEnsemble(prompt) || !settings.isSmartEnsemble() || settings.getOpenRouterApiKey().trim().isEmpty())
            return new AiClient(context).askStreaming(prompt,history,memory,listener);

        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<String> gem=pool.submit(new Callable<String>(){public String call()throws Exception{return new AiClient(context).askStreaming(prompt,history,memory,null);}});
        Future<String> free=pool.submit(new Callable<String>(){public String call()throws Exception{return new OpenRouterClient(context).ask(prompt,history,memory);}});
        String a="",b="";
        try{a=gem.get(24,TimeUnit.SECONDS);}catch(Exception ignored){}
        try{b=free.get(24,TimeUnit.SECONDS);}catch(Exception ignored){}
        pool.shutdownNow();

        if(a.isEmpty()&&b.isEmpty())return new AiClient(context).askStreaming(prompt,history,memory,listener);
        if(a.isEmpty()){if(listener!=null)listener.onDelta(b);return b;}
        if(b.isEmpty()){if(listener!=null)listener.onDelta(a);return a;}

        String synthesis="Kullanıcının sorusu:\n"+prompt+
                "\n\nBirinci AI cevabı:\n"+a+
                "\n\nİkinci bağımsız AI cevabı:\n"+b+
                "\n\nGörev: İki cevabın en güvenilir ortak noktalarını birleştir. Çelişki varsa kesin olmayanı kesinmiş gibi yazma; gerekirse kısa biçimde belirt. Tek, doğal Türkçe Lakdoz cevabı üret. 'AI 1/AI 2' deme.";
        return new AiClient(context).askStreaming(synthesis,java.util.Collections.emptyList(),memory,listener);
    }

    private boolean shouldEnsemble(String prompt){
        String p=prompt==null?"":prompt.toLowerCase(new Locale("tr","TR"));
        if(p.length()>180)return true;
        String[] cues={"karşılaştır","karsilastir","hangisi","neden","analiz","araştır","arastir","detaylı","detayli","en iyi","mantıklı","mantikli","doğru mu","dogru mu","avantaj","dezavantaj","plan yap","strateji","öner","oner"};
        for(String c:cues)if(p.contains(c))return true;
        return false;
    }
}
