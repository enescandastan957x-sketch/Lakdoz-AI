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

    public Result tryHandle(String raw) {
        String c = fold(raw);

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

    private static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return n.replace('ı','i').replace('ş','s').replace('ğ','g').replace('ç','c').replace('ö','o').replace('ü','u');
    }
}
