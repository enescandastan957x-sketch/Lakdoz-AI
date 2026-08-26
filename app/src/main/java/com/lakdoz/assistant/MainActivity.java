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
    private TextView status, currentTitle, listenCaption, listenPartial, voiceAnswer;
    private EditText input, searchInput;
    private ScrollView messageScroll;
    private LinearLayout messageList, conversationList, drawer;
    private FrameLayout root, listeningOverlay;
    private View scrim, pulseRing;
    private Button voiceSoundButton, repeatListenButton;
    private SpeechRecognizer recognizer;
    private TextToSpeech systemTts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor();
    private final Object speechLock = new Object();
    private final StringBuilder speechBuffer = new StringBuilder();

    private final int BG = Color.rgb(5, 13, 24);
    private final int PANEL = Color.rgb(9, 22, 38);
    private final int CARD = Color.rgb(15, 31, 50);
    private final int USER = Color.rgb(24, 83, 142);
    private final int ACCENT = Color.rgb(50, 166, 207);
    private final int PURPLE = Color.rgb(126, 91, 239);
    private final int TEXT = Color.rgb(240, 246, 252);
    private final int MUTED = Color.rgb(145, 162, 184);
    private final int GREEN = Color.rgb(76, 222, 183);

    private static final String[] VOICE_LABELS = {
            "Kadın • Yumuşak — Aoede", "Kadın • Net — Kore", "Kadın • Genç — Leda", "Kadın • Sakin — Callirrhoe",
            "Erkek • Enerjik — Puck", "Erkek • Bilgilendirici — Charon", "Erkek • Güçlü — Orus", "Erkek • Olgun — Gacrux",
            "Telefonun hızlı Türkçe sesi"
    };
    private static final String[] VOICE_NAMES = {"Aoede","Kore","Leda","Callirrhoe","Puck","Charon","Orus","Gacrux","SYSTEM"};
    private static final String[] VOICE_STYLES = {
            "Sıcak, yumuşak, doğal ve samimi bir Türkçe ile konuş.", "Net, kendinden emin ve doğal bir Türkçe ile konuş.",
            "Genç, canlı, pozitif ve doğal bir Türkçe ile konuş.", "Sakin, nazik ve doğal bir Türkçe ile konuş.",
            "Enerjik, arkadaş canlısı ve doğal bir Türkçe ile konuş.", "Bilgilendirici, güven veren ve doğal bir Türkçe ile konuş.",
            "Güçlü, kararlı ve net bir Türkçe ile konuş.", "Olgun, sıcak ve sakin bir Türkçe ile konuş.", ""
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
        main.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = iconButton("☰"); menu.setOnClickListener(v -> openDrawer());
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));
        ImageView avatar = mascot();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(46), dp(46)); alp.setMargins(dp(9),0,dp(9),0); header.addView(avatar, alp);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        currentTitle = text("Yeni sohbet", 21, TEXT, true); currentTitle.setSingleLine(true); currentTitle.setEllipsize(TextUtils.TruncateAt.END); titles.addView(currentTitle);
        TextView version = text("Lakdoz 1.0.2 • Akıllı Hafıza • Canlı Ses", 11, MUTED, false); titles.addView(version);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsBtn = iconButton("⚙"); settingsBtn.setOnClickListener(v -> showSettings()); header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(48),dp(46)));
        main.addView(header);

        status = text("● Hazır • " + history.conversationCount() + " sohbet hafızada", 12, GREEN, false);
        status.setPadding(dp(112),0,0,dp(8)); main.addView(status);

        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); messageScroll.setVerticalScrollBarEnabled(false);
        messageList = new LinearLayout(this); messageList.setOrientation(LinearLayout.VERTICAL); messageList.setPadding(0,dp(5),0,dp(12)); messageScroll.addView(messageList);
        main.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        LinearLayout composer = new LinearLayout(this); composer.setOrientation(LinearLayout.VERTICAL); composer.setPadding(dp(8),dp(8),dp(8),dp(8)); composer.setBackground(gradient(new int[]{Color.rgb(12,28,45),Color.rgb(17,26,48)},22));
        input = new EditText(this); input.setHint("Lakdoz'a mesaj yaz…"); input.setHintTextColor(Color.rgb(107,126,151)); input.setTextColor(TEXT); input.setTextSize(16); input.setMinLines(2); input.setMaxLines(5); input.setPadding(dp(13),dp(10),dp(13),dp(10)); input.setBackground(rounded(CARD,17)); composer.addView(input);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0,dp(8),0,0);
        Button talk = actionButton("🎙  Konuş", Color.rgb(28,47,71)); Button send = actionButton("Gönder  ➜", Color.rgb(34,111,184));
        talk.setOnClickListener(v -> listen()); send.setOnClickListener(v -> submit(input.getText().toString(), false));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,dp(50),1); half.setMargins(dp(3),0,dp(3),0); actions.addView(talk,half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,dp(50),1); half2.setMargins(dp(3),0,dp(3),0); actions.addView(send,half2); composer.addView(actions); main.addView(composer);

        scrim = new View(this); scrim.setBackgroundColor(Color.argb(165,0,0,0)); scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer()); root.addView(scrim,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        drawer = buildDrawer(); drawer.setVisibility(View.GONE); int drawerWidth=Math.min(dp(330),(int)(getResources().getDisplayMetrics().widthPixels*0.88f)); root.addView(drawer,new FrameLayout.LayoutParams(drawerWidth,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.START));
        listeningOverlay = buildListeningOverlay(); listeningOverlay.setVisibility(View.GONE); root.addView(listeningOverlay,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout buildDrawer() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16),dp(16),dp(14),dp(14)); panel.setBackground(gradient(new int[]{Color.rgb(10,25,42),Color.rgb(8,17,31)},0));
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); ImageView cat=mascot(); top.addView(cat,new LinearLayout.LayoutParams(dp(52),dp(52))); TextView brand=text("  LAKDOZ",22,TEXT,true); top.addView(brand,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); Button close=iconButton("×"); close.setOnClickListener(v->closeDrawer()); top.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44))); panel.addView(top);
        TextView memory=text("🧠 Ortak hafıza aktif • " + history.conversationCount() + " sohbet bağlı",12,Color.rgb(164,194,255),false); memory.setPadding(0,dp(10),0,dp(9)); panel.addView(memory);
        Button newChat=actionButton("＋  Yeni sohbet",Color.rgb(39,116,190)); newChat.setOnClickListener(v->{history.newConversation();if(searchInput!=null)searchInput.setText("");closeDrawer();refreshHistory();}); LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50)); nlp.setMargins(0,dp(4),0,dp(10)); panel.addView(newChat,nlp);
        searchInput=new EditText(this); searchInput.setSingleLine(true); searchInput.setTextSize(15); searchInput.setHint("Tüm sohbetlerde ara"); searchInput.setHintTextColor(Color.rgb(108,126,151)); searchInput.setTextColor(TEXT); searchInput.setPadding(dp(13),0,dp(10),0); searchInput.setBackground(rounded(Color.rgb(18,34,53),15)); panel.addView(searchInput,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        searchInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){refreshConversationList(s==null?"":s.toString());} public void afterTextChanged(Editable e){}});
        TextView recent=text("SON SOHBETLER",11,Color.rgb(114,137,166),true); recent.setPadding(dp(2),dp(16),0,dp(6)); panel.addView(recent);
        ScrollView cs=new ScrollView(this); cs.setVerticalScrollBarEnabled(false); conversationList=new LinearLayout(this); conversationList.setOrientation(LinearLayout.VERTICAL); cs.addView(conversationList); panel.addView(cs,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1)); return panel;
    }

    private FrameLayout buildListeningOverlay() {
        FrameLayout overlay=new FrameLayout(this); overlay.setBackground(gradient(new int[]{Color.rgb(4,12,24),Color.rgb(8,20,37),Color.rgb(10,11,31)},0));
        LinearLayout column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER_HORIZONTAL); column.setPadding(dp(22),dp(42),dp(22),dp(24)); overlay.addView(column,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title=text("Lakdoz Sesli Asistan",24,TEXT,true); title.setGravity(Gravity.CENTER); column.addView(title);
        listenCaption=text("Dinliyorum…",16,GREEN,true); listenCaption.setGravity(Gravity.CENTER); listenCaption.setPadding(0,dp(7),0,dp(18)); column.addView(listenCaption);

        FrameLayout orb=new FrameLayout(this); GradientDrawable ringBg=new GradientDrawable(); ringBg.setShape(GradientDrawable.OVAL); ringBg.setColor(Color.rgb(11,29,52)); ringBg.setStroke(dp(3),Color.rgb(46,180,226)); orb.setBackground(ringBg); column.addView(orb,new LinearLayout.LayoutParams(dp(230),dp(230)));
        pulseRing=new View(this); GradientDrawable inner=new GradientDrawable(); inner.setShape(GradientDrawable.OVAL); inner.setColor(Color.TRANSPARENT); inner.setStroke(dp(6),PURPLE); pulseRing.setBackground(inner); orb.addView(pulseRing,new FrameLayout.LayoutParams(dp(190),dp(190),Gravity.CENTER));
        ImageView cat=mascot(); orb.addView(cat,new FrameLayout.LayoutParams(dp(155),dp(155),Gravity.CENTER));

        listenPartial=text("Konuşmaya başlayabilirsin…",17,Color.rgb(205,219,237),false); listenPartial.setGravity(Gravity.CENTER); listenPartial.setPadding(dp(12),dp(22),dp(12),dp(10)); column.addView(listenPartial);
        voiceAnswer=text("",18,TEXT,false); voiceAnswer.setLineSpacing(0,1.12f); voiceAnswer.setPadding(dp(15),dp(13),dp(15),dp(13)); voiceAnswer.setBackground(gradient(new int[]{Color.rgb(15,39,61),Color.rgb(27,29,60)},18)); voiceAnswer.setVisibility(View.GONE); column.addView(voiceAnswer,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls=new LinearLayout(this); controls.setPadding(0,dp(14),0,dp(10));
        voiceSoundButton=actionButton(settings.isSoundEnabled()?"🔊  Ses açık":"🔇  Ses kapalı",Color.rgb(25,53,78)); voiceSoundButton.setOnClickListener(v->{settings.setSoundEnabled(!settings.isSoundEnabled());updateSoundButton();if(!settings.isSoundEnabled()&&systemTts!=null)systemTts.stop();});
        repeatListenButton=actionButton("🎙  Tekrar konuş",Color.rgb(42,111,168)); repeatListenButton.setOnClickListener(v->listen());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(50),1); cp.setMargins(dp(3),0,dp(3),0); controls.addView(voiceSoundButton,cp); LinearLayout.LayoutParams cp2=new LinearLayout.LayoutParams(0,dp(50),1); cp2.setMargins(dp(3),0,dp(3),0); controls.addView(repeatListenButton,cp2); column.addView(controls,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        Button close=actionButton("✕  Sesli modu kapat",Color.rgb(29,43,62)); close.setOnClickListener(v->{if(recognizer!=null)recognizer.cancel();hideListening();}); column.addView(close,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
        return overlay;
    }

    private ImageView mascot(){ ImageView v=new ImageView(this); v.setImageResource(R.drawable.lakdoz_mascot); v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setAdjustViewBounds(true); return v; }
    private void showListening(){ if(listeningOverlay==null)return; voiceAnswer.setText(""); voiceAnswer.setVisibility(View.GONE); listenPartial.setText("Konuşmaya başlayabilirsin…"); listenCaption.setText("Dinliyorum…"); listeningOverlay.setVisibility(View.VISIBLE); listeningOverlay.bringToFront(); updateSoundButton(); }
    private void hideListening(){ if(listeningOverlay!=null)listeningOverlay.setVisibility(View.GONE); if(pulseRing!=null){pulseRing.setScaleX(1f);pulseRing.setScaleY(1f);} }
    private void updateSoundButton(){ if(voiceSoundButton!=null)voiceSoundButton.setText(settings.isSoundEnabled()?"🔊  Ses açık":"🔇  Ses kapalı"); }

    private void openDrawer(){refreshConversationList(searchInput==null?"":searchInput.getText().toString());scrim.setVisibility(View.VISIBLE);drawer.setVisibility(View.VISIBLE);drawer.bringToFront();}
    private void closeDrawer(){if(scrim!=null)scrim.setVisibility(View.GONE);if(drawer!=null)drawer.setVisibility(View.GONE);}

    private void refreshConversationList(String query){
        if(conversationList==null)return; conversationList.removeAllViews(); String active=history.getActiveConversationId();
        for(HistoryStore.ConversationMeta c:history.listConversations(query)){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(10),dp(10),dp(9)); card.setBackground(rounded(c.id.equals(active)?Color.rgb(28,61,91):Color.TRANSPARENT,14));
            TextView t=text(c.title,15,TEXT,true); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); card.addView(t);
            TextView p=text(c.preview==null?"":c.preview.replace('\n',' '),12,Color.rgb(118,139,166),false); p.setSingleLine(true); p.setEllipsize(TextUtils.TruncateAt.END); p.setPadding(0,dp(3),0,0); card.addView(p);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(66)); lp.setMargins(0,dp(3),0,dp(3)); conversationList.addView(card,lp);
            card.setOnClickListener(v->{history.switchConversation(c.id);closeDrawer();refreshHistory();});
            card.setOnLongClickListener(v->{history.switchConversation(c.id);new AlertDialog.Builder(this).setTitle("Sohbeti sil").setMessage("“"+c.title+"” silinsin mi?").setPositiveButton("Sil",(d,w)->{history.deleteActiveConversation();refreshHistory();}).setNegativeButton("İptal",null).show();return true;});
        }
    }

    private void refreshHistory(){ if(messageList==null)return; currentTitle.setText(history.getActiveTitle()); messageList.removeAllViews(); List<HistoryStore.Turn> turns=history.load(); if(turns.isEmpty())addWelcome(); else for(HistoryStore.Turn t:turns)addBubble(t.role,t.text); if(conversationList!=null)refreshConversationList(searchInput==null?"":searchInput.getText().toString()); status.setText("● Hazır • " + history.conversationCount() + " sohbet hafızada"); scrollBottom(); }

    private void addWelcome(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); box.setPadding(dp(14),dp(18),dp(14),dp(18)); box.setBackground(gradient(new int[]{Color.rgb(13,32,50),Color.rgb(20,24,49)},22));
        ImageView cat=mascot(); box.addView(cat,new LinearLayout.LayoutParams(dp(125),dp(125)));
        LinearLayout texts=new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(dp(15),0,0,0); TextView w=text("Merhaba! 👋\nBen Lakdoz.",23,TEXT,true); texts.addView(w); TextView mem=text("Eski sohbetlerini de tarayıp kaldığımız yerden devam edebilirim.",14,Color.rgb(164,184,208),false); mem.setPadding(0,dp(7),0,0); texts.addView(mem); box.addView(texts,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0,dp(18),0,0); messageList.addView(box,lp);
    }

    private TextView addBubble(String role,String value){
        boolean user="user".equals(role); LinearLayout row=new LinearLayout(this); row.setGravity(user?Gravity.END:Gravity.START); row.setPadding(0,dp(5),0,dp(5));
        if(!user){ImageView cat=mascot();LinearLayout.LayoutParams av=new LinearLayout.LayoutParams(dp(40),dp(40));av.setMargins(0,dp(4),dp(7),0);row.addView(cat,av);}
        LinearLayout bubble=new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL); bubble.setPadding(dp(14),dp(10),dp(14),dp(11)); bubble.setBackground(user?gradient(new int[]{Color.rgb(25,91,151),Color.rgb(37,66,139)},18):gradient(new int[]{Color.rgb(15,34,53),Color.rgb(19,27,48)},18));
        TextView who=text(user?"SEN":"LAKDOZ",11,user?Color.rgb(196,224,255):GREEN,true); bubble.addView(who); TextView body=text(value,17,TEXT,false); body.setLineSpacing(0,1.12f); body.setPadding(0,dp(4),0,0); bubble.addView(body);
        int maxWidth=(int)(getResources().getDisplayMetrics().widthPixels*(user?0.82f:0.77f)); row.addView(bubble,new LinearLayout.LayoutParams(maxWidth,ViewGroup.LayoutParams.WRAP_CONTENT)); messageList.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); return body;
    }

    private void submit(String text, boolean voiceMode){
        final String q=text==null?"":text.trim(); if(q.isEmpty())return; if(settings.getGeminiApiKey().isEmpty()){Toast.makeText(this,"Önce Ayarlar'dan Gemini API anahtarını ekle.",Toast.LENGTH_LONG).show();showSettings();return;}
        List<HistoryStore.Turn> before=history.load(); input.setText(""); history.add("user",q); refreshHistory(); final TextView streaming=addBubble("assistant",""); scrollBottom(); status.setText("● Lakdoz yanıtlıyor…");
        if(voiceMode){ listeningOverlay.setVisibility(View.VISIBLE); listeningOverlay.bringToFront(); listenPartial.setText("Sen: " + q); listenCaption.setText("Lakdoz düşünüyor…"); voiceAnswer.setText(""); voiceAnswer.setVisibility(View.VISIBLE); }
        synchronized(speechLock){speechBuffer.setLength(0);}
        executor.execute(()->{
            try{
                LocalCommandRouter.Result local=new LocalCommandRouter(getApplicationContext()).tryHandle(q); String answer;
                if(local.handled){ answer=local.response; runOnUiThread(()->{streaming.setText(local.response);if(voiceMode)voiceAnswer.setText(local.response);}); queueVoiceChunk(local.response); }
                else{
                    answer=new AiClient(getApplicationContext()).askStreaming(q,before,delta->{ runOnUiThread(()->{streaming.append(delta); if(voiceMode){voiceAnswer.append(delta);listenCaption.setText(settings.isSoundEnabled()?"Lakdoz yanıtlıyor ve konuşuyor…":"Lakdoz yanıtlıyor…");} scrollBottom();}); consumeSpeechDelta(delta,false); });
                    consumeSpeechDelta("",true);
                }
                history.add("assistant",answer);
                runOnUiThread(()->{ status.setText("● Hazır • Hafıza güncellendi"); refreshHistory(); if(voiceMode){listeningOverlay.setVisibility(View.VISIBLE);listeningOverlay.bringToFront();listenCaption.setText("Cevap hazır • tekrar konuşabilirsin");} });
            }catch(Exception e){ final String err=friendlyError(e); history.add("assistant",err); runOnUiThread(()->{status.setText("● Hazır");refreshHistory();if(voiceMode){listeningOverlay.setVisibility(View.VISIBLE);listeningOverlay.bringToFront();voiceAnswer.setVisibility(View.VISIBLE);voiceAnswer.setText(err);listenCaption.setText("Bir sorun oluştu");}}); }
        });
    }

    private void consumeSpeechDelta(String delta,boolean flush){
        if(!settings.isSoundEnabled())return; String ready=null; synchronized(speechLock){speechBuffer.append(delta);int cut=findSpeechCut(speechBuffer);if(flush&&speechBuffer.length()>0)cut=speechBuffer.length();if(cut>0){ready=speechBuffer.substring(0,cut).trim();speechBuffer.delete(0,cut);}} if(ready!=null&&!ready.isEmpty())queueVoiceChunk(ready);
    }
    private int findSpeechCut(StringBuilder b){ if(b.length()<18)return 0; int max=Math.min(b.length(),85); for(int i=18;i<max;i++){char c=b.charAt(i);if(c=='.'||c=='!'||c=='?'||c=='\n'||c==';')return i+1;} return b.length()>=48?48:0; }
    private void queueVoiceChunk(String text){ if(!settings.isSoundEnabled()||text==null||text.trim().isEmpty())return; final String chunk=text.trim(); if(!settings.useGeminiVoice()){runOnUiThread(()->speakSystemQueued(chunk));return;} voiceExecutor.execute(()->{try{new GeminiTtsClient(getApplicationContext()).speak(chunk);}catch(Exception e){runOnUiThread(()->speakSystemQueued(chunk));}}); }

    private String friendlyError(Exception e){String m=e.getMessage()==null?"":e.getMessage();if(m.toLowerCase().contains("timeout")||m.toLowerCase().contains("timed out"))return"Bağlantı zaman aşımına uğradı. Tekrar dene.";if(m.contains("HTTP 503"))return"Gemini şu anda yoğun. Biraz sonra tekrar dene.";if(m.contains("HTTP 429"))return"Gemini kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";return"Bağlantı başarısız: "+(m.isEmpty()?"Gemini servisine ulaşılamadı.":m);}

    private void showSettings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView mode=text("🧠 Ortak hafıza: " + history.conversationCount() + " sohbet\n⚡ Streaming yanıt + erken ses",14,Color.DKGRAY,false); mode.setPadding(0,0,0,dp(12));box.addView(mode);
        EditText key=new EditText(this);key.setHint(settings.getGeminiApiKey().isEmpty()?"Gemini API anahtarı":"Gemini anahtarı kayıtlı • değiştirmek için yenisini gir");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(key);
        TextView voiceTitle=text("Lakdoz insan sesi",16,Color.DKGRAY,true); voiceTitle.setPadding(0,dp(16),0,dp(5));box.addView(voiceTitle);
        Spinner voices=new Spinner(this);ArrayAdapter<String> va=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,VOICE_LABELS);va.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);voices.setAdapter(va);voices.setSelection(currentVoiceIndex());box.addView(voices);
        Button sound=new Button(this);sound.setText(settings.isSoundEnabled()?"🔊 Ses açık — kapat":"🔇 Ses kapalı — aç");sound.setOnClickListener(v->{settings.setSoundEnabled(!settings.isSoundEnabled());sound.setText(settings.isSoundEnabled()?"🔊 Ses açık — kapat":"🔇 Ses kapalı — aç");});box.addView(sound);
        Button preview=new Button(this);preview.setText("Seçili sesi dene");box.addView(preview);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Lakdoz 1.0.2 Ayarları").setView(box).setPositiveButton("Kaydet",null).setNeutralButton("Bağlantıyı test et",null).setNegativeButton("İptal",null).create();
        dialog.setOnShowListener(x->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);saveVoice(voices.getSelectedItemPosition());dialog.dismiss();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);}catch(Exception e){return;}status.setText("● Bağlantı test ediliyor…");executor.execute(()->{try{new AiClient(getApplicationContext()).testConnection();runOnUiThread(()->{status.setText("● Gemini bağlantısı başarılı");Toast.makeText(this,"Bağlantı başarılı",Toast.LENGTH_SHORT).show();});}catch(Exception e){runOnUiThread(()->status.setText("● "+friendlyError(e)));}});});
            preview.setOnClickListener(v->{saveVoice(voices.getSelectedItemPosition());settings.setSoundEnabled(true);updateSoundButton();queueVoiceChunk("Merhaba. Ben Lakdoz. Bu ses profilini seçtin.");});
        });dialog.show();
    }

    private int currentVoiceIndex(){if(!settings.useGeminiVoice())return VOICE_NAMES.length-1;String v=settings.getGeminiVoice();for(int i=0;i<VOICE_NAMES.length-1;i++)if(VOICE_NAMES[i].equals(v))return i;return 1;}
    private void saveVoice(int i){i=Math.max(0,Math.min(i,VOICE_NAMES.length-1));if("SYSTEM".equals(VOICE_NAMES[i]))settings.setUseGeminiVoice(false);else{settings.setUseGeminiVoice(true);settings.setGeminiVoice(VOICE_NAMES[i]);settings.setGeminiVoiceStyle(VOICE_STYLES[i]);}}
    private void initSystemTts(){systemTts=new TextToSpeech(this,s->{if(s==TextToSpeech.SUCCESS){systemTts.setLanguage(Locale.forLanguageTag("tr-TR"));systemTts.setSpeechRate(settings.getSpeechRate());systemTts.setPitch(settings.getSpeechPitch());}});}
    private void speakSystemQueued(String text){if(settings.isSoundEnabled()&&systemTts!=null)systemTts.speak(text,TextToSpeech.QUEUE_ADD,null,"lakdoz-"+System.nanoTime());}

    private void ensureMic(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}
    private void listen(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ensureMic();return;} showListening(); if(recognizer!=null)recognizer.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle p){status.setText("● Dinliyorum…");listenCaption.setText("Dinliyorum…");}
            public void onBeginningOfSpeech(){listenCaption.setText("Seni duyuyorum…");}
            public void onRmsChanged(float v){float s=1f+Math.max(0f,Math.min(0.20f,(v+2f)/50f));if(pulseRing!=null){pulseRing.setScaleX(s);pulseRing.setScaleY(s);}}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){listenCaption.setText("Anlıyorum…");status.setText("● Anlıyorum…");}
            public void onError(int e){listenCaption.setText("Seni anlayamadım • tekrar deneyebilirsin");status.setText("● Hazır");}
            public void onResults(Bundle b){ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty()){String q=xs.get(0);listenPartial.setText("Sen: "+q);listenCaption.setText("Lakdoz düşünüyor…");submit(q,true);}else listenCaption.setText("Bir şey duyamadım • tekrar konuş");}
            public void onPartialResults(Bundle b){ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty())listenPartial.setText(xs.get(0));}
            public void onEvent(int t,Bundle b){}
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR");i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);recognizer.startListening(i);
    }

    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private Button iconButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(20);b.setTextColor(TEXT);b.setPadding(0,0,0,0);b.setBackground(rounded(Color.rgb(20,38,58),14));return b;}
    private Button actionButton(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setAllCaps(false);b.setBackground(rounded(color,16));return b;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable gradient(int[] colors,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);g.setCornerRadius(dp(radius));return g;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density);}
    private void scrollBottom(){if(messageScroll!=null)messageScroll.post(()->messageScroll.fullScroll(View.FOCUS_DOWN));}
    private void applyInsets(){View content=findViewById(android.R.id.content);if(content==null)return;content.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});}
    @Override public void onBackPressed(){if(listeningOverlay!=null&&listeningOverlay.getVisibility()==View.VISIBLE){if(recognizer!=null)recognizer.cancel();hideListening();return;}if(drawer!=null&&drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}super.onBackPressed();}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(systemTts!=null)systemTts.shutdown();executor.shutdownNow();voiceExecutor.shutdownNow();super.onDestroy();}
}
