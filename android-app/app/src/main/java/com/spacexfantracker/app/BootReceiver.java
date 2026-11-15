package com.spacexfantracker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The system clears every alarm on a restart (and an app update can too): the reminders still ahead are set again.
 *  Also once exact alarms are allowed in the settings: set again as exact ones. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
            ReminderStore.rearmAll(context);
        }
    }
}
