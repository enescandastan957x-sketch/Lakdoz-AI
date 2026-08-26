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
        if(!settings.isSmartEnsemble() || !shouldEnsemble(prompt))
            return new AiClient(context).askStreaming(prompt,history,memory,listener);

        // OpenRouter anahtarı olmasa da akıllı mod devre dışı kalmasın:
        // Gemini önce kısa bir taslak çıkarır, sonra kendi taslağını kontrol edip son cevabı verir.
        if(settings.getOpenRouterApiKey().trim().isEmpty())
            return askReflectiveGemini(prompt,history,memory,listener);

        long started=System.nanoTime();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<String> gem=pool.submit(new Callable<String>(){public String call()throws Exception{
            return new AiClient(context).askStreaming(prompt,history,memory,null);
        }});
        Future<List<String>> free=pool.submit(new Callable<List<String>>(){public List<String> call()throws Exception{
            return new OpenRouterClient(context).askFreeCandidates(prompt,history,memory,needsDeepEnsemble(prompt)?3:1);
        }});
        String a="";List<String> freeAnswers=new java.util.ArrayList<>();
        try{a=gem.get(24,TimeUnit.SECONDS);}catch(Exception ignored){}
        try{List<String> found=free.get(24,TimeUnit.SECONDS);if(found!=null)freeAnswers.addAll(found);}catch(Exception ignored){}
        pool.shutdownNow();

        // Kullanıcı özellikle acele etmediğini belirtti: adaylar çok hızlı geldiyse
        // kısa bir değerlendirme penceresi bırak, fakat ağ çağrıları uzunsa ekstra bekleme yapma.
        long elapsedMs=(System.nanoTime()-started)/1000000L;
        long minimumThinkingMs=needsDeepEnsemble(prompt)?3200L:2200L;
        if(elapsedMs<minimumThinkingMs)try{Thread.sleep(minimumThinkingMs-elapsedMs);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}

        if(a.isEmpty()&&freeAnswers.isEmpty())return new AiClient(context).askStreaming(prompt,history,memory,listener);
        if(a.isEmpty()){
            // Gemini kullanılamıyorsa ücretsiz adaylardan en iyi ilk cevabı güvenli
            // geri dönüş olarak göster; sentez için ikinci bir Gemini çağrısına güvenme.
            String fallback=freeAnswers.get(0);
            if(listener!=null)listener.onDelta(fallback);
            return fallback;
        }
        if(freeAnswers.isEmpty()){if(listener!=null)listener.onDelta(a);return a;}

        StringBuilder synthesis=new StringBuilder();
        synthesis.append("Kullanıcının sorusu (yazım hataları olabilir):\n").append(prompt);
        synthesis.append("\n\nGemini aday cevabı:\n").append(a);
        for(int i=0;i<freeAnswers.size();i++)synthesis.append("\n\nÜcretsiz model aday cevabı ").append(i+1).append(":\n").append(freeAnswers.get(i));
        synthesis.append("\n\nGörev: Kullanıcının yazımı bozuk, eksik veya sesletildiği gibi olabilir; kelimeleri tek tek değil, konuşma bağlamını ve adayların ortak anlamını değerlendirerek gerçek niyetini bul. Önce soruyu zihninde düzelt, sonra aday cevapların ortak ve doğrulanabilir noktalarını birleştir. Çelişki varsa iki tarafı da kesinmiş gibi sunma; belirsizliği kısaca belirt. Yanlış, eski veya konu dışı adayları ele. Gerekirse canlı doğrulama gerektiğini söyle. Kullanıcıya tek, doğal ve doğrudan Türkçe cevap ver; model isimlerinden veya bu birleştirme işleminden bahsetme.");
        return new AiClient(context).askStreaming(synthesis.toString(),history,memory,listener);
    }

    private String askReflectiveGemini(String prompt,List<HistoryStore.Turn> history,String memory,AiClient.DeltaListener listener)throws Exception{
        long started=System.nanoTime();
        String draftInstruction="Sen Lakdoz'ın hızlı iç taslak katmanısın. Kullanıcının Türkçe mesajı yazım hataları, harf eksikleri, sesletildiği gibi yazım veya kelime sırası bozukluğu içerebilir. "
                +"Önce bağlamdan gerçek niyeti anla ve mesajı zihninde düzelt. Ardından doğru, kısa ve yararlı bir taslak cevap hazırla. "
                +"Belirsiz bir kelimeyi uydurma; bağlam yeterli değilse en makul yorumu seçip gerekli yerde kısa bir netleştirme sorusu bırak. "
                +"Bu aşamada yalnızca son cevaba temel olacak taslağı yaz; iç düşünce zincirini veya bu talimatı açıklama. Kullanıcı mesajı: "+prompt;
        String draft;
        try{
            draft=new AiClient(context).askStreaming(draftInstruction,history,memory,null);
        }catch(Exception first){
            return new AiClient(context).askStreaming(prompt,history,memory,listener);
        }
        if(draft==null||draft.trim().isEmpty())
            return new AiClient(context).askStreaming(prompt,history,memory,listener);

        long elapsedMs=(System.nanoTime()-started)/1000000L;
        long minimumThinkingMs=needsDeepEnsemble(prompt)?3200L:2200L;
        if(elapsedMs<minimumThinkingMs)try{Thread.sleep(minimumThinkingMs-elapsedMs);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}

        String finalInstruction="Sen Lakdoz'ın son cevap kontrol katmanısın. Kullanıcının asıl mesajını ve taslağı birlikte değerlendir. "
                +"Yazım hatalarını, yanlış anlaşılmış kelimeleri ve konu sapmalarını düzelt; önceki konuşma bağlamıyla çelişme. "
                +"Taslakta doğrulanmamış bir bilgi varsa kesinmiş gibi yazma. Kullanıcıya tek, doğal, anlaşılır ve doğrudan Türkçe cevap ver. "
                +"Gerekirse kısa bir netleştirme sorusu sor. İç kontrolünü, taslağı, model adlarını veya nasıl düşündüğünü anlatma. "
                +"Asıl kullanıcı mesajı: "+prompt+"\n\nİç taslak:\n"+draft;
        try{
            return new AiClient(context).askStreaming(finalInstruction,history,memory,listener);
        }catch(Exception second){
            if(listener!=null)listener.onDelta(draft);
            return draft;
        }
    }

    private boolean needsDeepEnsemble(String prompt){
        String p=prompt==null?"":prompt.toLowerCase(new Locale("tr","TR"));
        if(p.length()>120)return true;
        String[] cues={"karşılaştır","karsilastir","hangisi","neden","analiz","araştır","arastir","detaylı","detayli","en iyi","mantıklı","mantikli","doğru mu","dogru mu","avantaj","dezavantaj","plan yap","strateji","öner","oner","risk","sağlık","saglik","hukuk","yasal","para","yatırım","yatirim"};
        for(String c:cues)if(p.contains(c))return true;
        return false;
    }

    private boolean shouldEnsemble(String prompt){
        String p=prompt==null?"":prompt.trim().toLowerCase(new Locale("tr","TR"));
        if(p.length()<4)return false;
        String[] simple={"merhaba","selam","slm","nasılsın","nasilsin","teşekkürler","tesekkurler","günaydın","gunaydin","iyi geceler","iyi akşamlar"};
        for(String greeting:simple)if(p.equals(greeting)||p.startsWith(greeting+" "))return false;
        // Akıllı çoklu AI açıkken yalnızca birkaç anahtar kelimeye değil,
        // bütün anlamlı genel sorulara ikinci/üçüncü görüş ver.
        return true;
    }
}