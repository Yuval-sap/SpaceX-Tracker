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
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The home screen widget: the next launch's photo, its name and the time left. The site keeps it up to date while the
 * app is open (window.AndroidApp.setNextLaunch - the name and words in the site's language); with the app closed,
 * LaunchWatchJob does every 30 minutes (Launch Library's English name, unless it's the launch the site last gave).
 * More than a day away it reads "2 d 21 h" (refreshed by those updates); in the last day a live countdown that ticks on
 * its own. A tap opens the app.
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
    static void setNext(Context context, String id, String name, long t0, String imageUrl, JSONObject labels, boolean fromSite) {
        if (id == null || id.isEmpty() || t0 <= 0) return;
        SharedPreferences p = prefs(context);
        if (!fromSite) {
            // Launch Library's own name ("Falcon 9 Block 5 | ..."): the site's (translated) one is kept for the same launch
            name = id.equals(p.getString("id", "")) ? p.getString("name", name) : name.replace(" Block 5", "");
        }
        SharedPreferences.Editor e = p.edit().putString("id", id).putString("name", name).putLong("t0", t0)
                .putString("image", imageUrl == null ? "" : imageUrl);
        if (labels != null) e.putString("labels", labels.toString());
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
        JSONObject labels;
        try {
            labels = new JSONObject(p.getString("labels", "{}"));
        } catch (JSONException e) {
            labels = new JSONObject();
        }
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_next_launch);
        v.setTextViewText(R.id.widget_label, labels.optString("next", "Next launch"));
        String name = p.getString("name", "");
        v.setTextViewText(R.id.widget_name, name.isEmpty() ? context.getString(R.string.app_name) : name);

        Bitmap photo = loadImage(context);
        if (photo != null) v.setImageViewBitmap(R.id.widget_image, photo);

        long t0 = p.getLong("t0", 0);
        long diff = t0 - System.currentTimeMillis();
        if (t0 <= 0) {
            v.setViewVisibility(R.id.widget_chrono, View.GONE);
            v.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
            v.setTextViewText(R.id.widget_countdown, "—");
        } else if (diff > DAY_MS) {
            long days = diff / DAY_MS;
            long hours = (diff % DAY_MS) / (60 * 60 * 1000L);
            v.setViewVisibility(R.id.widget_chrono, View.GONE);
            v.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
            v.setTextViewText(R.id.widget_countdown, days + " " + labels.optString("day", "d") + "  " + hours + " " + labels.optString("hour", "h"));
        } else if (diff > 0) {
            v.setViewVisibility(R.id.widget_countdown, View.GONE);
            v.setViewVisibility(R.id.widget_chrono, View.VISIBLE);
            v.setChronometer(R.id.widget_chrono, SystemClock.elapsedRealtime() + diff, null, true);
            v.setChronometerCountDown(R.id.widget_chrono, true);
        } else {
            v.setViewVisibility(R.id.widget_chrono, View.GONE);
            v.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
            v.setTextViewText(R.id.widget_countdown, labels.optString("now", "Launching now"));
        }

        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
    }

    /** drawn again when the last day starts (the live countdown takes over) and just after T-0 */
    private static void scheduleTicks(Context context, long t0) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long[] at = { t0 - DAY_MS, t0 + 60 * 1000L };
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
