package com.lakdoz.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class HistoryStore {
    private final SharedPreferences prefs;
    private static final String KEY = "chat_history_v2";

    public static final class Turn {
        public final String role;
        public final String text;
        public Turn(String role, String text) { this.role = role; this.text = text; }
    }

    public HistoryStore(Context context) {
        prefs = context.getSharedPreferences("lakdoz", Context.MODE_PRIVATE);
    }

    public synchronized List<Turn> load() {
        ArrayList<Turn> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Turn(o.optString("role", "assistant"), o.optString("text", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void add(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        List<Turn> turns = load();
        turns.add(new Turn(role, text.trim()));
        while (turns.size() > 80) turns.remove(0);
        JSONArray arr = new JSONArray();
        try {
            for (Turn t : turns) {
                JSONObject o = new JSONObject();
                o.put("role", t.role);
                o.put("text", t.text);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public synchronized void clear() { prefs.edit().remove(KEY).apply(); }
}
