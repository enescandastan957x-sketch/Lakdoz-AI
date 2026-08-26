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
import android.widget.Button;
import android.widget.CheckBox;
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
    private TextView chat;
    private TextView status;
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
        refreshHistory();
        ensureMic();
        if (settings.getApiKey().isEmpty() && settings.getBackendUrl().isEmpty()) {
            status.setText("AI bağlantısı henüz ayarlı değil. 'AI AYARLARI'na dokun.");
        }
    }

    private View buildUi() {
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(Color.rgb(246,246,248));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Lakdoz AI");
        title.setTextSize(29);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = button("AI AYARLARI");
        settingsBtn.setOnClickListener(v -> showSettings());
        top.addView(settingsBtn);
        root.addView(top);

        TextView sub = new TextView(this);
        sub.setText("0.5 • konuş, sor, cevabı Lakdoz söylesin");
        sub.setTextSize(14);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        status = new TextView(this);
        status.setText("Hazır");
        status.setTextSize(14);
        status.setPadding(0,p/2,0,p/2);
        root.addView(status);

        scroll = new ScrollView(this);
        chat = new TextView(this);
        chat.setTextSize(17);
        chat.setTextColor(Color.rgb(30,30,35));
        chat.setLineSpacing(0,1.15f);
        chat.setPadding(0,p,0,p);
        scroll.addView(chat);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        input = new EditText(this);
        input.setHint("Bana bir şey sor veya komut ver…");
        input.setMinLines(2);
        input.setMaxLines(5);
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        Button talk = button("KONUŞ");
        talk.setOnClickListener(v -> listen());
        Button send = button("GÖNDER");
        send.setOnClickListener(v -> submit(input.getText().toString()));
        Button clear = button("GEÇMİŞİ SİL");
        clear.setOnClickListener(v -> { history.clear(); refreshHistory(); });
        row.addView(talk, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(send, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);
        return root;
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); return b; }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    private void showSettings() {
        int p = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p,p,p,p);
        TextView note = new TextView(this);
        note.setText("Önerilen: kendi güvenli Lakdoz sunucunu kullan. Kişisel test için API anahtarını bu cihazda Android Keystore ile şifreli saklayabilirsin. Anahtar APK'nın içine gömülmez.");
        note.setTextSize(14);
        box.addView(note);

        EditText backend = new EditText(this);
        backend.setHint("Güvenli sunucu URL'si (opsiyonel)");
        backend.setText(settings.getBackendUrl());
        box.addView(backend);
        EditText key = new EditText(this);
        key.setHint("OpenAI API anahtarı (kişisel test modu)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(settings.getApiKey());
        box.addView(key);
        EditText model = new EditText(this);
        model.setHint("Model");
        model.setText(settings.getModel());
        box.addView(model);
        CheckBox web = new CheckBox(this);
        web.setText("Güncel sorularda web araması kullan");
        web.setChecked(settings.isWebEnabled());
        box.addView(web);

        new AlertDialog.Builder(this).setTitle("Lakdoz AI bağlantısı").setView(box)
                .setPositiveButton("Kaydet", (d,w) -> {
                    try {
                        settings.setBackendUrl(backend.getText().toString());
                        settings.setApiKey(key.getText().toString());
                        settings.setModel(model.getText().toString().trim().isEmpty() ? "gpt-5.6-luna" : model.getText().toString().trim());
                        settings.setWebEnabled(web.isChecked());
                        status.setText("AI bağlantısı kaydedildi.");
                    } catch (Exception e) {
                        Toast.makeText(this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("İptal", null).show();
    }

    private void submit(String text) {
        final String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return;
        input.setText("");
        List<HistoryStore.Turn> before = history.load();
        history.add("user", q);
        refreshHistory();
        status.setText("Lakdoz düşünüyor…");
        executor.execute(() -> {
            String answer;
            try {
                LocalCommandRouter.Result local = new LocalCommandRouter(getApplicationContext()).tryHandle(q);
                if (local.handled) answer = local.response;
                else answer = new AiClient(getApplicationContext()).ask(q, before);
            } catch (Exception e) {
                answer = "Bağlantı/işlem hatası: " + (e.getMessage() == null ? "bilinmeyen hata" : e.getMessage());
            }
            final String out = answer;
            history.add("assistant", out);
            runOnUiThread(() -> {
                status.setText("Hazır");
                refreshHistory();
                speak(out);
            });
        });
    }

    private void refreshHistory() {
        StringBuilder sb = new StringBuilder();
        for (HistoryStore.Turn t : history.load()) {
            sb.append("user".equals(t.role) ? "Sen\n" : "Lakdoz\n");
            sb.append(t.text).append("\n\n");
        }
        if (sb.length() == 0) sb.append("Merhaba. Bana bir şey sor. İstersen sesli de konuşabilirsin.");
        chat.setText(sb.toString());
        chat.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void initTts() {
        tts = new TextToSpeech(this, statusCode -> {
            if (statusCode == TextToSpeech.SUCCESS) tts.setLanguage(Locale.forLanguageTag("tr-TR"));
        });
    }

    private void speak(String text) {
        if (tts != null && text != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lakdoz-answer");
    }

    private void ensureMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
    }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureMic();
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) { status.setText("Dinliyorum…"); }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() { status.setText("Düşünüyorum…"); }
            public void onError(int e) { status.setText("Ses algılama hatası: " + e); }
            public void onResults(Bundle b) {
                java.util.ArrayList<String> xs = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (xs != null && !xs.isEmpty()) submit(xs.get(0));
            }
            public void onPartialResults(Bundle b) {}
            public void onEvent(int t, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        recognizer.startListening(i);
    }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        executor.shutdownNow();
        super.onDestroy();
    }
}
