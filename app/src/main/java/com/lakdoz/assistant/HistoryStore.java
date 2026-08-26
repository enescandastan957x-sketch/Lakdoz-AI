package com.lakdoz.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HistoryStore {
    private final SharedPreferences prefs;
    private static final String OLD_KEY = "chat_history_v2";
    private static final String THREADS_KEY = "chat_threads_v3";
    private static final String ACTIVE_KEY = "active_chat_id_v3";

    public static final class Turn {
        public final String role;
        public final String text;
        public Turn(String role, String text) { this.role = role; this.text = text; }
    }

    public static final class ConversationMeta {
        public final String id;
        public final String title;
        public final long updatedAt;
        public final String preview;
        public ConversationMeta(String id, String title, long updatedAt, String preview) {
            this.id = id; this.title = title; this.updatedAt = updatedAt; this.preview = preview;
        }
    }

    private static final class ThreadData {
        String id;
        String title;
        long updatedAt;
        final ArrayList<Turn> turns = new ArrayList<>();
    }

    private static final class MemoryHit {
        String text;
        int score;
        long updated;
        MemoryHit(String text, int score, long updated) { this.text=text; this.score=score; this.updated=updated; }
    }

    public HistoryStore(Context context) {
        prefs = context.getSharedPreferences("lakdoz", Context.MODE_PRIVATE);
        migrateOldHistoryIfNeeded();
        ensureActiveConversation();
    }

    public synchronized List<Turn> load() {
        String active = getActiveConversationId();
        for (ThreadData t : readThreads()) if (t.id.equals(active)) return new ArrayList<>(t.turns);
        return new ArrayList<>();
    }

    public synchronized void add(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        ArrayList<ThreadData> threads = readThreads();
        String active = getActiveConversationId();
        ThreadData target = null;
        for (ThreadData t : threads) if (t.id.equals(active)) { target = t; break; }
        if (target == null) {
            target = newThread(); threads.add(target); prefs.edit().putString(ACTIVE_KEY, target.id).apply();
        }
        String clean = text.trim();
        target.turns.add(new Turn(role, clean));
        while (target.turns.size() > 160) target.turns.remove(0);
        if ("user".equals(role) && (target.title == null || target.title.isEmpty() || "Yeni sohbet".equals(target.title))) target.title = makeTitle(clean);
        target.updatedAt = System.currentTimeMillis();
        writeThreads(threads);
    }

    public synchronized String buildRelevantMemory(String query, int maxChars) {
        ArrayList<ThreadData> threads = readThreads();
        if (threads.isEmpty()) return "";
        String active = getActiveConversationId();
        Set<String> qTokens = tokenize(query == null ? "" : query);
        boolean explicitMemory = containsMemoryCue(query == null ? "" : query);
        int limit = Math.max(900, maxChars);
        StringBuilder out = new StringBuilder();
        HashSet<String> seen = new HashSet<>();

        // 1) Always include a compact snapshot of the most recent OTHER chats.
        ArrayList<ThreadData> recent = new ArrayList<>(threads);
        Collections.sort(recent, (a,b) -> Long.compare(b.updatedAt, a.updatedAt));
        int recentThreads = 0;
        for (ThreadData t : recent) {
            if (t.id.equals(active) || t.turns.isEmpty()) continue;
            // İlgisiz eski sohbetleri her soruya eklemek bağlamı kirletir.
            // Kullanıcı açıkça hafıza istemediyse yalnızca konu olarak eşleşen
            // sohbetleri taşırız; yazım hataları overlap/fuzzyClose ile tolere edilir.
            if (!explicitMemory && !threadMatches(t, qTokens)) continue;
            StringBuilder line = new StringBuilder();
            line.append("[").append(safeTitle(t.title)).append("] ");
            int start = Math.max(0, t.turns.size() - (explicitMemory ? 4 : 2));
            for (int i = start; i < t.turns.size(); i++) {
                Turn turn = t.turns.get(i);
                if (turn.text == null || turn.text.trim().isEmpty()) continue;
                String who = "user".equals(turn.role) ? "Kullanıcı" : "Lakdoz";
                line.append(who).append(": ").append(shorten(turn.text, 260)).append(" | ");
            }
            String s = line.toString().trim();
            if (!s.isEmpty() && !seen.contains(s)) {
                if (out.length() + s.length() + 1 > limit) break;
                out.append(s).append('\n');
                seen.add(s);
                recentThreads++;
            }
            if (recentThreads >= (explicitMemory ? 5 : 3)) break;
        }

        // 2) Add semantically relevant token-overlap hits from ALL chats.
        ArrayList<MemoryHit> hits = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ThreadData t : threads) {
            for (Turn turn : t.turns) {
                if (turn.text == null || turn.text.trim().isEmpty()) continue;
                int overlap = overlap(qTokens, tokenize(turn.text));
                if (overlap == 0 && !explicitMemory) continue;
                int score = overlap * 20;
                if (!t.id.equals(active)) score += 10;
                long ageDays = Math.max(0, (now - t.updatedAt) / 86400000L);
                score += Math.max(0, 12 - (int)Math.min(12, ageDays));
                if (explicitMemory) score += 6;
                String who = "user".equals(turn.role) ? "Kullanıcı" : "Lakdoz";
                String text = "[" + safeTitle(t.title) + "] " + who + ": " + shorten(turn.text.replace('\n',' '), 360);
                hits.add(new MemoryHit(text, score, t.updatedAt));
            }
        }
        Collections.sort(hits, (a,b) -> {
            int s = Integer.compare(b.score, a.score);
            return s != 0 ? s : Long.compare(b.updated, a.updated);
        });
        for (MemoryHit h : hits) {
            if (seen.contains(h.text)) continue;
            if (out.length() + h.text.length() + 1 > limit) break;
            out.append(h.text).append('\n');
            seen.add(h.text);
        }
        return out.toString().trim();
    }

    public synchronized int conversationCount() { return readThreads().size(); }

    private boolean containsMemoryCue(String q) {
        String s = q.toLowerCase(new Locale("tr","TR"));
        return s.contains("hatırla") || s.contains("hatırlıyor") || s.contains("geçen") || s.contains("önceki") ||
                s.contains("eski sohbet") || s.contains("konuşmuştuk") || s.contains("daha önce") ||
                s.contains("en son") || s.contains("kaldığımız") || s.contains("devam et");
    }

    private Set<String> tokenize(String text) {
        HashSet<String> out = new HashSet<>();
        String norm = text.toLowerCase(new Locale("tr","TR")).replaceAll("[^a-z0-9çğıöşü]+", " ");
        for (String p : norm.split("\\s+")) {
            if (p.length() < 3) continue;
            if (p.equals("ve") || p.equals("bir") || p.equals("ile") || p.equals("için") || p.equals("ama") || p.equals("gibi") || p.equals("daha") || p.equals("bana")) continue;
            String root = softStem(p);
            out.add(p);
            out.add(root);
            addSynonyms(out, root);
        }
        return out;
    }

    private String softStem(String p) {
        String x = p;
        String[] suffixes = {"lerden","lardan","lerin","ların","leri","ları","lerde","larda","den","dan","nin","nın","nun","nün","de","da","ye","ya","yi","yı","yu","yü"};
        for (String s : suffixes) if (x.length() > s.length() + 3 && x.endsWith(s)) { x = x.substring(0, x.length() - s.length()); break; }
        return x;
    }

    private void addSynonyms(Set<String> out, String x) {
        if (x.contains("hava") || x.contains("sicak") || x.contains("sıcak")) { out.add("weather"); out.add("iklim"); }
        if (x.contains("video")) { out.add("film"); out.add("klip"); }
        if (x.contains("yapay") || x.equals("ai")) { out.add("ai"); out.add("yapayzeka"); }
        if (x.contains("ses")) { out.add("voice"); out.add("konusma"); }
        if (x.contains("telefon")) { out.add("mobil"); out.add("android"); }
        if (x.contains("traş") || x.contains("tıraş") || x.contains("sakal")) { out.add("sakal"); out.add("tiras"); }
    }

    private int overlap(Set<String> a, Set<String> b) {
        int n=0;
        for (String x:a) {
            if (b.contains(x)) { n++; continue; }
            for (String y:b) {
                if (x.length() >= 5 && y.length() >= 5 && fuzzyClose(x,y)) { n++; break; }
            }
        }
        return n;
    }

    private boolean fuzzyClose(String a, String b) {
        if (Math.abs(a.length()-b.length()) > 2) return false;
        int i=0,j=0,edits=0;
        while(i<a.length() && j<b.length()) {
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++edits>1)return false;
            if(a.length()>b.length())i++;
            else if(b.length()>a.length())j++;
            else{i++;j++;}
        }
        if(i<a.length()||j<b.length())edits++;
        return edits<=1;
    }
    private boolean threadMatches(ThreadData thread, Set<String> qTokens) {
        if (thread == null || qTokens == null || qTokens.isEmpty()) return false;
        int best = overlap(qTokens, tokenize(thread.title == null ? "" : thread.title));
        for (Turn turn : thread.turns) {
            if (turn != null && turn.text != null) {
                best = Math.max(best, overlap(qTokens, tokenize(turn.text)));
                if (best >= 2) return true;
            }
        }
        return best > 0;
    }

    private String shorten(String s, int max) { if (s == null) return ""; s=s.trim(); return s.length() <= max ? s : s.substring(0,max).trim()+"…"; }

    public synchronized String newConversation() {
        ArrayList<ThreadData> threads = readThreads(); ThreadData t = newThread(); threads.add(t);
        while (threads.size() > 80) { Collections.sort(threads, Comparator.comparingLong(a -> a.updatedAt)); threads.remove(0); }
        writeThreads(threads); prefs.edit().putString(ACTIVE_KEY, t.id).apply(); return t.id;
    }

    public synchronized void switchConversation(String id) {
        if (id == null || id.isEmpty()) return;
        for (ThreadData t : readThreads()) if (id.equals(t.id)) { prefs.edit().putString(ACTIVE_KEY, id).apply(); return; }
    }

    public synchronized String getActiveConversationId() { String id = prefs.getString(ACTIVE_KEY, ""); return id == null ? "" : id; }
    public synchronized String getActiveTitle() { String active=getActiveConversationId(); for(ThreadData t:readThreads()) if(t.id.equals(active)) return t.title; return "Yeni sohbet"; }

    public synchronized List<ConversationMeta> listConversations(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT); ArrayList<ConversationMeta> out = new ArrayList<>();
        for (ThreadData t : readThreads()) {
            StringBuilder hay = new StringBuilder(t.title == null ? "" : t.title); String preview="";
            for (int i=t.turns.size()-1;i>=0;i--) { Turn turn=t.turns.get(i); hay.append(' ').append(turn.text); if(preview.isEmpty()&&turn.text!=null&&!turn.text.isEmpty()) preview=turn.text; }
            if(!q.isEmpty()&&!hay.toString().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(new ConversationMeta(t.id,safeTitle(t.title),t.updatedAt,preview));
        }
        Collections.sort(out,(a,b)->Long.compare(b.updatedAt,a.updatedAt)); return out;
    }

    public synchronized void clear() {
        ArrayList<ThreadData> threads=readThreads(); String active=getActiveConversationId();
        for(ThreadData t:threads) if(t.id.equals(active)){t.turns.clear();t.title="Yeni sohbet";t.updatedAt=System.currentTimeMillis();break;}
        writeThreads(threads);
    }

    public synchronized void deleteActiveConversation() {
        ArrayList<ThreadData> threads=readThreads(); String active=getActiveConversationId();
        for(int i=threads.size()-1;i>=0;i--) if(threads.get(i).id.equals(active)) threads.remove(i);
        if(threads.isEmpty()) threads.add(newThread());
        Collections.sort(threads,(a,b)->Long.compare(b.updatedAt,a.updatedAt)); prefs.edit().putString(ACTIVE_KEY,threads.get(0).id).apply(); writeThreads(threads);
    }

    private void ensureActiveConversation() {
        ArrayList<ThreadData> threads=readThreads();
        if(threads.isEmpty()){ThreadData t=newThread();threads.add(t);writeThreads(threads);prefs.edit().putString(ACTIVE_KEY,t.id).apply();return;}
        String active=getActiveConversationId(); for(ThreadData t:threads) if(t.id.equals(active)) return;
        Collections.sort(threads,(a,b)->Long.compare(b.updatedAt,a.updatedAt)); prefs.edit().putString(ACTIVE_KEY,threads.get(0).id).apply();
    }

    private void migrateOldHistoryIfNeeded() {
        String existing=prefs.getString(THREADS_KEY,""); if(existing!=null&&!existing.isEmpty()) return;
        String raw=prefs.getString(OLD_KEY,"[]"); ArrayList<ThreadData> threads=new ArrayList<>(); ThreadData t=newThread();
        try{JSONArray arr=new JSONArray(raw==null?"[]":raw);for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o==null)continue;String role=o.optString("role","assistant");String text=o.optString("text","");if(!text.trim().isEmpty())t.turns.add(new Turn(role,text.trim()));if("user".equals(role)&&"Yeni sohbet".equals(t.title)&&!text.trim().isEmpty())t.title=makeTitle(text);}}catch(Exception ignored){}
        threads.add(t);writeThreads(threads);prefs.edit().putString(ACTIVE_KEY,t.id).remove(OLD_KEY).apply();
    }

    private ThreadData newThread(){ThreadData t=new ThreadData();t.id="c"+System.currentTimeMillis()+"_"+Math.abs((int)(Math.random()*100000));t.title="Yeni sohbet";t.updatedAt=System.currentTimeMillis();return t;}

    private ArrayList<ThreadData> readThreads(){ArrayList<ThreadData> out=new ArrayList<>();String raw=prefs.getString(THREADS_KEY,"[]");try{JSONArray arr=new JSONArray(raw==null?"[]":raw);for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o==null)continue;ThreadData t=new ThreadData();t.id=o.optString("id","");t.title=safeTitle(o.optString("title","Yeni sohbet"));t.updatedAt=o.optLong("updated",System.currentTimeMillis());JSONArray turns=o.optJSONArray("turns");if(turns!=null)for(int j=0;j<turns.length();j++){JSONObject x=turns.optJSONObject(j);if(x!=null)t.turns.add(new Turn(x.optString("role","assistant"),x.optString("text","")));}if(!t.id.isEmpty())out.add(t);}}catch(Exception ignored){}return out;}

    private void writeThreads(List<ThreadData> threads){JSONArray arr=new JSONArray();try{for(ThreadData t:threads){JSONObject o=new JSONObject();o.put("id",t.id);o.put("title",safeTitle(t.title));o.put("updated",t.updatedAt);JSONArray turns=new JSONArray();for(Turn turn:t.turns){JSONObject x=new JSONObject();x.put("role",turn.role);x.put("text",turn.text);turns.put(x);}o.put("turns",turns);arr.put(o);}prefs.edit().putString(THREADS_KEY,arr.toString()).apply();}catch(Exception ignored){}}
    private String makeTitle(String text){String s=text==null?"":text.replace('\n',' ').trim();if(s.length()>34)s=s.substring(0,34).trim()+"…";return s.isEmpty()?"Yeni sohbet":s;}
    private String safeTitle(String title){return title==null||title.trim().isEmpty()?"Yeni sohbet":title.trim();}
}