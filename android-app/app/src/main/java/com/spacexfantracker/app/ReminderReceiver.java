package com.spacexfantracker.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** A reminder's alarm went off: the notification, which opens the app on that mission when tapped. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String EXTRA_MISSION = "mission";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_BODY = "body";
    static final String EXTRA_NOTIFICATION_ID = "notificationId";
    static final String CHANNEL_ID = "launch_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        String missionId = intent.getStringExtra(EXTRA_MISSION);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String body = intent.getStringExtra(EXTRA_BODY);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, (int) (System.currentTimeMillis() & 0x7FFFFFFF));
        if (title == null || title.isEmpty()) title = context.getString(R.string.app_name);

        ensureChannel(context);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;   // the user didn't allow notifications
        }

        // tapping it opens the mission's window in the app (the site opens "?mission=<id>" itself)
        Intent open = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(MainActivity.SITE_URL + (missionId != null ? "?mission=" + Uri.encode(missionId) : "")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, notificationId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF10B981)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(content);
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
        } catch (SecurityException ignored) { }
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        nm.createNotificationChannel(channel);
    }
}
