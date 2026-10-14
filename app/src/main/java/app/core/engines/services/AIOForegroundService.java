package app.core.engines.services;

import static androidx.core.app.NotificationCompat.Builder;
import static app.core.AIOApp.INSTANCE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import org.websnake.vidchain.app.VidChainServiceStarts;

import com.aio.R;

import app.ui.main.MotherActivity;

/**
 * AIOForegroundService is a persistent foreground service that ensures
 * background operations can continue without being interrupted by the Android system.
 * This service shows a silent notification while a download needs it (T9: it no longer restarts itself).
 */
public class AIOForegroundService extends Service {

    private static final String CHANNEL_ID = "AIO System Service";
    private static final int NOTIFICATION_ID = 1430;

    private Notification notification;
    private volatile boolean isServiceRunning = false;   // T9 review: read from the download threads
    @Nullable private static AIOForegroundService instance;

    /**
     * Returns the current instance of the service.
     * @return singleton instance of AIOForegroundService, or null if not yet initialized.
     */
    @Nullable
    public static AIOForegroundService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotification();   // T9 P0b: the notification exists before onStartCommand promotes the service
    }

    /**
     * T8.9 (inherited bug fix): Android 15 stops a dataSync foreground service after 6 h in 24 h and kills the app
     * when the service does not stop itself within seconds of this call. The original had no handler, so the app
     * died while the phone lay idle and the next share always cold-started it.
     * T9 P0b: no new ask until the user opens the app again (that is when Android gives the time back).
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        VidChainServiceStarts.timedOut(this);
        stopService();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Binding not used in this service.
    }

    /**
     * T9 P0b (owner's bug report 2026-10-14, S25 Ultra soft-reboot): the promotion to the foreground is the first thing
     * on every path. A refusal (no time budget left, a start from the background) is recorded and the service stops;
     * nothing asks again until the start gate allows it. START_NOT_STICKY: Android does not restart it either.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (!isServiceRunning) {
            try {
                int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
                isServiceRunning = true;
                VidChainServiceStarts.started(this);
            } catch (RuntimeException error) {
                VidChainServiceStarts.refused(this, error);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (!wanted) stopService();   // the last download ended before the service came up
        return START_NOT_STICKY;
    }

    /**
     * T9 P0b: the service never restarts itself. The original sent a restart broadcast from here; when Android
     * refused the promotion, onCreate stopped the service, this broadcast started it again, and so on: 4,105 starts in
     * 80 s filled system_server's memory. DownloadSystem asks for the service while a download needs it.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        isServiceRunning = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    /**
     * Returns whether the foreground service is currently running.
     */
    public boolean isIsServiceRunning() {
        return this.isServiceRunning;
    }

    /**
     * Stops the service and removes the foreground notification.
     */
    public void stopService() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); // Remove notification immediately
            stopSelf();           // Stop service
            isServiceRunning = false;
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    /**
     * T9 decision 4: true while a download needs the service. The service used to be started every second for as long
     * as the app lived, and restarted itself when Android stopped it; now it runs only while a download waits or runs.
     */
    private static volatile boolean wanted = false;

    public static boolean isWanted() {
        return wanted;
    }

    /**
     * T9 P0b: asks for the service at most as often as the start gate allows (state kept across process restarts).
     * Android 12+ gets a plain start: it carries no promise, so a refusal can never crash the app with
     * ForegroundServiceDidNotStartInTimeException; onStartCommand then promotes it. Older Android has no background-start
     * rule and no time budget, so the foreground start there is always promoted.
     */
    public static void updateService() {
        wanted = true;
        AIOForegroundService running = instance;
        if (running != null && running.isServiceRunning) return;   // T9: already in the foreground, nothing to ask
        if (!VidChainServiceStarts.mayAsk(INSTANCE)) return;
        try {
            Intent serviceIntent = new Intent(INSTANCE, AIOForegroundService.class);
            if (Build.VERSION.SDK_INT >= 31) INSTANCE.startService(serviceIntent);
            else INSTANCE.startForegroundService(serviceIntent);
        } catch (Exception err) {
            VidChainServiceStarts.refused(INSTANCE, err);
        }
    }

    /** T9 decision 4: no download left: leave the foreground and stop (no restart) */
    public static void stopWhenIdle() {
        wanted = false;
        AIOForegroundService running = instance;
        // T9 P0b: a service not yet promoted stops itself in onStartCommand (stopping it before the promotion breaks
        // the foreground-start promise on Android 11 and older)
        if (running != null && running.isServiceRunning) running.stopService();
    }

    /**
     * Creates a notification channel and prepares the foreground notification.
     */
    private void createNotification() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.title_aio_system_service),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
        notification = getCustomNotification();
    }

    /**
     * Builds a custom persistent notification that links back to the main activity.
     * @return a Notification object to be shown in the foreground.
     */
    private Notification getCustomNotification() {
        Intent notificationIntent = new Intent(this, MotherActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.title_aio_video_downloader))
                .setContentText(getString(R.string.title_search_and_download_your_videos))
                .setSmallIcon(R.drawable.ic_launcher_logo_v4)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(true)
                .build();
    }
}