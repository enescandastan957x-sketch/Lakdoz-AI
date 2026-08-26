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
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private TextView status, currentTitle, listenCaption, listenPartial;
    private EditText input, searchInput;
    private ScrollView messageScroll;
    private LinearLayout messageList, conversationList, drawer;
    private FrameLayout root, listeningOverlay;
    private View scrim, pulseRing;
    private SpeechRecognizer recognizer;
    private TextToSpeech systemTts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor();
    private final Object speechLock = new Object();
    private final StringBuilder speechBuffer = new StringBuilder();

    private final int BG = Color.rgb(5, 9, 16);
    private final int PANEL = Color.rgb(12, 18, 29);
    private final int CARD = Color.rgb(18, 27, 41);
    private final int USER = Color.rgb(37, 82, 153);
    private final int ACCENT = Color.rgb(79, 132, 236);
    private final int TEXT = Color.rgb(241, 245, 251);
    private final int MUTED = Color.rgb(143, 155, 176);
    private final int GREEN = Color.rgb(101, 226, 191);

    private static final String[] VOICE_LABELS = {
            "Kadın • Yumuşak — Aoede", "Kadın • Net — Kore", "Kadın • Genç — Leda", "Kadın • Sakin — Callirrhoe",
            "Erkek • Enerjik — Puck", "Erkek • Bilgilendirici — Charon", "Erkek • Güçlü — Orus", "Erkek • Olgun — Gacrux",
            "Telefonun hızlı Türkçe sesi"
    };
    private static final String[] VOICE_NAMES = {"Aoede","Kore","Leda","Callirrhoe","Puck","Charon","Orus","Gacrux","SYSTEM"};
    private static final String[] VOICE_STYLES = {
            "Sıcak, yumuşak, doğal ve samimi bir Türkçe ile konuş.", "Net, kendinden emin, dengeli ve doğal bir Türkçe ile konuş.",
            "Genç, canlı, pozitif ve doğal bir Türkçe ile konuş.", "Sakin, rahatlatıcı, nazik ve doğal bir Türkçe ile konuş.",
            "Enerjik, neşeli, arkadaş canlısı ve doğal bir Türkçe ile konuş.", "Bilgilendirici, güven veren, sakin ve profesyonel bir Türkçe ile konuş.",
            "Güçlü, kararlı, net ve güven veren bir Türkçe ile konuş.", "Olgun, sıcak, sakin ve güven veren bir Türkçe ile konuş.", ""
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
        if (settings.getGeminiApiKey().isEmpty()) status.setText("● Gemini bağlantısı ayarlı değil");
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setId(android.R.id.content);
        root.setBackgroundColor(BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = iconButton("☰"); menu.setOnClickListener(v -> openDrawer());
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));

        ImageView avatar = mascot(dp(44));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44)); alp.setMargins(dp(9),0,dp(8),0); header.addView(avatar, alp);

        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        currentTitle = new TextView(this); currentTitle.setText("Yeni sohbet"); currentTitle.setTextColor(Color.WHITE); currentTitle.setTextSize(21); currentTitle.setTypeface(Typeface.DEFAULT_BOLD); currentTitle.setSingleLine(true); currentTitle.setEllipsize(TextUtils.TruncateAt.END); titles.addView(currentTitle);
        TextView version = new TextView(this); version.setText("Lakdoz 1.0 • Akıllı Hafıza • Hızlı Ses"); version.setTextColor(MUTED); version.setTextSize(11); titles.addView(version);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = iconButton("⚙"); settingsBtn.setOnClickListener(v -> showSettings()); header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(48),dp(46)));
        main.addView(header);

        status = new TextView(this); status.setText("● Hazır • Tüm sohbet hafızası açık"); status.setTextColor(GREEN); status.setTextSize(12); status.setPadding(dp(108),0,0,dp(7)); main.addView(status);

        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); messageScroll.setVerticalScrollBarEnabled(false);
        messageList = new LinearLayout(this); messageList.setOrientation(LinearLayout.VERTICAL); messageList.setPadding(0,dp(6),0,dp(14)); messageScroll.addView(messageList);
        main.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        LinearLayout composer = new LinearLayout(this); composer.setOrientation(LinearLayout.VERTICAL); composer.setPadding(dp(8),dp(8),dp(8),dp(8)); composer.setBackground(rounded(Color.rgb(13,20,31),22));
        input = new EditText(this); input.setHint("Lakdoz'a mesaj yaz…"); input.setHintTextColor(Color.rgb(105,119,140)); input.setTextColor(TEXT); input.setTextSize(16); input.setMinLines(2); input.setMaxLines(5); input.setPadding(dp(13),dp(10),dp(13),dp(10)); input.setBackground(rounded(CARD,17)); composer.addView(input);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0,dp(8),0,0);
        Button talk = actionButton("🎙 Konuş", Color.rgb(31,44,64)); Button send = actionButton("Gönder ➜", Color.rgb(48,94,178)); talk.setOnClickListener(v -> listen()); send.setOnClickListener(v -> submit(input.getText().toString()));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,dp(50),1); half.setMargins(dp(3),0,dp(3),0); actions.addView(talk,half); LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,dp(50),1); half2.setMargins(dp(3),0,dp(3),0); actions.addView(send,half2); composer.addView(actions); main.addView(composer);

        scrim = new View(this); scrim.setBackgroundColor(Color.argb(155,0,0,0)); scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer()); root.addView(scrim,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        drawer = buildDrawer(); drawer.setVisibility(View.GONE); int drawerWidth=Math.min(dp(330),(int)(getResources().getDisplayMetrics().widthPixels*0.88f)); root.addView(drawer,new FrameLayout.LayoutParams(drawerWidth,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.START));
        listeningOverlay = buildListeningOverlay(); listeningOverlay.setVisibility(View.GONE); root.addView(listeningOverlay,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout buildDrawer() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16),dp(16),dp(14),dp(14)); panel.setBackgroundColor(PANEL);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);ImageView cat=mascot(dp(48));top.addView(cat,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView brand=new TextView(this);brand.setText("  LAKDOZ");brand.setTextColor(Color.WHITE);brand.setTextSize(22);brand.setTypeface(Typeface.DEFAULT_BOLD);top.addView(brand,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));Button close=iconButton("×");close.setOnClickListener(v->closeDrawer());top.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(top);
        TextView memory = new TextView(this); memory.setText("🧠 Akıllı hafıza açık • Tüm sohbetler bağlı"); memory.setTextColor(Color.rgb(164,183,255)); memory.setTextSize(12); memory.setPadding(0,dp(10),0,dp(8)); panel.addView(memory);
        Button newChat=actionButton("＋ Yeni sohbet",ACCENT);newChat.setOnClickListener(v->{history.newConversation();if(searchInput!=null)searchInput.setText("");closeDrawer();refreshHistory();});LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50));nlp.setMargins(0,dp(4),0,dp(10));panel.addView(newChat,nlp);
        searchInput=new EditText(this);searchInput.setSingleLine(true);searchInput.setTextSize(15);searchInput.setHint("Sohbetlerde ara");searchInput.setHintTextColor(Color.rgb(108,118,137));searchInput.setTextColor(TEXT);searchInput.setPadding(dp(13),0,dp(10),0);searchInput.setBackground(rounded(Color.rgb(21,27,39),15));panel.addView(searchInput,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));searchInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){refreshConversationList(s==null?"":s.toString());}public void afterTextChanged(Editable e){}});
        TextView recent=new TextView(this);recent.setText("SON SOHBETLER");recent.setTextColor(Color.rgb(113,126,147));recent.setTextSize(11);recent.setTypeface(Typeface.DEFAULT_BOLD);recent.setPadding(dp(2),dp(16),0,dp(6));panel.addView(recent);
        ScrollView cs=new ScrollView(this);cs.setVerticalScrollBarEnabled(false);conversationList=new LinearLayout(this);conversationList.setOrientation(LinearLayout.VERTICAL);cs.addView(conversationList);panel.addView(cs,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));return panel;
    }

    private FrameLayout buildListeningOverlay() {
        FrameLayout overlay=new FrameLayout(this);overlay.setBackgroundColor(Color.rgb(5,10,20));
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);column.setPadding(dp(22),dp(70),dp(22),dp(30));overlay.addView(column,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title=new TextView(this);title.setText("Dinliyorum…");title.setTextColor(Color.WHITE);title.setTextSize(28);title.setTypeface(Typeface.DEFAULT_BOLD);title.setGravity(Gravity.CENTER);column.addView(title);
        TextView hint=new TextView(this);hint.setText("Konuşmaya başlayabilirsin");hint.setTextColor(MUTED);hint.setTextSize(15);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(6),0,dp(28));column.addView(hint);
        FrameLayout orb=new FrameLayout(this);GradientDrawable ringBg=new GradientDrawable();ringBg.setShape(GradientDrawable.OVAL);ringBg.setColor(Color.argb(28,60,130,255));ringBg.setStroke(dp(3),Color.rgb(79,176,255));orb.setBackground(ringBg);column.addView(orb,new LinearLayout.LayoutParams(dp(210),dp(210)));
        pulseRing=new View(this);GradientDrawable inner=new GradientDrawable();inner.setShape(GradientDrawable.OVAL);inner.setColor(Color.TRANSPARENT);inner.setStroke(dp(5),Color.rgb(127,92,255));pulseRing.setBackground(inner);FrameLayout.LayoutParams rlp=new FrameLayout.LayoutParams(dp(170),dp(170),Gravity.CENTER);orb.addView(pulseRing,rlp);
        ImageView cat=mascot(dp(132));FrameLayout.LayoutParams clp=new FrameLayout.LayoutParams(dp(132),dp(132),Gravity.CENTER);orb.addView(cat,clp);
        listenPartial=new TextView(this);listenPartial.setText("Seni duyduğum kelimeler burada görünecek…");listenPartial.setTextColor(Color.rgb(214,223,238));listenPartial.setTextSize(18);listenPartial.setGravity(Gravity.CENTER);listenPartial.setPadding(dp(12),dp(30),dp(12),0);column.addView(listenPartial);
        listenCaption=new TextView(this);listenCaption.setText("Canlı dinleme");listenCaption.setTextColor(GREEN);listenCaption.setTextSize(13);listenCaption.setGravity(Gravity.CENTER);listenCaption.setPadding(0,dp(10),0,dp(24));column.addView(listenCaption);
        Button cancel=actionButton("✕ Konuşmayı bitir",Color.rgb(29,42,62));cancel.setOnClickListener(v->{if(recognizer!=null)recognizer.cancel();hideListening();});column.addView(cancel,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));return overlay;
    }

    private ImageView mascot(int size){ImageView v=new ImageView(this);v.setImageResource(R.drawable.lakdoz_mascot);v.setScaleType(ImageView.ScaleType.CENTER_CROP);return v;}
    private void showListening(){listenPartial.setText("Seni dinliyorum…");listenCaption.setText("Canlı dinleme");listeningOverlay.setVisibility(View.VISIBLE);listeningOverlay.bringToFront();}
    private void hideListening(){listeningOverlay.setVisibility(View.GONE);if(pulseRing!=null){pulseRing.setScaleX(1f);pulseRing.setScaleY(1f);}}

    private void openDrawer(){refreshConversationList(searchInput==null?"":searchInput.getText().toString());scrim.setVisibility(View.VISIBLE);drawer.setVisibility(View.VISIBLE);drawer.bringToFront();}
    private void closeDrawer(){if(scrim!=null)scrim.setVisibility(View.GONE);if(drawer!=null)drawer.setVisibility(View.GONE);}

    private void refreshConversationList(String query){if(conversationList==null)return;conversationList.removeAllViews();String active=history.getActiveConversationId();for(HistoryStore.ConversationMeta c:history.listConversations(query)){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(10),dp(9));card.setBackground(rounded(c.id.equals(active)?Color.rgb(38,57,86):Color.TRANSPARENT,14));TextView t=new TextView(this);t.setText(c.title);t.setTextColor(TEXT);t.setTextSize(15);t.setTypeface(Typeface.DEFAULT_BOLD);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);card.addView(t);TextView p=new TextView(this);p.setText(c.preview==null?"":c.preview.replace('\n',' '));p.setTextColor(Color.rgb(113,124,143));p.setTextSize(12);p.setSingleLine(true);p.setEllipsize(TextUtils.TruncateAt.END);p.setPadding(0,dp(3),0,0);card.addView(p);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(66));lp.setMargins(0,dp(3),0,dp(3));conversationList.addView(card,lp);card.setOnClickListener(v->{history.switchConversation(c.id);closeDrawer();refreshHistory();});card.setOnLongClickListener(v->{history.switchConversation(c.id);new AlertDialog.Builder(this).setTitle("Sohbeti sil").setMessage("“"+c.title+"” silinsin mi?").setPositiveButton("Sil",(d,w)->{history.deleteActiveConversation();refreshHistory();}).setNegativeButton("İptal",null).show();return true;});}}

    private void refreshHistory(){if(messageList==null)return;currentTitle.setText(history.getActiveTitle());messageList.removeAllViews();List<HistoryStore.Turn> turns=history.load();if(turns.isEmpty())addWelcome();else for(HistoryStore.Turn t:turns)addBubble(t.role,t.text);if(conversationList!=null)refreshConversationList(searchInput==null?"":searchInput.getText().toString());scrollBottom();}

    private void addWelcome(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(dp(18),dp(22),dp(18),dp(22));box.setBackground(rounded(Color.rgb(13,19,30),22));ImageView cat=mascot(dp(115));box.addView(cat,new LinearLayout.LayoutParams(dp(115),dp(115)));LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(16),0,0,0);TextView w=new TextView(this);w.setText("Merhaba! 👋\nBen Lakdoz.");w.setTextColor(TEXT);w.setTextSize(23);w.setTypeface(Typeface.DEFAULT_BOLD);texts.addView(w);TextView mem=new TextView(this);mem.setText("Önceki sohbetlerini hatırlayıp ilgili yerden devam edebilirim.");mem.setTextColor(MUTED);mem.setTextSize(14);mem.setPadding(0,dp(7),0,0);texts.addView(mem);box.addView(texts,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.setMargins(0,dp(20),0,0);messageList.addView(box,lp);}

    private TextView addBubble(String role,String text){boolean user="user".equals(role);LinearLayout row=new LinearLayout(this);row.setGravity(user?Gravity.END:Gravity.START);row.setPadding(0,dp(5),0,dp(5));if(!user){ImageView cat=mascot(dp(38));LinearLayout.LayoutParams avlp=new LinearLayout.LayoutParams(dp(38),dp(38));avlp.setMargins(0,dp(4),dp(7),0);row.addView(cat,avlp);}LinearLayout bubble=new LinearLayout(this);bubble.setOrientation(LinearLayout.VERTICAL);bubble.setPadding(dp(14),dp(10),dp(14),dp(11));bubble.setBackground(rounded(user?USER:CARD,18));TextView who=new TextView(this);who.setText(user?"SEN":"LAKDOZ");who.setTextSize(11);who.setTypeface(Typeface.DEFAULT_BOLD);who.setTextColor(user?Color.rgb(190,215,255):GREEN);bubble.addView(who);TextView body=new TextView(this);body.setText(text);body.setTextColor(TEXT);body.setTextSize(17);body.setLineSpacing(0,1.12f);body.setPadding(0,dp(4),0,0);bubble.addView(body);int maxWidth=(int)(getResources().getDisplayMetrics().widthPixels*(user?0.82f:0.78f));row.addView(bubble,new LinearLayout.LayoutParams(maxWidth,ViewGroup.LayoutParams.WRAP_CONTENT));messageList.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return body;}

    private void submit(String text){final String q=text==null?"":text.trim();if(q.isEmpty())return;if(settings.getGeminiApiKey().isEmpty()){Toast.makeText(this,"Önce Ayarlar'dan Gemini API anahtarını ekle.",Toast.LENGTH_LONG).show();showSettings();return;}input.setText("");List<HistoryStore.Turn> before=history.load();history.add("user",q);refreshHistory();final TextView streaming=addBubble("assistant","");scrollBottom();status.setText("● Lakdoz yazıyor…");synchronized(speechLock){speechBuffer.setLength(0);}executor.execute(()->{try{LocalCommandRouter.Result local=new LocalCommandRouter(getApplicationContext()).tryHandle(q);String answer;if(local.handled){answer=local.response;runOnUiThread(()->streaming.setText(local.response));queueVoiceChunk(local.response);}else{answer=new AiClient(getApplicationContext()).askStreaming(q,before,delta->{runOnUiThread(()->{streaming.append(delta);scrollBottom();});consumeSpeechDelta(delta,false);});consumeSpeechDelta("",true);}history.add("assistant",answer);runOnUiThread(()->{status.setText("● Hazır • Hafıza güncellendi");refreshHistory();});}catch(Exception e){final String err=friendlyError(e);history.add("assistant",err);runOnUiThread(()->{status.setText("● Hazır");refreshHistory();});}});}

    private void consumeSpeechDelta(String delta,boolean flush){String ready=null;synchronized(speechLock){speechBuffer.append(delta);int cut=findSpeechCut(speechBuffer);if(flush&&speechBuffer.length()>0)cut=speechBuffer.length();if(cut>0){ready=speechBuffer.substring(0,cut).trim();speechBuffer.delete(0,cut);}}if(ready!=null&&!ready.isEmpty())queueVoiceChunk(ready);}
    private int findSpeechCut(StringBuilder b){if(b.length()<35)return 0;int max=Math.min(b.length(),130);for(int i=35;i<max;i++){char c=b.charAt(i);if(c=='.'||c=='!'||c=='?'||c=='\n')return i+1;}return b.length()>=80?80:0;}
    private void queueVoiceChunk(String text){if(text==null||text.trim().isEmpty())return;final String chunk=text.trim();if(!settings.useGeminiVoice()){runOnUiThread(()->speakSystemQueued(chunk));return;}voiceExecutor.execute(()->{try{new GeminiTtsClient(getApplicationContext()).speak(chunk);}catch(Exception e){runOnUiThread(()->speakSystemQueued(chunk));}});}

    private String friendlyError(Exception e){String m=e.getMessage()==null?"":e.getMessage();if(m.toLowerCase().contains("timeout")||m.toLowerCase().contains("timed out"))return"Bağlantı zaman aşımına uğradı. Tekrar dene.";if(m.contains("HTTP 503"))return"Gemini şu anda yoğun. Biraz sonra tekrar dene.";if(m.contains("HTTP 429"))return"Gemini kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";return"Bağlantı başarısız: "+(m.isEmpty()?"Gemini servisine ulaşılamadı.":m);}

    private void showSettings(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(8));TextView mode=new TextView(this);mode.setText("🧠 Tüm sohbet hafızası açık\n⚡ Streaming yanıt + erken ses başlatma");mode.setTextColor(Color.DKGRAY);mode.setTextSize(14);mode.setPadding(0,0,0,dp(12));box.addView(mode);EditText key=new EditText(this);key.setHint(settings.getGeminiApiKey().isEmpty()?"Gemini API anahtarı":"Gemini anahtarı kayıtlı • değiştirmek için yenisini gir");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(key);TextView voiceTitle=new TextView(this);voiceTitle.setText("Lakdoz sesi");voiceTitle.setTypeface(Typeface.DEFAULT_BOLD);voiceTitle.setTextSize(16);voiceTitle.setPadding(0,dp(16),0,dp(5));box.addView(voiceTitle);Spinner voices=new Spinner(this);ArrayAdapter<String> va=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,VOICE_LABELS);va.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);voices.setAdapter(va);voices.setSelection(currentVoiceIndex());box.addView(voices);Button preview=new Button(this);preview.setText("Seçili sesi dene");box.addView(preview);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Lakdoz 1.0 Ayarları").setView(box).setPositiveButton("Kaydet",null).setNeutralButton("Bağlantıyı test et",null).setNegativeButton("İptal",null).create();dialog.setOnShowListener(x->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);saveVoice(voices.getSelectedItemPosition());dialog.dismiss();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);}catch(Exception e){return;}status.setText("● Bağlantı test ediliyor…");executor.execute(()->{try{new AiClient(getApplicationContext()).testConnection();runOnUiThread(()->{status.setText("● Gemini bağlantısı başarılı");Toast.makeText(this,"Bağlantı başarılı",Toast.LENGTH_SHORT).show();});}catch(Exception e){runOnUiThread(()->status.setText("● "+friendlyError(e)));}});});preview.setOnClickListener(v->{saveVoice(voices.getSelectedItemPosition());queueVoiceChunk("Merhaba. Ben Lakdoz. Bu ses profilini seçtin.");});});dialog.show();}

    private int currentVoiceIndex(){if(!settings.useGeminiVoice())return VOICE_NAMES.length-1;String v=settings.getGeminiVoice();for(int i=0;i<VOICE_NAMES.length-1;i++)if(VOICE_NAMES[i].equals(v))return i;return 1;}
    private void saveVoice(int i){i=Math.max(0,Math.min(i,VOICE_NAMES.length-1));if("SYSTEM".equals(VOICE_NAMES[i]))settings.setUseGeminiVoice(false);else{settings.setUseGeminiVoice(true);settings.setGeminiVoice(VOICE_NAMES[i]);settings.setGeminiVoiceStyle(VOICE_STYLES[i]);}}
    private void initSystemTts(){systemTts=new TextToSpeech(this,s->{if(s==TextToSpeech.SUCCESS){systemTts.setLanguage(Locale.forLanguageTag("tr-TR"));systemTts.setSpeechRate(settings.getSpeechRate());systemTts.setPitch(settings.getSpeechPitch());}});}
    private void speakSystemQueued(String text){if(systemTts!=null)systemTts.speak(text,TextToSpeech.QUEUE_ADD,null,"lakdoz-"+System.nanoTime());}

    private void ensureMic(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}
    private void listen(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ensureMic();return;}showListening();if(recognizer!=null)recognizer.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer.setRecognitionListener(new RecognitionListener(){public void onReadyForSpeech(Bundle p){status.setText("● Dinliyorum…");listenCaption.setText("Canlı dinleme aktif");}public void onBeginningOfSpeech(){listenCaption.setText("Seni duyuyorum…");}public void onRmsChanged(float v){float s=1f+Math.max(0f,Math.min(0.18f,(v+2f)/55f));if(pulseRing!=null){pulseRing.setScaleX(s);pulseRing.setScaleY(s);}}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){listenCaption.setText("Anlıyorum…");status.setText("● Anlıyorum…");}public void onError(int e){hideListening();status.setText("● Ses algılama hatası");}public void onResults(Bundle b){hideListening();ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty())submit(xs.get(0));}public void onPartialResults(Bundle b){ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty())listenPartial.setText(xs.get(0));}public void onEvent(int t,Bundle b){}});Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR");i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);recognizer.startListening(i);}

    private Button iconButton(String text){Button b=new Button(this);b.setText(text);b.setTextSize(20);b.setTextColor(TEXT);b.setPadding(0,0,0,0);b.setBackground(rounded(Color.rgb(24,33,48),14));return b;}
    private Button actionButton(String text,int color){Button b=new Button(this);b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setAllCaps(false);b.setBackground(rounded(color,16));return b;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density);}
    private void scrollBottom(){if(messageScroll!=null)messageScroll.post(()->messageScroll.fullScroll(View.FOCUS_DOWN));}
    private void applyInsets(){View content=findViewById(android.R.id.content);if(content==null)return;content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});}
    @Override public void onBackPressed(){if(listeningOverlay!=null&&listeningOverlay.getVisibility()==View.VISIBLE){if(recognizer!=null)recognizer.cancel();hideListening();return;}if(drawer!=null&&drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}super.onBackPressed();}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(systemTts!=null)systemTts.shutdown();executor.shutdownNow();voiceExecutor.shutdownNow();super.onDestroy();}
}
