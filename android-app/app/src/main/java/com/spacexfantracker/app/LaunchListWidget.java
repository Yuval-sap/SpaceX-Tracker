package com.spacexfantracker.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The upcoming launches widget (4x3): a header and the next launches, one row each - the name in the site's language,
 * the date and time in the phone's time zone and the site's language, and the time left as the site's cards show it
 * ("4d 8h 28m") - as many rows as the widget's height has room for (up to 5). The site's fonts and colors (drawn as
 * pictures, as the large widget's text: NextLaunchWidget). A tap on a row opens that launch's window in the app, on
 * the header the app. Drawn again every minute while the phone is awake.
 */
public class LaunchListWidget extends AppWidgetProvider {
    private static final int MAX_ROWS = 5;
    private static final int HEADER_DP = 36, ROW_DP = 52;
    private static final int[] ROW_IDS = { R.id.widget_row0, R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4 };

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
        int[] ids = m.getAppWidgetIds(new ComponentName(context, LaunchListWidget.class));
        if (ids.length == 0) return false;
        SharedPreferences p = NextLaunchWidget.prefs(context);
        JSONObject site = NextLaunchWidget.siteOf(p);
        JSONObject labels = site.optJSONObject("labels");
        if (labels == null) labels = new JSONObject();
        NextLaunchWidget.Style st = new NextLaunchWidget.Style(site.optString("mode", "night"));
        boolean rtl = site.optBoolean("rtl", false);
        Locale locale = localeOf(site.optString("lang", ""));
        String header = labels.optString("upcoming", "Upcoming launches");
        List<JSONObject> launches = NextLaunchWidget.upcoming(context);
        float d = context.getResources().getDisplayMetrics().density;
        long now = System.currentTimeMillis();

        for (int wid : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_list);
            int[] size = NextLaunchWidget.sizeDp(context, m, wid, 250, 200);
            int rows = Math.max(1, Math.min(MAX_ROWS, (size[1] - HEADER_DP - 8) / ROW_DP));
            v.setImageViewBitmap(R.id.widget_header, drawHeader(context, size[0], d, rtl, st, header));
            v.setContentDescription(R.id.widget_header, header);
            v.setOnClickPendingIntent(R.id.widget_header, NextLaunchWidget.openApp(context, 3, null, null));
            for (int i = 0; i < MAX_ROWS; i++) {
                if (i >= rows || (i >= launches.size() && !(i == 0 && launches.isEmpty()))) {
                    v.setViewVisibility(ROW_IDS[i], View.GONE);
                    continue;
                }
                JSONObject l = i < launches.size() ? launches.get(i) : null;
                String name = l != null ? l.optString("name") : "—";
                long t0 = l != null ? l.optLong("t0") : 0;
                String when = t0 > 0 ? formatWhen(t0, locale) : "";
                boolean last = i == Math.min(rows, Math.max(1, launches.size())) - 1;
                v.setImageViewBitmap(ROW_IDS[i], drawRow(context, size[0], d, rtl, st, name, when, t0 > now ? t0 - now : -1, !last));
                v.setContentDescription(ROW_IDS[i], name + (when.isEmpty() ? "" : ", " + when));
                v.setOnClickPendingIntent(ROW_IDS[i], l != null
                        ? NextLaunchWidget.openApp(context, 100 + i, MainActivity.EXTRA_OPEN_LAUNCH, l.optString("id"))
                        : NextLaunchWidget.openApp(context, 3, null, null));
                v.setViewVisibility(ROW_IDS[i], View.VISIBLE);
            }
            m.updateAppWidget(wid, v);
        }
        return true;
    }

    /** the site's language as the phone formats dates in it ("iw" is Hebrew's old code) */
    private static Locale localeOf(String tag) {
        if (tag == null || tag.isEmpty()) return Locale.getDefault();
        if (tag.toLowerCase(Locale.ROOT).startsWith("iw")) tag = "he";
        return Locale.forLanguageTag(tag);
    }

    /** "21 Oct, 01:14" - the date and time in that language's own order and clock (12 / 24 hours), the phone's time zone */
    private static String formatWhen(long t0, Locale locale) {
        try {
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMjmm");
            return new SimpleDateFormat(pattern, locale).format(new Date(t0));
        } catch (Exception e) {
            return new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(t0));
        }
    }

    /** the header: the words at the start, in the label's look (the mode's font and accent) */
    private static Bitmap drawHeader(Context c, int wDp, float d, boolean rtl, NextLaunchWidget.Style st, String text) {
        float k = NextLaunchWidget.textScale(c, d);
        int W = Math.round(wDp * d), H = Math.round(HEADER_DP * d);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * d;
        int fullW = Math.max(1, Math.round(W - 2 * pad));
        StaticLayout l = NextLaunchWidget.layout(
                NextLaunchWidget.mixed(text, NextLaunchWidget.font(c, st.boldLatin), st.cover, NextLaunchWidget.font(c, st.boldOther)),
                NextLaunchWidget.paint(NextLaunchWidget.font(c, st.boldOther), 13 * k, st.accent, st.labelSpacing), fullW, 1, rtl);
        NextLaunchWidget.draw(cv, l, pad, H - 4 * d - l.getHeight());
        return b;
    }

    /** a row: the name over the date and time at the start, the time left at the end, a thin line under it */
    private static Bitmap drawRow(Context c, int wDp, float d, boolean rtl, NextLaunchWidget.Style st, String name, String when,
                                  long leftMs, boolean line) {
        float k = NextLaunchWidget.textScale(c, d);
        int W = Math.round(wDp * d), H = Math.round(ROW_DP * d);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * d;

        TextPaint cdPaint = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.counter), 15 * k, st.digits, 0);
        CharSequence cd = leftMs > 0 ? NextLaunchWidget.counterText(leftMs, Math.round(11 * k), st.units) : "";
        int cw = leftMs > 0 ? (int) Math.ceil(Layout.getDesiredWidth(cd, cdPaint)) + 1 : 0;

        int textW = Math.max(1, Math.round(W - 2 * pad - (cw > 0 ? cw + 10 * d : 0)));
        StaticLayout nameL = NextLaunchWidget.layout(
                NextLaunchWidget.mixed(name, NextLaunchWidget.font(c, st.nameLatin), st.cover, NextLaunchWidget.font(c, st.nameOther)),
                NextLaunchWidget.paint(NextLaunchWidget.font(c, st.nameOther), 14 * k, 0xFFFFFFFF, 0), textW, 1, rtl);
        StaticLayout whenL = NextLaunchWidget.layout(
                NextLaunchWidget.mixed(when, NextLaunchWidget.font(c, R.font.space_grotesk_regular), NextLaunchWidget.SPACE_GROTESK,
                        NextLaunchWidget.font(c, R.font.rubik_regular)),
                NextLaunchWidget.paint(NextLaunchWidget.font(c, R.font.rubik_regular), 11 * k, st.muted, 0), textW, 1, rtl);
        float textH = nameL.getHeight() + 2 * d + (when.isEmpty() ? 0 : whenL.getHeight());
        float top = (H - textH) / 2;
        float textX = rtl ? W - pad - textW : pad;
        NextLaunchWidget.draw(cv, nameL, textX, top);
        if (!when.isEmpty()) NextLaunchWidget.draw(cv, whenL, textX, top + nameL.getHeight() + 2 * d);

        if (cw > 0) {
            StaticLayout cdL = NextLaunchWidget.layout(cd, cdPaint, cw, 1, false);
            NextLaunchWidget.draw(cv, cdL, rtl ? pad : W - pad - cw, (H - cdL.getHeight()) / 2f);
        }
        if (line) {
            Paint lp = new Paint();
            lp.setColor(0x1FFFFFFF);
            lp.setStrokeWidth(Math.max(1, d * 0.75f));
            cv.drawLine(pad, H - 1, W - pad, H - 1, lp);
        }
        return b;
    }
}
