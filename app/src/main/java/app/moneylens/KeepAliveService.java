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
  public static final String ACTION_QUICK_ENTRY = "app.moneylens.ACTION_QUICK_ENTRY";
  public static final String KEY_QUICK_ENTRY = "ml_quick_entry_text";
  public static final int QUICK_FEEDBACK_ID = 2111;
  private static final int FG_ID = 2109;
  private static final String CHANNEL_ID = "scheduler_keepalive";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean startupSynced = false;
  private boolean stoppedSelfBecauseDenied = false;
  private final Runnable watchdog = new Runnable() {
    @Override public void run() {
      try {
        SharedPreferences prefs = getSharedPreferences("health", 0);
        if (!prefs.getBoolean("alwaysOnScheduler", true)) {
          stopSelf();
          return;
        }
        boolean eco = !Vault.hasMeta(KeepAliveService.this) || Vault.fastBool(KeepAliveService.this, "ecoCapture", true);
        if (!CaptureService.connected) {
          NotificationListenerService.requestRebind(new ComponentName(KeepAliveService.this, CaptureService.class));
          // Retry listener rebind gently every 5 minutes only while disconnected.
          handler.postDelayed(this, 5 * 60_000L);
          return;
        }
        if (!eco) {
          syncRemindersAsync();
          handler.postDelayed(this, 15 * 60_000L);
        }
        // In Eco Mode when CaptureService is connected, sleep at 0% CPU until OS/Alarm triggers.
      } catch (Exception ignored) {}
    }
  };

  private void syncRemindersAsync() {
    new Thread(() -> {
      try {
        Vault.configureClassifierLazy(KeepAliveService.this);
        Automation.checkDueReminders(KeepAliveService.this);
        AlarmReceiver.scheduleNext(KeepAliveService.this);
      } catch (Exception ignored) {}
    }, "ml-scheduler-sync").start();
  }

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
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    SharedPreferences prefs = getSharedPreferences("health", 0);
    if (!prefs.getBoolean("alwaysOnScheduler", true)) {
      running = false;
      stopSelf();
      return START_NOT_STICKY;
    }
    running = true;
    stoppedSelfBecauseDenied = false;
    if (!startForegroundQuiet()) {
      stoppedSelfBecauseDenied = true;
      running = false;
      stopSelf();
      return START_NOT_STICKY;
    }
    handler.removeCallbacks(watchdog);
    if (!startupSynced) {
      startupSynced = true;
      syncRemindersAsync();
    }
    handler.post(watchdog);
    return START_STICKY;
  }

  public static void refreshNotification(Context c) {
    try {
      if (c == null || !running) return;
      NotificationManager nm = c.getSystemService(NotificationManager.class);
      if (nm != null) nm.notify(FG_ID, buildForegroundNotification(c));
    } catch (Exception ignored) {}
  }

  private static Notification buildForegroundNotification(Context c) {
    boolean eco = !Vault.hasMeta(c) || Vault.fastBool(c, "ecoCapture", true);
    boolean locked = Vault.hasMeta(c) && Vault.fastBool(c, "appLock", false);
    boolean ms = Vault.hasMeta(c) && "ms".equals(Vault.fastStr(c, "lang", "en"));
    Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pi = PendingIntent.getActivity(c, 2109, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String title = ms
        ? (eco ? "Penjadual Eko MoneyLens" : "Penjadual Tugas MoneyLens Aktif")
        : (eco ? "MoneyLens Eco Scheduler" : "MoneyLens Task Scheduler Active");
    String body = locked
        ? (ms ? "Bil & penggera aktif · Buka kunci untuk rekod pantas" : "Auto-capture & alarms active · Unlock for Quick Entry")
        : (ms ? "Gunakan Rekod Pantas di bawah (cth. Nasi Lemak 12.50 tunai)" : "Use Quick Log below (e.g. Nasi Lemak 12.50 cash)");
    Notification.Builder nb = new Notification.Builder(c, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setContentIntent(pi)
        .setOngoing(true);
    PendingIntent quickFormPi = PendingIntent.getActivity(
        c, 2113,
        new Intent(c, MainActivity.class).putExtra("screen", "quick").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    if (!locked) {
      RemoteInput ri = new RemoteInput.Builder(KEY_QUICK_ENTRY)
          .setLabel(ms ? "cth. Nasi Lemak 12.50 tunai / Makan 60 kongsi Ali" : "e.g. Nasi Lemak 12.50 cash / Dinner 60 split Sarah")
          .build();
      int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      PendingIntent replyPi = PendingIntent.getBroadcast(
          c, 2112,
          new Intent(c, IgnoreReceiver.class).setAction(ACTION_QUICK_ENTRY),
          replyFlags);
      nb.addAction(new Notification.Action.Builder(null, ms ? "⚡ Rekod Pantas" : "⚡ Quick Log", replyPi)
          .addRemoteInput(ri)
          .build());
      nb.addAction(new Notification.Action.Builder(null, ms ? "Borang" : "Quick Form", quickFormPi).build());
    } else {
      nb.addAction(new Notification.Action.Builder(null, ms ? "Buka Kunci & Rekod" : "Unlock & Log", quickFormPi).build());
    }
    return nb.build();
  }

  private boolean startForegroundQuiet() {
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Always-on task scheduler", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps auto-capture, inline Quick Log, and BNPL/PTPTN payment reminders active in the background.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
      }
      Notification n = buildForegroundNotification(this);
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(FG_ID, n);
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    if (getSharedPreferences("health", 0).getBoolean("alwaysOnScheduler", true)) {
      scheduleImmediateRestart(this);
    }
  }

  @Override public void onDestroy() {
    running = false;
    handler.removeCallbacks(watchdog);
    if (!stoppedSelfBecauseDenied && getSharedPreferences("health", 0).getBoolean("alwaysOnScheduler", true)) {
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
