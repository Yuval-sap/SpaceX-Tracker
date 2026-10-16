package com.spacexfantracker.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.util.SparseArray;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.res.ResourcesCompat;

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
 * The home screen widget: the next launch's photo, its name and the time left, in the language, direction and fonts
 * the owner reads the site in. The site keeps it up to date while the app is open (window.AndroidApp.setNextLaunch:
 * the launch, the next few launches, its words, its direction and mode, and the launch's flight timeline); with the
 * app closed, LaunchWatchJob does every 30 minutes (the site's name for that launch when it gave one, Launch
 * Library's otherwise).
 *
 * Before T-0: the site's title ("SPACEX LAUNCH TRACKER", as its header has it) at the top left; at the bottom the
 * label, then one row with the name (two lines when it's long - the row grows upward) and the time
 * left at its other end, exactly as the site's launch cards show it ("4d 8h 28m", English letters in any language),
 * drawn again every minute while the phone is awake (an alarm that doesn't wake it).
 * From T-0 until the launch leaves the site's hero (missionInFlightWindowMs): at the top "Current launch" with the
 * T+ clock at the other end and the name under it; at the bottom "Status" over the hero's own status - the
 * timeline's current event and its explanation, LIFTOFF in the first 3 minutes without one, MISSION IN PROGRESS after
 * the last - each one drawn at its moment. Then the next launch.
 *
 * The text is drawn here into a picture: a widget can't use the app's fonts (the home screen shows a widget's text in
 * the phone's own font), and the site's look is its fonts - every letter in the first font that has it, as a browser
 * does with "Orbitron, Rubik" (Latin letters and digits in the mode's font, Hebrew, Cyrillic, Arabic in Rubik). Only
 * the T+ clock is the phone's: it ticks every second on its own, which a picture can't. A tap opens the app; while
 * the site's live button is red the widget has one too ("Watch live"), which opens the app playing the broadcast.
 */
public class NextLaunchWidget extends AppWidgetProvider {
    private static final String PREFS = "next_launch_widget";
    static final String ACTION_REFRESH = "com.spacexfantracker.app.WIDGET_REFRESH";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final String IMAGE_FILE = "widget_launch.jpg";
    /** the upcoming launches widget's background: the first launch's photo, softly blurred (LaunchListWidget) */
    static final String LIST_IMAGE_FILE = "widget_list.jpg";
    private static final int IMAGE_MAX_WIDTH = 720;
    /** the site's own for a Falcon flight when it gave none (missionInFlightWindowMs) */
    static final long DEFAULT_FLIGHT_MS = 75 * 60 * 1000L;
    /** the site's live button turns red this long before T-0 (renderHeroLiveButton) */
    static final long LIVE_BEFORE_MS = 60 * 60 * 1000L;
    /** the site shows LIFTOFF this long when no timeline event has come yet */
    private static final int LIFTOFF_SEC = 180;
    /** the text picture's size cap (pixels) - the home screen takes a widget's pictures through a limited channel */
    private static final int MAX_TEXT_PIXELS = 700_000;

    // the glyphs each Latin-only font has (from their cmap tables): code point ranges, first / last
    static final int[] ORBITRON = { 0x20, 0x5D, 0x5F, 0x7E, 0xA0, 0xA3, 0xA8, 0xA8, 0xAF, 0xB0, 0xB4, 0xB4,
            0xB6, 0xB6, 0xB8, 0xB8, 0xBF, 0xCF, 0xD1, 0xD7, 0xD9, 0xDD, 0xDF, 0xEF, 0xF1, 0xF7, 0xF9, 0xFD, 0xFF, 0xFF,
            0x131, 0x131, 0x152, 0x153, 0x160, 0x161, 0x178, 0x178, 0x17D, 0x17E, 0x2013, 0x2014, 0x2018, 0x2019,
            0x201C, 0x201D, 0x2022, 0x2022, 0x2026, 0x2026, 0x20AC, 0x20AC, 0x2212, 0x2212 };
    static final int[] ZEN_DOTS = { 0x20, 0x7E, 0xA0, 0xFF, 0x131, 0x131, 0x152, 0x153, 0x174, 0x178,
            0x1E80, 0x1E85, 0x1E9E, 0x1E9E, 0x1EF2, 0x1EF3, 0x2013, 0x2014, 0x2018, 0x201A, 0x201C, 0x201E,
            0x2022, 0x2022, 0x2026, 0x2026, 0x2039, 0x203A, 0x20AC, 0x20AC, 0x2122, 0x2122, 0x2212, 0x2212 };
    static final int[] AUDIOWIDE = { 0x20, 0x7E, 0xA0, 0x17E, 0x192, 0x192, 0x1FC, 0x1FF, 0x1E80, 0x1E85,
            0x1EF2, 0x1EF3, 0x2013, 0x2014, 0x2018, 0x201A, 0x201C, 0x201E, 0x2020, 0x2022, 0x2026, 0x2026,
            0x2030, 0x2030, 0x2039, 0x203A, 0x20AC, 0x20AC, 0x2122, 0x2122, 0x2212, 0x2212 };
    static final int[] SPACE_GROTESK = { 0x20, 0x7E, 0xA0, 0x180, 0x18F, 0x18F, 0x192, 0x192, 0x1A0, 0x1A1,
            0x1AF, 0x1B0, 0x1C4, 0x1DC, 0x1E6, 0x1E7, 0x1EA, 0x1EB, 0x1FA, 0x21B, 0x22A, 0x22D, 0x230, 0x233,
            0x1E08, 0x1EF9, 0x2010, 0x2010, 0x2012, 0x2015, 0x2018, 0x201A, 0x201C, 0x201E, 0x2020, 0x2022,
            0x2026, 0x2026, 0x2030, 0x2030, 0x2032, 0x2033, 0x2039, 0x203A, 0x20AC, 0x20AC, 0x2122, 0x2122,
            0x2190, 0x2199, 0x2212, 0x2212 };

    private static final SparseArray<Typeface> FONTS = new SparseArray<>();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        render(context);
        LaunchWatchJob.schedule(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        render(context);   // a new size: the text drawn again for it
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
        if (!ACTION_REFRESH.equals(intent.getAction())) return;
        if (flightOver(context)) {
            // the flight has left the site's hero: the next launch (its photo is downloaded - off this thread)
            PendingResult result = goAsync();
            Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    advance(app);
                } catch (Exception ignored) {
                } finally {
                    result.finish();
                }
            }).start();
        } else {
            render(context);
        }
    }

    /** any of the app's three widgets on the home screen (this one, SmallLaunchWidget, LaunchListWidget) */
    static boolean hasWidgets(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        return m != null && (count(context, m, NextLaunchWidget.class) + count(context, m, SmallLaunchWidget.class)
                + count(context, m, LaunchListWidget.class)) > 0;
    }

    static int count(Context context, AppWidgetManager m, Class<?> provider) {
        return m.getAppWidgetIds(new ComponentName(context, provider)).length;
    }

    /** Launch Library's next launches (LaunchWatchJob, with the app closed) - the list widget's when the site's are old */
    static void setLaunchLibraryList(Context context, JSONArray list) {
        prefs(context).edit().putString("ll", list.toString()).putLong("llAt", System.currentTimeMillis()).apply();
    }

    /**
     * The launches still ahead, soonest first ({id, name, t0 (ms), image}): the site's list in its language, the times
     * from Launch Library when it was asked later than the site last gave its list, and its launches the site's list
     * doesn't have (their English names).
     */
    static java.util.List<JSONObject> upcoming(Context context) {
        SharedPreferences p = prefs(context);
        java.util.LinkedHashMap<String, JSONObject> byId = new java.util.LinkedHashMap<>();
        JSONArray site = siteOf(p).optJSONArray("upcoming");
        try {
            if (site != null) {
                for (int i = 0; i < site.length(); i++) {
                    JSONObject l = site.optJSONObject(i);
                    if (l == null || l.optString("id").isEmpty()) continue;
                    byId.put(l.optString("id"), new JSONObject().put("id", l.optString("id")).put("name", l.optString("name"))
                            .put("image", l.optString("image")).put("t0", LaunchWatch.parseIso(l.optString("t0", ""))));
                }
            }
            JSONArray ll = new JSONArray(p.getString("ll", "[]"));
            boolean llNewer = p.getLong("llAt", 0) > p.getLong("siteAt", 0);
            for (int i = 0; i < ll.length(); i++) {
                JSONObject l = ll.optJSONObject(i);
                if (l == null || l.optString("id").isEmpty()) continue;
                JSONObject known = byId.get(l.optString("id"));
                if (known == null) {
                    byId.put(l.optString("id"), new JSONObject().put("id", l.optString("id"))
                            .put("name", l.optString("name").replace(" Block 5", "")).put("image", l.optString("image"))
                            .put("t0", l.optLong("t0")));
                } else if (llNewer && l.optLong("t0") > 0) {
                    known.put("t0", l.optLong("t0"));
                }
            }
        } catch (JSONException ignored) { }
        long now = System.currentTimeMillis();
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONObject l : byId.values()) if (l.optLong("t0") > now) out.add(l);
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.optLong("t0"), b.optLong("t0")));
        return out;
    }

    /** on a background thread - a new photo is downloaded here */
    static void setNext(Context context, String id, String name, long t0, String imageUrl, JSONObject site, boolean fromSite) {
        if (id == null || id.isEmpty() || t0 <= 0) return;
        SharedPreferences p = prefs(context);
        if (!fromSite) {
            // a launch in flight stays until it leaves the hero - Launch Library lists only the ones still ahead
            long curT0 = p.getLong("t0", 0);
            long now = System.currentTimeMillis();
            if (!id.equals(p.getString("id", "")) && curT0 > 0 && now >= curT0 && now < curT0 + flightMs(p, siteOf(p))) {
                render(context);
                return;
            }
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
        if (site != null) e.putString("site", site.toString()).putLong("siteAt", System.currentTimeMillis());
        e.apply();
        if (imageUrl != null && !imageUrl.isEmpty() && !imageUrl.equals(p.getString("imageFor", ""))) {
            if (downloadImage(context, imageUrl, IMAGE_FILE, false)) p.edit().putString("imageFor", imageUrl).apply();
        }
        listImage(context);
        render(context);
        scheduleBackups(context, t0, flightMs(p, siteOf(p)));
    }

    /** the flight over: the next of the launches the site listed, or Launch Library's when it listed none ahead */
    private static void advance(Context context) {
        SharedPreferences p = prefs(context);
        JSONArray list = siteOf(p).optJSONArray("upcoming");
        long now = System.currentTimeMillis();
        String cur = p.getString("id", "");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject l = list.optJSONObject(i);
                if (l == null || cur.equals(l.optString("id"))) continue;
                long t0 = LaunchWatch.parseIso(l.optString("t0", ""));
                if (t0 <= now) continue;
                setNext(context, l.optString("id", ""), l.optString("name", ""), t0, l.optString("image", ""), null, true);
                return;
            }
        }
        LaunchWatchJob.refreshNextLaunch(context);
        if (flightOver(context)) render(context);
    }

    private static boolean flightOver(Context context) {
        SharedPreferences p = prefs(context);
        long t0 = p.getLong("t0", 0);
        return t0 > 0 && System.currentTimeMillis() >= t0 + flightMs(p, siteOf(p));
    }

    /** how long after T-0 the site keeps the launch in its hero - its own figure for this launch, when it gave one */
    static long flightMs(SharedPreferences p, JSONObject site) {
        long ms = p.getString("id", "").equals(site.optString("id")) ? site.optLong("flightMs", 0) : 0;
        return ms > 0 ? ms : DEFAULT_FLIGHT_MS;
    }

    /** all three widgets drawn again, and the next redraw set */
    static void render(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        if (m == null) return;
        boolean big = renderBig(context, m);
        boolean small = SmallLaunchWidget.render(context, m);
        boolean list = LaunchListWidget.render(context, m);

        // the next redraw: the next minute ahead of T-0 (and T-0 itself), the next status change in flight - and the
        // next minute while the list has a launch ahead
        SharedPreferences p = prefs(context);
        JSONObject site = siteOf(p);
        String id = p.getString("id", "");
        long t0 = p.getLong("t0", 0);
        long now = System.currentTimeMillis();
        long flightMs = flightMs(p, site);
        long nextMinute = (now / 60000L + 1) * 60000L + 500;
        long next = Long.MAX_VALUE;
        if (big || small) {
            if (t0 > 0 && now < t0) next = Math.min(nextMinute, t0 + 300);
            else if (t0 > 0 && now < t0 + flightMs)
                next = t0 + nextFlightChangeSec(site, id, (now - t0) / 1000.0, flightMs) * 1000L + 300;
        }
        if (list && !upcoming(context).isEmpty()) next = Math.min(next, nextMinute);
        scheduleTick(context, next == Long.MAX_VALUE ? 0 : next);
    }

    /** this widget (the photo, the name, the time left / the flight) - false when there is none on the screen */
    private static boolean renderBig(Context context, AppWidgetManager m) {
        int[] ids = m.getAppWidgetIds(new ComponentName(context, NextLaunchWidget.class));
        if (ids.length == 0) return false;
        SharedPreferences p = prefs(context);
        JSONObject site = siteOf(p);
        JSONObject labels = site.optJSONObject("labels");
        if (labels == null) labels = new JSONObject();
        Style st = new Style(site.optString("mode", "night"));
        boolean rtl = site.optBoolean("rtl", false);
        String id = p.getString("id", "");
        String name = p.getString("name", "");
        if (name.isEmpty()) name = context.getString(R.string.app_name);
        long t0 = p.getLong("t0", 0);
        long now = System.currentTimeMillis();
        long flightMs = flightMs(p, site);
        boolean ahead = t0 > 0 && now < t0;
        boolean flying = t0 > 0 && !ahead && now < t0 + flightMs;
        long elapsedMs = now - t0;
        String[] status = flying ? flightStatus(site, id, labels, elapsedMs / 1000.0) : null;

        Bitmap photo = loadImage(context);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FROM_WIDGET, true);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float density = context.getResources().getDisplayMetrics().density;
        // "Watch live" while the site's own button is red: from an hour before T-0 (earlier when the site already has
        // the broadcast) until the flight leaves the hero - a tap opens the app and starts the broadcast in it
        JSONObject live = id.equals(site.optString("id")) ? site.optJSONObject("live") : null;
        boolean liveOn = (ahead || flying) && (now >= t0 - LIVE_BEFORE_MS || (live != null && live.optBoolean("early")));
        String liveText = live != null ? live.optString("label", "Watch live") : "Watch live";
        Bitmap pill = liveOn ? drawLivePill(context, liveText, rtl) : null;
        Intent watchIntent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_WATCH_LIVE, id).putExtra(MainActivity.EXTRA_FROM_WIDGET, true);
        PendingIntent watch = PendingIntent.getActivity(context, 1, watchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int wid : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), rtl ? R.layout.widget_rtl : R.layout.widget_ltr);
            if (photo != null) v.setImageViewBitmap(R.id.widget_image, photo);
            // the size it has on the screen now: width x height of the orientation showing
            Bundle o = m.getAppWidgetOptions(wid);
            int wDp = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int hDp = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (wDp <= 0) wDp = 250;
            if (hDp <= 0) hDp = 110;
            float[] pillDp = pill != null ? new float[] { pill.getWidth() / density, pill.getHeight() / density } : null;
            float[] pillAt = new float[2];
            Bitmap text = flying
                    ? drawFlight(context, wDp, hDp, rtl, st, labels.optString("current", "Current launch"), name,
                            labels.optString("status", "STATUS"), status[0], status[1], pillDp, pillAt)
                    : drawCountdown(context, wDp, hDp, rtl, st, site.optString("title", "SPACEX LAUNCH TRACKER"),
                            labels.optString("next", "Next launch"), name, ahead ? t0 - now : -1, pillDp, pillAt);
            v.setImageViewBitmap(R.id.widget_text, text);
            if (pill != null) {
                v.setImageViewBitmap(R.id.widget_live, pill);
                v.setContentDescription(R.id.widget_live, liveText);
                v.setViewPadding(R.id.widget_live_wrap, Math.round(pillAt[0] * density), Math.round(pillAt[1] * density), 0, 0);
                v.setOnClickPendingIntent(R.id.widget_live, watch);
                v.setViewVisibility(R.id.widget_live, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.widget_live, View.GONE);
            }
            // what the picture says, for the screen reader
            v.setContentDescription(R.id.widget_text, flying
                    ? labels.optString("current", "Current launch") + ": " + name + ". " + status[0]
                    : labels.optString("next", "Next launch") + ": " + name);
            if (flying) {
                // "T+00:08:39" as the site's: the clock gives "08:39" in the first hour, "1:08:39" after it
                String format = elapsedMs < 3600_000L ? "T+00:%s" : elapsedMs < 36_000_000L ? "T+0%s" : "T+%s";
                v.setChronometer(R.id.widget_tplus, SystemClock.elapsedRealtime() - elapsedMs, format, true);
                v.setTextColor(R.id.widget_tplus, st.muted);
                v.setViewVisibility(R.id.widget_tplus, View.VISIBLE);
            } else {
                v.setChronometer(R.id.widget_tplus, SystemClock.elapsedRealtime(), null, false);
                v.setViewVisibility(R.id.widget_tplus, View.GONE);
            }
            v.setOnClickPendingIntent(android.R.id.background, tap);   // the widget's frame: the app opens out of it
            m.updateAppWidget(wid, v);
        }
        return true;
    }

    /** a widget's size on the screen now (dp): width x height of the orientation showing */
    static int[] sizeDp(Context context, AppWidgetManager m, int wid, int defW, int defH) {
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Bundle o = m.getAppWidgetOptions(wid);
        int wDp = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return new int[] { wDp > 0 ? wDp : defW, hDp > 0 ? hDp : defH };
    }

    /** a tap that opens the app (growing out of the widget), with an extra for the page when given */
    static PendingIntent openApp(Context context, int code, String extra, String value) {
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FROM_WIDGET, true);
        if (extra != null) open.putExtra(extra, value);
        return PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** the hero's status at that second of the flight: {its text, its explanation} */
    static String[] flightStatus(JSONObject site, String id, JSONObject labels, double elapsed) {
        JSONArray tl = id.equals(site.optString("id")) ? site.optJSONArray("timeline") : null;
        if (tl != null && tl.length() > 0) {
            JSONObject cur = null;
            int at = -1;
            for (int i = 0; i < tl.length(); i++) {
                JSONObject e = tl.optJSONObject(i);
                if (e == null) continue;
                if (e.optDouble("t", 0) > elapsed) break;
                cur = e;
                at = i;
            }
            // the last event stays up only so long (a minute on a Falcon flight, 5 on Starship)
            if (cur != null && at == tl.length() - 1 && elapsed - cur.optDouble("t", 0) > site.optDouble("tail", 60)) cur = null;
            if (cur != null) return new String[] { cur.optString("title", ""), cur.optString("explain", "") };
        }
        if (elapsed < LIFTOFF_SEC) return new String[] { labels.optString("liftoff", "LIFTOFF"), "" };
        return new String[] { labels.optString("inProgress", "MISSION IN PROGRESS"), "" };
    }

    /** the next second of the flight anything shown changes at: an event, LIFTOFF's end, the clock's hour, the end */
    static long nextFlightChangeSec(JSONObject site, String id, double elapsed, long flightMs) {
        double next = flightMs / 1000.0;
        JSONArray tl = id.equals(site.optString("id")) ? site.optJSONArray("timeline") : null;
        double tail = site.optDouble("tail", 60);
        if (tl != null) {
            for (int i = 0; i < tl.length(); i++) {
                JSONObject e = tl.optJSONObject(i);
                if (e == null) continue;
                double t = e.optDouble("t", 0);
                if (t > elapsed) next = Math.min(next, t);
                if (i == tl.length() - 1 && t + tail + 1 > elapsed) next = Math.min(next, t + tail + 1);
            }
        }
        for (double t : new double[] { LIFTOFF_SEC, 3600, 36000 }) if (t > elapsed) next = Math.min(next, t);
        return (long) Math.ceil(next);
    }

    // ---- drawing ----

    /** the mode's fonts and colors - the site's, the night's on the day mode (the widget sits on a dark photo) */
    static final class Style {
        final int nameLatin, nameOther, boldLatin, boldOther, counter;
        final int[] cover;
        final float labelSpacing;
        final int accent, digits, units, status, muted, title;

        Style(String mode) {
            if ("mars".equals(mode)) {
                nameLatin = R.font.zen_dots;
                boldLatin = R.font.zen_dots;
                counter = R.font.zen_dots;
                nameOther = R.font.rubik_regular;
                boldOther = R.font.rubik_regular;
                cover = ZEN_DOTS;
                labelSpacing = 0.1f;
                accent = 0xFFFF8C5A;
                digits = 0xFFFF6B35;
                units = 0xFFC4A080;
                status = 0xFFF9AE8D;
                muted = 0xFFC4A080;
                title = 0xFFF8EFE6;
            } else if ("moon".equals(mode)) {
                nameLatin = R.font.audiowide;
                boldLatin = R.font.audiowide;
                counter = R.font.audiowide;
                nameOther = R.font.rubik_regular;
                boldOther = R.font.rubik_regular;
                cover = AUDIOWIDE;
                labelSpacing = 0.1f;
                accent = 0xFF6ABFFB;
                digits = 0xFF3FA9F5;
                units = 0xFF8FA3BC;
                status = 0xFF3FA9F5;
                muted = 0xFF8FA3BC;
                title = 0xFFEEF3FA;
            } else {
                nameLatin = R.font.orbitron_semibold;
                boldLatin = R.font.orbitron_bold;
                counter = R.font.orbitron_bold;
                nameOther = R.font.rubik_semibold;
                boldOther = R.font.rubik_bold;
                cover = ORBITRON;
                labelSpacing = 0.04f;
                accent = 0xFF34D399;
                digits = 0xFF34D399;
                units = 0xFFD4D4D8;   // the d / h / m a little lighter than the site's (#A1A1AA), on the dark photo
                status = 0xFF34D399;
                muted = 0xFFA1A1AA;
                title = 0xFFFFFFFF;
            }
        }
    }

    /** the picture's scale: the screen's density, less for a very large widget */
    static float scale(Context c, int wDp, int hDp) {
        float s = c.getResources().getDisplayMetrics().density;
        if (wDp * hDp * s * s > MAX_TEXT_PIXELS) s = (float) Math.sqrt(MAX_TEXT_PIXELS / (double) (wDp * hDp));
        return s;
    }

    /** text sizes are sp - the phone's text size setting counts, within reason (the widget doesn't grow with it) */
    static float textScale(Context c, float s) {
        return s * Math.min(1.3f, Math.max(0.85f, c.getResources().getConfiguration().fontScale));
    }

    /**
     * Before T-0 (and after the flight, until the next launch): the title at the top; at the bottom the label row and
     * the name / time-left row. The live button (pillDp: its width and height, or null) goes at the label row's far end,
     * just above the time left; where it goes is written to pillAt (dp).
     */
    private static Bitmap drawCountdown(Context c, int wDp, int hDp, boolean rtl, Style st, String title, String label,
                                        String name, long leftMs, float[] pillDp, float[] pillAt) {
        float s = scale(c, wDp, hDp), k = textScale(c, s);
        int W = Math.round(wDp * s), H = Math.round(hDp * s);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * s, padT = 14 * s, padB = 14 * s;
        int fullW = Math.max(1, Math.round(W - 2 * pad));
        float pillW = pillDp != null ? pillDp[0] * s : 0, pillH = pillDp != null ? pillDp[1] * s : 0;

        // the site's title at the top left in any language, as its header - kept to the left 45% of the widget, clear of
        // the rocket in the middle of most launch photos (smaller to fit there)
        CharSequence titleText = mixed(title, font(c, st.boldLatin), st.cover, font(c, st.boldOther));
        TextPaint titlePaint = paint(font(c, st.boldOther), 14 * k, st.title, -0.025f);
        float tw = Layout.getDesiredWidth(titleText, titlePaint);
        float titleMax = W * 0.45f - pad;
        if (tw > titleMax) titlePaint.setTextSize(titlePaint.getTextSize() * titleMax / tw);
        StaticLayout titleL = layout(titleText, titlePaint, fullW, 1, false);
        draw(cv, titleL, pad, padT);
        float titleBottom = padT + titleL.getHeight();

        // the time left as the cards': the numbers in the mode's color, the letters smaller and muted, left to right
        TextPaint cdPaint = paint(font(c, st.counter), 17 * k, st.digits, 0);
        CharSequence cd = leftMs > 0 ? counterText(leftMs, Math.round(13 * k), st.units) : "\u2014";
        int cw = (int) Math.ceil(Layout.getDesiredWidth(cd, cdPaint)) + 1;
        StaticLayout cdL = layout(cd, cdPaint, cw, 1, false);

        int nameW = Math.max(1, Math.round(W - 2 * pad - cw - 12 * s));
        CharSequence nameText = mixed(name, font(c, st.nameLatin), st.cover, font(c, st.nameOther));
        TextPaint namePaint = paint(font(c, st.nameOther), 17 * k, 0xFFFFFFFF, 0);
        int labelW = Math.max(1, Math.round(fullW - (pillW > 0 ? pillW + 8 * s : 0)));
        StaticLayout labelL = layout(mixed(label, font(c, st.boldLatin), st.cover, font(c, st.boldOther)),
                paint(font(c, st.boldOther), 13 * k, st.accent, st.labelSpacing), labelW, 1, rtl);
        float rowH = Math.max(labelL.getHeight(), pillH);
        // a long name takes two lines - one when two would reach up into the title
        StaticLayout nameL = layout(nameText, namePaint, nameW, 2, rtl);
        if (H - padB - nameL.getHeight() - 3 * s - rowH < titleBottom + 4 * s) {
            nameL = layout(nameText, namePaint, nameW, 1, rtl);
        }
        float nameTop = H - padB - nameL.getHeight();
        float baseline = nameTop + nameL.getLineBaseline(nameL.getLineCount() - 1);
        draw(cv, nameL, rtl ? W - pad - nameW : pad, nameTop);
        draw(cv, cdL, rtl ? pad : W - pad - cw, baseline - cdL.getLineBaseline(0));
        float rowTop = nameTop - 3 * s - rowH;
        draw(cv, labelL, rtl ? W - pad - labelW : pad, rowTop + (rowH - labelL.getHeight()) / 2);
        if (pillW > 0) {
            pillAt[0] = (rtl ? pad : W - pad - pillW) / s;
            pillAt[1] = (rowTop + (rowH - pillH) / 2) / s;
        }
        return b;
    }

    /**
     * In flight: "Current launch" and the name at the top (the T+ clock is the layout's own view, at the top's far
     * end); at the bottom "Status", the status, its explanation. The live button at the "Status" row's far end.
     */
    private static Bitmap drawFlight(Context c, int wDp, int hDp, boolean rtl, Style st, String label, String name,
                                     String statusLabel, String status, String explain, float[] pillDp, float[] pillAt) {
        float s = scale(c, wDp, hDp), k = textScale(c, s);
        int W = Math.round(wDp * s), H = Math.round(hDp * s);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * s, padT = 14 * s, padB = 14 * s;
        int fullW = Math.max(1, Math.round(W - 2 * pad));
        float pillW = pillDp != null ? pillDp[0] * s : 0, pillH = pillDp != null ? pillDp[1] * s : 0;

        // top: "Current launch", the T+ clock at the other end (room kept for it)
        int labelW = Math.max(1, Math.round(fullW - 96 * s));
        TextPaint labelPaint = paint(font(c, st.boldOther), 11 * k, st.accent, st.labelSpacing);
        // the label over the name a little larger, as before T-0
        TextPaint topLabelPaint = paint(font(c, st.boldOther), 13 * k, st.accent, st.labelSpacing);
        StaticLayout labelL = layout(mixed(label, font(c, st.boldLatin), st.cover, font(c, st.boldOther)), topLabelPaint, labelW, 1, rtl);
        draw(cv, labelL, rtl ? W - pad - labelW : pad, padT);
        float nameTop = padT + labelL.getHeight() + 3 * s;

        CharSequence nameText = mixed(name, font(c, st.nameLatin), st.cover, font(c, st.nameOther));
        TextPaint namePaint = paint(font(c, st.nameOther), 17 * k, 0xFFFFFFFF, 0);
        CharSequence statusText = mixed(status, font(c, st.boldLatin), st.cover, font(c, st.boldOther));
        TextPaint statusPaint = paint(font(c, st.boldOther), 17 * k, st.status, -0.02f);
        CharSequence statusLabelText = mixed(statusLabel, font(c, st.boldLatin), st.cover, font(c, st.boldOther));
        CharSequence explainText = mixed(explain, font(c, R.font.space_grotesk_regular), SPACE_GROTESK, font(c, R.font.rubik_regular));
        TextPaint explainPaint = paint(font(c, R.font.rubik_regular), 12 * k, st.muted, 0);
        int statusLabelW = Math.max(1, Math.round(fullW - (pillW > 0 ? pillW + 8 * s : 0)));

        // bottom: "Status" (and the live button), the status, its explanation - as many of the explanation's lines
        // (and the name's) as fit
        for (int nameLines = 2; nameLines >= 1; nameLines--) {
            StaticLayout nameL = layout(nameText, namePaint, fullW, nameLines, rtl);
            float nameBottom = nameTop + nameL.getHeight();
            for (int explainLines = explain.isEmpty() ? 0 : 2; explainLines >= 0; explainLines--) {
                StaticLayout slL = layout(statusLabelText, labelPaint, statusLabelW, 1, rtl);
                float rowH = Math.max(slL.getHeight(), pillH);
                StaticLayout sL = layout(statusText, statusPaint, fullW, 1, rtl);
                StaticLayout eL = explainLines > 0 ? layout(explainText, explainPaint, fullW, explainLines, rtl) : null;
                float total = rowH + 2 * s + sL.getHeight() + (eL != null ? 2 * s + eL.getHeight() : 0);
                float top = H - padB - total;
                if (top < nameBottom + 6 * s && !(nameLines == 1 && explainLines == 0)) continue;
                draw(cv, nameL, pad, nameTop);
                draw(cv, slL, rtl ? W - pad - statusLabelW : pad, top + (rowH - slL.getHeight()) / 2);
                if (pillW > 0) {
                    pillAt[0] = (rtl ? pad : W - pad - pillW) / s;
                    pillAt[1] = (top + (rowH - pillH) / 2) / s;
                }
                top += rowH + 2 * s;
                draw(cv, sL, pad, top);
                if (eL != null) draw(cv, eL, pad, top + sL.getHeight() + 2 * s);
                return b;
            }
        }
        return b;
    }

    /**
     * The site's red live button, small: a red pill with a play mark and its words ("Watch live" in the site's
     * language), drawn at the screen's own density - shown as it is, not stretched.
     */
    private static Bitmap drawLivePill(Context c, String text, boolean rtl) {
        float d = c.getResources().getDisplayMetrics().density;
        float k = textScale(c, d);
        TextPaint tp = paint(font(c, R.font.rubik_semibold), 11 * k, 0xFFFFFFFF, 0);
        CharSequence label = mixed(text, font(c, R.font.space_grotesk_semibold), SPACE_GROTESK, font(c, R.font.rubik_semibold));
        int textW = (int) Math.ceil(Layout.getDesiredWidth(label, tp)) + 1;
        StaticLayout tl = layout(label, tp, textW, 1, rtl);
        float h = Math.max(22 * d, tl.getHeight() + 8 * d), padX = 9 * d, tri = 7 * d, gap = 5 * d;
        int W = Math.round(padX + tri + gap + textW + padX), H = Math.round(h);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0.5f * d, 0.5f * d, W - 0.5f * d, H - 0.5f * d);
        fill.setColor(0xFFDC2626);
        cv.drawRoundRect(r, H / 2f, H / 2f, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(d);
        fill.setColor(0xFFEF4444);
        cv.drawRoundRect(r, H / 2f, H / 2f, fill);
        // the play mark at the start, the words after it - the start is the right in a right-to-left language
        float triX = rtl ? W - padX - tri : padX, textX = rtl ? padX : padX + tri + gap;
        Path play = new Path();
        float cy = H / 2f, th = tri * 1.1f;
        // pointing right in every language, as a play mark does
        play.moveTo(triX, cy - th / 2);
        play.lineTo(triX + tri, cy);
        play.lineTo(triX, cy + th / 2);
        play.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        cv.drawPath(play, fill);
        draw(cv, tl, textX, (H - tl.getHeight()) / 2f);
        return b;
    }

    /** "4d 8h 28m" - the cards' time left: days, hours, minutes */
    static CharSequence counterText(long leftMs, int unitPx, int unitColor) {
        long d = leftMs / DAY_MS;
        long h = (leftMs % DAY_MS) / (60 * 60 * 1000L);
        long m = (leftMs % (60 * 60 * 1000L)) / (60 * 1000L);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        long[] values = { d, h, m };
        String[] units = { "d", "h", "m" };
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.valueOf(values[i]));
            int start = sb.length();
            sb.append(units[i]);
            sb.setSpan(new AbsoluteSizeSpan(unitPx), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(unitColor), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** each letter in the first of the two fonts that has it, as the browser does with "Orbitron, Rubik" */
    static CharSequence mixed(String text, Typeface latin, int[] cover, Typeface other) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            boolean in = covers(cover, text.codePointAt(i));
            int j = i;
            while (j < text.length()) {
                int cp = text.codePointAt(j);
                if (covers(cover, cp) != in) break;
                j += Character.charCount(cp);
            }
            sb.setSpan(new FontSpan(in ? latin : other), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = j;
        }
        return sb;
    }

    private static boolean covers(int[] ranges, int cp) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (cp < ranges[i]) return false;
            if (cp <= ranges[i + 1]) return true;
        }
        return false;
    }

    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface typeface;

        FontSpan(Typeface typeface) {
            this.typeface = typeface;
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            p.setTypeface(typeface);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setTypeface(typeface);
        }
    }

    static TextPaint paint(Typeface typeface, float sizePx, int color, float letterSpacing) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(typeface);
        p.setTextSize(sizePx);
        p.setColor(color);
        p.setLetterSpacing(letterSpacing);
        return p;
    }

    /** the text in the site's direction, starting at the start side of its box, cut with "…" past its lines */
    static StaticLayout layout(CharSequence text, TextPaint paint, int width, int maxLines, boolean rtl) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    static void draw(Canvas cv, StaticLayout l, float x, float y) {
        cv.save();
        cv.translate(x, y);
        l.draw(cv);
        cv.restore();
    }

    static Typeface font(Context c, int res) {
        synchronized (FONTS) {
            Typeface t = FONTS.get(res);
            if (t == null) {
                try {
                    t = ResourcesCompat.getFont(c, res);
                } catch (Exception ignored) { }
                if (t == null) t = Typeface.DEFAULT_BOLD;
                FONTS.put(res, t);
            }
            return t;
        }
    }

    // ---- data and timers ----

    static JSONObject siteOf(SharedPreferences p) {
        try {
            return new JSONObject(p.getString("site", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** the launch with that id as the site last listed it (its name in the site's language, its photo), or null */
    private static JSONObject siteLaunch(SharedPreferences p, String id) {
        JSONArray list = siteOf(p).optJSONArray("upcoming");
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            if (l != null && id.equals(l.optString("id"))) return l;
        }
        return null;
    }

    /** the next redraw - an alarm that doesn't wake the phone: it comes while the screen is on, waits if not */
    static void scheduleTick(Context context, long at) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, NextLaunchWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 7710, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (at <= 0) {
            am.cancel(pi);
            return;
        }
        boolean exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExact(AlarmManager.RTC, at, pi);
            else am.set(AlarmManager.RTC, at, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC, at, pi);
        }
    }

    /** T-0 and the flight's end once more, in case the chain of redraws above was broken (the app updated, killed) */
    private static void scheduleBackups(Context context, long t0, long flightMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long[] at = { t0 + 1000, t0 + flightMs + 1000 };
        for (int i = 0; i < at.length; i++) {
            Intent intent = new Intent(context, NextLaunchWidget.class).setAction(ACTION_REFRESH);
            PendingIntent pi = PendingIntent.getBroadcast(context, 7700 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (at[i] > now) am.set(AlarmManager.RTC, at[i], pi);
            else am.cancel(pi);
        }
    }

    /** the photo, made small enough for a widget (a launch photo can be several MB), kept in the app's files */
    /** on a background thread: the list widget's background for the first launch in it, when that launch changed */
    static void listImage(Context context) {
        SharedPreferences p = prefs(context);
        java.util.List<JSONObject> list = upcoming(context);
        String url = list.isEmpty() ? "" : list.get(0).optString("image", "");
        // "v2|": the photo once more after the blur changed (the softer one)
        String key = "v2|" + url;
        if (url.isEmpty() || key.equals(p.getString("listImageFor", ""))) return;
        if (downloadImage(context, url, LIST_IMAGE_FILE, true)) {
            p.edit().putString("listImageFor", key).apply();
            render(context);
        }
    }

    /** the photo, made small enough for a widget (a launch photo can be several MB), kept in the app's files - blurred too */
    private static boolean downloadImage(Context context, String url, String file, boolean blur) {
        HttpURLConnection conn = null;
        File tmp = new File(context.getCacheDir(), file + ".part");
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
            if (blur) {
                // a light blur: halved twice and doubled twice, each step smoothed (one jump down to an eighth and back
                // was too much - blocky)
                int w = bmp.getWidth(), h = bmp.getHeight();
                Bitmap half = Bitmap.createScaledBitmap(bmp, Math.max(1, w / 2), Math.max(1, h / 2), true);
                Bitmap quarter = Bitmap.createScaledBitmap(half, Math.max(1, w / 4), Math.max(1, h / 4), true);
                half.recycle();
                Bitmap up = Bitmap.createScaledBitmap(quarter, Math.max(1, w / 2), Math.max(1, h / 2), true);
                quarter.recycle();
                Bitmap soft = Bitmap.createScaledBitmap(up, w, h, true);
                up.recycle();
                bmp.recycle();
                bmp = soft;
            }
            try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), file))) {
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

    static Bitmap loadImage(Context context) {
        return loadImage(context, IMAGE_FILE);
    }

    static Bitmap loadImage(Context context, String file) {
        File f = new File(context.getFilesDir(), file);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
