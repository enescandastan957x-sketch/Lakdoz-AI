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
        String p=prompt==null?"":prompt.trim();
        boolean tiny=p.length()<18 && !p.contains("?");

        if(settings.isSmartEnsemble() && !settings.getOpenRouterApiKey().trim().isEmpty() && !tiny){
            ExecutorService pool=Executors.newFixedThreadPool(2);
            Future<String> primary=pool.submit(new Callable<String>(){public String call()throws Exception{return new AiClient(context).askStreaming(p,history,memory,null);}});
            Future<String> second=pool.submit(new Callable<String>(){public String call()throws Exception{return new OpenRouterClient(context).ask(p,history,memory);}});
            String a="",b="";
            try{a=primary.get(40,TimeUnit.SECONDS);}catch(Exception ignored){}
            try{b=second.get(40,TimeUnit.SECONDS);}catch(Exception ignored){}
            pool.shutdownNow();

            if(!a.isEmpty()&&!b.isEmpty()){
                String review="Kullanıcının asıl sorusu:\n"+p+
                        "\n\nBirinci uzman cevabı:\n"+a+
                        "\n\nİkinci bağımsız uzman cevabı:\n"+b+
                        "\n\nBu iki cevabı eleştirel biçimde kontrol et. Kullanıcının konuşma bağlamını koru. Yanlış varsayımı, uydurmayı ve çelişkiyi temizle. En doğru, doğal Türkçe tek cevabı ver. Kaynak modellerden bahsetme.";
                return new AiClient(context).askStreaming(review,history,memory,listener);
            }
            if(!a.isEmpty()){if(listener!=null)listener.onDelta(a);return a;}
            if(!b.isEmpty()){if(listener!=null)listener.onDelta(b);return b;}
        }

        if(tiny)return new AiClient(context).askStreaming(p,history,memory,listener);

        String draft=new AiClient(context).askStreaming(p,history,memory,null);
        String verify="Kullanıcının sorusu:\n"+p+
                "\n\nİlk taslak cevap:\n"+draft+
                "\n\nTaslağı dikkatle kontrol et. Konuşma bağlamına uyuyor mu, kullanıcının yazım hatalarını doğru yorumlamış mı, gereksiz varsayım veya uydurma var mı kontrol et. Gerekirse düzelt. Sadece nihai cevabı doğal Türkçe ver.";
        try{
            return new AiClient(context).askStreaming(verify,history,memory,listener);
        }catch(Exception e){
            if(listener!=null)listener.onDelta(draft);
            return draft;
        }
    }

    private boolean shouldEnsemble(String prompt){
        String p=prompt==null?"":prompt.toLowerCase(new Locale("tr","TR"));
        if(p.length()>180)return true;
        String[] cues={"karşılaştır","karsilastir","hangisi","neden","analiz","araştır","arastir","detaylı","detayli","en iyi","mantıklı","mantikli","doğru mu","dogru mu","avantaj","dezavantaj","plan yap","strateji","öner","oner"};
        for(String c:cues)if(p.contains(c))return true;
        return false;
    }
}
