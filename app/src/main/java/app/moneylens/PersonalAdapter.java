package app.moneylens;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java on-device personalization adapter that learns recency-weighted merchant -> category/subcategory
 * preferences strictly from the opt-in {@code correctionLog} in ledger state (zero network/Android deps).
 */
public final class PersonalAdapter {
  private PersonalAdapter() {}

  static String normalizeKey(String raw) {
    if (raw == null) return "";
    String low = raw.toLowerCase(Locale.ROOT).replace('\u00A0', ' ').trim();
    if (low.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(low.length());
    boolean prevSpace = true;
    for (int i = 0; i < low.length(); i++) {
      char ch = low.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        sb.append(ch);
        prevSpace = false;
      } else if (!prevSpace) {
        sb.append(' ');
        prevSpace = true;
      }
    }
    String norm = sb.toString().trim();
    return "detected payment".equals(norm) ? "" : norm;
  }

  private static void accumulateVotes(
      Map<String, Map<String, double[]>> votesByKey,
      String key,
      String catSubPair,
      double weight) {
    if (key.isEmpty()) return;
    Map<String, double[]> pairScores = votesByKey.computeIfAbsent(key, _k -> new HashMap<>(4));
    double[] cur = pairScores.computeIfAbsent(catSubPair, _p -> new double[2]);
    cur[0] += weight; // weighted score
    cur[1] += 1.0;    // count
  }

  private static String[] pickWinner(Map<String, double[]> pairScores, int minCount, double minShare) {
    if (pairScores == null || pairScores.isEmpty()) return null;
    double totalWeight = 0.0;
    double totalCount = 0.0;
    String bestPair = null;
    double bestWeight = -1.0;
    for (Map.Entry<String, double[]> e : pairScores.entrySet()) {
      double w = e.getValue()[0];
      totalWeight += w;
      totalCount += e.getValue()[1];
      if (w > bestWeight) {
        bestWeight = w;
        bestPair = e.getKey();
      }
    }
    if (bestPair == null || totalCount < minCount || totalWeight <= 0.0) return null;
    if ((bestWeight / totalWeight) < minShare) return null;
    int sep = bestPair.indexOf("||");
    if (sep < 0) return new String[]{bestPair, ""};
    return new String[]{bestPair.substring(0, sep), bestPair.substring(sep + 2)};
  }

  /** Returns learned {category, subcategory} if opt-in log gives a confident match, otherwise null. */
  public static String[] predict(JSONObject state, String merchant, String canonicalMerchant) {
    if (state == null) return null;
    JSONObject settings = state.optJSONObject("settings");
    if (settings == null || !settings.optBoolean("correctionLogOptIn", false)) return null;
    JSONArray log = state.optJSONArray("correctionLog");
    if (log == null || log.length() == 0) return null;

    String mKey = normalizeKey(merchant);
    String cKey = normalizeKey(canonicalMerchant);
    if (mKey.isEmpty() && cKey.isEmpty()) return null;

    Map<String, Map<String, double[]>> votesByKey = new HashMap<>(Math.min(256, log.length() * 2 + 8));
    int n = log.length();
    for (int i = 0; i < n; i++) {
      JSONObject entry = log.optJSONObject(i);
      if (entry == null) continue;
      String action = entry.optString("action", "");
      if (!"quickNote".equals(action) && !"edit".equals(action) && !"draftConfirm".equals(action)) continue;
      JSONObject after = entry.optJSONObject("after");
      if (after == null) continue;
      String cat = after.optString("category", "").trim();
      if (cat.isEmpty() || "Miscellaneous".equals(cat) || "Other".equals(cat)) continue;
      String sub = after.optString("subcategory", "").trim();
      String pair = cat + "||" + sub;
      double weight = 0.5 + 0.5 * ((i + 1.0) / n);

      String aMerchKey = normalizeKey(after.optString("merchant", ""));
      accumulateVotes(votesByKey, aMerchKey, pair, weight);
      JSONObject before = entry.optJSONObject("before");
      if (before != null) {
        String bMerchKey = normalizeKey(before.optString("merchant", ""));
        if (!bMerchKey.isEmpty() && !bMerchKey.equals(aMerchKey)) {
          accumulateVotes(votesByKey, bMerchKey, pair, weight);
        }
      }
    }

    if (!mKey.isEmpty()) {
      String[] hit = pickWinner(votesByKey.get(mKey), 1, 0.65);
      if (hit != null) return hit;
    }
    if (!cKey.isEmpty() && !cKey.equals(mKey)) {
      return pickWinner(votesByKey.get(cKey), 2, 0.70);
    }
    return null;
  }

