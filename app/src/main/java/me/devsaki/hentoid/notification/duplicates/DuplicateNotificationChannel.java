package me.devsaki.hentoid.notification.duplicates;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Objects;

public class DuplicateNotificationChannel {

    private DuplicateNotificationChannel() {
        throw new IllegalStateException("Utility class");
    }

    static final String ID = "duplicate";

    public static void init(@NonNull final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "Duplicates detection";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(ID, name, importance);
            channel.setSound(null, null);
            channel.setVibrationPattern(null);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager, "notificationManager must not be null");
            notificationManager.createNotificationChannel(channel);
        }
    }
}
