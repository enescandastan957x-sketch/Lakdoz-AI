package com.lakdoz.assistant;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
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
    public LocalCommandRouter(Context context) { this.context = context; }

    public Result tryHandle(String raw) { return tryHandle(raw, null); }

    public Result tryHandle(String raw, List<HistoryStore.Turn> history) {
        String c = fold(raw);

        if (isWeatherRequest(raw, history)) {
            String place = extractWeatherPlace(raw, history);
            if (!place.isEmpty()) {
                try { return Result.yes(fetchWeather(place)); }
                catch (Exception e) { return Result.yes("Şu anda canlı hava verisine ulaşamadım. Biraz sonra tekrar deneyebilirsin."); }
            }
        }

        if (c.contains("youtube")) {
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
            if (c.contains(app[0]) && (c.contains("ac") || c.contains("baslat"))) {
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
        if (f.contains("hava") || f.contains("weather") || f.contains("sicaklik") || f.contains("kac derece") || f.contains("yagmur") || f.contains("ruzgar")) return true;
        if (history != null && raw != null && raw.trim().length() <= 70) {
            for (int i = history.size() - 1, seen = 0; i >= 0 && seen < 4; i--, seen++) {
                HistoryStore.Turn t = history.get(i);
                String x = fold(t.text);
                if (x.contains("hava") || x.contains("sicaklik") || x.contains("kac derece") || x.contains("weather")) return true;
            }
        }
        return false;
    }

    private String extractWeatherPlace(String raw, List<HistoryStore.Turn> history) {
        String s = raw == null ? "" : raw.trim();
        String cleaned = s.replaceAll("(?iu)\\b(hava\\s*durumu|hava|kaç\\s*derece|kac\\s*derece|sıcaklık|sicaklik|bugün|bugun|yarın|yarin|nasıl|nasil|ne\\s*kadar|şu\\s*an|su\\s*an|internetten|bak|ara|öğren|ogren|göster|goster|yağmur|yagmur|var\\s*mı|var\\s*mi)\\b", " ")
                .replaceAll("[?!.]+", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() >= 2) return cleaned;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                HistoryStore.Turn t = history.get(i);
                if (!"user".equals(t.role)) continue;
                String candidate = t.text == null ? "" : t.text.trim();
                String f = fold(candidate);
                if (!f.contains("hava") && !f.contains("sicaklik") && !f.contains("kac derece") && candidate.length() >= 2 && candidate.length() <= 70) return candidate;
            }
        }
        return "";
    }

    private String fetchWeather(String place) throws Exception {
        String geocode = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(place, "UTF-8") + "&count=1&language=tr&format=json";
        JSONObject geo = getJson(geocode);
        JSONArray results = geo.optJSONArray("results");
        if (results == null || results.length() == 0) return place + " için konumu bulamadım. Şehir veya bölge adını biraz daha açık yazabilir misin?";
        JSONObject g = results.getJSONObject(0);
        double lat = g.getDouble("latitude"), lon = g.getDouble("longitude");
        String name = g.optString("name", place);
        String admin = g.optString("admin1", "");
        String country = g.optString("country", "");
        String label = name + (admin.isEmpty() ? "" : ", " + admin) + (country.isEmpty() ? "" : ", " + country);

        String forecast = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1";
        JSONObject w = getJson(forecast);
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

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(9000); c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "LakdozAI/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); c.disconnect();
        return new JSONObject(sb.toString());
    }

    private String weatherText(int code) {
        if (code == 0) return "açık";
        if (code == 1 || code == 2) return "az bulutlu";
        if (code == 3) return "kapalı";
        if (code == 45 || code == 48) return "sisli";
        if (code >= 51 && code <= 57) return "çisenti";
        if (code >= 61 && code <= 67) return "yağmurlu";
        if (code >= 71 && code <= 77) return "karlı";
        if (code >= 80 && code <= 82) return "sağanak yağışlı";
        if (code >= 85 && code <= 86) return "kar sağanaklı";
        if (code >= 95) return "gök gürültülü";
        return "değişken hava";
    }

    private static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return n.replace('ı','i').replace('ş','s').replace('ğ','g').replace('ç','c').replace('ö','o').replace('ü','u');
    }
}
