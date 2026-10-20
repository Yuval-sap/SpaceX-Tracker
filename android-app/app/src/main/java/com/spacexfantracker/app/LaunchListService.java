package com.spacexfantracker.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The upcoming launches widget's rows (LaunchListWidget's list, which scrolls inside the widget): every launch still
 * ahead, each a picture drawn in the site's fonts at the widget's width, its tap filling in the launch to open.
 */
public class LaunchListService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Rows(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
    }

    private static final class Rows implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private List<JSONObject> launches = new ArrayList<>();
        private JSONObject site = new JSONObject();

        Rows(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override
        public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            SharedPreferences p = NextLaunchWidget.prefs(context);
            site = NextLaunchWidget.siteOf(p);
            launches = NextLaunchWidget.upcoming(context);
        }

        @Override
        public void onDestroy() { }

        @Override
        public int getCount() {
            return launches.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_list_row);
            if (position < 0 || position >= launches.size()) return row;
            JSONObject l = launches.get(position);
            AppWidgetManager m = AppWidgetManager.getInstance(context);
            int wDp = NextLaunchWidget.sizeDp(context, m, widgetId, 250, 200)[0];
            float d = context.getResources().getDisplayMetrics().density;
            NextLaunchWidget.Style st = new NextLaunchWidget.Style(site.optString("mode", "night"));
            boolean rtl = site.optBoolean("rtl", false);
            Locale locale = LaunchListWidget.localeOf(site.optString("lang", ""));
            String name = l.optString("name");
            long t0 = l.optLong("t0");
            long now = System.currentTimeMillis();
            String when = t0 > 0 ? LaunchListWidget.formatWhen(t0, locale) : "";
            row.setImageViewBitmap(R.id.widget_row, LaunchListWidget.drawRow(context, wDp, d, rtl, st, name, when,
                    t0 > now ? t0 - now : -1, NextLaunchWidget.onHold(site, l.optString("id")), position < launches.size() - 1));
            row.setContentDescription(R.id.widget_row, name + (when.isEmpty() ? "" : ", " + when));
            row.setOnClickFillInIntent(R.id.widget_row, new Intent().putExtra(MainActivity.EXTRA_OPEN_LAUNCH, l.optString("id")));
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position < launches.size() ? launches.get(position).optString("id").hashCode() : position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
