package com.lakdoz.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private HistoryStore history;
    private SecureSettings settings;
    private TextView chat, status;
    private EditText input;
    private ScrollView scroll;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new HistoryStore(this);
        settings = new SecureSettings(this);
        initTts();
        setContentView(buildUi());
        applyInsets();
        refreshHistory();
        ensureMic();
        if (settings.getGeminiApiKey().isEmpty()) status.setText("Gemini bağlantısı ayarlı değil • AI AYARLARI");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setId(android.R.id.content);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9,11,16));
        root.setPadding(dp(18),dp(10),dp(18),dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Lakdoz AI"); title.setTextSize(30); title.setTextColor(Color.WHITE); title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = button("AI AYARLARI"); settingsBtn.setOnClickListener(v -> showSettings()); top.addView(settingsBtn);
        root.addView(top);

        TextView sub = new TextView(this);
        sub.setText("0.8 • Gemini Auto-Fallback • Galaxy S25 Ultra"); sub.setTextSize(14); sub.setTextColor(Color.rgb(156,163,175));
        sub.setPadding(0,dp(2),0,dp(8)); root.addView(sub);

        status = new TextView(this); status.setText("Hazır"); status.setTextSize(14); status.setTextColor(Color.rgb(129,230,217));
        status.setPadding(0,0,0,dp(8)); root.addView(status);

        scroll = new ScrollView(this); scroll.setFillViewport(true);
        chat = new TextView(this); chat.setTextSize(18); chat.setTextColor(Color.rgb(235,237,242)); chat.setLineSpacing(0,1.18f);
        chat.setPadding(dp(4),dp(12),dp(4),dp(16)); scroll.addView(chat);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        input = new EditText(this); input.setHint("Bana bir şey sor veya komut ver…"); input.setHintTextColor(Color.rgb(125,133,147));
        input.setTextColor(Color.WHITE); input.setTextSize(17); input.setBackgroundColor(Color.rgb(20,24,33)); input.setPadding(dp(14),dp(12),dp(14),dp(12));
        input.setMinLines(2); input.setMaxLines(5); root.addView(input);

        LinearLayout row = new LinearLayout(this); row.setPadding(0,dp(8),0,0);
        Button talk = button("KONUŞ"); talk.setOnClickListener(v -> listen());
        Button send = button("GÖNDER"); send.setOnClickListener(v -> submit(input.getText().toString()));
        Button clear = button("SİL"); clear.setOnClickListener(v -> { history.clear(); refreshHistory(); });
        row.addView(talk,new LinearLayout.LayoutParams(0,dp(54),1)); row.addView(send,new LinearLayout.LayoutParams(0,dp(54),1)); row.addView(clear,new LinearLayout.LayoutParams(0,dp(54),0.72f));
        root.addView(row);
        return root;
    }

    private void applyInsets() {
        View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(Math.max(dp(18),bars.left+dp(8)), Math.max(dp(10),bars.top+dp(8)), Math.max(dp(18),bars.right+dp(8)), Math.max(dp(12),bars.bottom+dp(8)));
            return insets;
        });
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(14); b.setAllCaps(false); return b; }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14),dp(14),dp(14),dp(14));
        TextView note = new TextView(this);
        note.setText("Gemini anahtarın bu cihazda Android Keystore ile şifrelenir. Lakdoz önce ücretsiz katmana uygun Flash modellerini dener; yoğunluk veya geçici hata olursa otomatik yeniden dener ve yedek modele geçer.");
        note.setTextSize(14); box.addView(note);
        EditText key = new EditText(this);
        key.setHint(settings.getGeminiApiKey().isEmpty() ? "Gemini API anahtarı" : "Anahtar kayıtlı • değiştirmek için yenisini gir");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(key);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Lakdoz AI • Gemini").setView(box)
                .setPositiveButton("Kaydet",null).setNeutralButton("Bağlantıyı test et",null).setNegativeButton("İptal",null).create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try { String k = key.getText().toString().trim(); if (!k.isEmpty()) settings.setGeminiApiKey(k); settings.setGeminiModel("gemini-2.5-flash"); status.setText("Gemini ayarları kaydedildi."); dialog.dismiss(); }
                catch (Exception e) { Toast.makeText(this,"Kaydedilemedi: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try { String k = key.getText().toString().trim(); if (!k.isEmpty()) settings.setGeminiApiKey(k); settings.setGeminiModel("gemini-2.5-flash"); }
                catch (Exception e) { Toast.makeText(this,"Kaydedilemedi: "+e.getMessage(),Toast.LENGTH_LONG).show(); return; }
                status.setText("Gemini test ediliyor • yoğunluk varsa otomatik tekrar denenecek…");
                executor.execute(() -> {
                    String msg;
                    try { msg = new AiClient(getApplicationContext()).testConnection(); }
                    catch (Exception e) { msg = friendlyError(e); }
                    final String out = msg;
                    runOnUiThread(() -> { status.setText(out.startsWith("Bağlantı başarısız") ? out : "Gemini bağlantısı başarılı ✓"); Toast.makeText(this,out,Toast.LENGTH_LONG).show(); });
                });
            });
        });
        dialog.show();
    }

    private void submit(String text) {
        final String q = text == null ? "" : text.trim(); if (q.isEmpty()) return;
        if (settings.getGeminiApiKey().isEmpty()) { Toast.makeText(this,"Önce AI AYARLARI bölümünden Gemini anahtarını ekle.",Toast.LENGTH_LONG).show(); showSettings(); return; }
        input.setText(""); List<HistoryStore.Turn> before = history.load(); history.add("user",q); refreshHistory(); status.setText("Lakdoz düşünüyor • gerekirse yedek modele geçecek…");
        executor.execute(() -> {
            String answer;
            try { LocalCommandRouter.Result local = new LocalCommandRouter(getApplicationContext()).tryHandle(q); answer = local.handled ? local.response : new AiClient(getApplicationContext()).ask(q,before); }
            catch (Exception e) { answer = friendlyError(e); }
            final String out = answer; history.add("assistant",out);
            runOnUiThread(() -> { status.setText("Hazır"); refreshHistory(); speak(out); });
        });
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("HTTP 503")) return "Bağlantı başarısız: Gemini şu anda çok yoğun. Lakdoz birkaç kez ve farklı modellerle denedi; biraz sonra tekrar dene.";
        if (m.contains("HTTP 429")) return "Bağlantı başarısız: Gemini ücretsiz kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";
        if (m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("reddedildi")) return "Bağlantı başarısız: Gemini API anahtarı kabul edilmedi. AI AYARLARI bölümündeki anahtarı kontrol et.";
        if (m.contains("HTTP 404")) return "Bağlantı başarısız: Kullanılabilir bir Gemini modeli bulunamadı. Lakdoz otomatik model seçimini denedi.";
        return "Bağlantı başarısız: " + (m.isEmpty() ? "Gemini servisine ulaşılamadı." : m);
    }

    private void refreshHistory() {
        StringBuilder sb = new StringBuilder();
        for (HistoryStore.Turn t : history.load()) { sb.append("user".equals(t.role) ? "Sen\n" : "Lakdoz\n"); sb.append(t.text).append("\n\n"); }
        if (sb.length()==0) sb.append("Merhaba. Ben Lakdoz. Bana yazabilir veya sesli konuşabilirsin.");
        chat.setText(sb.toString()); chat.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void initTts() { tts = new TextToSpeech(this, s -> { if (s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.forLanguageTag("tr-TR")); }); }
    private void speak(String text) { if (tts != null && text != null) tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"lakdoz-answer"); }
    private void ensureMic() { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ensureMic(); return; }
        if (recognizer != null) recognizer.destroy(); recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p){status.setText("Dinliyorum…");} public void onBeginningOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){status.setText("Düşünüyorum…");} public void onError(int e){status.setText("Ses algılama hatası: "+e);}
            public void onResults(Bundle b){ java.util.ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(xs!=null&&!xs.isEmpty()) submit(xs.get(0)); }
            public void onPartialResults(Bundle b){} public void onEvent(int t,Bundle b){}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR"); recognizer.startListening(i);
    }

    @Override protected void onDestroy(){ if(recognizer!=null) recognizer.destroy(); if(tts!=null) tts.shutdown(); executor.shutdownNow(); super.onDestroy(); }
}
