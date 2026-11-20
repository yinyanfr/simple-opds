package io.github.einkopds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.net.Proxy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadService extends Service {
    private static final String CHANNEL = "book_download";
    private static final String CANCEL = "io.github.einkopds.CANCEL_DOWNLOAD";
    private static final int NOTIFICATION_ID = 1;

    interface Observer {
        void onDownloadChanged();
    }

    static final class Job {
        final Uri folder;
        final LibraryStore.Source source;
        final FeedReader.Link link;
        final String title;
        final Http.Call call;

        Job(Uri folder, LibraryStore.Source source, FeedReader.Link link, String title, Proxy proxy) {
            this.folder = folder;
            this.source = source;
            this.link = link;
            this.title = title;
            call = new Http.Call(proxy);
        }
    }

    // State and observers are accessed only on the main thread.
    private static Job active;
    private static UiMessage message;
    private static Observer observer;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Job running;

    static boolean isRunning() {
        return active != null;
    }

    static boolean isCancelling() {
        return active != null && active.call.cancelled();
    }

    static String message(Context context) {
        return message == null ? "" : message.text(context);
    }

    static void observe(Observer next) {
        observer = next;
        next.onDownloadChanged();
    }

    static void unobserve(Observer previous) {
        if (observer == previous) observer = null;
    }

    static void start(Context context, Job job) {
        if (active != null) return;
        active = job;
        update(R.string.download_preparing);
        Intent intent = new Intent(context, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException failure) {
            active = null;
            update(R.string.error_download_start_app);
        }
    }

    static void cancel() {
        if (active != null && !active.call.cancelled()) {
            active.call.cancel();
            update(R.string.download_cancelling);
        }
    }

    static void dismiss() {
        if (active == null) update(null);
    }

    private static void update(int resource, Object... arguments) {
        update(new UiMessage(resource, arguments));
    }

    private static void update(UiMessage next) {
        if (message == next) return;
        message = next;
        if (observer != null) observer.onDownloadChanged();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) cancel();
        Job job = active;
        if (job == null) {
            stopSelf(startId);
        } else if (running != job) {
            running = job;
            try {
                startForeground(NOTIFICATION_ID, notification(job.title));
                if (!job.call.cancelled()) update(R.string.download_title, job.title);
                worker.execute(() -> run(job));
            } catch (RuntimeException failure) {
                finish(job, new UiMessage(R.string.error_download_start));
            }
        }
        return START_NOT_STICKY;
    }

    private void run(Job job) {
        UiMessage result;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EinkOPDS:Download");
        try {
            wakeLock.acquire(30 * 60 * 1000L);
            String name = BookFiles.download(this, job.folder, job.source, job.link, job.title,
                    job.call, (bytes, percent) -> main.post(() -> {
                        if (active == job && !job.call.cancelled()) {
                            if (percent < 0) update(R.string.download_amount, bytes / (1024 * 1024));
                            else update(R.string.download_percent, percent);
                        }
                    }));
            result = new UiMessage(R.string.download_saved, name);
        } catch (Exception failure) {
            result = job.call.cancelled() ? new UiMessage(R.string.download_cancelled)
                    : Http.error(failure, R.string.error_download_failed);
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
        UiMessage finished = result;
        main.post(() -> finish(job, finished));
    }

    private void finish(Job job, UiMessage result) {
        if (active != job) return;
        active = null;
        running = null;
        stopForeground(true);
        stopSelf();
        update(result);
    }

    private Notification notification(String title) {
        Context localized = AppLanguage.localizedContext(this);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent view = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, DownloadService.class).setAction(CANCEL);
        PendingIntent stop = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, localized.getString(R.string.download_channel),
                    NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(localized.getString(R.string.download_notification))
                .setContentText(title)
                .setContentIntent(view)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_close, localized.getString(R.string.cancel_download), stop)
                .build();
    }

    @Override public void onDestroy() {
        if (running != null && active == running) {
            running.call.cancel();
            active = null;
            update(R.string.download_interrupted);
        }
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
