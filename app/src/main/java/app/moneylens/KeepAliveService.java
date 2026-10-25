package app.moneylens;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.service.notification.NotificationListenerService;

/**
 * Always-on foreground task scheduler that keeps notification capture bound and fires
 * BNPL / PTPTN / Bill reminders on time across reboots and task removals unless Force Stopped.
 */
public final class KeepAliveService extends Service {
  public static volatile boolean running = false;
  private static final int FG_ID = 2109;
  private static final String CHANNEL_ID = "scheduler_keepalive";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable heartbeat = new Runnable() {
    @Override public void run() {
      try {
        if (!CaptureService.connected) {
          NotificationListenerService.requestRebind(new ComponentName(KeepAliveService.this, CaptureService.class));
        }
        new Thread(() -> {
          try {
            Automation.checkDueReminders(KeepAliveService.this);
            AlarmReceiver.scheduleNext(KeepAliveService.this);
          } catch (Exception ignored) {}
        }).start();
      } catch (Exception ignored) {}
      handler.postDelayed(this, 60_000L);
    }
  };

  public static void ensureRunning(Context c) {
    try {
      SharedPreferences prefs = c.getSharedPreferences("health", 0);
      if (!prefs.getBoolean("alwaysOnScheduler", true)) return;
      Intent i = new Intent(c, KeepAliveService.class);
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
      else c.startService(i);
    } catch (Exception ignored) {}
  }

  public static void stopScheduler(Context c) {
    try {
      c.stopService(new Intent(c, KeepAliveService.class));
    } catch (Exception ignored) {}
  }

  @Override public void onCreate() {
    super.onCreate();
    running = true;
    startForegroundQuiet();
    handler.post(heartbeat);
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    running = true;
    startForegroundQuiet();
    handler.removeCallbacks(heartbeat);
    handler.post(heartbeat);
    return START_STICKY;
  }

  private void startForegroundQuiet() {
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Always-on task scheduler", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps auto-capture and BNPL/PTPTN payment reminders active in the background.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
      }
      Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent pi = PendingIntent.getActivity(this, 2109, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification n = new Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("MoneyLens Task Scheduler Active")
        .setContentText("Auto-capture & BNPL/PTPTN reminders running")
        .setContentIntent(pi)
        .setOngoing(true)
        .build();
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(FG_ID, n);
      }
    } catch (Exception ignored) {}
  }

  @Override public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    scheduleImmediateRestart(this);
  }

  @Override public void onDestroy() {
    running = false;
    handler.removeCallbacks(heartbeat);
    if (getSharedPreferences("health", 0).getBoolean("alwaysOnScheduler", true)) {
      scheduleImmediateRestart(this);
    }
    super.onDestroy();
  }

  public static void scheduleImmediateRestart(Context c) {
    try {
      AlarmManager am = c.getSystemService(AlarmManager.class);
      if (am == null) return;
      Intent i = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_RESTART_SERVICE);
      PendingIntent pi = PendingIntent.getBroadcast(c, 2110, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      long triggerAt = System.currentTimeMillis() + 1500L;
      try {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
      } catch (SecurityException se) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
      }
    } catch (Exception ignored) {}
  }

  @Override public IBinder onBind(Intent intent) {
    return null;
  }
}
