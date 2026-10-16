package com.spacexfantracker.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The home screen widget: the next launch's photo, its name and the time left, in the language, direction and display
 * font the owner reads the site in. The site keeps it up to date while the app is open (window.AndroidApp.setNextLaunch:
 * the launch, the next few launches' names in its language, its words, its direction and mode); with the app closed,
 * LaunchWatchJob does every 30 minutes (the site's name for that launch when it gave one, Launch Library's otherwise).
 * The name and the time left share one row, at its two ends; a long name takes two lines and the row grows upward.
 * The time left exactly as the site's launch cards show it - "4d 8h 28m", English letters in any language, the
 * mode's colors and font - drawn again every minute while the phone is awake (a timer that doesn't wake it).
 * The label and the name: Latin text in the mode's own font (Orbitron / Zen Dots / Audiowide), any other script in
 * Rubik - as the site does (those fonts have Latin letters only). A tap opens the app.
 */
public class NextLaunchWidget extends AppWidgetProvider {
    private static final String PREFS = "next_launch_widget";
    static final String ACTION_REFRESH = "com.spacexfantracker.app.WIDGET_REFRESH";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final String IMAGE_FILE = "widget_launch.jpg";
    private static final int IMAGE_MAX_WIDTH = 720;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        render(context);
        LaunchWatchJob.schedule(context);
    }

    @Override
    public void onEnabled(Context context) {
        LaunchWatchJob.schedule(context);
        // the first widget on the screen: the next launch now, not in up to half an hour
        new Thread(() -> {
            try {
                LaunchWatchJob.refreshNextLaunch(context.getApplicationContext());
            } catch (Exception ignored) { }
        }).start();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) render(context);
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        return m != null && m.getAppWidgetIds(new ComponentName(context, NextLaunchWidget.class)).length > 0;
    }

    /** on a background thread - a new photo is downloaded here */
    static void setNext(Context context, String id, String name, long t0, String imageUrl, JSONObject site, boolean fromSite) {
        if (id == null || id.isEmpty() || t0 <= 0) return;
        SharedPreferences p = prefs(context);
        if (!fromSite) {
            // Launch Library's own name ("Falcon 9 Block 5 | ..."): the site's (translated) one for that launch when the site
            // gave it - its own launch or one of the next few it listed - and its photo
            JSONObject known = siteLaunch(p, id);
            if (known != null) {
                name = known.optString("name", name);
                String img = known.optString("image", "");
                if (!img.isEmpty()) imageUrl = img;
            } else {
                name = name.replace(" Block 5", "");
            }
        }
        SharedPreferences.Editor e = p.edit().putString("id", id).putString("name", name).putLong("t0", t0)
                .putString("image", imageUrl == null ? "" : imageUrl);
        if (site != null) e.putString("site", site.toString());
        e.apply();
        if (imageUrl != null && !imageUrl.isEmpty() && !imageUrl.equals(p.getString("imageFor", ""))) {
            if (downloadImage(context, imageUrl)) p.edit().putString("imageFor", imageUrl).apply();
        }
        render(context);
        scheduleTicks(context, t0);
    }

    static void render(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(context, NextLaunchWidget.class));
        if (ids.length == 0) return;
        SharedPreferences p = prefs(context);
        JSONObject site;
        try {
            site = new JSONObject(p.getString("site", "{}"));
        } catch (JSONException e) {
            site = new JSONObject();
        }
        JSONObject labels = site.optJSONObject("labels");
        if (labels == null) labels = new JSONObject();
        String mode = site.optString("mode", "night");
        RemoteViews v = new RemoteViews(context.getPackageName(), layoutFor(site.optBoolean("rtl", false), mode));

        int accent = "mars".equals(mode) ? 0xFFFF8C5A : "moon".equals(mode) ? 0xFF6ABFFB : 0xFF34D399;
        showText(v, R.id.widget_label_latin, R.id.widget_label_other, labels.optString("next", "Next launch"));
        v.setTextColor(R.id.widget_label_latin, accent);
        v.setTextColor(R.id.widget_label_other, accent);
        String name = p.getString("name", "");
        showText(v, R.id.widget_name_latin, R.id.widget_name_other, name.isEmpty() ? context.getString(R.string.app_name) : name);

        Bitmap photo = loadImage(context);
        if (photo != null) v.setImageViewBitmap(R.id.widget_image, photo);

        // the site's launch cards' colors for this mode: the numbers in its accent, the d / h / m letters muted (the
        // night's on the day mode too - the widget sits on a dark photo, where the day's slate would not read)
        int digits = "mars".equals(mode) ? 0xFFFF6B35 : "moon".equals(mode) ? 0xFF3FA9F5 : 0xFF34D399;
        int units = "mars".equals(mode) ? 0xFFC4A080 : "moon".equals(mode) ? 0xFF8FA3BC : 0xFFA1A1AA;
        int[] nums = { R.id.widget_cd_n1, R.id.widget_cd_n2, R.id.widget_cd_n3 };
        int[] unitIds = { R.id.widget_cd_u1, R.id.widget_cd_u2, R.id.widget_cd_u3 };
        for (int id : nums) v.setTextColor(id, digits);
        for (int id : unitIds) v.setTextColor(id, units);
        v.setTextColor(R.id.widget_cd_text, digits);
        long t0 = p.getLong("t0", 0);
        long diff = t0 - System.currentTimeMillis();
        if (t0 > 0 && diff > 0) {
            long d = diff / DAY_MS;
            long h = (diff % DAY_MS) / (60 * 60 * 1000L);
            long min = (diff % (60 * 60 * 1000L)) / (60 * 1000L);
            v.setTextViewText(R.id.widget_cd_n1, String.valueOf(d));
            v.setTextViewText(R.id.widget_cd_n2, String.valueOf(h));
            v.setTextViewText(R.id.widget_cd_n3, String.valueOf(min));
            for (int id : nums) v.setViewVisibility(id, View.VISIBLE);
            for (int id : unitIds) v.setViewVisibility(id, View.VISIBLE);
            v.setViewVisibility(R.id.widget_cd_text, View.GONE);
        } else {
            for (int id : nums) v.setViewVisibility(id, View.GONE);
            for (int id : unitIds) v.setViewVisibility(id, View.GONE);
            v.setViewVisibility(R.id.widget_cd_text, View.VISIBLE);
            // the cards' own words for it, English like their time left
            v.setTextViewText(R.id.widget_cd_text, t0 > 0 ? "IN PROGRESS" : "\u2014");
        }

        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
        scheduleMinuteTick(context, t0 > 0 && diff > 0);
    }

    /** the next minute's redraw - an alarm that doesn't wake the phone: it comes while the screen is on, waits if not */
    private static void scheduleMinuteTick(Context context, boolean ahead) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, NextLaunchWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 7710, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!ahead) {
            am.cancel(pi);
            return;
        }
        long next = (System.currentTimeMillis() / 60000L + 1) * 60000L + 500;
        boolean exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExact(AlarmManager.RTC, next, pi);
            else am.set(AlarmManager.RTC, next, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC, next, pi);
        }
    }

    /** the layout for the site's direction and the mode's display font */
    private static int layoutFor(boolean rtl, String mode) {
        if ("mars".equals(mode)) return rtl ? R.layout.widget_rtl_zendots : R.layout.widget_ltr_zendots;
        if ("moon".equals(mode)) return rtl ? R.layout.widget_rtl_audiowide : R.layout.widget_ltr_audiowide;
        return rtl ? R.layout.widget_rtl_orbitron : R.layout.widget_ltr_orbitron;
    }

    /** a text in the mode's font when it's all Latin, in Rubik otherwise (the mode fonts have Latin letters only) */
    private static void showText(RemoteViews v, int latinId, int otherId, String text) {
        boolean latin = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp) && cp >= 0x250) {
                latin = false;
                break;
            }
            i += Character.charCount(cp);
        }
        v.setTextViewText(latin ? latinId : otherId, text);
        v.setViewVisibility(latinId, latin ? View.VISIBLE : View.GONE);
        v.setViewVisibility(otherId, latin ? View.GONE : View.VISIBLE);
    }

    /** the launch with that id as the site last listed it (its name in the site's language, its photo), or null */
    private static JSONObject siteLaunch(SharedPreferences p, String id) {
        try {
            JSONObject site = new JSONObject(p.getString("site", "{}"));
            JSONArray list = site.optJSONArray("upcoming");
            if (list == null) return null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject l = list.optJSONObject(i);
                if (l != null && id.equals(l.optString("id"))) return l;
            }
        } catch (JSONException ignored) { }
        return null;
    }

    /** drawn again just after T-0 ("IN PROGRESS") - the minute ticks stop there */
    private static void scheduleTicks(Context context, long t0) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long[] at = { 0, t0 + 60 * 1000L };
        for (int i = 0; i < at.length; i++) {
            Intent intent = new Intent(context, NextLaunchWidget.class).setAction(ACTION_REFRESH);
            PendingIntent pi = PendingIntent.getBroadcast(context, 7700 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (at[i] > now) am.set(AlarmManager.RTC, at[i], pi);
            else am.cancel(pi);
        }
    }

    /** the photo, made small enough for a widget (a launch photo can be several MB), kept in the app's files */
    private static boolean downloadImage(Context context, String url) {
        HttpURLConnection conn = null;
        File tmp = new File(context.getCacheDir(), IMAGE_FILE + ".part");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                long total = 0;
                for (int n; (n = in.read(buf)) != -1; ) {
                    total += n;
                    if (total > 20L * 1024 * 1024) return false;
                    out.write(buf, 0, n);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), bounds);
            if (bounds.outWidth <= 0) return false;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= IMAGE_MAX_WIDTH) opts.inSampleSize *= 2;
            Bitmap bmp = BitmapFactory.decodeFile(tmp.getPath(), opts);
            if (bmp == null) return false;
            if (bmp.getWidth() > IMAGE_MAX_WIDTH) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, IMAGE_MAX_WIDTH,
                        Math.max(1, Math.round(bmp.getHeight() * (IMAGE_MAX_WIDTH / (float) bmp.getWidth()))), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), IMAGE_FILE))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
            bmp.recycle();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Bitmap loadImage(Context context) {
        File f = new File(context.getFilesDir(), IMAGE_FILE);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
