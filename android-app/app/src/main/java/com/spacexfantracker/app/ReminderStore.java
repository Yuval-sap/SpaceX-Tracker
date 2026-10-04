package com.spacexfantracker.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Launch reminders set from the site (window.AndroidApp.scheduleReminders): each one is a system alarm, so the
 * notification arrives even when the app is closed. Kept in SharedPreferences as well, because the system forgets
 * every alarm when the phone restarts - BootReceiver sets them again from here.
 *
 * Stored as { missionId: [ { "at": epochMs, "title": "...", "body": "..." }, ... ] }.
 */
final class ReminderStore {
    private static final String PREFS = "launch_reminders";
    private static final String KEY = "reminders";
    static final int MAX_PER_MISSION = 8;

    private ReminderStore() { }

    static synchronized void schedule(Context context, String missionId, JSONArray items) {
        cancelAlarms(context, missionId);
        JSONObject all = load(context);
        JSONArray kept = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length() && kept.length() < MAX_PER_MISSION; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optLong("at", 0) <= now) continue;
            kept.put(item);
        }
        try {
            if (kept.length() > 0) all.put(missionId, kept); else all.remove(missionId);
        } catch (JSONException ignored) { }
        save(context, all);
        setAlarms(context, missionId, kept);
    }

    static synchronized void cancel(Context context, String missionId) {
        cancelAlarms(context, missionId);
        JSONObject all = load(context);
        all.remove(missionId);
        save(context, all);
    }

    /** After a restart or an app update: every reminder still ahead is set again; past ones are dropped. */
    static synchronized void rearmAll(Context context) {
        JSONObject all = load(context);
        JSONObject fresh = new JSONObject();
        long now = System.currentTimeMillis();
        Iterator<String> ids = all.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONArray items = all.optJSONArray(id);
            if (items == null) continue;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && item.optLong("at", 0) > now) kept.put(item);
            }
            if (kept.length() == 0) continue;
            try { fresh.put(id, kept); } catch (JSONException ignored) { }
            setAlarms(context, id, kept);
        }
        save(context, fresh);
    }

    // ------------------------------------------------------------------ alarms

    private static void setAlarms(Context context, String missionId, JSONArray items) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            long at = item.optLong("at", 0);
            Intent intent = new Intent(context, ReminderReceiver.class)
                    .putExtra(ReminderReceiver.EXTRA_MISSION, missionId)
                    .putExtra(ReminderReceiver.EXTRA_TITLE, item.optString("title", ""))
                    .putExtra(ReminderReceiver.EXTRA_BODY, item.optString("body", ""))
                    .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode(missionId, i));
            PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(missionId, i), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Exact when the system allows it (Android 12+ asks the user for "alarms & reminders"); otherwise the
            // closest the system allows - in deep sleep that can be a few minutes late
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        }
    }

    private static void cancelAlarms(Context context, String missionId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < MAX_PER_MISSION; i++) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(missionId, i), intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    static int requestCode(String missionId, int index) {
        return (missionId.hashCode() & 0x0FFFFFFF) * MAX_PER_MISSION + index;
    }

    // ------------------------------------------------------------------ storage

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
