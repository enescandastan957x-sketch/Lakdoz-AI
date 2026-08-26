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
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private TextToSpeech systemTts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor();

    private final int BG = Color.rgb(7, 10, 16);
    private final int PANEL = Color.rgb(13, 18, 27);
    private final int CARD = Color.rgb(20, 27, 39);
    private final int CARD_ALT = Color.rgb(27, 36, 51);
    private final int ACCENT = Color.rgb(111, 159, 255);
    private final int TEXT = Color.rgb(241, 244, 249);
    private final int MUTED = Color.rgb(145, 156, 174);
    private final int GREEN = Color.rgb(116, 224, 198);

    private static final String[] VOICE_LABELS = {
            "Kadın • Yumuşak — Aoede",
            "Kadın • Net — Kore",
            "Kadın • Genç — Leda",
            "Kadın • Sakin — Callirrhoe",
            "Erkek • Enerjik — Puck",
            "Erkek • Bilgilendirici — Charon",
            "Erkek • Güçlü — Orus",
            "Erkek • Olgun — Gacrux",
            "Telefonun sistem Türkçe sesi"
    };

    private static final String[] VOICE_NAMES = {
            "Aoede", "Kore", "Leda", "Callirrhoe",
            "Puck", "Charon", "Orus", "Gacrux", "SYSTEM"
    };

    private static final String[] VOICE_STYLES = {
            "Sıcak, yumuşak, doğal ve samimi bir Türkçe ile konuş. Cümleleri akıcı ve rahat söyle.",
            "Net, kendinden emin, dengeli ve doğal bir Türkçe ile konuş. Gereksiz dramatizasyondan kaçın.",
            "Genç, canlı, pozitif ve doğal bir Türkçe ile konuş. Hızlı değil, enerjik ve anlaşılır ol.",
            "Sakin, rahatlatıcı, nazik ve doğal bir Türkçe ile konuş. Yumuşak bir ritim kullan.",
            "Enerjik, neşeli, arkadaş canlısı ve doğal bir Türkçe ile konuş. Cümlelere canlılık kat.",
            "Bilgilendirici, güven veren, sakin ve profesyonel bir Türkçe ile konuş. Haber sunucusu gibi değil, doğal ol.",
            "Güçlü, kararlı, net ve güven veren bir Türkçe ile konuş. Tonun doğal ve ölçülü olsun.",
            "Olgun, sıcak, sakin ve güven veren bir Türkçe ile konuş. Dengeli ve doğal bir ritim kullan.",
            ""
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new HistoryStore(this);
        settings = new SecureSettings(this);
        initSystemTts();
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
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = new TextView(this);
        brand.setText("LAKDOZ");
        brand.setTextSize(18);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(Color.WHITE);
        brand.setLetterSpacing(0.08f);
        brand.setPadding(dp(4), 0, 0, dp(2));
        sidebar.addView(brand);

        TextView mini = new TextView(this);
        mini.setText("AI ASSISTANT");
        mini.setTextSize(9);
        mini.setTextColor(Color.rgb(105, 123, 153));
        mini.setLetterSpacing(0.13f);
        mini.setPadding(dp(4), 0, 0, dp(11));
        sidebar.addView(mini);

        Button newChat = smallButton("＋ Yeni sohbet", ACCENT);
        newChat.setOnClickListener(v -> {
            history.newConversation();
            searchInput.setText("");
            refreshHistory();
            input.requestFocus();
        });
        sidebar.addView(newChat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(12);
        searchInput.setHint("⌕  Sohbet ara");
        searchInput.setHintTextColor(Color.rgb(105, 115, 133));
        searchInput.setTextColor(TEXT);
        searchInput.setPadding(dp(9), 0, dp(7), 0);
        searchInput.setBackground(rounded(Color.rgb(20, 25, 36), 12));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        searchLp.setMargins(0, dp(9), 0, dp(8));
        sidebar.addView(searchInput, searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                refreshConversationList(s == null ? "" : s.toString());
            }
            public void afterTextChanged(Editable e) {}
        });

        TextView recent = tinyLabel("SON SOHBETLER");
        sidebar.addView(recent);

        ScrollView convoScroll = new ScrollView(this);
        convoScroll.setFillViewport(true);
        convoScroll.setVerticalScrollBarEnabled(false);
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
        currentTitle.setTextSize(22);
        currentTitle.setTypeface(Typeface.DEFAULT_BOLD);
        currentTitle.setSingleLine(true);
        currentTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(currentTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = smallButton("⚙ Ayarlar", Color.rgb(36, 44, 60));
        settingsBtn.setOnClickListener(v -> showSettings());
        header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(88), dp(42)));
        main.addView(header);

        TextView version = new TextView(this);
        version.setText("0.9 • Gemini AI Voice • Galaxy S25 Ultra");
        version.setTextColor(MUTED);
        version.setTextSize(11);
        version.setPadding(0, dp(2), 0, dp(3));
        main.addView(version);

        status = new TextView(this);
        status.setText("● Hazır");
        status.setTextColor(GREEN);
        status.setTextSize(12);
        status.setMaxLines(2);
        status.setPadding(0, 0, 0, dp(7));
        main.addView(status);

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageScroll.setVerticalScrollBarEnabled(false);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(0, dp(4), 0, dp(12));
        messageScroll.addView(messageList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(8), dp(8), dp(8), dp(7));
        composer.setBackground(rounded(Color.rgb(15, 20, 30), 19));
        input = new EditText(this);
        input.setHint("Lakdoz'a mesaj yaz…");
        input.setHintTextColor(Color.rgb(112, 122, 140));
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setPadding(dp(11), dp(8), dp(11), dp(8));
        input.setBackground(rounded(CARD, 14));
        composer.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout composerButtons = new LinearLayout(this);
        composerButtons.setPadding(0, dp(7), 0, 0);
        Button talk = actionButton("◉ Konuş", Color.rgb(36, 48, 67));
        talk.setOnClickListener(v -> listen());
        Button send = actionButton("Gönder ➜", Color.rgb(50, 93, 165));
        send.setOnClickListener(v -> submit(input.getText().toString()));
        Button clear = actionButton("Temizle", Color.rgb(36, 44, 57));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Bu sohbet temizlensin mi?")
                .setMessage("Yalnızca açık olan sohbetin mesajları silinir.")
                .setPositiveButton("Temizle", (d,w) -> { history.clear(); refreshHistory(); })
                .setNegativeButton("İptal", null).show());
        composerButtons.addView(talk, weightButtonLp());
        composerButtons.addView(send, weightButtonLp());
        composerButtons.addView(clear, weightButtonLp());
        composer.addView(composerButtons);
        main.addView(composer);
        return root;
    }

    private LinearLayout.LayoutParams weightButtonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void refreshConversationList(String query) {
        if (conversationList == null) return;
        conversationList.removeAllViews();
        String active = history.getActiveConversationId();
        List<HistoryStore.ConversationMeta> conversations = history.listConversations(query);
        for (HistoryStore.ConversationMeta c : conversations) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(7), dp(7), dp(6));
            card.setBackground(rounded(c.id.equals(active) ? Color.rgb(37, 55, 82) : Color.TRANSPARENT, 11));

            TextView title = new TextView(this);
            title.setText(c.title);
            title.setTextSize(12);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(c.id.equals(active) ? Color.WHITE : Color.rgb(202, 208, 219));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(title);

            if (c.preview != null && !c.preview.isEmpty()) {
                TextView preview = new TextView(this);
                preview.setText(c.preview.replace('\n', ' '));
                preview.setTextSize(9);
                preview.setTextColor(Color.rgb(111, 123, 142));
                preview.setSingleLine(true);
                preview.setEllipsize(TextUtils.TruncateAt.END);
                preview.setPadding(0, dp(2), 0, 0);
                card.addView(preview);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(57));
            lp.setMargins(0, dp(2), 0, dp(2));
            conversationList.addView(card, lp);
            card.setOnClickListener(v -> {
                history.switchConversation(c.id);
                refreshHistory();
            });
            card.setOnLongClickListener(v -> {
                history.switchConversation(c.id);
                new AlertDialog.Builder(this)
                        .setTitle("Sohbeti sil")
                        .setMessage("“" + c.title + "” silinsin mi?")
                        .setPositiveButton("Sil", (d,w) -> { history.deleteActiveConversation(); refreshHistory(); })
                        .setNegativeButton("İptal", null).show();
                return true;
            });
        }
        if (conversations.isEmpty()) {
            TextView empty = tinyLabel("Sonuç bulunamadı");
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
            LinearLayout welcomeCard = new LinearLayout(this);
            welcomeCard.setOrientation(LinearLayout.VERTICAL);
            welcomeCard.setGravity(Gravity.CENTER);
            welcomeCard.setPadding(dp(18), dp(28), dp(18), dp(26));
            welcomeCard.setBackground(rounded(Color.rgb(13, 18, 27), 20));

            TextView logo = new TextView(this);
            logo.setText("✦");
            logo.setTextColor(Color.rgb(139, 178, 255));
            logo.setTextSize(32);
            logo.setGravity(Gravity.CENTER);
            welcomeCard.addView(logo);

            TextView welcome = new TextView(this);
            welcome.setText("Merhaba, ben Lakdoz.\nNe yapmak istersin?");
            welcome.setTextColor(Color.rgb(220, 226, 237));
            welcome.setTextSize(18);
            welcome.setTypeface(Typeface.DEFAULT_BOLD);
            welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(12), dp(5), dp(12), 0);
            welcomeCard.addView(welcome);

            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.setMargins(0, dp(20), 0, 0);
            messageList.addView(welcomeCard, wlp);
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
        bubble.setPadding(dp(12), dp(8), dp(12), dp(10));
        bubble.setBackground(rounded(user ? Color.rgb(38, 65, 103) : Color.rgb(22, 29, 42), 17));

        TextView who = new TextView(this);
        who.setText(user ? "SEN" : "✦ LAKDOZ");
        who.setTextSize(10);
        who.setLetterSpacing(0.08f);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        who.setTextColor(user ? Color.rgb(171, 203, 255) : GREEN);
        bubble.addView(who);

        TextView body = new TextView(this);
        body.setText(turn.text);
        body.setTextColor(TEXT);
        body.setTextSize(15);
        body.setLineSpacing(0, 1.13f);
        body.setPadding(0, dp(4), 0, 0);
        bubble.addView(body);

        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
        bubbleLp.gravity = user ? Gravity.END : Gravity.START;
        row.addView(bubble, bubbleLp);
        messageList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showSettings() {
        ScrollView settingsScroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(16));
        settingsScroll.addView(box);

        box.addView(sectionLabel("Gemini bağlantısı"));
        TextView aiInfo = infoText("API anahtarın Android Keystore ile şifreli tutulur. AI sesleri de aynı Gemini bağlantısını kullanır.");
        box.addView(aiInfo);

        EditText key = new EditText(this);
        key.setHint(settings.getGeminiApiKey().isEmpty() ? "Gemini API anahtarı" : "Anahtar kayıtlı • değiştirmek için yenisini gir");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(key);

        Button testAi = dialogButton("Gemini bağlantısını test et");
        box.addView(testAi, dialogButtonLp());

        TextView voiceHeader = sectionLabel("Lakdoz AI sesi");
        voiceHeader.setPadding(0, dp(19), 0, dp(4));
        box.addView(voiceHeader);
        box.addView(infoText("Birden fazla kadın ve erkek ses profili arasından seçim yapabilirsin. Gemini sesleri internet kullanır; sorun olursa Lakdoz otomatik olarak telefonun Türkçe sistem sesine döner."));

        Spinner voiceSpinner = new Spinner(this);
        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, VOICE_LABELS);
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(voiceAdapter);
        voiceSpinner.setSelection(currentVoiceIndex());
        box.addView(voiceSpinner);

        Button previewVoice = dialogButton("▶ Seçili sesi dene");
        LinearLayout.LayoutParams plp = dialogButtonLp();
        plp.setMargins(0, dp(8), 0, 0);
        box.addView(previewVoice, plp);

        TextView note = infoText("Not: Google sesleri ad ve karakter olarak sunuyor; kadın/erkek gruplaması Lakdoz'un profil düzenidir. Gerçek Gemini ses adı seçimin yanında gösterilir.");
        note.setPadding(0, dp(9), 0, 0);
        box.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Lakdoz Ayarları")
                .setView(settingsScroll)
                .setPositiveButton("Kaydet", null)
                .setNegativeButton("İptal", null)
                .create();

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
                runOnUiThread(() -> {
                    status.setText(out.startsWith("Bağlantı başarısız") ? out : "● Gemini bağlantısı başarılı");
                    Toast.makeText(this, out, Toast.LENGTH_LONG).show();
                });
            });
        });

        previewVoice.setOnClickListener(v -> {
            int index = voiceSpinner.getSelectedItemPosition();
            if (index >= VOICE_NAMES.length - 1) {
                systemSpeak("Merhaba, ben Lakdoz. Bu telefonun sistem sesidir.");
                return;
            }
            try {
                String k = key.getText().toString().trim();
                if (!k.isEmpty()) settings.setGeminiApiKey(k);
            } catch (Exception ignored) {}
            if (settings.getGeminiApiKey().isEmpty()) {
                Toast.makeText(this, "AI sesini denemek için önce Gemini anahtarını kaydet.", Toast.LENGTH_LONG).show();
                return;
            }
            status.setText("Seçili AI sesi hazırlanıyor…");
            previewVoice.setEnabled(false);
            voiceExecutor.execute(() -> {
                try {
                    new GeminiTtsClient(getApplicationContext()).speak(
                            "Merhaba, ben Lakdoz. Bu ses profilini nasıl buldun?",
                            VOICE_NAMES[index], VOICE_STYLES[index]);
                    runOnUiThread(() -> status.setText("● Ses önizlemesi oynatılıyor"));
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        status.setText("AI sesi kullanılamadı • sistem sesiyle deniyorum");
                        systemSpeak("Merhaba, ben Lakdoz. AI sesi şu anda kullanılamadığı için sistem sesini kullanıyorum.");
                        Toast.makeText(this, compactVoiceError(e), Toast.LENGTH_LONG).show();
                    });
                } finally {
                    runOnUiThread(() -> previewVoice.setEnabled(true));
                }
            });
        });

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String k = key.getText().toString().trim();
                if (!k.isEmpty()) settings.setGeminiApiKey(k);
                int index = voiceSpinner.getSelectedItemPosition();
                boolean gemini = index < VOICE_NAMES.length - 1;
                settings.setUseGeminiVoice(gemini);
                if (gemini) {
                    settings.setGeminiVoice(VOICE_NAMES[index]);
                    settings.setGeminiVoiceStyle(VOICE_STYLES[index]);
                }
                status.setText("● Ayarlar kaydedildi");
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private int currentVoiceIndex() {
        if (!settings.useGeminiVoice()) return VOICE_NAMES.length - 1;
        String saved = settings.getGeminiVoice();
        for (int i = 0; i < VOICE_NAMES.length - 1; i++) if (VOICE_NAMES[i].equals(saved)) return i;
        return 1;
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
        status.setText("◌ Lakdoz düşünüyor…");
        executor.execute(() -> {
            String answer;
            try {
                LocalCommandRouter.Result local = new LocalCommandRouter(getApplicationContext()).tryHandle(q);
                answer = local.handled ? local.response : new AiClient(getApplicationContext()).ask(q, before);
            } catch (Exception e) {
                answer = friendlyError(e);
            }
            final String out = answer;
            history.add("assistant", out);
            runOnUiThread(() -> {
                status.setText("● Hazır");
                refreshHistory();
                speak(out);
            });
        });
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String clean = text.replace("*", "").replace("#", "").trim();
        if (!settings.useGeminiVoice()) {
            systemSpeak(clean);
            return;
        }
        status.setText("◌ AI sesi hazırlanıyor…");
        voiceExecutor.execute(() -> {
            try {
                new GeminiTtsClient(getApplicationContext()).speak(clean);
                runOnUiThread(() -> status.setText("● Hazır"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("● AI sesi geçici kullanılamadı • sistem sesi");
                    systemSpeak(clean);
                });
            }
        });
    }

    private String compactVoiceError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("429")) return "AI ses kotası şu anda dolu. Sistem sesi kullanılabilir.";
        if (m.contains("503") || m.contains("500") || m.contains("504")) return "Gemini ses servisi şu anda yoğun. Sistem sesi kullanılabilir.";
        return "AI sesi şu anda kullanılamadı. Sistem sesi yedek olarak çalışıyor.";
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("HTTP 503")) return "Bağlantı başarısız: Gemini şu anda çok yoğun. Lakdoz farklı modellerle yeniden denedi; biraz sonra tekrar dene.";
        if (m.contains("HTTP 429")) return "Bağlantı başarısız: Gemini ücretsiz kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";
        if (m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("reddedildi")) return "Bağlantı başarısız: Gemini API anahtarı kabul edilmedi. Ayarlardaki anahtarı kontrol et.";
        if (m.contains("HTTP 404")) return "Bağlantı başarısız: Kullanılabilir bir Gemini modeli bulunamadı.";
        return "Bağlantı başarısız: " + (m.isEmpty() ? "Gemini servisine ulaşılamadı." : m);
    }

    private void initSystemTts() {
        systemTts = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                systemTts.setLanguage(Locale.forLanguageTag("tr-TR"));
                systemTts.setSpeechRate(settings == null ? 1.0f : settings.getSpeechRate());
                systemTts.setPitch(settings == null ? 1.0f : settings.getSpeechPitch());
            }
        });
    }

    private void systemSpeak(String text) {
        if (systemTts == null || text == null) return;
        systemTts.setLanguage(Locale.forLanguageTag("tr-TR"));
        systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lakdoz-system-voice");
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
            public void onReadyForSpeech(Bundle p) { status.setText("◉ Dinliyorum…"); }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() { status.setText("◌ Düşünüyorum…"); }
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

    private Button smallButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setBackground(rounded(color, 12));
        return b;
    }

    private Button actionButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, 14));
        return b;
    }

    private Button dialogButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams dialogButtonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        p.setMargins(0, dp(6), 0, 0);
        return p;
    }

    private TextView tinyLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.rgb(111, 121, 139));
        v.setTextSize(9);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.08f);
        v.setPadding(dp(3), dp(4), 0, dp(4));
        return v;
    }

    private TextView sectionLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.DKGRAY);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(17);
        v.setPadding(0, dp(7), 0, dp(5));
        return v;
    }

    private TextView infoText(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(Color.GRAY);
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (systemTts != null) systemTts.shutdown();
        executor.shutdownNow();
        voiceExecutor.shutdownNow();
        super.onDestroy();
    }
}
