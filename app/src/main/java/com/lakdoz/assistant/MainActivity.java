package com.lakdoz.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private HistoryStore history;
    private SecureSettings settings;
    private TextView status, currentTitle;
    private EditText input, searchInput;
    private ScrollView messageScroll;
    private LinearLayout messageList, conversationList;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final int BG = Color.rgb(8, 11, 17);
    private final int PANEL = Color.rgb(14, 18, 27);
    private final int CARD = Color.rgb(22, 28, 40);
    private final int ACCENT = Color.rgb(106, 155, 255);
    private final int TEXT = Color.rgb(239, 242, 248);
    private final int MUTED = Color.rgb(145, 154, 171);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new HistoryStore(this);
        settings = new SecureSettings(this);
        initTts();
        setContentView(buildUi());
        applyInsets();
        refreshHistory();
        ensureMic();
        if (settings.getGeminiApiKey().isEmpty()) status.setText("Gemini bağlantısı ayarlı değil • Ayarlar");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setId(android.R.id.content);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(9), dp(12), dp(9), dp(10));
        sidebar.setBackgroundColor(PANEL);
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = new TextView(this);
        brand.setText("LAKDOZ");
        brand.setTextSize(18);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(Color.WHITE);
        brand.setPadding(dp(3), 0, 0, dp(10));
        sidebar.addView(brand);

        Button newChat = smallButton("＋ Yeni sohbet");
        newChat.setOnClickListener(v -> {
            history.newConversation();
            if (searchInput != null) searchInput.setText("");
            refreshHistory();
            input.requestFocus();
        });
        sidebar.addView(newChat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(13);
        searchInput.setHint("Sohbet ara");
        searchInput.setHintTextColor(Color.rgb(105, 115, 133));
        searchInput.setTextColor(TEXT);
        searchInput.setPadding(dp(10), 0, dp(8), 0);
        searchInput.setBackground(rounded(Color.rgb(20, 25, 35), 12));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        searchLp.setMargins(0, dp(9), 0, dp(8));
        sidebar.addView(searchInput, searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { refreshConversationList(s == null ? "" : s.toString()); }
            public void afterTextChanged(Editable e) {}
        });

        TextView recent = tinyLabel("SON SOHBETLER");
        sidebar.addView(recent);

        ScrollView convoScroll = new ScrollView(this);
        convoScroll.setFillViewport(true);
        conversationList = new LinearLayout(this);
        conversationList.setOrientation(LinearLayout.VERTICAL);
        convoScroll.addView(conversationList);
        sidebar.addView(convoScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(14), dp(11), dp(12), dp(10));
        root.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        currentTitle = new TextView(this);
        currentTitle.setText("Yeni sohbet");
        currentTitle.setTextColor(Color.WHITE);
        currentTitle.setTextSize(23);
        currentTitle.setTypeface(Typeface.DEFAULT_BOLD);
        currentTitle.setSingleLine(true);
        currentTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(currentTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = smallButton("Ayarlar");
        settingsBtn.setOnClickListener(v -> showSettings());
        header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(78), dp(42)));
        main.addView(header);

        TextView version = new TextView(this);
        version.setText("Lakdoz 0.9 • Gemini • Sesli asistan");
        version.setTextColor(MUTED);
        version.setTextSize(12);
        version.setPadding(0, dp(2), 0, dp(3));
        main.addView(version);

        status = new TextView(this);
        status.setText("Hazır");
        status.setTextColor(Color.rgb(114, 220, 202));
        status.setTextSize(12);
        status.setMaxLines(2);
        status.setPadding(0, 0, 0, dp(7));
        main.addView(status);

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(0, dp(4), 0, dp(12));
        messageScroll.addView(messageList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        input = new EditText(this);
        input.setHint("Mesaj yaz…");
        input.setHintTextColor(Color.rgb(112, 122, 140));
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setPadding(dp(13), dp(10), dp(13), dp(10));
        input.setBackground(rounded(CARD, 16));
        main.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout composerButtons = new LinearLayout(this);
        composerButtons.setPadding(0, dp(8), 0, 0);
        Button talk = actionButton("Konuş");
        talk.setOnClickListener(v -> listen());
        Button send = actionButton("Gönder");
        send.setOnClickListener(v -> submit(input.getText().toString()));
        Button clear = actionButton("Temizle");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Bu sohbet temizlensin mi?")
                .setMessage("Sadece açık olan sohbetin mesajları silinir.")
                .setPositiveButton("Temizle", (d,w) -> { history.clear(); refreshHistory(); })
                .setNegativeButton("İptal", null).show());
        composerButtons.addView(talk, weightButtonLp());
        composerButtons.addView(send, weightButtonLp());
        composerButtons.addView(clear, weightButtonLp());
        main.addView(composerButtons);
        return root;
    }

    private LinearLayout.LayoutParams weightButtonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void refreshConversationList(String query) {
        if (conversationList == null) return;
        conversationList.removeAllViews();
        String active = history.getActiveConversationId();
        List<HistoryStore.ConversationMeta> conversations = history.listConversations(query);
        for (HistoryStore.ConversationMeta c : conversations) {
            TextView item = new TextView(this);
            item.setText(c.title);
            item.setTextSize(13);
            item.setTextColor(c.id.equals(active) ? Color.WHITE : Color.rgb(197, 203, 215));
            item.setMaxLines(2);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(9), dp(6), dp(7), dp(6));
            item.setBackground(rounded(c.id.equals(active) ? Color.rgb(37, 55, 82) : Color.TRANSPARENT, 11));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            lp.setMargins(0, dp(2), 0, dp(2));
            conversationList.addView(item, lp);
            item.setOnClickListener(v -> {
                history.switchConversation(c.id);
                refreshHistory();
            });
            item.setOnLongClickListener(v -> {
                history.switchConversation(c.id);
                new AlertDialog.Builder(this).setTitle("Sohbeti sil")
                        .setMessage("“" + c.title + "” silinsin mi?")
                        .setPositiveButton("Sil", (d,w) -> { history.deleteActiveConversation(); refreshHistory(); })
                        .setNegativeButton("İptal", null).show();
                return true;
            });
        }
        if (conversations.isEmpty()) {
            TextView empty = tinyLabel("Sonuç yok");
            empty.setPadding(dp(5), dp(10), 0, 0);
            conversationList.addView(empty);
        }
    }

    private void refreshHistory() {
        if (messageList == null) return;
        currentTitle.setText(history.getActiveTitle());
        messageList.removeAllViews();
        List<HistoryStore.Turn> turns = history.load();
        if (turns.isEmpty()) {
            TextView welcome = new TextView(this);
            welcome.setText("Merhaba. Ben Lakdoz.\nBana yazabilir veya sesli konuşabilirsin.");
            welcome.setTextColor(Color.rgb(205, 212, 225));
            welcome.setTextSize(18);
            welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(12), dp(40), dp(12), dp(20));
            messageList.addView(welcome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (HistoryStore.Turn turn : turns) addMessageBubble(turn);
        }
        refreshConversationList(searchInput == null ? "" : searchInput.getText().toString());
        messageList.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addMessageBubble(HistoryStore.Turn turn) {
        boolean user = "user".equals(turn.role);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(9));
        bubble.setBackground(rounded(user ? Color.rgb(35, 59, 92) : Color.rgb(24, 30, 42), 16));

        TextView who = new TextView(this);
        who.setText(user ? "Sen" : "Lakdoz");
        who.setTextSize(11);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        who.setTextColor(user ? Color.rgb(166, 200, 255) : Color.rgb(119, 224, 203));
        bubble.addView(who);

        TextView body = new TextView(this);
        body.setText(turn.text);
        body.setTextColor(TEXT);
        body.setTextSize(16);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(0, dp(3), 0, 0);
        bubble.addView(body);

        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bubbleLp.gravity = user ? Gravity.END : Gravity.START;
        bubbleLp.width = Math.min(dp(280), Math.max(dp(170), getResources().getDisplayMetrics().widthPixels - dp(180)));
        row.addView(bubble, bubbleLp);
        messageList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(12));
        scroll.addView(box);

        TextView aiLabel = sectionLabel("Gemini bağlantısı");
        box.addView(aiLabel);
        EditText key = new EditText(this);
        key.setHint(settings.getGeminiApiKey().isEmpty() ? "Gemini API anahtarı" : "Anahtar kayıtlı • değiştirmek için yenisini gir");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        Button testAi = smallButton("Gemini bağlantısını test et");
        box.addView(testAi, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        TextView voiceLabel = sectionLabel("Lakdoz sesi");
        voiceLabel.setPadding(0, dp(18), 0, dp(6));
        box.addView(voiceLabel);

        ArrayList<Voice> voices = getAvailableVoices();
        ArrayList<String> labels = new ArrayList<>();
        labels.add("Varsayılan Türkçe sesi");
        for (Voice v : voices) labels.add(voiceDisplayName(v));
        Spinner voiceSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);
        box.addView(voiceSpinner);
        String savedVoice = settings.getVoiceName();
        if (!savedVoice.isEmpty()) {
            for (int i = 0; i < voices.size(); i++) if (savedVoice.equals(voices.get(i).getName())) { voiceSpinner.setSelection(i + 1); break; }
        }

        TextView rateText = new TextView(this);
        rateText.setTextColor(Color.DKGRAY);
        box.addView(rateText);
        SeekBar rate = new SeekBar(this);
        rate.setMax(70);
        rate.setProgress(clamp(Math.round((settings.getSpeechRate() - 0.70f) * 100f), 0, 70));
        box.addView(rate);

        TextView pitchText = new TextView(this);
        pitchText.setTextColor(Color.DKGRAY);
        box.addView(pitchText);
        SeekBar pitch = new SeekBar(this);
        pitch.setMax(60);
        pitch.setProgress(clamp(Math.round((settings.getSpeechPitch() - 0.80f) * 100f), 0, 60));
        box.addView(pitch);

        Runnable updateLabels = () -> {
            rateText.setText(String.format(Locale.forLanguageTag("tr-TR"), "Konuşma hızı: %.2fx", 0.70f + rate.getProgress() / 100f));
            pitchText.setText(String.format(Locale.forLanguageTag("tr-TR"), "Ses tonu: %.2fx", 0.80f + pitch.getProgress() / 100f));
        };
        updateLabels.run();
        rate.setOnSeekBarChangeListener(simpleSeek(updateLabels));
        pitch.setOnSeekBarChangeListener(simpleSeek(updateLabels));

        Button preview = smallButton("Sesi dene");
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Lakdoz Ayarları")
                .setView(scroll)
                .setPositiveButton("Kaydet", null)
                .setNegativeButton("İptal", null)
                .create();

        preview.setOnClickListener(v -> {
            Voice selected = voiceSpinner.getSelectedItemPosition() <= 0 ? null : voices.get(voiceSpinner.getSelectedItemPosition() - 1);
            applyVoice(selected, 0.70f + rate.getProgress() / 100f, 0.80f + pitch.getProgress() / 100f);
            speakNow("Merhaba, ben Lakdoz. Bu ses ayarını nasıl buldun?");
        });

        testAi.setOnClickListener(v -> {
            try {
                String k = key.getText().toString().trim();
                if (!k.isEmpty()) settings.setGeminiApiKey(k);
            } catch (Exception e) {
                Toast.makeText(this, "Anahtar kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            status.setText("Gemini bağlantısı test ediliyor…");
            executor.execute(() -> {
                String msg;
                try { msg = new AiClient(getApplicationContext()).testConnection(); }
                catch (Exception e) { msg = friendlyError(e); }
                final String out = msg;
                runOnUiThread(() -> { status.setText(out.startsWith("Bağlantı başarısız") ? out : "Gemini bağlantısı başarılı ✓"); Toast.makeText(this, out, Toast.LENGTH_LONG).show(); });
            });
        });

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String k = key.getText().toString().trim();
                if (!k.isEmpty()) settings.setGeminiApiKey(k);
                Voice selected = voiceSpinner.getSelectedItemPosition() <= 0 ? null : voices.get(voiceSpinner.getSelectedItemPosition() - 1);
                settings.setVoiceName(selected == null ? "" : selected.getName());
                settings.setSpeechRate(0.70f + rate.getProgress() / 100f);
                settings.setSpeechPitch(0.80f + pitch.getProgress() / 100f);
                applySavedVoice();
                status.setText("Ayarlar kaydedildi.");
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.setOnDismissListener(d -> applySavedVoice());
        dialog.show();
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(Runnable r) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { r.run(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private ArrayList<Voice> getAvailableVoices() {
        ArrayList<Voice> turkish = new ArrayList<>();
        if (tts == null) return turkish;
        try {
            Set<Voice> set = tts.getVoices();
            if (set != null) {
                for (Voice v : set) if (v.getLocale() != null && "tr".equals(v.getLocale().getLanguage())) turkish.add(v);
                Collections.sort(turkish, Comparator.comparing(Voice::getName));
            }
        } catch (Exception ignored) {}
        return turkish;
    }

    private String voiceDisplayName(Voice v) {
        if (v == null) return "Varsayılan";
        String name = v.getName();
        if (name.length() > 38) name = name.substring(0, 38) + "…";
        return "Türkçe • " + name;
    }

    private void initTts() {
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) applySavedVoice();
        });
    }

    private void applySavedVoice() {
        if (tts == null) return;
        try {
            tts.setLanguage(Locale.forLanguageTag("tr-TR"));
            String name = settings.getVoiceName();
            Voice selected = null;
            if (!name.isEmpty()) for (Voice v : getAvailableVoices()) if (name.equals(v.getName())) { selected = v; break; }
            applyVoice(selected, settings.getSpeechRate(), settings.getSpeechPitch());
        } catch (Exception ignored) {}
    }

    private void applyVoice(Voice voice, float rate, float pitch) {
        if (tts == null) return;
        try {
            tts.setLanguage(Locale.forLanguageTag("tr-TR"));
            if (voice != null) tts.setVoice(voice);
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
        } catch (Exception ignored) {}
    }

    private void speak(String text) {
        if (tts == null || text == null) return;
        applySavedVoice();
        speakNow(text.replace("*", "").replace("#", ""));
    }

    private void speakNow(String text) {
        if (tts != null && text != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lakdoz-answer");
    }

    private void submit(String text) {
        final String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return;
        if (settings.getGeminiApiKey().isEmpty()) {
            Toast.makeText(this, "Önce Ayarlar bölümünden Gemini anahtarını ekle.", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        input.setText("");
        List<HistoryStore.Turn> before = history.load();
        history.add("user", q);
        refreshHistory();
        status.setText("Lakdoz düşünüyor…");
        executor.execute(() -> {
            String answer;
            try {
                LocalCommandRouter.Result local = new LocalCommandRouter(getApplicationContext()).tryHandle(q);
                answer = local.handled ? local.response : new AiClient(getApplicationContext()).ask(q, before);
            } catch (Exception e) { answer = friendlyError(e); }
            final String out = answer;
            history.add("assistant", out);
            runOnUiThread(() -> {
                status.setText("Hazır");
                refreshHistory();
                speak(out);
            });
        });
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("HTTP 503")) return "Bağlantı başarısız: Gemini şu anda çok yoğun. Lakdoz birkaç kez ve farklı modellerle denedi; biraz sonra tekrar dene.";
        if (m.contains("HTTP 429")) return "Bağlantı başarısız: Gemini ücretsiz kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";
        if (m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("reddedildi")) return "Bağlantı başarısız: Gemini API anahtarı kabul edilmedi. Ayarlardaki anahtarı kontrol et.";
        if (m.contains("HTTP 404")) return "Bağlantı başarısız: Kullanılabilir bir Gemini modeli bulunamadı.";
        return "Bağlantı başarısız: " + (m.isEmpty() ? "Gemini servisine ulaşılamadı." : m);
    }

    private void ensureMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
    }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ensureMic(); return; }
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
                ArrayList<String> xs = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
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

    private void applyInsets() {
        View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + dp(5), bars.top + dp(5), bars.right + dp(5), bars.bottom + dp(5));
            return insets;
        });
        content.requestApplyInsets();
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(rounded(Color.rgb(31, 38, 52), 12));
        return b;
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(Color.rgb(34, 44, 61), 14));
        return b;
    }

    private TextView tinyLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.rgb(111, 121, 139));
        v.setTextSize(10);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(3), dp(4), 0, dp(4));
        return v;
    }

    private TextView sectionLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.DKGRAY);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(16);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        executor.shutdownNow();
        super.onDestroy();
    }
}
