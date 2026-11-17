package app.moneylens;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.*;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Resizable MoneyLens Overview home-screen widget + shared multi-design widget renderer. */
public class MoneyWidget extends AppWidgetProvider {
  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

  protected boolean isAdaptiveCardStyle() { return false; }
  protected int defaultWidthDp() { return 260; }
  protected int defaultHeightDp() { return 110; }

  @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    if (ids == null || ids.length == 0) return;
    final PendingResult pr = goAsync();
    EXEC.execute(() -> {
      try {
        updateProvider(c, m, ids, null, isAdaptiveCardStyle(), defaultWidthDp(), defaultHeightDp());
      } catch (Exception ignored) {
      } finally {
        pr.finish();
      }
    });
  }

  @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int appWidgetId, Bundle newOptions) {
    final int w = newOptions != null ? newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultWidthDp()) : defaultWidthDp();
    final int h = newOptions != null ? newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultHeightDp()) : defaultHeightDp();
    final PendingResult pr = goAsync();
    EXEC.execute(() -> {
      try {
        updateProvider(c, m, new int[]{appWidgetId}, null, isAdaptiveCardStyle(), w, h);
      } catch (Exception ignored) {
      } finally {
        pr.finish();
      }
    });
  }

  private static JSONObject loadModel(Context c, JSONObject preloaded) {
    try {
      JSONObject s = preloaded != null ? preloaded : Vault.read(c);
      return WidgetModel.build(s, LocalDate.now());
    } catch (Exception e) {
      try { return WidgetModel.build(null, LocalDate.now()); } catch (Exception ignored) { return new JSONObject(); }
    }
  }

  static void updateWithState(Context c, AppWidgetManager m, int[] ids, JSONObject preloaded) {
    updateProvider(c, m, ids, preloaded, false, 260, 110);
  }

  static void updateProvider(Context c, AppWidgetManager m, int[] ids, JSONObject preloaded, boolean adaptiveStyle, int fallbackW, int fallbackH) {
    if (m == null || ids == null || ids.length == 0) return;
    JSONObject model = loadModel(c, preloaded);
    String style = adaptiveStyle ? (model.optBoolean("dark") ? "dark" : "light") : "emerald";

    for (int id : ids) {
      try {
        if (Build.VERSION.SDK_INT >= 31) {
          Map<SizeF, RemoteViews> sizes = new LinkedHashMap<>();
          sizes.put(new SizeF(110f, 40f), buildTier(c, model, style, "compact"));
          sizes.put(new SizeF(110f, 105f), buildTier(c, model, style, "medium"));
          sizes.put(new SizeF(240f, 105f), buildTier(c, model, style, "wide"));
          sizes.put(new SizeF(240f, 225f), buildTier(c, model, style, "large"));
          m.updateAppWidget(id, new RemoteViews(sizes));
        } else {
          Bundle opts = m.getAppWidgetOptions(id);
          int w = opts != null ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallbackW) : fallbackW;
          int h = opts != null ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallbackH) : fallbackH;
          m.updateAppWidget(id, buildTier(c, model, style, WidgetModel.selectSizeTier(w, h)));
        }
      } catch (Exception ignored) {}
    }
  }

  private static RemoteViews buildTier(Context c, JSONObject model, String style, String tier) {
    int layoutId = "compact".equals(tier) ? R.layout.widget_compact
        : "medium".equals(tier) ? R.layout.widget_medium
        : "large".equals(tier) ? R.layout.widget_large
        : R.layout.widget;

    RemoteViews v = new RemoteViews(c.getPackageName(), layoutId);
    applyPalette(v, style, tier);

    boolean locked = model.optBoolean("locked", false);
    String primary = model.optString("primaryMetric", "Private");
    String status = model.optString("statusLine", "Private · Offline · Auto-Capture");
    String due = model.optString("dueText", "On-device vault");
    String pace = model.optString("paceText", "On pace");
    String compactLbl = model.optString("compactLabel", "Safe today");

    if ("compact".equals(tier)) {
      v.setTextViewText(R.id.widget_title, locked ? "MoneyLens" : compactLbl);
      v.setTextViewText(R.id.widget_primary_metric, primary);
    } else if ("medium".equals(tier)) {
      v.setTextViewText(R.id.widget_pace_badge, locked ? "Locked" : pace);
      v.setTextViewText(R.id.widget_primary_metric, primary);
      v.setTextViewText(R.id.widget_status, status);
    } else if ("wide".equals(tier)) {
      v.setTextViewText(R.id.widget_due, due);
      v.setTextViewText(R.id.widget_status, status);
    } else {
      v.setTextViewText(R.id.widget_pace_badge, locked ? "Locked" : pace);
      v.setTextViewText(R.id.widget_due, due);
      v.setTextViewText(R.id.widget_primary_metric, primary);
      v.setTextViewText(R.id.widget_status, status);

      int[] barIds = new int[]{
          R.id.widget_bar_1, R.id.widget_bar_2, R.id.widget_bar_3,
          R.id.widget_bar_4, R.id.widget_bar_5, R.id.widget_bar_6, R.id.widget_bar_7
      };
      JSONArray bars = model.optJSONArray("bars7");
      for (int i = 0; i < barIds.length; i++) {
        int val = (bars != null && i < bars.length()) ? bars.optInt(i, 6) : 6;
        v.setProgressBar(barIds[i], 100, locked ? 6 : val, false);
      }

      v.setTextViewText(R.id.widget_budget_label, model.optString("budgetLabel", "Budgets"));
      v.setProgressBar(R.id.widget_budget_bar, 100, locked ? 0 : model.optInt("budgetPct", 0), false);

      int[] txIds = new int[]{R.id.widget_tx_1, R.id.widget_tx_2, R.id.widget_tx_3};
      JSONArray recent = model.optJSONArray("recentTx");
      if (locked || recent == null || recent.length() == 0) {
        v.setTextViewText(txIds[0], locked ? "Unlock MoneyLens to inspect recent activity" : "No recent transactions yet");
        v.setViewVisibility(txIds[0], View.VISIBLE);
        v.setViewVisibility(txIds[1], View.GONE);
        v.setViewVisibility(txIds[2], View.GONE);
      } else {
        for (int i = 0; i < txIds.length; i++) {
          if (i < recent.length()) {
            v.setTextViewText(txIds[i], recent.optString(i, ""));
            v.setViewVisibility(txIds[i], View.VISIBLE);
            bind(c, v, txIds[i], "transactions");
          } else {
            v.setViewVisibility(txIds[i], View.GONE);
          }
        }
      }
    }

    bind(c, v, R.id.widget_root, "home");
    bind(c, v, R.id.widget_add, "quick");
    if (!"compact".equals(tier)) {
      bind(c, v, R.id.widget_inbox, "inbox");
    }
    if ("medium".equals(tier)) {
      bind(c, v, R.id.widget_pace_badge, "review");
    }
    if ("wide".equals(tier) || "large".equals(tier)) {
      bind(c, v, R.id.widget_scan, "scan");
      bind(c, v, R.id.widget_plan, "plan");
      bind(c, v, R.id.widget_due, "review");
    }
    if ("large".equals(tier)) {
      bind(c, v, R.id.widget_pace_badge, "review");
      bind(c, v, R.id.widget_budget_label, "review");
    }
    return v;
  }

  private static void applyPalette(RemoteViews v, String style, String tier) {
    int bgRes = "light".equals(style) ? R.drawable.widget_bg_light
        : "dark".equals(style) ? R.drawable.widget_bg_dark
        : R.drawable.widget_bg_emerald;
    int pillRes = "light".equals(style) ? R.drawable.widget_pill_soft
        : "dark".equals(style) ? R.drawable.widget_pill_soft_dark
        : R.drawable.widget_pill_emerald;
    int ink = "light".equals(style) ? 0xFF162017 : 0xFFF5F8F5;
    int sub = "light".equals(style) ? 0xFF4E5E50 : "dark".equals(style) ? 0xFF9BB09E : 0xFFC9EBD7;
    int btnInk = "light".equals(style) ? 0xFF1B4332 : "dark".equals(style) ? 0xFFA8D5BA : 0xFFFFFFFF;

    v.setInt(R.id.widget_root, "setBackgroundResource", bgRes);
    v.setInt(R.id.widget_add, "setBackgroundResource", pillRes);
    v.setTextColor(R.id.widget_add, btnInk);

    if ("compact".equals(tier)) {
      v.setTextColor(R.id.widget_title, sub);
      v.setTextColor(R.id.widget_primary_metric, ink);
      return;
    }

    v.setTextColor(R.id.widget_title, ink);
    v.setTextColor(R.id.widget_status, "wide".equals(tier) ? ink : sub);
    v.setInt(R.id.widget_inbox, "setBackgroundResource", pillRes);
    v.setTextColor(R.id.widget_inbox, btnInk);

    if ("medium".equals(tier)) {
      v.setInt(R.id.widget_pace_badge, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_pace_badge, btnInk);
      v.setTextColor(R.id.widget_primary_metric, ink);
    } else if ("wide".equals(tier)) {
      v.setInt(R.id.widget_due, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_due, btnInk);
      v.setInt(R.id.widget_scan, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_scan, btnInk);
      v.setInt(R.id.widget_plan, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_plan, btnInk);
    } else {
      v.setInt(R.id.widget_pace_badge, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_pace_badge, btnInk);
      v.setTextColor(R.id.widget_due, sub);
      v.setTextColor(R.id.widget_primary_metric, ink);
      v.setTextColor(R.id.widget_budget_label, sub);
      v.setTextColor(R.id.widget_tx_1, ink);
      v.setTextColor(R.id.widget_tx_2, sub);
      v.setTextColor(R.id.widget_tx_3, sub);
      v.setInt(R.id.widget_scan, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_scan, btnInk);
      v.setInt(R.id.widget_plan, "setBackgroundResource", pillRes);
      v.setTextColor(R.id.widget_plan, btnInk);
    }
  }

  static void bind(Context c, RemoteViews v, int id, String screen) {
    Intent i = new Intent(c, MainActivity.class)
        .putExtra("screen", screen)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    v.setOnClickPendingIntent(id, PendingIntent.getActivity(
        c, screen.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
  }

  public static void refresh(Context c) { refresh(c, null); }

  public static void refresh(Context c, JSONObject s) {
    if (c == null) return;
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    if (m == null) return;
    int[] ovIds = m.getAppWidgetIds(new ComponentName(c, MoneyWidget.class));
    int[] cpIds = m.getAppWidgetIds(new ComponentName(c, MoneyWidgetCompact.class));
    int[] inIds = m.getAppWidgetIds(new ComponentName(c, MoneyWidgetInsights.class));
    if ((ovIds == null || ovIds.length == 0) && (cpIds == null || cpIds.length == 0) && (inIds == null || inIds.length == 0)) return;
    JSONObject model = loadModel(c, s);
    refreshProviderIds(c, m, ovIds, model, false, 260, 110);
    refreshProviderIds(c, m, cpIds, model, true, 120, 48);
    refreshProviderIds(c, m, inIds, model, true, 260, 190);
  }

  private static void refreshProviderIds(Context c, AppWidgetManager m, int[] ids, JSONObject model, boolean adaptive, int defW, int defH) {
    if (ids == null || ids.length == 0) return;
    String style = adaptive ? (model.optBoolean("dark") ? "dark" : "light") : "emerald";
    for (int id : ids) {
      try {
        if (Build.VERSION.SDK_INT >= 31) {
          Map<SizeF, RemoteViews> sizes = new LinkedHashMap<>();
          sizes.put(new SizeF(110f, 40f), buildTier(c, model, style, "compact"));
          sizes.put(new SizeF(110f, 105f), buildTier(c, model, style, "medium"));
          sizes.put(new SizeF(240f, 105f), buildTier(c, model, style, "wide"));
          sizes.put(new SizeF(240f, 225f), buildTier(c, model, style, "large"));
          m.updateAppWidget(id, new RemoteViews(sizes));
        } else {
          Bundle opts = m.getAppWidgetOptions(id);
          int w = opts != null ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW) : defW;
          int h = opts != null ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defH) : defH;
          m.updateAppWidget(id, buildTier(c, model, style, WidgetModel.selectSizeTier(w, h)));
        }
      } catch (Exception ignored) {}
    }
  }
}
