package com.spacexfantracker.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Every 30 minutes, with the app closed too (the system runs it, and again after a restart): the launches with a
 * reminder are checked for a new time or a hold (LaunchWatch), and the home screen widget gets the next launch
 * (NextLaunchWidget). Nothing to do - no reminders, no widget - means no request at all.
 */
public class LaunchWatchJob extends JobService {
    private static final int JOB_ID = 4417;
    private static final long PERIOD_MS = 30 * 60 * 1000L;
    private static final String UPCOMING_URL =
            "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?lsp__id=121&limit=6&mode=list";

    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, LaunchWatchJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Context context = getApplicationContext();
        new Thread(() -> {
            try {
                refreshNextLaunch(context);
            } catch (Exception ignored) { }
            try {
                if (!LaunchWatch.isEmpty(context)) LaunchWatch.checkAll(context);
            } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** the widget's next launch from Launch Library (in English - the site's own name for it is kept when it's the same) */
    static void refreshNextLaunch(Context context) {
        if (!NextLaunchWidget.hasWidgets(context)) return;
        JSONObject data = LaunchWatch.fetchJson(UPCOMING_URL);
        JSONArray results = data != null ? data.optJSONArray("results") : null;
        if (results == null) {
            NextLaunchWidget.render(context);   // at least the countdown moves on
            return;
        }
        long now = System.currentTimeMillis();
        // all of them for the upcoming launches widget (the site's names kept for the ones it listed)
        JSONArray list = new JSONArray();
        for (int i = 0; i < results.length(); i++) {
            JSONObject l = results.optJSONObject(i);
            if (l == null) continue;
            try {
                list.put(new JSONObject().put("id", l.optString("id", "")).put("name", l.optString("name", ""))
                        .put("image", l.optString("image", "")).put("t0", LaunchWatch.parseIso(l.optString("net", ""))));
            } catch (org.json.JSONException ignored) { }
        }
        NextLaunchWidget.setLaunchLibraryList(context, list);
        NextLaunchWidget.listImage(context);
        for (int i = 0; i < results.length(); i++) {
            JSONObject l = results.optJSONObject(i);
            if (l == null) continue;
            long net = LaunchWatch.parseIso(l.optString("net", ""));
            if (net <= now) continue;
            NextLaunchWidget.setNext(context, l.optString("id", ""), l.optString("name", ""), net,
                    l.optString("image", ""), null, false);
            return;
        }
        NextLaunchWidget.render(context);
    }
}
