package app.moneylens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java sparse Word+Char TF-IDF linear classifier synced with Colab Foundry exports.
 * Zero Android framework imports so it runs identically inside the Android APK and JVM tests.
 */
public final class MlClassifier {
  public static final class Prediction {
    public final String category;
    public final String subcategory;
    public final double probability;
    public final double margin;
    public final int activeSignals;

    Prediction(String category, String subcategory, double probability, double margin, int activeSignals) {
      this.category = category;
      this.subcategory = subcategory;
      this.probability = probability;
      this.margin = margin;
      this.activeSignals = activeSignals;
    }
  }

  private static final class Feature {
    final double idf;
    final int[] classIdx;
    final double[] weights;

    Feature(double idf, int[] classIdx, double[] weights) {
      this.idf = idf;
      this.classIdx = classIdx;
      this.weights = weights;
    }
  }

  private static final class Model {
    final String[] categories;
    final String[] subcategories;
    final double[] intercepts;
    final Map<String, Feature> wordFeatures;
    final Map<String, Feature> charFeatures;

    Model(String[] categories, String[] subcategories, double[] intercepts,
          Map<String, Feature> wordFeatures, Map<String, Feature> charFeatures) {
      this.categories = categories;
      this.subcategories = subcategories;
      this.intercepts = intercepts;
      this.wordFeatures = wordFeatures;
      this.charFeatures = charFeatures;
    }
  }

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;

  private MlClassifier() {}

  public static boolean isLoaded() {
    return activeModel != null;
  }

  public static synchronized void clear() {
    activeModel = null;
    autoLoadAttempted = false;
  }

