package app.moneylens;

import android.app.*;
import android.content.*;
import android.service.notification.NotificationListenerService;
import org.json.*;
import java.time.*;

/**
 * Exact AlarmManager broadcast receiver for BNPL, PTPTN, and recurring bill reminders,
 * 1-tap Mark Paid, Snooze, Dismiss, and automatic restart of KeepAliveService & CaptureService.
 */
public final class AlarmReceiver extends BroadcastReceiver {
  public static final String ACTION_CHECK_REMINDERS = "app.moneylens.ACTION_CHECK_REMINDERS";
  public static final String ACTION_RESTART_SERVICE = "app.moneylens.ACTION_RESTART_SERVICE";
  public static final String ACTION_SNOOZE_BILL = "app.moneylens.ACTION_SNOOZE_BILL";
  public static final String ACTION_DISMISS_BILL = "app.moneylens.ACTION_DISMISS_BILL";
  public static final String ACTION_PAY_BILL = "app.moneylens.ACTION_PAY_BILL";

  @Override public void onReceive(Context c, Intent i) {
    String action = i == null ? "" : String.valueOf(i.getAction());
    try {
      NotificationListenerService.requestRebind(new ComponentName(c, CaptureService.class));
    } catch (Exception ignored) {}
    KeepAliveService.ensureRunning(c);

    PendingResult pending = goAsync();
    new Thread(() -> {
      try {
        if (ACTION_PAY_BILL.equals(action)) {
          String billId = i.getStringExtra("billId");
          int notifId = i.getIntExtra("notifId", 0);
          if (billId != null && !billId.isEmpty()) {
            Vault.command(c, "payBillAuto", new JSONObject().put("id", billId));
            MoneyWidget.refresh(c);
            MainActivity.notifyCaptured();
          }
          if (notifId != 0) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(notifId);
          }
        } else if (ACTION_SNOOZE_BILL.equals(action)) {
          String billId = i.getStringExtra("billId");
          int notifId = i.getIntExtra("notifId", 0);
          if (billId != null && !billId.isEmpty()) {
            Vault.command(c, "snoozeBill", new JSONObject().put("id", billId).put("durationMs", 3600_000L));
          }
          if (notifId != 0) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(notifId);
          }
        } else if (ACTION_DISMISS_BILL.equals(action)) {
          String billId = i.getStringExtra("billId");
          int notifId = i.getIntExtra("notifId", 0);
          if (billId != null && !billId.isEmpty()) {
            Vault.command(c, "dismissBillCycle", new JSONObject().put("id", billId));
          }
          if (notifId != 0) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(notifId);
          }
        } else {
          Automation.checkDueReminders(c);
        }
        scheduleNext(c);
      } catch (Exception ignored) {
      } finally {
        pending.finish();
      }
    }).start();
  }

  public static void scheduleNext(Context c) {
    try {
      AlarmManager am = c.getSystemService(AlarmManager.class);
      if (am == null) return;
      long nowMs = System.currentTimeMillis();
      long nextTriggerMs = nowMs + 15 * 60_000L;
      try {
        JSONObject s = Vault.read(c);
        JSONArray bills = s.optJSONArray("bills");
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        if (bills != null) {
          for (int i = 0; i < bills.length(); i++) {
            JSONObject b = bills.getJSONObject(i);
            if (b.optBoolean("paid") || b.optBoolean("cancelled")) continue;
            long snooze = b.optLong("snoozeUntil", 0);
            if (snooze > nowMs && snooze < nextTriggerMs) {
              nextTriggerMs = snooze;
            }
            String rt = b.optString("remindTime", "09:00");
            String[] parts = rt.split(":");
            int hh = parts.length == 2 ? Integer.parseInt(parts[0]) : 9;
            int mm = parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
            ZonedDateTime candidate = ZonedDateTime.of(today, LocalTime.of(hh, mm), zone);
            long candMs = candidate.toInstant().toEpochMilli();
            if (candMs <= nowMs) {
              candMs = candidate.plusDays(1).toInstant().toEpochMilli();
            }
            if (candMs > nowMs && candMs < nextTriggerMs) {
              nextTriggerMs = candMs;
            }
          }
        }
      } catch (Exception ignored) {}

      Intent intent = new Intent(c, AlarmReceiver.class).setAction(ACTION_CHECK_REMINDERS);
      PendingIntent pi = PendingIntent.getBroadcast(c, 2111, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      try {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pi);
      } catch (SecurityException se) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pi);
      }
    } catch (Exception ignored) {}
  }
}
