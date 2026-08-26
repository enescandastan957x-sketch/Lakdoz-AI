package com.lakdoz.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
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
import android.view.WindowManager;
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
    private TextView status,currentTitle,listenCaption,listenPartial,listenTitle;
    private EditText input,searchInput;
    private ScrollView messageScroll,listenScroll;
    private LinearLayout messageList,conversationList,drawer;
    private FrameLayout root,listeningOverlay;
    private View scrim,pulseRing;
    private Button soundToggle,repeatListen;
    private SpeechRecognizer recognizer;
    private TextToSpeech systemTts;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService voiceExecutor=Executors.newSingleThreadExecutor();
    private final Object speechLock=new Object();
    private final StringBuilder speechBuffer=new StringBuilder();

    private final int BG=Color.rgb(5,9,18);
    private final int PANEL=Color.rgb(12,20,35);
    private final int CARD=Color.rgb(17,28,45);
    private final int USER=Color.rgb(32,75,140);
    private final int ACCENT=Color.rgb(104,118,255);
    private final int CYAN=Color.rgb(55,214,201);
    private final int PURPLE=Color.rgb(154,107,255);
    private final int TEXT=Color.rgb(244,247,252);
    private final int MUTED=Color.rgb(151,164,187);

    private static final String[] VOICE_LABELS={
            "Kadın • Yumuşak — Aoede","Kadın • Net — Kore","Kadın • Genç — Leda","Kadın • Sakin — Callirrhoe",
            "Erkek • Enerjik — Puck","Erkek • Bilgilendirici — Charon","Erkek • Güçlü — Orus","Erkek • Olgun — Gacrux",
            "Telefonun hızlı Türkçe sesi"
    };
    private static final String[] VOICE_NAMES={"Aoede","Kore","Leda","Callirrhoe","Puck","Charon","Orus","Gacrux","SYSTEM"};
    private static final String[] VOICE_STYLES={
            "Sıcak, yumuşak, doğal ve samimi bir Türkçe ile konuş.","Net, kendinden emin, dengeli ve doğal bir Türkçe ile konuş.",
            "Genç, canlı, pozitif ve doğal bir Türkçe ile konuş.","Sakin, rahatlatıcı, nazik ve doğal bir Türkçe ile konuş.",
            "Enerjik, neşeli, arkadaş canlısı ve doğal bir Türkçe ile konuş.","Bilgilendirici, güven veren, sakin ve profesyonel bir Türkçe ile konuş.",
            "Güçlü, kararlı, net ve güven veren bir Türkçe ile konuş.","Olgun, sıcak, sakin ve güven veren bir Türkçe ile konuş.",""
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);history=new HistoryStore(this);settings=new SecureSettings(this);initSystemTts();setContentView(buildUi());applyInsets();refreshHistory();ensureMic();
        if(settings.getGeminiApiKey().isEmpty())status.setText("● Lakdoz AI bağlantısı ayarlı değil");
    }

    private View buildUi(){
        root=new FrameLayout(this);root.setId(android.R.id.content);root.setBackgroundColor(BG);
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);main.setPadding(dp(14),dp(10),dp(14),dp(10));root.addView(main,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button menu=iconButton("☰");menu.setOnClickListener(v->openDrawer());header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(46)));
        ImageView avatar=mascot();LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(dp(46),dp(46));alp.setMargins(dp(9),0,dp(9),0);header.addView(avatar,alp);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);currentTitle=new TextView(this);currentTitle.setText("Yeni sohbet");currentTitle.setTextColor(Color.WHITE);currentTitle.setTextSize(21);currentTitle.setTypeface(Typeface.DEFAULT_BOLD);currentTitle.setSingleLine(true);currentTitle.setEllipsize(TextUtils.TruncateAt.END);titles.addView(currentTitle);TextView version=new TextView(this);version.setText("Lakdoz AI • Smart Core • Canlı Kaynaklar");version.setTextColor(MUTED);version.setTextSize(11);titles.addView(version);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));Button settingsBtn=iconButton("⚙");settingsBtn.setOnClickListener(v->showSettings());header.addView(settingsBtn,new LinearLayout.LayoutParams(dp(48),dp(46)));main.addView(header);
        status=new TextView(this);status.setText("● Hazır • Smart Core aktif");status.setTextColor(CYAN);status.setTextSize(12);status.setPadding(dp(112),0,0,dp(7));main.addView(status);
        messageScroll=new ScrollView(this);messageScroll.setFillViewport(true);messageScroll.setVerticalScrollBarEnabled(false);messageList=new LinearLayout(this);messageList.setOrientation(LinearLayout.VERTICAL);messageList.setPadding(0,dp(6),0,dp(14));messageScroll.addView(messageList);main.addView(messageScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setOrientation(LinearLayout.VERTICAL);composer.setPadding(dp(8),dp(8),dp(8),dp(8));composer.setBackground(rounded(Color.rgb(12,20,34),22));input=new EditText(this);input.setHint("Lakdoz'a mesaj yaz…");input.setHintTextColor(Color.rgb(108,121,143));input.setTextColor(TEXT);input.setTextSize(16);input.setMinLines(2);input.setMaxLines(5);input.setPadding(dp(13),dp(10),dp(13),dp(10));input.setBackground(rounded(CARD,17));input.setOnFocusChangeListener((v,hasFocus)->{if(hasFocus){v.postDelayed(()->{View content=findViewById(android.R.id.content);if(content!=null)content.requestApplyInsets();},80);}});composer.addView(input);
        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(8),0,0);Button talk=actionButton("🎙 Konuş",Color.rgb(30,45,69));Button send=actionButton("Gönder ➜",Color.rgb(70,91,205));talk.setOnClickListener(v->listen());send.setOnClickListener(v->submit(input.getText().toString(),false));LinearLayout.LayoutParams p1=new LinearLayout.LayoutParams(0,dp(50),1);p1.setMargins(dp(3),0,dp(3),0);actions.addView(talk,p1);LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(50),1);p2.setMargins(dp(3),0,dp(3),0);actions.addView(send,p2);composer.addView(actions);main.addView(composer);
        scrim=new View(this);scrim.setBackgroundColor(Color.argb(165,0,0,0));scrim.setVisibility(View.GONE);scrim.setOnClickListener(v->closeDrawer());root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        drawer=buildDrawer();drawer.setVisibility(View.GONE);int drawerWidth=Math.min(dp(330),(int)(getResources().getDisplayMetrics().widthPixels*0.88f));root.addView(drawer,new FrameLayout.LayoutParams(drawerWidth,-1,Gravity.START));
        listeningOverlay=buildListeningOverlay();listeningOverlay.setVisibility(View.GONE);root.addView(listeningOverlay,new FrameLayout.LayoutParams(-1,-1));return root;
    }

    private LinearLayout buildDrawer(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),dp(16),dp(14),dp(14));panel.setBackgroundColor(PANEL);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);ImageView cat=mascot();top.addView(cat,new LinearLayout.LayoutParams(dp(50),dp(50)));TextView brand=new TextView(this);brand.setText("  LAKDOZ");brand.setTextColor(Color.WHITE);brand.setTextSize(22);brand.setTypeface(Typeface.DEFAULT_BOLD);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));Button close=iconButton("×");close.setOnClickListener(v->closeDrawer());top.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(top);
        TextView memory=new TextView(this);memory.setText("🧠 Hafıza: tüm sohbetler arasında bağlı");memory.setTextColor(Color.rgb(189,173,255));memory.setTextSize(12);memory.setPadding(0,dp(10),0,dp(8));panel.addView(memory);
        Button newChat=actionButton("＋ Yeni sohbet",ACCENT);newChat.setOnClickListener(v->{history.newConversation();if(searchInput!=null)searchInput.setText("");closeDrawer();refreshHistory();});LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(-1,dp(50));nlp.setMargins(0,dp(4),0,dp(10));panel.addView(newChat,nlp);
        searchInput=new EditText(this);searchInput.setSingleLine(true);searchInput.setTextSize(15);searchInput.setHint("Tüm sohbetlerde ara");searchInput.setHintTextColor(Color.rgb(108,118,137));searchInput.setTextColor(TEXT);searchInput.setPadding(dp(13),0,dp(10),0);searchInput.setBackground(rounded(Color.rgb(19,29,47),15));panel.addView(searchInput,new LinearLayout.LayoutParams(-1,dp(48)));searchInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){refreshConversationList(s==null?"":s.toString());}public void afterTextChanged(Editable e){}});
        TextView recent=new TextView(this);recent.setText("SON SOHBETLER");recent.setTextColor(MUTED);recent.setTextSize(11);recent.setTypeface(Typeface.DEFAULT_BOLD);recent.setPadding(dp(2),dp(16),0,dp(6));panel.addView(recent);ScrollView cs=new ScrollView(this);cs.setVerticalScrollBarEnabled(false);conversationList=new LinearLayout(this);conversationList.setOrientation(LinearLayout.VERTICAL);cs.addView(conversationList);panel.addView(cs,new LinearLayout.LayoutParams(-1,0,1));return panel;
    }

    private FrameLayout buildListeningOverlay(){
        FrameLayout overlay=new FrameLayout(this);overlay.setBackgroundColor(Color.rgb(5,10,22));
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);column.setPadding(dp(22),dp(50),dp(22),dp(24));overlay.addView(column,new FrameLayout.LayoutParams(-1,-1));
        listenTitle=new TextView(this);listenTitle.setText("Dinliyorum…");listenTitle.setTextColor(Color.WHITE);listenTitle.setTextSize(28);listenTitle.setTypeface(Typeface.DEFAULT_BOLD);listenTitle.setGravity(Gravity.CENTER);column.addView(listenTitle);
        TextView hint=new TextView(this);hint.setText("Konuş; cevabımı da bu ekranda vereceğim");hint.setTextColor(MUTED);hint.setTextSize(14);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(6),0,dp(24));column.addView(hint);
        FrameLayout orb=new FrameLayout(this);GradientDrawable ring=new GradientDrawable();ring.setShape(GradientDrawable.OVAL);ring.setColor(Color.argb(35,58,95,175));ring.setStroke(dp(3),Color.rgb(69,190,255));orb.setBackground(ring);column.addView(orb,new LinearLayout.LayoutParams(dp(226),dp(226)));
        pulseRing=new View(this);GradientDrawable inner=new GradientDrawable();inner.setShape(GradientDrawable.OVAL);inner.setColor(Color.TRANSPARENT);inner.setStroke(dp(6),PURPLE);pulseRing.setBackground(inner);orb.addView(pulseRing,new FrameLayout.LayoutParams(dp(186),dp(186),Gravity.CENTER));ImageView cat=mascot();cat.setScaleType(ImageView.ScaleType.CENTER_CROP);orb.addView(cat,new FrameLayout.LayoutParams(dp(150),dp(150),Gravity.CENTER));
        listenScroll=new ScrollView(this);listenScroll.setFillViewport(false);listenScroll.setVerticalScrollBarEnabled(true);listenPartial=new TextView(this);listenPartial.setText("Seni dinliyorum…");listenPartial.setTextColor(TEXT);listenPartial.setTextSize(18);listenPartial.setGravity(Gravity.CENTER_HORIZONTAL);listenPartial.setPadding(dp(10),dp(18),dp(10),dp(18));listenScroll.addView(listenPartial,new ScrollView.LayoutParams(-1,-2));LinearLayout.LayoutParams lsp=new LinearLayout.LayoutParams(-1,0,1);lsp.setMargins(0,dp(8),0,dp(4));column.addView(listenScroll,lsp);
        listenCaption=new TextView(this);listenCaption.setText("Canlı dinleme aktif");listenCaption.setTextColor(CYAN);listenCaption.setTextSize(13);listenCaption.setGravity(Gravity.CENTER);listenCaption.setPadding(0,dp(8),0,dp(16));column.addView(listenCaption);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);soundToggle=actionButton(settings.isSoundEnabled()?"🔊 Ses açık":"🔇 Ses kapalı",Color.rgb(29,43,66));soundToggle.setOnClickListener(v->toggleSound());repeatListen=actionButton(settings.isContinuousVoice()?"♾ Sürekli açık":"🎙 Tekrar dinle",Color.rgb(57,81,160));repeatListen.setOnClickListener(v->{if(settings.isContinuousVoice()){settings.setContinuousVoice(false);repeatListen.setText("🎙 Tekrar dinle");}else{settings.setContinuousVoice(true);repeatListen.setText("♾ Sürekli açık");startRecognizerOnly();}});LinearLayout.LayoutParams cp1=new LinearLayout.LayoutParams(0,dp(50),1);cp1.setMargins(dp(3),0,dp(3),0);controls.addView(soundToggle,cp1);LinearLayout.LayoutParams cp2=new LinearLayout.LayoutParams(0,dp(50),1);cp2.setMargins(dp(3),0,dp(3),0);controls.addView(repeatListen,cp2);column.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        Button cancel=actionButton("✕ Sesli sohbeti kapat",Color.rgb(26,38,58));cancel.setOnClickListener(v->{if(recognizer!=null)recognizer.cancel();hideListening();});LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,dp(54));cl.setMargins(0,dp(10),0,0);column.addView(cancel,cl);return overlay;
    }

    private ImageView mascot(){ImageView v=new ImageView(this);try{v.setImageBitmap(BitmapFactory.decodeResource(getResources(),R.drawable.lakdoz_mascot));}catch(Exception e){v.setImageResource(R.drawable.lakdoz_mascot);}v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setAdjustViewBounds(true);GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.rgb(28,42,67));v.setBackground(circle);v.setClipToOutline(true);v.setPadding(0,0,0,0);return v;}
    private void showListening(){listenTitle.setText("Dinliyorum…");listenPartial.setText("Seni dinliyorum…");listenCaption.setText("Canlı sohbet aktif");soundToggle.setText(settings.isSoundEnabled()?"🔊 Ses açık":"🔇 Ses kapalı");repeatListen.setText(settings.isContinuousVoice()?"♾ Sürekli açık":"🎙 Tekrar dinle");listeningOverlay.setVisibility(View.VISIBLE);listeningOverlay.bringToFront();if(listenScroll!=null)listenScroll.scrollTo(0,0);}
    private void hideListening(){listeningOverlay.setVisibility(View.GONE);if(pulseRing!=null){pulseRing.setScaleX(1f);pulseRing.setScaleY(1f);}}
    private void toggleSound(){boolean on=!settings.isSoundEnabled();settings.setSoundEnabled(on);soundToggle.setText(on?"🔊 Ses açık":"🔇 Ses kapalı");if(!on&&systemTts!=null)systemTts.stop();}

    private void openDrawer(){refreshConversationList(searchInput==null?"":searchInput.getText().toString());scrim.setVisibility(View.VISIBLE);drawer.setVisibility(View.VISIBLE);drawer.bringToFront();}
    private void closeDrawer(){if(scrim!=null)scrim.setVisibility(View.GONE);if(drawer!=null)drawer.setVisibility(View.GONE);}
    private void refreshConversationList(String query){if(conversationList==null)return;conversationList.removeAllViews();String active=history.getActiveConversationId();for(HistoryStore.ConversationMeta c:history.listConversations(query)){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(10),dp(9));card.setBackground(rounded(c.id.equals(active)?Color.rgb(37,52,86):Color.TRANSPARENT,14));TextView t=new TextView(this);t.setText(c.title);t.setTextColor(TEXT);t.setTextSize(15);t.setTypeface(Typeface.DEFAULT_BOLD);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);card.addView(t);TextView p=new TextView(this);p.setText(c.preview==null?"":c.preview.replace('\n',' '));p.setTextColor(MUTED);p.setTextSize(12);p.setSingleLine(true);p.setEllipsize(TextUtils.TruncateAt.END);p.setPadding(0,dp(3),0,0);card.addView(p);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(66));lp.setMargins(0,dp(3),0,dp(3));conversationList.addView(card,lp);card.setOnClickListener(v->{history.switchConversation(c.id);closeDrawer();refreshHistory();});card.setOnLongClickListener(v->{history.switchConversation(c.id);new AlertDialog.Builder(this).setTitle("Sohbeti sil").setMessage("“"+c.title+"” silinsin mi?").setPositiveButton("Sil",(d,w)->{history.deleteActiveConversation();refreshHistory();}).setNegativeButton("İptal",null).show();return true;});}}
    private void refreshHistory(){if(messageList==null)return;currentTitle.setText(history.getActiveTitle());messageList.removeAllViews();List<HistoryStore.Turn> turns=history.load();if(turns.isEmpty())addWelcome();else for(HistoryStore.Turn t:turns)addBubble(t.role,t.text);if(conversationList!=null)refreshConversationList(searchInput==null?"":searchInput.getText().toString());scrollBottom();}
    private void addWelcome(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(dp(16),dp(20),dp(16),dp(20));box.setBackground(rounded(Color.rgb(13,22,38),22));ImageView cat=mascot();box.addView(cat,new LinearLayout.LayoutParams(dp(118),dp(118)));LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(15),0,0,0);TextView w=new TextView(this);w.setText("Merhaba! 👋\nBen Lakdoz.");w.setTextColor(TEXT);w.setTextSize(23);w.setTypeface(Typeface.DEFAULT_BOLD);texts.addView(w);TextView mem=new TextView(this);mem.setText("Yeni sohbet açsan da eski konuşmalarından ilgili bilgileri hatırlayabilirim.");mem.setTextColor(MUTED);mem.setTextSize(14);mem.setPadding(0,dp(7),0,0);texts.addView(mem);box.addView(texts,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(20),0,0);messageList.addView(box,lp);}
    private TextView addBubble(String role,String text){boolean user="user".equals(role);LinearLayout row=new LinearLayout(this);row.setGravity(user?Gravity.END:Gravity.START);row.setPadding(0,dp(5),0,dp(5));if(!user){ImageView cat=mascot();LinearLayout.LayoutParams av=new LinearLayout.LayoutParams(dp(40),dp(40));av.setMargins(0,dp(4),dp(7),0);row.addView(cat,av);}LinearLayout bubble=new LinearLayout(this);bubble.setOrientation(LinearLayout.VERTICAL);bubble.setPadding(dp(14),dp(10),dp(14),dp(11));bubble.setBackground(rounded(user?USER:CARD,18));TextView who=new TextView(this);who.setText(user?"SEN":"LAKDOZ");who.setTextSize(11);who.setTypeface(Typeface.DEFAULT_BOLD);who.setTextColor(user?Color.rgb(190,215,255):CYAN);bubble.addView(who);TextView body=new TextView(this);body.setText(text);body.setTextColor(TEXT);body.setTextSize(17);body.setLineSpacing(0,1.12f);body.setPadding(0,dp(4),0,0);bubble.addView(body);int max=(int)(getResources().getDisplayMetrics().widthPixels*(user?0.82f:0.76f));row.addView(bubble,new LinearLayout.LayoutParams(max,-2));messageList.addView(row,new LinearLayout.LayoutParams(-1,-2));return body;}

    private void submit(String text,boolean fromVoice){
        final String q=text==null?"":text.trim();if(q.isEmpty())return;if(settings.getGeminiApiKey().isEmpty()){Toast.makeText(this,"Önce Ayarlar'dan AI bağlantı anahtarını ekle.",Toast.LENGTH_LONG).show();showSettings();return;}
        if(!fromVoice)input.setText("");List<HistoryStore.Turn> before=history.load();String memory=history.buildRelevantMemory(q,7000);history.add("user",q);if(!fromVoice)refreshHistory();final TextView streaming=fromVoice?null:addBubble("assistant","");if(!fromVoice)scrollBottom();status.setText(memory.isEmpty()?"● Lakdoz yazıyor…":"● Hafızadan bağlam bulundu • Lakdoz yazıyor…");synchronized(speechLock){speechBuffer.setLength(0);}if(fromVoice){listenTitle.setText("Lakdoz düşünüyor…");listenPartial.setText(q);listenCaption.setText(memory.isEmpty()?"Cevap hazırlanıyor…":"Eski sohbetlerden ilgili bağlam bulundu");}
        executor.execute(()->{try{String understood=smartInterpret(q,before,memory);LocalCommandRouter.Result local=new LocalCommandRouter(getApplicationContext()).tryHandle(understood,before);String answer;if(local.handled){answer=local.response;if(fromVoice)runOnUiThread(()->{listenTitle.setText("Lakdoz");listenPartial.setText(local.response);listenCaption.setText(settings.isContinuousVoice()?"Cevabım bitince seni tekrar dinleyeceğim":"Hazırım");});else runOnUiThread(()->streaming.setText(local.response));queueVoiceChunk(local.response);if(fromVoice&&settings.isContinuousVoice())queueAutoRelisten();}else{final StringBuilder voiceScreen=new StringBuilder();answer=new SmartAiOrchestrator(getApplicationContext()).ask(understood,before,memory,delta->{if(fromVoice){voiceScreen.append(delta);runOnUiThread(()->{listenTitle.setText("Lakdoz konuşuyor…");listenPartial.setText(voiceScreen.toString());listenCaption.setText("Cevap geliyor…");if(listenScroll!=null)listenScroll.post(()->listenScroll.fullScroll(View.FOCUS_DOWN));});}else runOnUiThread(()->{streaming.append(delta);scrollBottom();});consumeSpeechDelta(delta,false);});consumeSpeechDelta("",true);}history.add("assistant",answer);final String out=answer;runOnUiThread(()->{status.setText("● Hazır • Smart Core aktif");if(fromVoice){listenTitle.setText("Lakdoz");listenPartial.setText(out);listenCaption.setText(settings.isContinuousVoice()?"Cevabım bitince seni tekrar dinleyeceğim":"Hazırım • tekrar konuşabilirsin");if(listenScroll!=null)listenScroll.post(()->listenScroll.fullScroll(View.FOCUS_DOWN));if(settings.isContinuousVoice())queueAutoRelisten();}else refreshHistory();});}catch(Exception e){final String err=friendlyError(e);history.add("assistant",err);runOnUiThread(()->{status.setText("● Hazır");if(fromVoice){listenTitle.setText("Bir sorun oldu");listenPartial.setText(err);listenCaption.setText("Tekrar deneyebilirsin");}else refreshHistory();});}});
    }

    private String smartInterpret(String raw,List<HistoryStore.Turn> before,String memory){
        try{
            String instruction="Rewrite the user's intended message in natural Turkish. Correct likely typos and missing letters. Preserve the recent conversation topic, person, place, product and time when this is a follow-up. Do not invent a new entity. Return only the rewritten message. Input: "+raw;
            String out=new AiClient(getApplicationContext()).askStreaming(instruction,before,memory,null);
            if(out==null)return raw;
            out=out.trim();
            if(out.isEmpty()||out.length()>Math.max(240,raw.length()*4))return raw;
            return out;
        }catch(Exception e){return raw;}
    }

    private void consumeSpeechDelta(String delta,boolean flush){String ready=null;synchronized(speechLock){speechBuffer.append(delta);int cut=findSpeechCut(speechBuffer);if(flush&&speechBuffer.length()>0)cut=speechBuffer.length();if(cut>0){ready=speechBuffer.substring(0,cut).trim();speechBuffer.delete(0,cut);}}if(ready!=null&&!ready.isEmpty())queueVoiceChunk(ready);}
    private int findSpeechCut(StringBuilder b){if(b.length()<18)return 0;int max=Math.min(b.length(),88);for(int i=18;i<max;i++){char c=b.charAt(i);if(c=='.'||c=='!'||c=='?'||c=='\n')return i+1;}return b.length()>=44?44:0;}
    private void queueVoiceChunk(String text){if(text==null||text.trim().isEmpty()||!settings.isSoundEnabled())return;final String chunk=text.trim();if(!settings.useGeminiVoice()){runOnUiThread(()->speakSystemQueued(chunk));return;}voiceExecutor.execute(()->{if(!settings.isSoundEnabled())return;GeminiTtsClient tts=new GeminiTtsClient(getApplicationContext());try{tts.speak(chunk);}catch(Exception first){try{Thread.sleep(320);if(settings.isSoundEnabled())tts.speak(chunk);}catch(Exception second){runOnUiThread(()->{if(listeningOverlay!=null&&listeningOverlay.getVisibility()==View.VISIBLE)listenCaption.setText("Seçili ses geçici olarak kullanılamıyor");});}}});}
    private void queueAutoRelisten(){voiceExecutor.execute(()->{try{Thread.sleep(180);}catch(Exception ignored){}runOnUiThread(()->{if(listeningOverlay!=null&&listeningOverlay.getVisibility()==View.VISIBLE&&settings.isContinuousVoice()){listenTitle.setText("Dinliyorum…");listenPartial.setText("Seni dinliyorum…");listenCaption.setText("Konuşabilirsin");startRecognizerOnly();}});});}
    private String friendlyError(Exception e){String m=e.getMessage()==null?"":e.getMessage();if(m.toLowerCase().contains("timeout")||m.toLowerCase().contains("timed out"))return"Bağlantı zaman aşımına uğradı. Tekrar dene.";if(m.contains("HTTP 503"))return"Lakdoz AI şu anda yoğun. Biraz sonra tekrar dene.";if(m.contains("HTTP 429"))return"Lakdoz AI kullanım sınırına ulaşıldı. Bir süre sonra tekrar dene.";return"Bağlantı başarısız: "+(m.isEmpty()?"Lakdoz AI servisine ulaşılamadı.":m);}

    private void showSettings(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(8));TextView mode=new TextView(this);mode.setText("🧠 Tüm sohbet hafızası bağlı\n🌐 Canlı kaynaklar + web arama\n🧠 Zor sorularda çoklu AI\n⚡ Hızlı yanıt + erken ses");mode.setTextColor(Color.DKGRAY);mode.setTextSize(14);mode.setPadding(0,0,0,dp(12));box.addView(mode);EditText key=new EditText(this);key.setHint(settings.getGeminiApiKey().isEmpty()?"AI bağlantı anahtarı":"AI bağlantısı kayıtlı • değiştirmek için yenisini gir");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(key);
        EditText routerKey=new EditText(this);routerKey.setHint(settings.getOpenRouterApiKey().isEmpty()?"İsteğe bağlı: Ücretsiz çoklu AI anahtarı (OpenRouter)":"Ücretsiz çoklu AI bağlantısı kayıtlı • değiştirmek için yenisini gir");routerKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(routerKey);
        Button ensemble=new Button(this);ensemble.setText(settings.isSmartEnsemble()?"🧠 Akıllı çoklu AI açık":"🧠 Akıllı çoklu AI kapalı");box.addView(ensemble);ensemble.setOnClickListener(v->{boolean on=!settings.isSmartEnsemble();settings.setSmartEnsemble(on);ensemble.setText(on?"🧠 Akıllı çoklu AI açık":"🧠 Akıllı çoklu AI kapalı");});
        TextView voiceTitle=new TextView(this);voiceTitle.setText("Lakdoz sesi");voiceTitle.setTypeface(Typeface.DEFAULT_BOLD);voiceTitle.setTextSize(16);voiceTitle.setPadding(0,dp(16),0,dp(5));box.addView(voiceTitle);Spinner voices=new Spinner(this);ArrayAdapter<String> va=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,VOICE_LABELS);va.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);voices.setAdapter(va);voices.setSelection(currentVoiceIndex());box.addView(voices);Button preview=new Button(this);preview.setText("Seçili sesi dene");box.addView(preview);
        Button sound=new Button(this);sound.setText(settings.isSoundEnabled()?"🔊 Seslendirme açık":"🔇 Seslendirme kapalı");box.addView(sound);sound.setOnClickListener(v->{boolean on=!settings.isSoundEnabled();settings.setSoundEnabled(on);sound.setText(on?"🔊 Seslendirme açık":"🔇 Seslendirme kapalı");if(!on&&systemTts!=null)systemTts.stop();});Button continuous=new Button(this);continuous.setText(settings.isContinuousVoice()?"♾ Sürekli sesli sohbet açık":"🎙 Sürekli sesli sohbet kapalı");box.addView(continuous);continuous.setOnClickListener(v->{boolean on=!settings.isContinuousVoice();settings.setContinuousVoice(on);continuous.setText(on?"♾ Sürekli sesli sohbet açık":"🎙 Sürekli sesli sohbet kapalı");});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Lakdoz AI Ayarları").setView(box).setPositiveButton("Kaydet",null).setNeutralButton("Bağlantıyı test et",null).setNegativeButton("İptal",null).create();dialog.setOnShowListener(x->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);String rk=routerKey.getText().toString().trim();if(!rk.isEmpty())settings.setOpenRouterApiKey(rk);saveVoice(voices.getSelectedItemPosition());dialog.dismiss();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{String k=key.getText().toString().trim();if(!k.isEmpty())settings.setGeminiApiKey(k);String rk=routerKey.getText().toString().trim();if(!rk.isEmpty())settings.setOpenRouterApiKey(rk);}catch(Exception e){return;}status.setText("● Bağlantı test ediliyor…");executor.execute(()->{try{new AiClient(getApplicationContext()).testConnection();runOnUiThread(()->{status.setText("● Lakdoz AI hazır");Toast.makeText(this,"Lakdoz AI hazır",Toast.LENGTH_SHORT).show();});}catch(Exception e){runOnUiThread(()->status.setText("● "+friendlyError(e)));}});});preview.setOnClickListener(v->{saveVoice(voices.getSelectedItemPosition());settings.setSoundEnabled(true);queueVoiceChunk("Merhaba. Ben Lakdoz. Bu ses profilini seçtin.");});});dialog.show();
    }

    private int currentVoiceIndex(){if(!settings.useGeminiVoice())return VOICE_NAMES.length-1;String v=settings.getGeminiVoice();for(int i=0;i<VOICE_NAMES.length-1;i++)if(VOICE_NAMES[i].equals(v))return i;return 1;}
    private void saveVoice(int i){i=Math.max(0,Math.min(i,VOICE_NAMES.length-1));if("SYSTEM".equals(VOICE_NAMES[i]))settings.setUseGeminiVoice(false);else{settings.setUseGeminiVoice(true);settings.setGeminiVoice(VOICE_NAMES[i]);settings.setGeminiVoiceStyle(VOICE_STYLES[i]);}}
    private void initSystemTts(){systemTts=new TextToSpeech(this,s->{if(s==TextToSpeech.SUCCESS){systemTts.setLanguage(Locale.forLanguageTag("tr-TR"));systemTts.setSpeechRate(Math.max(1.15f,settings.getSpeechRate()));systemTts.setPitch(settings.getSpeechPitch());}});}
    private void speakSystemQueued(String text){if(systemTts!=null&&settings.isSoundEnabled())systemTts.speak(text,TextToSpeech.QUEUE_ADD,null,"lakdoz-"+System.nanoTime());}
    private void ensureMic(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}
    private void listen(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ensureMic();return;}showListening();startRecognizerOnly();}
    private void startRecognizerOnly(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ensureMic();return;}if(listeningOverlay.getVisibility()!=View.VISIBLE)showListening();listenTitle.setText("Dinliyorum…");listenPartial.setText("Seni dinliyorum…");listenCaption.setText("Canlı dinleme aktif");if(recognizer!=null)recognizer.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this);recognizer.setRecognitionListener(new RecognitionListener(){public void onReadyForSpeech(Bundle p){status.setText("● Dinliyorum…");}public void onBeginningOfSpeech(){listenCaption.setText("Seni duyuyorum…");}public void onRmsChanged(float v){float s=1f+Math.max(0f,Math.min(0.18f,(v+2f)/55f));if(pulseRing!=null){pulseRing.setScaleX(s);pulseRing.setScaleY(s);}}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){listenCaption.setText("Anlıyorum…");status.setText("● Anlıyorum…");}public void onError(int e){listenTitle.setText("Tekrar deneyelim");listenCaption.setText("Mikrofon seni anlayamadı");status.setText("● Ses algılama hatası");}public void onResults(Bundle b){ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty()){String q=xs.get(0);listenPartial.setText(q);listenTitle.setText("Lakdoz düşünüyor…");listenCaption.setText("Cevap hazırlanıyor…");submit(q,true);}}public void onPartialResults(Bundle b){ArrayList<String> xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(xs!=null&&!xs.isEmpty())listenPartial.setText(xs.get(0));}public void onEvent(int t,Bundle b){}});Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR");i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);recognizer.startListening(i);}

    private Button iconButton(String text){Button b=new Button(this);b.setText(text);b.setTextSize(20);b.setTextColor(TEXT);b.setPadding(0,0,0,0);b.setBackground(rounded(Color.rgb(24,34,52),14));return b;}
    private Button actionButton(String text,int color){Button b=new Button(this);b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setAllCaps(false);b.setBackground(rounded(color,16));return b;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density);}
    private void scrollBottom(){if(messageScroll!=null)messageScroll.post(()->messageScroll.fullScroll(View.FOCUS_DOWN));}
    private void applyInsets(){View content=findViewById(android.R.id.content);if(content==null)return;content.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());int bottom=Math.max(bars.bottom,ime.bottom);v.setPadding(bars.left,bars.top,bars.right,bottom);}return insets;});content.requestApplyInsets();}
    @Override public void onBackPressed(){if(listeningOverlay!=null&&listeningOverlay.getVisibility()==View.VISIBLE){if(recognizer!=null)recognizer.cancel();hideListening();return;}if(drawer!=null&&drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}super.onBackPressed();}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(systemTts!=null)systemTts.shutdown();executor.shutdownNow();voiceExecutor.shutdownNow();super.onDestroy();}
}
