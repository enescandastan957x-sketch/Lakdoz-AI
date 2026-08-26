package com.lakdoz.assistant;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public class LocalCommandRouter {
    public static final class Result {
        public final boolean handled;
        public final String response;
        Result(boolean handled, String response) { this.handled = handled; this.response = response; }
        static Result no() { return new Result(false, ""); }
        static Result yes(String r) { return new Result(true, r); }
    }

    private final Context context;
    private final SharedPreferences contextPrefs;
    public LocalCommandRouter(Context context) {
        this.context = context;
        this.contextPrefs = context.getSharedPreferences("lakdoz_context", Context.MODE_PRIVATE);
    }

    public Result tryHandle(String raw) { return tryHandle(raw, null); }

    public Result tryHandle(String raw, List<HistoryStore.Turn> history) {
        String c = fold(raw);

        if (isWeatherRequest(raw, history)) {
            String place = extractWeatherPlace(raw, history);
            if (!place.isEmpty()) {
                try { return Result.yes(fetchWeather(place, extractHoursAhead(raw))); }
                catch (Exception e) { return Result.yes("Şu anda canlı hava verisine ulaşamadım. Biraz sonra tekrar deneyebilirsin."); }
            }
        }

        Result liveInfo = tryLiveKnowledge(raw);
        if (liveInfo.handled) return liveInfo;

        if (containsLike(c, "youtube", 2)) {
            String q = raw.replaceAll("(?i)youtube(?:'dan|'da|'de|dan|den)?", "")
                    .replaceAll("(?i)uygulamasını|uygulamasini|aç|ac|ara|aramaya gir|şarkısını|sarkisini", " ")
                    .replaceAll("\\s+", " ").trim();
            if (q.length() >= 2) {
                try {
                    Intent search = new Intent(Intent.ACTION_SEARCH);
                    search.setPackage("com.google.android.youtube");
                    search.putExtra(SearchManager.QUERY, q);
                    search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(search);
                    return Result.yes("YouTube'da " + q + " aramasını açıyorum.");
                } catch (Exception ignored) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)));
                        i.setPackage("com.google.android.youtube");
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        context.startActivity(i);
                        return Result.yes("YouTube'da " + q + " aramasını açıyorum.");
                    } catch (Exception ignored2) {}
                }
            }
            if (c.contains("ac") || c.contains("baslat")) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    return Result.yes("YouTube'u açıyorum.");
                }
            }
        }

        if (c.contains("alarm") && (c.contains("kur") || c.contains("ayarla"))) {
            Matcher m = Pattern.compile("(?:saat\\s*)?(\\d{1,2})[\\.:](\\d{1,2})", Pattern.CASE_INSENSITIVE).matcher(raw);
            if (m.find()) {
                int hour = Integer.parseInt(m.group(1));
                int minute = Integer.parseInt(m.group(2));
                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, hour)
                            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, "Lakdoz")
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(i);
                        return Result.yes(String.format(Locale.forLanguageTag("tr-TR"), "%02d:%02d için alarm kurma komutunu gönderdim.", hour, minute));
                    } catch (Exception ignored) {
                        Intent show = new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { context.startActivity(show); } catch (Exception ignored2) {}
                        return Result.yes("Alarm uygulamasını açıyorum; bu telefonda doğrudan alarm oluşturma engellenmiş olabilir.");
                    }
                }
            }
        }

        String[][] apps = {
                {"whatsapp", "com.whatsapp"},
                {"instagram", "com.instagram.android"},
                {"spotify", "com.spotify.music"}
        };
        for (String[] app : apps) {
            if (containsLike(c, app[0], 2) && (c.contains("ac") || c.contains("baslat"))) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(app[1]);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    return Result.yes(app[0] + " açılıyor.");
                }
            }
        }

        return Result.no();
    }


    private boolean isWeatherRequest(String raw, List<HistoryStore.Turn> history) {
        String f = fold(raw);
        if (f.contains("hava") || f.contains("weather") || f.contains("sicaklik") || f.contains("kac derece") ||
                f.contains("yagmur") || f.contains("ruzgar") || f.contains("derece")) return true;

        boolean temporal = f.matches(".*\\b(\\d+\\s*saat\\s*sonra|bir\\s*saat\\s*sonra|sonra|aksam|gece|sabah|yarin|birazdan)\\b.*");
        boolean weatherFollowUp = temporal || f.contains("orada") || f.contains("ayni yer") ||
                f.contains("ayni yerde") || f.contains("peki") || f.contains("saatlik") ||
                f.contains("saatte") || f.contains("saat sonra");
        boolean shortClarification = f.contains("icin") || f.contains("soruyorum") || f.contains("devam et") ||
                f.contains("baska ne") || f.equals("peki");

        // Önceki hava cevabına yalnızca mevcut mesaj da hava ile ilişkili görünüyorsa dön.
        // Böylece “Magaluf hakkında bilgi ver, plajları nasılsın?” gibi konu değişimleri
        // önceki hava yanıtı tarafından yanlışlıkla hava sorgusuna çevrilmez.
        if (history != null && raw != null && raw.trim().length() <= 90 && (weatherFollowUp || shortClarification)) {
            for (int i = history.size() - 1, seen = 0; i >= 0 && seen < 8; i--, seen++) {
                HistoryStore.Turn t = history.get(i);
                String x = fold(t.text);
                if ("assistant".equals(t.role) && (x.contains("icin su an") || x.contains("yagis olasiligi") || x.contains("ruzgar"))) return true;
            }
        }
        return false;
    }

    private String extractWeatherPlace(String raw, List<HistoryStore.Turn> history) {
        String s = raw == null ? "" : raw.trim();
        String f = fold(s);
        String remembered = lastWeatherPlace(history);
        String persisted = contextPrefs.getString("last_weather_place", "");
        if (persisted == null) persisted = "";

        if (mentionsPalma(f)) return "Palma de Mallorca";

        boolean followUp = f.contains("saat") || f.contains("saate") || f.contains("sonra") ||
                f.contains("yagmur") || f.contains("ruzgar") || f.contains("derece") ||
                f.contains("gece") || f.contains("aksam") || f.contains("sabah") ||
                f.contains("yarin") || f.contains("orada") || f.contains("ayni") ||
                f.contains("ortalama") || f.contains("peki");

        if (followUp) {
            if (!remembered.isEmpty()) return remembered;
            if (!persisted.trim().isEmpty()) return persisted.trim();
        }

        String cleaned = s.replaceAll("(?iu)\\b(hava\\s*durumu|hava|kaç\\s*derece|kac\\s*derece|sıcaklık|sicaklik|bugün|bugun|yarın|yarin|nasıl|nasil|ne\\s*kadar|ne|şu\\s*an|su\\s*an|internetten|bak|ara|öğren|ogren|göster|goster|yağmur|yagmur|var\\s*mı|var\\s*mi|olacak|sonra|birazdan|akşam|aksam|gece|sabah|için|icin|soruyorum|ortalama|peki)\\b", " ")
                .replaceAll("(?iu)\\b\\d+\\s*(?:saat|saate|saatte|saatlik)\\b", " ")
                .replaceAll("[?!.]+", " ").replaceAll("\\s+", " ").trim();

        if (looksLikePlace(cleaned)) return cleaned;
        if (!remembered.isEmpty()) return remembered;
        if (!persisted.trim().isEmpty()) return persisted.trim();
        return "";
    }

    private boolean mentionsPalma(String folded) {
        if (folded.contains("palma") || folded.contains("mallorca") || folded.contains("mallorka")) return true;
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() >= 4 && levenshtein(token, "palma") <= 2) return true;
        }
        return false;
    }

    private boolean looksLikePlace(String s) {
        if (s == null) return false;
        String x = s.trim();
        if (x.length() < 2 || x.length() > 80) return false;
        String f = fold(x);
        if (f.matches(".*\\b(saat|sonra|olacak|nasil|neden|nedenini|bugun|yarin|gece|aksam|sabah)\\b.*")) return false;
        return x.matches(".*[A-Za-zÇĞİÖŞÜçğıöşü].*");
    }

    private String lastWeatherPlace(List<HistoryStore.Turn> history) {
        if (history == null) return "";
        Pattern p1 = Pattern.compile("^(.+?)\\s+için\\s+şu\\s+an", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern p2 = Pattern.compile("^(.+?)(?:'da|'de|'ta|'te)\\s+önümüzdeki", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryStore.Turn t = history.get(i);
            if (!"assistant".equals(t.role) || t.text == null) continue;
            String txt = t.text.trim();
            Matcher m = p1.matcher(txt);
            if (m.find()) return simplifyRememberedPlace(m.group(1));
            m = p2.matcher(txt);
            if (m.find()) return simplifyRememberedPlace(m.group(1));
        }
        return "";
    }

    private String simplifyRememberedPlace(String s) {
        if (s == null) return "";
        String x = s.trim();
        int comma = x.indexOf(',');
        if (comma > 0) x = x.substring(0, comma).trim();
        return x;
    }

    private int extractHoursAhead(String raw) {
        String f = fold(raw);
        Matcher m = Pattern.compile("(\\d{1,2})\\s*(?:saat|saate|saatte|saatlik)(?:\\s*sonra)?").matcher(f);
        if (m.find()) {
            try { return Math.max(1, Math.min(48, Integer.parseInt(m.group(1)))); } catch (Exception ignored) {}
        }
        if (f.contains("bir saat sonra")) return 1;
        if (f.contains("iki saat sonra")) return 2;
        if (f.contains("uc saat sonra")) return 3;
        if (f.contains("yarin")) return 24;
        if (f.contains("gece")) return 4;
        if (f.contains("aksam")) return 2;
        if (f.contains("sabah")) return 10;
        if (f.contains("birazdan")) return 1;
        return 0;
    }

    private String fetchWeather(String place, int hoursAhead) throws Exception {
        JSONObject g = resolvePlace(place);
        if (g == null) return place + " için konumu bulamadım. Yazım hatası olabilir; şehir veya bölge adını biraz daha açık yazar mısın?";

        double lat = g.getDouble("latitude"), lon = g.getDouble("longitude");
        String name = g.optString("name", place);
        String admin = g.optString("admin1", "");
        String country = g.optString("country", "");
        String label = name + (admin.isEmpty() ? "" : ", " + admin) + (country.isEmpty() ? "" : ", " + country);
        contextPrefs.edit().putString("last_weather_place", name).putString("last_weather_label", label).apply();

        String forecast = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,apparent_temperature,weather_code,precipitation_probability,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3";
        JSONObject w = getJson(forecast);

        if (hoursAhead > 0) return hourlyWeather(label, w, hoursAhead);

        JSONObject cur = w.getJSONObject("current");
        JSONObject daily = w.getJSONObject("daily");
        double temp = cur.optDouble("temperature_2m", Double.NaN);
        double feels = cur.optDouble("apparent_temperature", Double.NaN);
        double wind = cur.optDouble("wind_speed_10m", Double.NaN);
        int code = cur.optInt("weather_code", -1);
        JSONArray maxs = daily.optJSONArray("temperature_2m_max");
        JSONArray mins = daily.optJSONArray("temperature_2m_min");
        JSONArray rains = daily.optJSONArray("precipitation_probability_max");
        double max = maxs != null && maxs.length() > 0 ? maxs.optDouble(0, Double.NaN) : Double.NaN;
        double min = mins != null && mins.length() > 0 ? mins.optDouble(0, Double.NaN) : Double.NaN;
        int rain = rains != null && rains.length() > 0 ? rains.optInt(0, -1) : -1;

        StringBuilder out = new StringBuilder();
        out.append(label).append(" için şu an ").append(weatherText(code));
        if (!Double.isNaN(temp)) out.append(", ").append(Math.round(temp)).append("°C");
        if (!Double.isNaN(feels)) out.append(" (hissedilen ").append(Math.round(feels)).append("°C)");
        out.append(".");
        if (!Double.isNaN(min) && !Double.isNaN(max)) out.append(" Bugün yaklaşık ").append(Math.round(min)).append("–").append(Math.round(max)).append("°C.");
        if (rain >= 0) out.append(" En yüksek yağış olasılığı %").append(rain).append(".");
        if (!Double.isNaN(wind)) out.append(" Rüzgar ").append(Math.round(wind)).append(" km/sa.");
        return out.toString();
    }

    private JSONObject resolvePlace(String place) throws Exception {
        ArrayList<String> candidates = new ArrayList<>();
        addCandidate(candidates, place);
        String folded = fold(place).replaceAll("\\s+", " ").trim();
        addCandidate(candidates, folded);

        if (folded.contains("mallorca")) {
            if (folded.contains("pal") || folded.contains("palm") || folded.contains("palo") || folded.contains("pama")) addCandidate(candidates, "Palma de Mallorca");
            addCandidate(candidates, "Mallorca");
        }

        String[] parts = folded.split(" ");
        if (parts.length >= 2) {
            addCandidate(candidates, parts[parts.length - 1]);
            addCandidate(candidates, parts[parts.length - 2] + " " + parts[parts.length - 1]);
        }

        for (String q : candidates) {
            String geocode = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q, "UTF-8") + "&count=5&language=tr&format=json";
            JSONObject geo = getJson(geocode);
            JSONArray results = geo.optJSONArray("results");
            if (results != null && results.length() > 0) {
                JSONObject best = chooseBestResult(place, results);
                if (best != null) return best;
            }
        }
        return null;
    }

    private void addCandidate(ArrayList<String> list, String q) {
        if (q == null) return;
        String x = q.trim();
        if (x.length() < 2) return;
        for (String old : list) if (fold(old).equals(fold(x))) return;
        list.add(x);
    }

    private JSONObject chooseBestResult(String original, JSONArray results) {
        String target = fold(original);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String name = fold(r.optString("name", ""));
            String admin = fold(r.optString("admin1", ""));
            String country = fold(r.optString("country", ""));
            int score = 0;
            for (String token : target.split(" ")) {
                if (token.length() < 3) continue;
                if (name.contains(token)) score += 5;
                else if (admin.contains(token)) score += 3;
                else if (country.contains(token)) score += 1;
                else if (levenshtein(token, name) <= 2) score += 2;
            }
            int population = r.optInt("population", 0);
            if (population > 100000) score += 1;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return bestScore >= 2 ? best : null;
    }

    private int levenshtein(String a, String b) {
        if (a == null || b == null) return 99;
        if (a.length() > 20 || b.length() > 30) return 99;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private String hourlyWeather(String label, JSONObject w, int hoursAhead) throws Exception {
        JSONObject hourly = w.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray temps = hourly.getJSONArray("temperature_2m");
        JSONArray feels = hourly.optJSONArray("apparent_temperature");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray rains = hourly.optJSONArray("precipitation_probability");
        JSONArray winds = hourly.optJSONArray("wind_speed_10m");

        String currentIso = w.getJSONObject("current").optString("time", "");
        int base = 0;
        for (int i = 0; i < times.length(); i++) {
            if (times.optString(i).compareTo(currentIso) >= 0) { base = i; break; }
        }
        int idx = Math.min(times.length() - 1, base + hoursAhead);
        String time = times.optString(idx, "");
        String hm = time.length() >= 16 ? time.substring(11, 16) : time;
        double temp = temps.optDouble(idx, Double.NaN);
        double feel = feels == null ? Double.NaN : feels.optDouble(idx, Double.NaN);
        int code = codes.optInt(idx, -1);
        int rain = rains == null ? -1 : rains.optInt(idx, -1);
        double wind = winds == null ? Double.NaN : winds.optDouble(idx, Double.NaN);

        StringBuilder out = new StringBuilder();
        out.append(label).append(" için ").append(hoursAhead).append(" saat sonra");
        if (!hm.isEmpty()) out.append(" (").append(hm).append(" civarı)");
        out.append(" ").append(weatherText(code));
        if (!Double.isNaN(temp)) out.append(", yaklaşık ").append(Math.round(temp)).append("°C");
        if (!Double.isNaN(feel)) out.append(" (hissedilen ").append(Math.round(feel)).append("°C)");
        out.append(".");
        if (rain >= 0) out.append(" Yağış olasılığı %").append(rain).append(".");
        if (!Double.isNaN(wind)) out.append(" Rüzgar ").append(Math.round(wind)).append(" km/sa.");
        return out.toString();
    }


    private Result tryLiveKnowledge(String raw) {
        String f = fold(raw);
        try {
            if (f.matches(".*\\b(euro|dolar|usd|eur|sterlin|gbp)\\b.*") && (f.contains("kac") || f.contains("kur") || f.contains("cevir") || f.contains("eder"))) {
                Matcher amount = Pattern.compile("(\\d+(?:[\\.,]\\d+)?)").matcher(f);
                double value = 1.0;
                if (amount.find()) try { value = Double.parseDouble(amount.group(1).replace(',', '.')); } catch (Exception ignored) {}
                String from = f.contains("usd") || f.contains("dolar") ? "USD" : f.contains("gbp") || f.contains("sterlin") ? "GBP" : "EUR";
                String to = from.equals("EUR") ? (f.contains("dolar") || f.contains("usd") ? "USD" : "TRY") : (f.contains("euro") || f.contains("eur") ? "EUR" : "TRY");
                JSONObject fx = getJson("https://api.frankfurter.app/latest?amount=" + value + "&from=" + from + "&to=" + to);
                JSONObject rates = fx.optJSONObject("rates");
                if (rates != null && rates.has(to)) return Result.yes(String.format(Locale.forLanguageTag("tr-TR"), "%.2f %s yaklaşık %.2f %s.", value, from, rates.optDouble(to), to));
            }

            boolean wikiCue = f.contains("kimdir") || f.contains("nedir") || f.contains("neresi") || f.contains("wikipedia") || f.contains("wiki") || f.contains("internetten bak");
            if (wikiCue) {
                String q = raw.replaceAll("(?iu)\\b(kimdir|nedir|neresi|hakkında|hakkinda|wiki(?:pedia)?|internetten|bak|ara|öğren|ogren)\\b", " ").replaceAll("[?!.]+", " ").replaceAll("\\s+", " ").trim();
                if (q.length() >= 2) {
                    String searchUrl = "https://tr.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + URLEncoder.encode(q, "UTF-8") + "&utf8=1&format=json&srlimit=1";
                    JSONObject root = getJson(searchUrl);
                    JSONObject query = root.optJSONObject("query");
                    JSONArray arr = query == null ? null : query.optJSONArray("search");
                    if (arr != null && arr.length() > 0) {
                        String title = arr.getJSONObject(0).optString("title", q);
                        JSONObject page = getJson("https://tr.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8"));
                        String extract = page.optString("extract", "");
                        if (!extract.isEmpty()) {
                            if (extract.length() > 700) extract = extract.substring(0, 700).trim() + "…";
                            return Result.yes(extract);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return Result.no();
    }