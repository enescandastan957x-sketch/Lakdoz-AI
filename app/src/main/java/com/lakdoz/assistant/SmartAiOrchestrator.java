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

        long started=System.nanoTime();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<String> gem=pool.submit(new Callable<String>(){public String call()throws Exception{
            return new AiClient(context).askStreaming(prompt,history,memory,null);
        }});
        Future<List<String>> free=pool.submit(new Callable<List<String>>(){public List<String> call()throws Exception{
            return new OpenRouterClient(context).askFreeCandidates(prompt,history,memory);
        }});
        String a="";List<String> freeAnswers=new java.util.ArrayList<>();
        try{a=gem.get(24,TimeUnit.SECONDS);}catch(Exception ignored){}
        try{List<String> found=free.get(24,TimeUnit.SECONDS);if(found!=null)freeAnswers.addAll(found);}catch(Exception ignored){}
        pool.shutdownNow();

        // Kullanıcı özellikle acele etmediğini belirtti: adaylar çok hızlı geldiyse
        // kısa bir değerlendirme penceresi bırak, fakat ağ çağrıları uzunsa ekstra bekleme yapma.
        long elapsedMs=(System.nanoTime()-started)/1000000L;
        if(elapsedMs<4500L)try{Thread.sleep(4500L-elapsedMs);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}

        if(a.isEmpty()&&freeAnswers.isEmpty())return new AiClient(context).askStreaming(prompt,history,memory,listener);
        if(a.isEmpty()&&freeAnswers.size()==1){if(listener!=null)listener.onDelta(freeAnswers.get(0));return freeAnswers.get(0);}
        if(a.isEmpty()){a=freeAnswers.remove(0);}
        if(freeAnswers.isEmpty()){if(listener!=null)listener.onDelta(a);return a;}

        StringBuilder synthesis=new StringBuilder();
        synthesis.append("Kullanıcının sorusu (yazım hataları olabilir):\n").append(prompt);
        synthesis.append("\n\nGemini aday cevabı:\n").append(a);
        for(int i=0;i<freeAnswers.size();i++)synthesis.append("\n\nÜcretsiz model aday cevabı ").append(i+1).append(":\n").append(freeAnswers.get(i));
        synthesis.append("\n\nGörev: Önce sorunun gerçek niyetini bağlamdan anla. Aday cevapların ortak ve doğrulanabilir noktalarını birleştir. Çelişki varsa iki tarafı da kesinmiş gibi sunma; belirsizliği kısaca belirt. Yanlış veya konu dışı adayları ele. Gerekirse canlı doğrulama gerektiğini söyle. Kullanıcıya tek, doğal ve doğrudan Türkçe cevap ver; model isimlerinden veya bu birleştirme işleminden bahsetme.");
        return new AiClient(context).askStreaming(synthesis.toString(),history,memory,listener);
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