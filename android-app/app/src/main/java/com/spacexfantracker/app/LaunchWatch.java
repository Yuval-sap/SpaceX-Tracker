package com.spacexfantracker.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The launches with a reminder, watched while the app is closed: a launch pushed back (or brought forward), put on hold,
 * or left with no date gets a notification, and its reminders move with it - they were set for the old time. The site
 * hands each one over as its reminder is set (window.AndroidApp.watchLaunch: its name, the time it knows, the texts in
 * the site's language); LaunchWatchJob checks each every 30 minutes in the site's own shared copy of the upcoming
 * launches (SHARED_UPCOMING_URL - no request to Launch Library from the phone), and asks Launch Library itself only
 * about one that isn't in it while still ahead (one far off).
 *
 * Stored as { missionId: { "name", "net", "status", "locale", "texts": { "title", "moved", "hold", "tbd" } } }.
 */
final class LaunchWatch {
    private static final String PREFS = "launch_watch";
    private static final String KEY = "watched";
    static final String LAUNCH_URL = "https://ll.thespacedevs.com/2.2.0/launch/";
    /** the shared copy is too old to go by after this (see checkAll) */
    static final long STALE_MS = 20 * 60 * 1000L;
    /**
     * The site's worker: one copy of Launch Library's next 15 SpaceX launches for everyone, asked again at most every 4
     * minutes - the phone's own Launch Library requests (about 15 an hour from one address) stay the site's, which times
     * them on a launch day
     */
    static final String SHARED_UPCOMING_URL = "https://spacex-weather.yuvisapir24.workers.dev/launches";
    // a launch time that moved by less than this is the same launch time (Launch Library adds seconds)
    private static final long MOVE_THRESHOLD_MS = 5 * 60 * 1000L;
    // after this long past T-0 a launch is not watched any more
    private static final long DONE_AFTER_MS = 3 * 60 * 60 * 1000L;

    private LaunchWatch() { }

    /** the site's word is the newest: its time, name and texts; the status last seen stays as the baseline */
    static synchronized void watch(Context context, String missionId, JSONObject info) {
        JSONObject all = load(context);
        JSONObject prev = all.optJSONObject(missionId);
        try {
            if (prev != null && prev.has("status") && !info.has("status")) info.put("status", prev.optString("status"));
            all.put(missionId, info);
        } catch (JSONException ignored) { }
        save(context, all);
    }

    static synchronized void unwatch(Context context, String missionId) {
        JSONObject all = load(context);
        if (all.remove(missionId) != null) save(context, all);
    }

    static synchronized boolean isEmpty(Context context) {
        return load(context).length() == 0;
    }

    /**
     * on a background thread (LaunchWatchJob): each watched launch as Launch Library has it now - from the shared copy
     * (upcoming: its answer, or null), Launch Library itself only for one ahead and not in it
     */
    static void checkAll(Context context, JSONObject upcoming) {
        java.util.Map<String, JSONObject> shared = new java.util.HashMap<>();
        org.json.JSONArray results = upcoming != null ? upcoming.optJSONArray("results") : null;
        // a copy older than this is not a launch's current time - an hours-old one (Launch Library refusing the worker on
        // a launch day) gave a launch whose time had moved its old time back: a false "new time" and reminders moved
        // to it. Nothing is decided on it; the next check, with a fresh copy, does.
        if (upcoming != null && System.currentTimeMillis() - upcoming.optLong("_fetchedAt", System.currentTimeMillis()) > STALE_MS) {
            return;
        }
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject l = results.optJSONObject(i);
                if (l != null) shared.put(l.optString("id", ""), l);
            }
        }
        JSONObject all;
        synchronized (LaunchWatch.class) {
            all = load(context);
        }
        List<String> ids = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) ids.add(it.next());
        long now = System.currentTimeMillis();
        for (String id : ids) {
            JSONObject w = all.optJSONObject(id);
            if (w == null) continue;
            JSONObject launch = shared.get(id);
            if (launch == null) {
                long knownNet = parseIso(w.optString("net", ""));
                // not among the next launches any more and its time is past: it has flown - nothing to watch
                if (results != null && knownNet > 0 && now > knownNet) {
                    unwatch(context, id);
                    continue;
                }
                launch = fetchJson(LAUNCH_URL + id + "/?mode=list");
            }
            if (launch == null) continue;   // no answer (its limit, no network): next time
            String netStr = launch.optString("net", "");
            long net = parseIso(netStr);
            JSONObject st = launch.optJSONObject("status");
            String status = st != null ? st.optString("abbrev", "") : "";
            if ("Success".equals(status) || "Failure".equals(status) || "Partial Failure".equals(status)
                    || "In Flight".equals(status) || (net > 0 && now - net > DONE_AFTER_MS)) {
                unwatch(context, id);
                continue;
            }
            long oldNet = parseIso(w.optString("net", ""));
            String oldStatus = w.optString("status", "");
            String name = w.optString("name", launch.optString("name", ""));
            JSONObject texts = w.optJSONObject("texts");
            String body = null;
            if (net > 0 && oldNet > 0 && Math.abs(net - oldNet) >= MOVE_THRESHOLD_MS) {
                body = text(texts, "moved", "New time for {name}: {when}")
                        .replace("{name}", name).replace("{when}", formatWhen(net, w.optString("locale", "en")));
                ReminderStore.shift(context, id, net - oldNet);
            } else if (!oldStatus.isEmpty() && !oldStatus.equals(status)) {
                // (the first check only records the status - nothing to compare it with yet)
                if ("Hold".equals(status)) {
                    body = text(texts, "hold", "{name} is on hold").replace("{name}", name);
                } else if (("TBD".equals(status) || "TBC".equals(status)) && "Go".equals(oldStatus)) {
                    body = text(texts, "tbd", "{name}: the new date isn't set yet").replace("{name}", name);
                }
            }
            synchronized (LaunchWatch.class) {
                JSONObject fresh = load(context);
                JSONObject cur = fresh.optJSONObject(id);
                if (cur != null) {
                    try {
                        if (!netStr.isEmpty()) cur.put("net", netStr);
                        cur.put("status", status);
                        fresh.put(id, cur);
                    } catch (JSONException ignored) { }
                    save(context, fresh);
                }
            }
            if (body != null) {
                ReminderReceiver.notify(context, id, text(texts, "title", "Launch update"), body,
                        ("watch:" + id).hashCode() & 0x7FFFFFFF);
            }
        }
    }

    private static String text(JSONObject texts, String key, String fallback) {
        String t = texts != null ? texts.optString(key, "") : "";
        return t.isEmpty() ? fallback : t;
    }

    /** the date and time in the phone's time zone, written the site language's way */
    static String formatWhen(long ms, String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag == null || languageTag.isEmpty() ? "en" : languageTag);
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(new Date(ms));
    }

    /** "2026-10-20T22:14:00Z" (with or without milliseconds) -> epoch ms; 0 when it can't be read */
    static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0;
        String clean = s.replaceAll("\\.\\d+Z$", "Z");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = f.parse(clean);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** GET -> JSON, or null for anything but a 200 with JSON in it */
    static JSONObject fetchJson(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SpaceXTrackerApp");
            if (conn.getResponseCode() != 200) return null;
            String fetchedAt = conn.getHeaderField("X-Fetched-At");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                JSONObject json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                // the site's worker: when it asked Launch Library for this copy - it serves an old one while Launch
                // Library refuses it (its limit), even hours old on a launch day
                long at = parseIso(fetchedAt);
                if (at > 0) json.put("_fetchedAt", at);
                return json;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONObject(prefs.getString(KEY, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void save(Context context, JSONObject all) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, all.toString()).apply();
    }
}
