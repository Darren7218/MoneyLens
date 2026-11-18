package app.moneylens;

import org.json.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure Android-free widget layout tier selector and summary view-model. */
public final class WidgetModel {
  public static String selectSizeTier(int widthDp, int heightDp) {
    if (heightDp > 0 && heightDp < 105) return "compact";
    if (widthDp > 0 && widthDp < 240) return "medium";
    if (heightDp >= 225) return "large";
    return "wide";
  }

  private static String rm(long minor) {
    long abs = Math.abs(minor);
    return (minor < 0 ? "-RM " : "RM ") + BigDecimal.valueOf(abs, 2).toPlainString();
  }

  private static String signedRm(long minor) {
    return (minor < 0 ? "-RM " : minor > 0 ? "+RM " : "RM ") + BigDecimal.valueOf(Math.abs(minor), 2).toPlainString();
  }

  private static String shortText(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, Math.max(1, max - 1)) + "…";
  }

  public static JSONObject build(JSONObject s, LocalDate on) throws Exception {
    JSONObject out = new JSONObject();
    JSONObject settings = s != null ? s.optJSONObject("settings") : null;
    if (settings == null) settings = new JSONObject();
    boolean locked = s == null || settings.optBoolean("appLock", false);
    boolean blur = settings.optBoolean("privacyBlur", false);
    boolean widgetAmounts = settings.optBoolean("widgetAmounts", true);
    boolean showAmounts = !locked && !blur && widgetAmounts;
    boolean dark = "dark".equals(settings.optString("theme", "light"));
    boolean ms = "ms".equals(settings.optString("lang", "en"));

    out.put("locked", locked)
       .put("blur", blur)
       .put("showAmounts", showAmounts)
       .put("dark", dark)
       .put("ms", ms)
       .put("compactLabel", ms ? "Had selamat hari ini" : "Safe today")
       .put("primaryMetric", locked ? (ms ? "Dikunci" : "Locked") : !showAmounts ? "RM ••••" : "RM 0.00")
       .put("statusLine", locked ? (ms ? "Buka MoneyLens untuk lihat" : "Unlock MoneyLens to view") : "Private · Offline · Auto-Capture")
       .put("dueText", ms ? "Peti besi peranti" : "On-device vault")
       .put("paceText", ms ? "Terkawal" : "On pace")
       .put("paceStatus", "good")
       .put("budgetLabel", ms ? "Bajet · Tiada bajet lagi" : "Budgets · Tap Plan to set")
       .put("budgetPct", 0)
       .put("favoriteLabel", "");

    int[] bars = new int[]{6, 6, 6, 6, 6, 6, 6};
    JSONArray recentTx = new JSONArray();

    if (!locked && s != null) {
      JSONObject r = Finance.report(s, on);
      JSONObject dp = r.optJSONObject("dailyPacing");
      long avail = r.optLong("availableToSpend", 0L);
      long spent = r.optLong("spent", 0L);
      long safeDay = dp != null ? dp.optLong("safeDaily", 0L) : 0L;
      long todayRem = dp != null ? dp.optLong("todayRemaining", safeDay) : 0L;
      String pace = dp != null ? dp.optString("paceStatus", "good") : "good";
      out.put("paceStatus", pace);
      out.put("paceText", "over".equals(pace) ? (ms ? "Atas kadar" : "Over pace")
          : "watch".equals(pace) ? (ms ? "Hati-hati" : "Watch")
          : (ms ? "Terkawal" : "On pace"));

      if (showAmounts) {
        out.put("primaryMetric", rm(safeDay > 0 ? todayRem : avail));
        out.put("statusLine", (ms ? "Baki " : "Avail ") + rm(avail) + " · " + (ms ? "Belanja " : "Spent ") + rm(spent)
            + (safeDay > 0 ? (" · " + rm(safeDay) + "/d") : ""));
      } else {
        out.put("primaryMetric", "RM ••••");
        out.put("statusLine", (ms ? "Mod privasi aktif" : "Privacy mode · Amounts hidden"));
      }

      // Upcoming unpaid bill or open split receivable
      String nextDue = "", nextName = "";
      JSONArray bills = s.optJSONArray("bills");
      if (bills != null) {
        for (int i = 0; i < bills.length(); i++) {
          JSONObject b = bills.getJSONObject(i);
          if (b.optBoolean("paid") || b.optBoolean("cancelled")) continue;
          String d = b.optString("date", "");
          if (!d.isEmpty() && (nextDue.isEmpty() || d.compareTo(nextDue) < 0)) {
            nextDue = d;
            nextName = b.optString("name", "Bill");
          }
        }
      }
      if (!nextDue.isEmpty()) {
        String datePart = nextDue.length() >= 10 ? nextDue.substring(5) : nextDue;
        out.put("dueText", (ms ? "Bil: " : "Next: ") + shortText(nextName, 14) + " (" + datePart + ")");
      } else {
        JSONObject sh = r.optJSONObject("sharedSummary");
        long owed = sh != null ? sh.optLong("totalOwed", 0L) : 0L;
        if (owed > 0) {
          out.put("dueText", showAmounts ? ((ms ? "Kongsi: " : "Splits: ") + rm(owed)) : (ms ? "Kongsi aktif" : "Open splits"));
        }
      }

      // Normalize last 7 days from dailyPacing.dailySeries (14 days)
      if (dp != null) {
        JSONArray series = dp.optJSONArray("dailySeries");
        if (series != null && series.length() > 0) {
          int start = Math.max(0, series.length() - 7);
          long[] rawBars = new long[7];
          long maxVal = Math.max(1L, safeDay);
          int k = 0;
          for (int i = start; i < series.length() && k < 7; i++, k++) {
            long sp = Math.max(0L, series.getJSONObject(i).optLong("spent", 0L));
            rawBars[k] = sp;
            if (sp > maxVal) maxVal = sp;
          }
          for (int i = 0; i < 7; i++) {
            bars[i] = rawBars[i] <= 0L ? 6 : Math.max(14, Math.min(100, (int) Math.round(rawBars[i] * 100.0 / maxVal)));
          }
        }
      }

      // Highest-utilization budget watch
      JSONArray budgets = r.optJSONArray("budgets");
      if (budgets != null && budgets.length() > 0) {
        JSONObject topB = null;
        int topPct = -1;
        for (int i = 0; i < budgets.length(); i++) {
          JSONObject b = budgets.getJSONObject(i);
          int p = b.optInt("spentPct", 0);
          if (p > topPct) {
            topPct = p;
            topB = b;
          }
        }
        if (topB != null) {
          String bCat = topB.optString("subcategory", "").isEmpty()
              ? topB.optString("category", "Budget")
              : topB.optString("subcategory");
          long rem = topB.optLong("remaining", 0L);
          int clampPct = Math.max(0, Math.min(100, topPct));
          out.put("budgetPct", clampPct);
          if (showAmounts) {
            String remText = rem < 0
                ? ((ms ? "terlebih " : "over ") + rm(-rem))
                : (rm(rem) + (ms ? " baki" : " left"));
            out.put("budgetLabel", shortText(bCat, 18) + " · " + topPct + "% (" + remText + ")");
          } else {
            out.put("budgetLabel", shortText(bCat, 20) + " · " + topPct + "%");
          }
        }
      }

      // Latest 3 non-voided non-opening transactions
      JSONArray tx = s.optJSONArray("transactions");
      if (tx != null && tx.length() > 0) {
        List<JSONObject> active = new ArrayList<>();
        for (int i = 0; i < tx.length(); i++) {
          JSONObject t = tx.getJSONObject(i);
          if (t.optBoolean("voided") || "opening".equals(t.optString("kind"))) continue;
          active.add(t);
        }
        active.sort((a, b) -> {
          int dc = b.optString("date", "").compareTo(a.optString("date", ""));
          return dc != 0 ? dc : Long.compare(b.optLong("created", 0L), a.optLong("created", 0L));
        });
        String todayStr = on.toString();
        for (int i = 0; i < active.size() && i < 3; i++) {
          JSONObject t = active.get(i);
          String kind = t.optString("kind", "expense");
          long amt = t.optLong("amount", 0L);
          String merch = shortText(t.optString("merchant", t.optString("category", "Transaction")), 18);
          String amtStr;
          if (!showAmounts) amtStr = "•••";
          else if ("transfer".equals(kind)) amtStr = rm(amt);
          else if ("income".equals(kind) || "refund".equals(kind) || "reimbursement".equals(kind)) amtStr = signedRm(amt);
          else amtStr = signedRm(-amt);
          String dt = t.optString("date", "");
          String dLbl = dt.equals(todayStr) ? (ms ? "Hari ini" : "Today") : (dt.length() >= 10 ? dt.substring(5) : dt);
          recentTx.put(merch + " · " + amtStr + " · " + dLbl);
        }
      }

      // Top saved favorite shortcut
      JSONArray favs = s.optJSONArray("favorites");
      if (favs != null && favs.length() > 0) {
        JSONObject fav = favs.getJSONObject(0);
        String fn = fav.optString("name", "").trim();
        String fa = fav.optString("amount", "").trim();
        if (!fn.isEmpty()) {
          String amtDisp = showAmounts ? (fa.isEmpty() ? "" : (" · RM " + fa)) : " · RM •••";
          out.put("favoriteLabel", "★ " + shortText(fn, 14) + amtDisp);
        }
      }
    }

    JSONArray barArr = new JSONArray();
    for (int v : bars) barArr.put(v);
    out.put("bars7", barArr);
    out.put("recentTx", recentTx);
    return out;
  }
}