  public static synchronized boolean loadFromStream(InputStream in) {
    if (in == null) return false;
    try {
      PushbackInputStream pb = new PushbackInputStream(in, 2);
      byte[] hdr = new byte[2];
      int hdrRead = pb.read(hdr);
      if (hdrRead > 0) pb.unread(hdr, 0, hdrRead);
      boolean isGzip = hdrRead == 2 && (hdr[0] & 0xFF) == 0x1F && (hdr[1] & 0xFF) == 0x8B;
      try (InputStream src = isGzip ? new GZIPInputStream(pb) : pb) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = src.read(chunk)) >= 0) buf.write(chunk, 0, n);
        String json = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        activeModel = parseModel(json);
        autoLoadAttempted = true;
        return activeModel != null;
      }
    } catch (Exception ignored) {
      return false;
    }
  }

  public static synchronized boolean loadFromPath(Path path) {
    if (path == null || !Files.isRegularFile(path)) return false;
    try (InputStream in = Files.newInputStream(path)) {
      return loadFromStream(in);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static synchronized void ensureLoaded() {
    if (activeModel != null || autoLoadAttempted) return;
    autoLoadAttempted = true;
    for (String res : new String[]{"/moneylens_classifier.json.gz", "/moneylens_classifier.json"}) {
      try (InputStream cp = MlClassifier.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {}
    }
    for (String candidate : new String[]{
        "app/src/main/assets/moneylens_classifier.json.gz",
        "app/src/main/assets/moneylens_classifier.json",
        "src/main/assets/moneylens_classifier.json.gz",
        "src/main/assets/moneylens_classifier.json",
        "../app/src/main/assets/moneylens_classifier.json.gz",
        "../app/src/main/assets/moneylens_classifier.json"
    }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static Model parseModel(String rawJson) throws Exception {
    JSONObject root = new JSONObject(rawJson);
    JSONArray clsArr = root.getJSONArray("classes");
    JSONArray intArr = root.getJSONArray("intercepts");
    int numClasses = clsArr.length();
    String[] cats = new String[numClasses];
    String[] subs = new String[numClasses];
    double[] intercepts = new double[numClasses];
    for (int i = 0; i < numClasses; i++) {
      String pair = clsArr.getString(i);
      int split = pair.indexOf("||");
      if (split >= 0) {
        cats[i] = pair.substring(0, split);
        subs[i] = pair.substring(split + 2);
      } else {
        cats[i] = pair;
        subs[i] = "";
      }
      intercepts[i] = intArr.getDouble(i);
    }

    JSONObject featsObj = root.getJSONObject("features");
    Map<String, Feature> wordMap = new HashMap<>(Math.max(16, featsObj.length() / 2));
    Map<String, Feature> charMap = new HashMap<>(Math.max(16, featsObj.length() / 2));
    Iterator<String> keys = featsObj.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONArray entry = featsObj.getJSONArray(key);
      double idf = entry.getDouble(0);
      int block = entry.getInt(1);
      JSONArray pairs = entry.getJSONArray(2);
      int m = pairs.length();
      int[] cIdx = new int[m];
      double[] w = new double[m];
      for (int j = 0; j < m; j++) {
        JSONArray pw = pairs.getJSONArray(j);
        cIdx[j] = pw.getInt(0);
        w[j] = pw.getDouble(1);
      }
      Feature feat = new Feature(idf, cIdx, w);
      if (block == 0) wordMap.put(key, feat);
      else charMap.put(key, feat);
    }
    return new Model(cats, subs, intercepts, wordMap, charMap);
  }

  /** Exact bit-for-bit parity with Python java_parity_words(). */
  static List<String> tokenizeWords(String text) {
    List<String> words = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    String low = text.toLowerCase(Locale.ROOT);
    for (int i = 0; i < low.length(); i++) {
      char ch = low.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        buf.append(ch);
      } else if (buf.length() > 0) {
        if (buf.length() >= 2) words.add(buf.toString());
        buf.setLength(0);
      }
    }
    if (buf.length() >= 2) words.add(buf.toString());
    return words;
  }

  public static Prediction predict(String merchant, String text) {
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    String combined = (merchant == null ? "" : merchant) + "\n" + (text == null ? "" : text);
    List<String> words = tokenizeWords(combined);
    if (words.isEmpty()) return null;

    Map<String, Integer> wCounts = new HashMap<>();
    for (int i = 0; i < words.size(); i++) {
      String w = "w:" + words.get(i);
      if (m.wordFeatures.containsKey(w)) wCounts.put(w, wCounts.getOrDefault(w, 0) + 1);
      if (i + 1 < words.size()) {
        String bi = "w:" + words.get(i) + " " + words.get(i + 1);
        if (m.wordFeatures.containsKey(bi)) wCounts.put(bi, wCounts.getOrDefault(bi, 0) + 1);
      }
    }

    Map<String, Integer> cCounts = new HashMap<>();
    for (String word : words) {
      String padded = " " + word + " ";
      int len = padded.length();
      for (int n = 3; n <= 5; n++) {
        if (len < n) break;
        for (int start = 0; start <= len - n; start++) {
          String cg = "c:" + padded.substring(start, start + n);
          if (m.charFeatures.containsKey(cg)) cCounts.put(cg, cCounts.getOrDefault(cg, 0) + 1);
        }
      }
    }

    int activeSignals = wCounts.size() + cCounts.size();
    if (activeSignals == 0) return null;

    double[] logits = m.intercepts.clone();
    scoreBlock(wCounts, m.wordFeatures, logits);
    scoreBlock(cCounts, m.charFeatures, logits);

    double maxLogit = Double.NEGATIVE_INFINITY;
    for (double v : logits) if (v > maxLogit) maxLogit = v;
    double sumExp = 0.0;
    int bestIdx = 0;
    double bestLogit = Double.NEGATIVE_INFINITY;
    double secondLogit = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < logits.length; i++) {
      sumExp += Math.exp(logits[i] - maxLogit);
      if (logits[i] > bestLogit) {
        secondLogit = bestLogit;
        bestLogit = logits[i];
        bestIdx = i;
      } else if (logits[i] > secondLogit) {
        secondLogit = logits[i];
      }
    }
    if (sumExp <= 0.0) return null;
    double bestProb = Math.exp(bestLogit - maxLogit) / sumExp;
    double secondProb = secondLogit == Double.NEGATIVE_INFINITY ? 0.0 : Math.exp(secondLogit - maxLogit) / sumExp;
    return new Prediction(m.categories[bestIdx], m.subcategories[bestIdx], bestProb, bestProb - secondProb, activeSignals);
  }

  private static void scoreBlock(Map<String, Integer> counts, Map<String, Feature> vocab, double[] logits) {
    if (counts.isEmpty()) return;
    int sz = counts.size();
    Feature[] feats = new Feature[sz];
    double[] vals = new double[sz];
    double normSq = 0.0;
    int k = 0;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      Feature f = vocab.get(e.getKey());
      if (f == null) continue;
      double val = (1.0 + Math.log(e.getValue())) * f.idf;
      feats[k] = f;
      vals[k] = val;
      normSq += val * val;
      k++;
    }
    if (normSq <= 0.0) return;
    double invNorm = 1.0 / Math.sqrt(normSq);
    for (int i = 0; i < k; i++) {
      double scaled = vals[i] * invNorm;
      Feature f = feats[i];
      for (int j = 0; j < f.classIdx.length; j++) {
        logits[f.classIdx[j]] += scaled * f.weights[j];
      }
    }
  }
}
