package com.spacexfantracker.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * The small home screen widget (2x2): the next launch's photo, its name and the time left - the same launch, words,
 * fonts and colors as the large one (NextLaunchWidget, which keeps the data and draws all three widgets). In flight:
 * "Current launch", the name, the hero's status, and the T+ clock at the bottom (ticking every second on its own).
 * A tap opens the app.
 */
public class SmallLaunchWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        NextLaunchWidget.render(context);
        LaunchWatchJob.schedule(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        NextLaunchWidget.render(context);
    }

    @Override
    public void onEnabled(Context context) {
        LaunchWatchJob.schedule(context);
        new Thread(() -> {
            try {
                LaunchWatchJob.refreshNextLaunch(context.getApplicationContext());
            } catch (Exception ignored) { }
        }).start();
    }

    /** false when there is none on the screen */
    static boolean render(Context context, AppWidgetManager m) {
        int[] ids = m.getAppWidgetIds(new ComponentName(context, SmallLaunchWidget.class));
        if (ids.length == 0) return false;
        SharedPreferences p = NextLaunchWidget.prefs(context);
        JSONObject site = NextLaunchWidget.siteOf(p);
        JSONObject labels = site.optJSONObject("labels");
        if (labels == null) labels = new JSONObject();
        NextLaunchWidget.Style st = new NextLaunchWidget.Style(site.optString("mode", "night"));
        boolean rtl = site.optBoolean("rtl", false);
        String id = p.getString("id", "");
        String name = p.getString("name", "");
        if (name.isEmpty()) name = context.getString(R.string.app_name);
        long t0 = p.getLong("t0", 0);
        long now = System.currentTimeMillis();
        boolean ahead = t0 > 0 && now < t0;
        boolean flying = t0 > 0 && !ahead && now < t0 + NextLaunchWidget.flightMs(p, site);
        long elapsedMs = now - t0;
        String status = flying ? NextLaunchWidget.flightStatus(site, id, labels, elapsedMs / 1000.0)[0] : null;
        String label = flying ? labels.optString("current", "Current launch") : labels.optString("next", "Next launch");

        Bitmap photo = NextLaunchWidget.loadImage(context);
        for (int wid : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), rtl ? R.layout.widget_small_rtl : R.layout.widget_small_ltr);
            if (photo != null) v.setImageViewBitmap(R.id.widget_image, photo);
            int[] size = NextLaunchWidget.sizeDp(context, m, wid, 120, 120);
            v.setImageViewBitmap(R.id.widget_text, draw(context, size[0], size[1], rtl, st, label, name, ahead ? t0 - now : -1,
                    NextLaunchWidget.onHold(site, id), status));
            v.setContentDescription(R.id.widget_text, label + ": " + name + (status != null ? ". " + status : ""));
            if (flying) {
                String format = elapsedMs < 3600_000L ? "T+00:%s" : elapsedMs < 36_000_000L ? "T+0%s" : "T+%s";
                v.setChronometer(R.id.widget_tplus, SystemClock.elapsedRealtime() - elapsedMs, format, true);
                v.setTextColor(R.id.widget_tplus, st.muted);
                v.setViewVisibility(R.id.widget_tplus, View.VISIBLE);
            } else {
                v.setChronometer(R.id.widget_tplus, SystemClock.elapsedRealtime(), null, false);
                v.setViewVisibility(R.id.widget_tplus, View.GONE);
            }
            v.setOnClickPendingIntent(android.R.id.background, NextLaunchWidget.openApp(context, 2, null, null));
            m.updateAppWidget(wid, v);
        }
        return true;
    }

    /**
     * The label at the top; at the bottom the name over the time left (smaller to fit a narrow widget) - or, in flight,
     * the name over the status, above the T+ clock (the layout's own view, the bottom kept for it).
     */
    private static Bitmap draw(Context c, int wDp, int hDp, boolean rtl, NextLaunchWidget.Style st, String label, String name,
                               long leftMs, boolean hold, String status) {
        float s = NextLaunchWidget.scale(c, wDp, hDp), k = NextLaunchWidget.textScale(c, s);
        int W = Math.round(wDp * s), H = Math.round(hDp * s);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 12 * s, padT = 12 * s, padB = 12 * s;
        int fullW = Math.max(1, Math.round(W - 2 * pad));

        StaticLayout labelL = NextLaunchWidget.layout(
                NextLaunchWidget.mixed(label, NextLaunchWidget.font(c, st.boldLatin), st.cover, NextLaunchWidget.font(c, st.boldOther)),
                NextLaunchWidget.paint(NextLaunchWidget.font(c, st.boldOther), 11 * k, st.accent, st.labelSpacing), fullW, 1, rtl);
        NextLaunchWidget.draw(cv, labelL, pad, padT);
        float labelBottom = padT + labelL.getHeight();

        StaticLayout bottomL;
        float bottomTop;
        if (status != null) {
            // the status just above the T+ clock (about 18dp at the bottom)
            TextPaint sp = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.boldOther), 13 * k, st.status, -0.02f);
            CharSequence statusText = NextLaunchWidget.mixed(status, NextLaunchWidget.font(c, st.boldLatin), st.cover,
                    NextLaunchWidget.font(c, st.boldOther));
            NextLaunchWidget.fitToWidth(statusText, sp, fullW);   // one line, all of it - smaller rather than cut
            bottomL = NextLaunchWidget.layout(statusText, sp, fullW, 1, rtl);
            bottomTop = H - padB - 18 * s - 2 * s - bottomL.getHeight();
            NextLaunchWidget.draw(cv, bottomL, pad, bottomTop);
        } else {
            // the time left, left to right, at the start side - smaller when it doesn't fit the width
            TextPaint cdPaint = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.counter), 17 * k,
                    NextLaunchWidget.countdownColor(st, leftMs, hold), 0);
            CharSequence cd = leftMs > 0 ? NextLaunchWidget.counterText(leftMs, Math.round(13 * k), st.units) : "—";
            float cw = Layout.getDesiredWidth(cd, cdPaint);
            if (cw > fullW) {
                float f = fullW / cw;
                cdPaint.setTextSize(cdPaint.getTextSize() * f);
                if (leftMs > 0) cd = NextLaunchWidget.counterText(leftMs, Math.round(13 * k * f), st.units);
                cw = Layout.getDesiredWidth(cd, cdPaint);
            }
            int cwi = (int) Math.ceil(cw) + 1;
            bottomL = NextLaunchWidget.layout(cd, cdPaint, cwi, 1, false);
            bottomTop = H - padB - bottomL.getHeight();
            NextLaunchWidget.draw(cv, bottomL, rtl ? W - pad - cwi : pad, bottomTop);
        }

        // the name over it: two lines when they fit under the label, else one
        CharSequence nameText = NextLaunchWidget.mixed(name, NextLaunchWidget.font(c, st.nameLatin), st.cover,
                NextLaunchWidget.font(c, st.nameOther));
        TextPaint namePaint = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.nameOther), 14 * k, 0xFFFFFFFF, 0);
        StaticLayout nameL = NextLaunchWidget.layout(nameText, namePaint, fullW, 2, rtl);
        if (bottomTop - 3 * s - nameL.getHeight() < labelBottom + 4 * s) nameL = NextLaunchWidget.layout(nameText, namePaint, fullW, 1, rtl);
        NextLaunchWidget.draw(cv, nameL, pad, bottomTop - 3 * s - nameL.getHeight());
        return b;
    }
}