  /** Summarizes learned merchant -> category preferences from {@code correctionLog}, newest first. */
  public static JSONArray summarize(JSONObject state) {
    JSONArray out = new JSONArray();
    if (state == null) return out;
    JSONArray log = state.optJSONArray("correctionLog");
    if (log == null || log.length() == 0) return out;

    java.util.List<String> order = new java.util.ArrayList<>();
    Map<String, String> latestMerch = new HashMap<>();
    Map<String, String> latestCat = new HashMap<>();
    Map<String, String> latestSub = new HashMap<>();
    Map<String, Integer> counts = new HashMap<>();
    Map<String, Long> latestTs = new HashMap<>();

    for (int i = 0; i < log.length(); i++) {
      JSONObject entry = log.optJSONObject(i);
      if (entry == null) continue;
      JSONObject after = entry.optJSONObject("after");
      if (after == null) continue;
      String cat = after.optString("category", "").trim();
      if (cat.isEmpty() || "Miscellaneous".equals(cat) || "Other".equals(cat)) continue;
      String merch = after.optString("merchant", "").trim();
      String key = normalizeKey(merch);
      if (key.isEmpty()) continue;
      if (!counts.containsKey(key)) order.add(key);
      counts.put(key, counts.getOrDefault(key, 0) + 1);
      latestMerch.put(key, merch);
      latestCat.put(key, cat);
      latestSub.put(key, after.optString("subcategory", "").trim());
      latestTs.put(key, Math.max(latestTs.getOrDefault(key, 0L), entry.optLong("ts", i + 1L)));
    }
    order.sort((a, b) -> Long.compare(latestTs.getOrDefault(b, 0L), latestTs.getOrDefault(a, 0L)));
    for (String key : order) {
      try {
        JSONObject row = new JSONObject();
        row.put("key", key);
        row.put("merchant", latestMerch.get(key));
        row.put("category", latestCat.get(key));
        row.put("subcategory", latestSub.get(key));
        row.put("count", counts.get(key));
        out.put(row);
      } catch (JSONException ignored) {}
    }
    return out;
  }

  /** Removes all {@code correctionLog} entries matching {@code normKey} and returns the removed count. */
  public static int forgetKey(JSONObject state, String normKey) {
    if (state == null) return 0;
    String target = normalizeKey(normKey);
    if (target.isEmpty()) return 0;
    JSONArray log = state.optJSONArray("correctionLog");
    if (log == null || log.length() == 0) return 0;
    JSONArray kept = new JSONArray();
    int removed = 0;
    for (int i = 0; i < log.length(); i++) {
      JSONObject entry = log.optJSONObject(i);
      if (entry == null) continue;
      JSONObject after = entry.optJSONObject("after");
      JSONObject before = entry.optJSONObject("before");
      String ak = after != null ? normalizeKey(after.optString("merchant", "")) : "";
      String bk = before != null ? normalizeKey(before.optString("merchant", "")) : "";
      if (target.equals(ak) || target.equals(bk)) {
        removed++;
      } else {
        kept.put(entry);
      }
    }
    try {
      state.put("correctionLog", kept);
    } catch (JSONException ignored) {}
    return removed;
  }
}
