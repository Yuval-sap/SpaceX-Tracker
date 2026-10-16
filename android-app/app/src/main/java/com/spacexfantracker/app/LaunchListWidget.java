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
 * ("4d 8h" - no minutes, for room for the names; "8h 28m" in the last day). All of them in a list that scrolls inside
 * the widget (LaunchListService draws its rows), however small it is made. Behind them the first launch's photo,
 * softly blurred and darkened (NextLaunchWidget.listImage), changing with it. The site's fonts and colors (drawn as
 * pictures, as the large widget's text: NextLaunchWidget). A tap on a row opens that launch's window in the app, on
 * the header the app. Drawn again every minute while the phone is awake.
 */
public class LaunchListWidget extends AppWidgetProvider {
    static final int HEADER_DP = 36, ROW_DP = 52;

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
        String header = labels.optString("upcoming", "Upcoming launches");
        float d = context.getResources().getDisplayMetrics().density;
        Bitmap bg = NextLaunchWidget.loadImage(context, NextLaunchWidget.LIST_IMAGE_FILE);

        // the rows' taps: one opening of the app, each row filling in its launch (setOnClickFillInIntent)
        android.content.Intent template = new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_FROM_WIDGET, true);
        int mutable = android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0;
        android.app.PendingIntent rowTap = android.app.PendingIntent.getActivity(context, 100, template,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | mutable);

        for (int wid : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_list);
            if (bg != null) v.setImageViewBitmap(R.id.widget_list_bg, bg);
            int[] size = NextLaunchWidget.sizeDp(context, m, wid, 250, 200);
            v.setImageViewBitmap(R.id.widget_header, drawHeader(context, size[0], d, rtl, st, header,
                    site.optString("title", "SPACEX LAUNCH TRACKER")));
            v.setContentDescription(R.id.widget_header, header);
            v.setOnClickPendingIntent(R.id.widget_header, NextLaunchWidget.openApp(context, 3, null, null));
            // the list: its rows from LaunchListService (one per widget - each its own width)
            android.content.Intent svc = new android.content.Intent(context, LaunchListService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid);
            svc.setData(android.net.Uri.parse(svc.toUri(android.content.Intent.URI_INTENT_SCHEME)));
            v.setRemoteAdapter(R.id.widget_list, svc);
            v.setEmptyView(R.id.widget_list, R.id.widget_list_empty);
            v.setPendingIntentTemplate(R.id.widget_list, rowTap);
            m.updateAppWidget(wid, v);
        }
        // the rows drawn again (the time left, a new list)
        m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        return true;
    }

    /**
     * The time left without the minutes, for room for the names: "2d 16h" - in the last day "16h 13m" (days and hours
     * would read "0d 16h" then, and the last hour "0d 0h")
     */
    private static CharSequence shortCounter(long leftMs, int unitPx, int unitColor) {
        long d = leftMs / (24 * 60 * 60 * 1000L);
        long h = (leftMs / (60 * 60 * 1000L)) % 24;
        long m = (leftMs / (60 * 1000L)) % 60;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        long[] values = d > 0 ? new long[] { d, h } : new long[] { h, m };
        String[] units = d > 0 ? new String[] { "d", "h" } : new String[] { "h", "m" };
        for (int i = 0; i < 2; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.valueOf(values[i]));
            int start = sb.length();
            sb.append(units[i]);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(unitPx), start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(unitColor), start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** the site's language as the phone formats dates in it ("iw" is Hebrew's old code) */
    static Locale localeOf(String tag) {
        if (tag == null || tag.isEmpty()) return Locale.getDefault();
        if (tag.toLowerCase(Locale.ROOT).startsWith("iw")) tag = "he";
        return Locale.forLanguageTag(tag);
    }

    /** "21 Oct, 01:14" - the date and time in that language's own order and clock (12 / 24 hours), the phone's time zone */
    static String formatWhen(long t0, Locale locale) {
        try {
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMjmm");
            return new SimpleDateFormat(pattern, locale).format(new Date(t0));
        } catch (Exception e) {
            return new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(t0));
        }
    }

    /** the header: the words at the start, in the label's look (the mode's font and accent) */
    /**
     * The header: the words at the start, in the label's look (the mode's font and accent), and the site's title at the
     * other end of the same line, as the large widget and the site's header have it (smaller to fit what's left)
     */
    private static Bitmap drawHeader(Context c, int wDp, float d, boolean rtl, NextLaunchWidget.Style st, String text, String title) {
        float k = NextLaunchWidget.textScale(c, d);
        int W = Math.round(wDp * d), H = Math.round(HEADER_DP * d);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * d, gap = 12 * d;
        int fullW = Math.max(1, Math.round(W - 2 * pad));

        CharSequence labelText = NextLaunchWidget.mixed(text, NextLaunchWidget.font(c, st.boldLatin), st.cover,
                NextLaunchWidget.font(c, st.boldOther));
        TextPaint lp = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.boldOther), 13 * k, st.accent, st.labelSpacing);
        int labelW = Math.max(1, Math.min(Math.round(fullW * 0.55f), (int) Math.ceil(Layout.getDesiredWidth(labelText, lp)) + 1));
        StaticLayout l = NextLaunchWidget.layout(labelText, lp, labelW, 1, rtl);
        float baseline = H - 4 * d - l.getHeight() + l.getLineBaseline(0);
        NextLaunchWidget.draw(cv, l, rtl ? W - pad - labelW : pad, baseline - l.getLineBaseline(0));

        // the title in the room left, its baseline on the words'
        float room = fullW - labelW - gap;
        if (room > 20 * d) {
            CharSequence titleText = NextLaunchWidget.mixed(title, NextLaunchWidget.font(c, st.boldLatin), st.cover,
                    NextLaunchWidget.font(c, st.boldOther));
            TextPaint tp = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.boldOther), 12 * k, st.title, -0.025f);
            float tw = Layout.getDesiredWidth(titleText, tp);
            if (tw > room) {
                tp.setTextSize(tp.getTextSize() * room / tw);
                tw = Layout.getDesiredWidth(titleText, tp);
            }
            int twi = (int) Math.ceil(tw) + 1;
            StaticLayout t = NextLaunchWidget.layout(titleText, tp, twi, 1, false);
            NextLaunchWidget.draw(cv, t, rtl ? pad : W - pad - twi, baseline - t.getLineBaseline(0));
        }
        return b;
    }

    /** a row: the name over the date and time at the start, the time left at the end, a thin line under it */
    static Bitmap drawRow(Context c, int wDp, float d, boolean rtl, NextLaunchWidget.Style st, String name, String when,
                                  long leftMs, boolean line) {
        float k = NextLaunchWidget.textScale(c, d);
        int W = Math.round(wDp * d), H = Math.round(ROW_DP * d);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float pad = 16 * d;

        TextPaint cdPaint = NextLaunchWidget.paint(NextLaunchWidget.font(c, st.counter), 15 * k, st.digits, 0);
        CharSequence cd = leftMs > 0 ? shortCounter(leftMs, Math.round(11 * k), st.units) : "";
        int cw = leftMs > 0 ? (int) Math.ceil(Layout.getDesiredWidth(cd, cdPaint)) + 1 : 0;

        int textW = Math.max(1, Math.round(W - 2 * pad - (cw > 0 ? cw + 10 * d : 0)));
        StaticLayout nameL = NextLaunchWidget.layout(
                NextLaunchWidget.mixed(name, NextLaunchWidget.font(c, st.nameLatin), st.cover, NextLaunchWidget.font(c, st.nameOther)),
                NextLaunchWidget.paint(NextLaunchWidget.font(c, st.nameOther), 13 * k, 0xFFFFFFFF, 0), textW, 1, rtl);
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
