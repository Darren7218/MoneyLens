package app.moneylens;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure-JDK Multilingual Offline Semantic Search & Concept Classifier (MLSS v1).
 * Distilled from intfloat/multilingual-e5-base + Qwen2.5-7B-Instruct across EN/BM/Manglish/ZH.
 * Zero Android framework imports; lazy-loaded and trimmable via Vault.
 */
public final class SemanticSearch {
  public static final class Feature {
    public final String key;
    public final float weight;

    public Feature(String key, float weight) {
      this.key = key;
      this.weight = weight;
    }
  }

  public static final class CategoryPrediction {
    public final String category;
    public final String subcategory;
    public final double confidence;
    public final double margin;

    public CategoryPrediction(String category, String subcategory, double confidence, double margin) {
      this.category = category;
      this.subcategory = subcategory;
      this.confidence = confidence;
      this.margin = margin;
    }
  }

  public static final class ParsedQuery {
    public final String rawQuery;
    public final String semanticText;
    public final long minMinor;
    public final long maxMinor;
    public final String fromDate;
    public final String untilDate;
    public final String hardKind;
    public final String kindBoost;
    public final String accountHint;
    public final boolean taxReliefQuery;

    ParsedQuery(
        String rawQuery,
        String semanticText,
        long minMinor,
        long maxMinor,
        String fromDate,
        String untilDate,
        String hardKind,
        String kindBoost,
        String accountHint,
        boolean taxReliefQuery) {
      this.rawQuery = rawQuery;
      this.semanticText = semanticText;
      this.minMinor = minMinor;
      this.maxMinor = maxMinor;
      this.fromDate = fromDate;
      this.untilDate = untilDate;
      this.hardKind = hardKind;
      this.kindBoost = kindBoost;
      this.accountHint = accountHint;
      this.taxReliefQuery = taxReliefQuery;
    }

    public boolean hasStructuredConstraints() {
      return minMinor >= 0L
          || maxMinor >= 0L
          || !fromDate.isEmpty()
          || !untilDate.isEmpty()
          || !hardKind.isEmpty()
          || !kindBoost.isEmpty()
          || !accountHint.isEmpty()
          || taxReliefQuery;
    }
  }

  private static final class QMatrix {
    final int rows;
    final int cols;
    final float[] scales;
    final byte[] q;

    QMatrix(int rows, int cols, float[] scales, byte[] q) {
      this.rows = rows;
      this.cols = cols;
      this.scales = scales;
      this.q = q;
    }
  }

  private static final class Model {
    final int numBuckets;
    final int embDim;
    final int hiddenDim;
    final int numProtos;
    final float semanticThreshold;
    final float semanticMargin;
    final float classifyThreshold;
    final float classifyMargin;
    final long[] vocabHashes;
    final QMatrix emb;
    final QMatrix w1;
    final QMatrix w2;
    final float[] b1;
    final float[] ln1W;
    final float[] ln1B;
    final float[] b2;
    final String[] protoCats;
    final String[] protoSubs;
    final float[] protoUnitEmbs;

    Model(
        int numBuckets,
        int embDim,
        int hiddenDim,
        int numProtos,
        float semanticThreshold,
        float semanticMargin,
        float classifyThreshold,
        float classifyMargin,
        long[] vocabHashes,
        QMatrix emb,
        QMatrix w1,
        QMatrix w2,
        float[] b1,
        float[] ln1W,
        float[] ln1B,
        float[] b2,
        String[] protoCats,
        String[] protoSubs,
        QMatrix protoQ) {
      this.numBuckets = numBuckets;
      this.embDim = embDim;
      this.hiddenDim = hiddenDim;
      this.numProtos = numProtos;
      this.semanticThreshold = semanticThreshold;
      this.semanticMargin = semanticMargin;
      this.classifyThreshold = classifyThreshold;
      this.classifyMargin = classifyMargin;
      this.vocabHashes = vocabHashes;
      this.emb = emb;
      this.w1 = w1;
      this.w2 = w2;
      this.b1 = b1;
      this.ln1W = ln1W;
      this.ln1B = ln1B;
      this.b2 = b2;
      this.protoCats = protoCats;
      this.protoSubs = protoSubs;
      this.protoUnitEmbs = new float[numProtos * embDim];
      for (int r = 0; r < numProtos; r++) {
        int base = r * embDim;
        double normSq = 0.0;
        double[] row = new double[embDim];
        float sc = protoQ.scales[r];
        for (int c = 0; c < embDim; c++) {
          double v = protoQ.q[base + c] * (double) sc;
          row[c] = v;
          normSq += v * v;
        }
        double denom = Math.max(1e-6, Math.sqrt(normSq));
        for (int c = 0; c < embDim; c++) {
          this.protoUnitEmbs[base + c] = (float) (row[c] / denom);
        }
      }
    }
  }

  private static final int MAX_TX_CACHE_SIZE = 4096;
  private static final Map<String, float[]> txEmbCache =
      Collections.synchronizedMap(
          new LinkedHashMap<String, float[]>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
              return size() > MAX_TX_CACHE_SIZE;
            }
          });

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;
  private static volatile boolean disabled = false;
  private static volatile java.util.function.Supplier<InputStream> streamSupplier = null;

  private SemanticSearch() {}

  public static boolean isLoaded() {
    return activeModel != null;
  }

  public static synchronized void setDisabled(boolean off) {
    disabled = off;
  }

  public static synchronized void setStreamSupplier(java.util.function.Supplier<InputStream> supplier) {
    streamSupplier = supplier;
    if (activeModel == null) autoLoadAttempted = false;
  }

  public static synchronized void trim() {
    activeModel = null;
    autoLoadAttempted = false;
    txEmbCache.clear();
  }

  public static synchronized void clear() {
    activeModel = null;
    autoLoadAttempted = false;
    disabled = false;
    streamSupplier = null;
    txEmbCache.clear();
  }

  public static synchronized boolean loadFromStream(InputStream in) {
    if (in == null) return false;
    try {
      PushbackInputStream pb = new PushbackInputStream(in, 2);
      byte[] hdr = new byte[2];
      int hdrRead = pb.read(hdr);
      if (hdrRead > 0) pb.unread(hdr, 0, hdrRead);
      boolean isGzip = hdrRead == 2 && (hdr[0] & 0xFF) == 0x1F && (hdr[1] & 0xFF) == 0x8B;
      try (InputStream src = isGzip ? new GZIPInputStream(pb, 8192) : pb;
          DataInputStream dis = new DataInputStream(new BufferedInputStream(src, 16384))) {
        activeModel = parseBinaryModel(dis);
      }
      txEmbCache.clear();
      autoLoadAttempted = true;
      disabled = false;
      return activeModel != null;
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
    java.util.function.Supplier<InputStream> supplier = streamSupplier;
    if (supplier != null) {
      try (InputStream supplied = supplier.get()) {
        if (supplied != null && loadFromStream(supplied)) return;
      } catch (Exception ignored) {
      }
    }
    for (String res : new String[] {"/moneylens_search.bin.gz", "/moneylens_search.bin"}) {
      try (InputStream cp = SemanticSearch.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {
      }
    }
    for (String candidate :
        new String[] {
          "app/src/main/assets/moneylens_search.bin.gz",
          "app/src/main/assets/moneylens_search.bin",
          "src/main/assets/moneylens_search.bin.gz",
          "src/main/assets/moneylens_search.bin",
          "../app/src/main/assets/moneylens_search.bin.gz",
          "../app/src/main/assets/moneylens_search.bin"
        }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static String readUtf8LenPrefixed(DataInputStream dis) throws Exception {
    int len = dis.readUnsignedShort();
    byte[] bytes = new byte[len];
    dis.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static Model parseBinaryModel(DataInputStream dis) throws Exception {
    byte[] magic = new byte[4];
    dis.readFully(magic);
    if (magic[0] != 'M' || magic[1] != 'L' || magic[2] != 'S' || magic[3] != 'S') {
      throw new IllegalArgumentException("Bad MLSS magic");
    }
    int version = dis.readInt();
    if (version != 2) throw new IllegalArgumentException("Unsupported MLSS version: " + version);
    int numBuckets = dis.readInt();
    int embDim = dis.readInt();
    int hiddenDim = dis.readInt();
    int numProtos = dis.readInt();
    float semTh = dis.readFloat();
    float semMg = dis.readFloat();
    float clsTh = dis.readFloat();
    float clsMg = dis.readFloat();
    long[] vocabHashes = new long[numBuckets];
    for (int i = 0; i < numBuckets; i++) vocabHashes[i] = dis.readLong();

    QMatrix emb = readQuantizedMatrix(dis);
    QMatrix w1 = readQuantizedMatrix(dis);
    QMatrix w2 = readQuantizedMatrix(dis);
    float[] b1 = readFloatArray(dis, hiddenDim);
    float[] ln1W = readFloatArray(dis, hiddenDim);
    float[] ln1B = readFloatArray(dis, hiddenDim);
    float[] b2 = readFloatArray(dis, embDim);

    String[] protoCats = new String[numProtos];
    String[] protoSubs = new String[numProtos];
    for (int i = 0; i < numProtos; i++) protoCats[i] = readUtf8LenPrefixed(dis);
    for (int i = 0; i < numProtos; i++) protoSubs[i] = readUtf8LenPrefixed(dis);
    QMatrix protoQ = readQuantizedMatrix(dis);

    return new Model(
        numBuckets,
        embDim,
        hiddenDim,
        numProtos,
        semTh,
        semMg,
        clsTh,
        clsMg,
        vocabHashes,
        emb,
        w1,
        w2,
        b1,
        ln1W,
        ln1B,
        b2,
        protoCats,
        protoSubs,
        protoQ);
  }

  private static QMatrix readQuantizedMatrix(DataInputStream dis) throws Exception {
    int rows = dis.readInt();
    int cols = dis.readInt();
    float[] scales = readFloatArray(dis, rows);
    byte[] q = new byte[rows * cols];
    dis.readFully(q);
    return new QMatrix(rows, cols, scales, q);
  }

  private static float[] readFloatArray(DataInputStream dis, int n) throws Exception {
    float[] arr = new float[n];
    for (int i = 0; i < n; i++) arr[i] = dis.readFloat();
    return arr;
  }

  static long fnv1a63(String s) {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    long h = 0xCBF29CE484222325L;
    for (byte b : bytes) {
      h ^= (b & 0xFFL);
      h *= 0x100000001B3L;
    }
    return h & 0x7FFFFFFFFFFFFFFFL;
  }

  static String slugifyTag(String raw) {
    if (raw == null) return "";
    String norm = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
    StringBuilder out = new StringBuilder(norm.length());
    boolean prevUs = true;
    for (int i = 0; i < norm.length(); i++) {
      char ch = norm.charAt(i);
      if (Character.getType(ch) == Character.NON_SPACING_MARK) continue;
      if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
        out.append(ch);
        prevUs = false;
      } else if (!prevUs) {
        out.append('_');
        prevUs = true;
      }
    }
    String res = out.toString();
    int st = 0;
    int en = res.length();
    while (st < en && res.charAt(st) == '_') st++;
    while (en > st && res.charAt(en - 1) == '_') en--;
    String trimmed = res.substring(st, en);
    return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
  }

  public static String normalizeForFeatures(String raw) {
    if (raw == null || raw.isEmpty()) return "";
    String nfkd = Normalizer.normalize(raw, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(nfkd.length());
    for (int i = 0; i < nfkd.length(); i++) {
      char ch = nfkd.charAt(i);
      if (Character.getType(ch) != Character.NON_SPACING_MARK) sb.append(ch);
    }
    return sb.toString();
  }

  private static boolean isAsciiAlnum(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
  }

  private static boolean isCjkChar(char ch) {
    return (ch >= '\u3400' && ch <= '\u4dbf') || (ch >= '\u4e00' && ch <= '\u9fff');
  }

  static List<Feature> extractFeatures(String raw, List<Feature> fieldTags) {
    Map<String, Float> feats = new LinkedHashMap<>();
    String norm = normalizeForFeatures(raw);

    List<String> latinTokens = new ArrayList<>();
    List<String> cjkRuns = new ArrayList<>();
    StringBuilder latinBuf = new StringBuilder();
    StringBuilder cjkBuf = new StringBuilder();

    for (int i = 0; i < norm.length(); i++) {
      char ch = norm.charAt(i);
      if (isAsciiAlnum(ch)) {
        if (cjkBuf.length() > 0) {
          if (cjkRuns.size() < 32) {
            cjkRuns.add(cjkBuf.length() > 48 ? cjkBuf.substring(0, 48) : cjkBuf.toString());
          }
          cjkBuf.setLength(0);
        }
        latinBuf.append(ch);
      } else if (isCjkChar(ch)) {
        if (latinBuf.length() > 0) {
          if (latinTokens.size() < 48) latinTokens.add(latinBuf.toString());
          latinBuf.setLength(0);
        }
        cjkBuf.append(ch);
      } else {
        if (latinBuf.length() > 0) {
          if (latinTokens.size() < 48) latinTokens.add(latinBuf.toString());
          latinBuf.setLength(0);
        }
        if (cjkBuf.length() > 0) {
          if (cjkRuns.size() < 32) {
            cjkRuns.add(cjkBuf.length() > 48 ? cjkBuf.substring(0, 48) : cjkBuf.toString());
          }
          cjkBuf.setLength(0);
        }
      }
    }
    if (latinBuf.length() > 0 && latinTokens.size() < 48) latinTokens.add(latinBuf.toString());
    if (cjkBuf.length() > 0 && cjkRuns.size() < 32) {
      cjkRuns.add(cjkBuf.length() > 48 ? cjkBuf.substring(0, 48) : cjkBuf.toString());
    }

    for (int i = 0; i < latinTokens.size(); i++) {
      String tok = latinTokens.get(i);
      feats.putIfAbsent("w:" + tok, 3.0f);
      if (i > 0) {
        feats.putIfAbsent("wb:" + latinTokens.get(i - 1) + "_" + tok, 1.8f);
      }
      String wtrim = tok.length() > 18 ? tok.substring(0, 18) : tok;
      if (wtrim.length() >= 4) {
        String padded = "^" + wtrim + "$";
        int lp = padded.length();
        for (int st = 0; st <= lp - 4; st++) {
          feats.putIfAbsent("c4:" + padded.substring(st, st + 4), 0.55f);
        }
      }
    }

    for (String run : cjkRuns) {
      int lr = run.length();
      for (int i = 0; i < lr; i++) {
        feats.putIfAbsent("zh1:" + run.charAt(i), 0.50f);
      }
      for (int i = 0; i <= lr - 2; i++) {
        feats.putIfAbsent("zh2:" + run.substring(i, i + 2), 2.6f);
      }
      for (int i = 0; i <= lr - 3; i++) {
        feats.putIfAbsent("zh3:" + run.substring(i, i + 3), 1.8f);
      }
    }

    if (fieldTags != null) {
      for (Feature tag : fieldTags) {
        if (tag != null && tag.key != null && !tag.key.isEmpty()) {
          feats.putIfAbsent(tag.key, tag.weight);
        }
      }
    }

    List<Feature> out = new ArrayList<>(feats.size());
    for (Map.Entry<String, Float> e : feats.entrySet()) {
      out.add(new Feature(e.getKey(), e.getValue()));
    }
    return out;
  }

  private static float[] matVecAdd(QMatrix mat, float[] x, float[] bias) {
    float[] out = new float[mat.rows];
    int cols = mat.cols;
    for (int r = 0; r < mat.rows; r++) {
      int base = r * cols;
      double sum = 0.0;
      for (int c = 0; c < cols; c++) sum += mat.q[base + c] * (double) x[c];
      out[r] = (float) (sum * mat.scales[r] + bias[r]);
    }
    return out;
  }

  private static void layerNormInPlace(float[] v, float[] w, float[] b) {
    int n = v.length;
    double mean = 0.0;
    for (float x : v) mean += x;
    mean /= n;
    double var = 0.0;
    for (float x : v) {
      double d = x - mean;
      var += d * d;
    }
    var /= n;
    double invStd = 1.0 / Math.sqrt(var + 1e-5);
    for (int i = 0; i < n; i++) v[i] = (float) (((v[i] - mean) * invStd) * w[i] + b[i]);
  }

  private static float geluTanh(float x) {
    double v = x;
    double inner = 0.7978845608028654 * (v + 0.044715 * v * v * v);
    return (float) (0.5 * v * (1.0 + Math.tanh(inner)));
  }

  private static float[] encodeWithModel(Model m, String raw, List<Feature> fieldTags) {
    List<Feature> feats = extractFeatures(raw, fieldTags);
    float[] zero = new float[m.embDim];
    if (feats.isEmpty()) return zero;

    double totalW = 0.0;
    for (Feature f : feats) totalW += f.weight;
    if (totalW <= 1e-6) return zero;

    double invW = 1.0 / totalW;
    double cov = 0.0;
    Map<Integer, Double> bucketWeights = new LinkedHashMap<>(feats.size() * 2);
    for (Feature f : feats) {
      int row = Arrays.binarySearch(m.vocabHashes, fnv1a63(f.key));
      if (row >= 0) {
        double addW = f.weight * invW;
        double nw = bucketWeights.getOrDefault(row, 0.0) + addW;
        bucketWeights.put(row, nw);
        cov += addW;
      }
    }
    if (bucketWeights.isEmpty()) return zero;

    double[] acc0 = new double[m.embDim];
    for (Map.Entry<Integer, Double> bw : bucketWeights.entrySet()) {
      int b = bw.getKey();
      double w = bw.getValue() * m.emb.scales[b];
      int base = b * m.embDim;
      for (int d = 0; d < m.embDim; d++) acc0[d] += w * m.emb.q[base + d];
    }
    double n0 = 0.0;
    for (int d = 0; d < m.embDim; d++) n0 += acc0[d] * acc0[d];
    double r0 = Math.sqrt(n0);
    double d0 = Math.max(1e-6, r0);
    float[] e0 = new float[m.embDim];
    for (int d = 0; d < m.embDim; d++) e0[d] = (float) (acc0[d] / d0);

    float[] h = matVecAdd(m.w1, e0, m.b1);
    layerNormInPlace(h, m.ln1W, m.ln1B);
    for (int i = 0; i < h.length; i++) h[i] = geluTanh(h[i]);

    float[] delta = matVecAdd(m.w2, h, m.b2);
    double n1 = 0.0;
    float[] e1 = new float[m.embDim];
    for (int d = 0; d < m.embDim; d++) {
      e1[d] = e0[d] + delta[d];
      n1 += (double) e1[d] * e1[d];
    }
    double d1 = Math.max(1e-6, Math.sqrt(n1));
    double r3 = n0 * r0;
    double c2 = cov * cov;
    double gate = (r3 / (r3 + 0.24 * 0.24 * 0.24)) * (c2 / (c2 + 0.14 * 0.14));
    float[] out = new float[m.embDim];
    for (int d = 0; d < m.embDim; d++) out[d] = (float) (gate * (e1[d] / d1));
    return out;
  }

  public static float[] encodeQuery(String query) {
    if (disabled || query == null || query.trim().isEmpty()) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;
    return encodeWithModel(m, query, null);
  }

  static float[] encodeDocDirect(
      String merchant,
      String cat,
      String sub,
      String kind,
      String note,
      String person,
      String acctName,
      String taxBucket) {
    if (disabled) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    StringBuilder sb = new StringBuilder();
    appendNonEmpty(sb, merchant);
    appendNonEmpty(sb, cat);
    appendNonEmpty(sb, sub);
    appendNonEmpty(sb, note);
    appendNonEmpty(sb, person);
    appendNonEmpty(sb, taxBucket);
    if ("refund".equals(kind)) appendNonEmpty(sb, "refund reversal");
    else if ("shared".equals(kind)) appendNonEmpty(sb, "shared split");
    else if ("transfer".equals(kind)) appendNonEmpty(sb, "account transfer topup");
    else if ("income".equals(kind)) appendNonEmpty(sb, "income received");

    List<Feature> tags = new ArrayList<>(5);
    if (cat != null && !cat.isEmpty()) tags.add(new Feature("cat:" + slugifyTag(cat), 3.5f));
    if (sub != null && !sub.isEmpty()) tags.add(new Feature("sub:" + slugifyTag(sub), 4.5f));
    if (kind != null && !kind.isEmpty()) tags.add(new Feature("kind:" + slugifyTag(kind), 2.8f));
    if (taxBucket != null && !taxBucket.isEmpty()) {
      tags.add(new Feature("lhdn:tax_relief", 5.0f));
      tags.add(new Feature("lhdn:" + slugifyTag(taxBucket), 4.0f));
    }
    float[] vSurf = encodeWithModel(m, sb.toString(), tags);
    return blendWithPrototype(m, vSurf, cat, sub);
  }

  private static float[] blendWithPrototype(Model m, float[] vSurf, String cat, String sub) {
    if (vSurf == null || cat == null || cat.isEmpty()) return vSurf;
    String safeSub = sub == null ? "" : sub;
    int matchIdx = -1;
    for (int i = 0; i < m.numProtos; i++) {
      if (cat.equals(m.protoCats[i]) && safeSub.equals(m.protoSubs[i])) {
        matchIdx = i;
        break;
      }
    }
    if (matchIdx < 0) return vSurf;
    int base = matchIdx * m.embDim;
    float[] out = new float[m.embDim];
    double normSq = 0.0;
    for (int d = 0; d < m.embDim; d++) {
      double v = 0.35 * vSurf[d] + 0.65 * m.protoUnitEmbs[base + d];
      out[d] = (float) v;
      normSq += v * v;
    }
    double denom = Math.max(1e-6, Math.sqrt(normSq));
    for (int d = 0; d < m.embDim; d++) out[d] = (float) (out[d] / denom);
    return out;
  }

  public static float[] encodeTransaction(JSONObject tx, String accountName, String taxBucket) {
    if (disabled || tx == null) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    String rawMerch = tx.optString("merchant", "").trim();
    String cat = tx.optString("category", "").trim();
    String sub = tx.optString("subcategory", "").trim();
    String kind = tx.optString("kind", "").trim();
    String note = tx.optString("note", "").trim();
    String person = tx.optString("person", "").trim();
    StringBuilder tagText = new StringBuilder();
    JSONArray tagsArr = tx.optJSONArray("tags");
    if (tagsArr != null) {
      for (int i = 0; i < tagsArr.length(); i++) {
        String tg = tagsArr.optString(i, "").trim();
        if (!tg.isEmpty()) {
          if (tagText.length() > 0) tagText.append(' ');
          tagText.append(tg);
        }
      }
    }
    String fullNote = tagText.length() == 0 ? note : (note.isEmpty() ? tagText.toString() : note + " " + tagText);

    String cacheKey =
        rawMerch
            + "|"
            + cat
            + "|"
            + sub
            + "|"
            + kind
            + "|"
            + fullNote
            + "|"
            + person
            + "|"
            + (taxBucket == null ? "" : taxBucket);

    float[] cached = txEmbCache.get(cacheKey);
    if (cached != null) return cached;

    String merch = rawMerch;
    // Enrich noisy bank-statement merchant names with canonical brand name if loaded (cache miss only)
    if (MerchantCanonicalizer.isLoaded() && !merch.isEmpty() && MerchantCanonicalizer.isNoisyTruncatedSurface(merch)) {
      MerchantCanonicalizer.Result canon = MerchantCanonicalizer.canonicalize(merch, "");
      if (canon != null
          && canon.matchedCatalog
          && canon.similarity >= 0.62
          && !canon.canonicalName.equalsIgnoreCase(merch)) {
        merch = merch + " " + canon.canonicalName;
      }
    }

    float[] vec = encodeDocDirect(merch, cat, sub, kind, fullNote, person, accountName, taxBucket);
    if (vec != null) txEmbCache.put(cacheKey, vec);
    return vec;
  }

  private static void appendNonEmpty(StringBuilder sb, String s) {
    if (s == null) return;
    String t = s.trim();
    if (t.isEmpty()) return;
    if (sb.length() > 0) sb.append(' ');
    sb.append(t);
  }

  public static double dot(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length) return 0.0;
    double s = 0.0;
    for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
    return s;
  }

  public static double l2Norm(float[] v) {
    if (v == null) return 0.0;
    double s = 0.0;
    for (float x : v) s += (double) x * x;
    return Math.sqrt(s);
  }

  public static float semanticThreshold() {
    ensureLoaded();
    Model m = activeModel;
    return m != null ? m.semanticThreshold : 0.44f;
  }

  public static float classifyThreshold() {
    ensureLoaded();
    Model m = activeModel;
    return m != null ? m.classifyThreshold : 0.50f;
  }

  static CategoryPrediction topPrototype(String text) {
    if (disabled || text == null || text.trim().isEmpty()) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null || m.numProtos == 0) return null;
    return scoreAgainstProtos(m, text.trim(), false);
  }

  public static CategoryPrediction classifyMultilingual(String merchantClean, String rawText) {
    if (disabled) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null || m.numProtos == 0) return null;

    String mc = merchantClean == null ? "" : merchantClean.trim();
    String rt = rawText == null ? "" : rawText.trim();
    String queryText;
    if (mc.isEmpty() || "Quick expense".equalsIgnoreCase(mc)) queryText = rt;
    else if (rt.isEmpty() || mc.equalsIgnoreCase(rt)) queryText = mc;
    else queryText = mc;

    CategoryPrediction best = scoreAgainstProtos(m, queryText, true);
    if (best != null && best.confidence >= m.classifyThreshold && best.margin >= m.classifyMargin) {
      return best;
    }
    if (!rt.isEmpty() && !rt.equals(queryText)) {
      CategoryPrediction fallback = scoreAgainstProtos(m, mc.isEmpty() ? rt : (mc + " " + rt), true);
      if (fallback != null
          && fallback.confidence >= m.classifyThreshold
          && fallback.margin >= m.classifyMargin) {
        return fallback;
      }
    }
    return null;
  }

  private static boolean isNonExpenseCategory(String cat) {
    return "Salary".equals(cat) || "Other income".equals(cat) || "Miscellaneous".equals(cat);
  }

  private static CategoryPrediction scoreAgainstProtos(Model m, String text, boolean expenseOnly) {
    float[] q = encodeWithModel(m, text, null);
    if (l2Norm(q) < 1e-4) return null;

    int bestIdx = -1;
    double bestSim = -2.0;
    for (int i = 0; i < m.numProtos; i++) {
      if (expenseOnly && isNonExpenseCategory(m.protoCats[i])) continue;
      int base = i * m.embDim;
      double s = 0.0;
      for (int d = 0; d < m.embDim; d++) s += (double) q[d] * m.protoUnitEmbs[base + d];
      if (s > bestSim) {
        bestSim = s;
        bestIdx = i;
      }
    }
    if (bestIdx < 0) return null;
    String bestCat = m.protoCats[bestIdx];
    String bestSub = m.protoSubs[bestIdx];

    double bestOtherCatSim = -1.0;
    for (int i = 0; i < m.numProtos; i++) {
      if (expenseOnly && isNonExpenseCategory(m.protoCats[i])) continue;
      if (bestCat.equals(m.protoCats[i])) continue;
      int base = i * m.embDim;
      double s = 0.0;
      for (int d = 0; d < m.embDim; d++) s += (double) q[d] * m.protoUnitEmbs[base + d];
      if (s > bestOtherCatSim) bestOtherCatSim = s;
    }
    double margin = bestSim - Math.max(0.0, bestOtherCatSim);
    return new CategoryPrediction(bestCat, bestSub, bestSim, margin);
  }

  // --- Structured Multilingual Query Parser for Offline Search ---
  private static final Pattern AMT_RANGE_RE =
      Pattern.compile(
          "(?i)(?:rm\\s*)?([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s*(?:-|to|hingga|到|~)\\s*(?:rm\\s*)?([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\b");
  private static final Pattern AMT_CMP_RE =
      Pattern.compile(
          "(?i)(>=|<=|>|<|\\b(?:over|above|more\\s+than|at\\s+least|lebih(?:\\s+daripada)?|atas)\\b|\\b(?:under|below|less\\s+than|at\\s+most|bawah|kurang(?:\\s+daripada)?)\\b|超过|大于|至少|低于|小于|不超过)\\s*(?:rm|myr)?\\s*([0-9]{1,6}(?:\\.[0-9]{1,2})?)(?:\\s*(?:rm|ringgit|块|元))?");
  private static final Pattern EXPLICIT_ACCT_RE =
      Pattern.compile(
          "(?i)(?:\\b(?:account|acct|akaun):\\s*|\\b(?:via|from|dari|daripada|pakai|guna)\\s+|(?:用|从|通过)\\s*)(tng|touch\\s*'?n\\s*go|mae|maybank|cimb|rhb|hlb|gxbank|gx|aeon|ryt|shopeepay|spay|grabpay|boost|bigpay|cash|tunai|现金)\\b");
  private static final Pattern EXPLICIT_KIND_RE =
      Pattern.compile("(?i)\\b(?:type|kind):(expense|income|transfer|refund|shared|split)\\b");
  private static final Pattern ISO_DATE_RE = Pattern.compile("\\b(20[0-9]{2}-[01][0-9]-[0-3][0-9])\\b");
  private static final Pattern ISO_YM_RE = Pattern.compile("\\b(20[0-9]{2}-[01][0-9])\\b");
  private static final Pattern ZH_MONTH_RE = Pattern.compile("(?<![0-9])(1[0-2]|[1-9])月(?![0-9]{1,2}[日号])");
  private static final Pattern REL_DATE_QUERY_RE =
      Pattern.compile(
          "(?i)(?:\\b(?:today|hari\\s+ini|harini|yesterday|semalam|smlm|this\\s+week|minggu\\s+ini|last\\s+week|minggu\\s+lepas|this\\s+month|bulan\\s+ini|last\\s+month|bulan\\s+lepas)\\b|今天|昨日|昨天|这周|上周|这个月|本月|上个月|上月)");

  public static ParsedQuery parseQueryFilters(String rawQuery, LocalDate today) {
    String raw = rawQuery == null ? "" : rawQuery.trim();
    if (raw.isEmpty()) {
      return new ParsedQuery("", "", -1L, -1L, "", "", "", "", "", false);
    }
    LocalDate refToday = today != null ? today : LocalDate.now();
    String work = raw;
    long minMinor = -1L;
    long maxMinor = -1L;
    String fromDate = "";
    String untilDate = "";
    String hardKind = "";
    String kindBoost = "";
    String accountHint = "";

    Matcher rk = EXPLICIT_KIND_RE.matcher(work);
    if (rk.find()) {
      String k = rk.group(1).toLowerCase(Locale.ROOT);
      hardKind = "split".equals(k) ? "shared" : k;
      work = (work.substring(0, rk.start()) + " " + work.substring(rk.end())).trim();
    }

    Matcher ra = EXPLICIT_ACCT_RE.matcher(work);
    if (ra.find()) {
      accountHint = normalizeAccountAlias(ra.group(1));
      work = (work.substring(0, ra.start()) + " " + work.substring(ra.end())).trim();
    }

    Matcher ar = AMT_RANGE_RE.matcher(work);
    if (ar.find()) {
      long a = toMinorSafe(ar.group(1));
      long b = toMinorSafe(ar.group(2));
      if (a >= 0L && b >= 0L) {
        minMinor = Math.min(a, b);
        maxMinor = Math.max(a, b);
        work = (work.substring(0, ar.start()) + " " + work.substring(ar.end())).trim();
      }
    } else {
      Matcher ac = AMT_CMP_RE.matcher(work);
      while (ac.find()) {
        String op = ac.group(1).toLowerCase(Locale.ROOT).trim();
        long val = toMinorSafe(ac.group(2));
        if (val >= 0L) {
          if (">=".equals(op) || op.contains("at least") || op.contains("至少")) {
            minMinor = val;
          } else if ("<=".equals(op) || op.contains("at most") || op.contains("不超过")) {
            maxMinor = val;
          } else if (op.startsWith(">")
              || op.contains("over")
              || op.contains("above")
              || op.contains("more")
              || op.contains("lebih")
              || op.contains("atas")
              || op.contains("超过")
              || op.contains("大于")) {
            minMinor = val + 1L;
          } else if (op.startsWith("<")
              || op.contains("under")
              || op.contains("below")
              || op.contains("less")
              || op.contains("bawah")
              || op.contains("kurang")
              || op.contains("低于")
              || op.contains("小于")) {
            maxMinor = Math.max(0L, val - 1L);
          }
          work = (work.substring(0, ac.start()) + " " + work.substring(ac.end())).trim();
          ac = AMT_CMP_RE.matcher(work);
        }
      }
    }

    Matcher di = ISO_DATE_RE.matcher(work);
    if (di.find()) {
      fromDate = di.group(1);
      untilDate = di.group(1);
      work = (work.substring(0, di.start()) + " " + work.substring(di.end())).trim();
    } else {
      Matcher ymM = ISO_YM_RE.matcher(work);
      if (ymM.find()) {
        try {
          YearMonth ym = YearMonth.parse(ymM.group(1));
          fromDate = ym.atDay(1).toString();
          untilDate = ym.atEndOfMonth().toString();
          work = (work.substring(0, ymM.start()) + " " + work.substring(ymM.end())).trim();
        } catch (Exception ignored) {
        }
      }
    }

    if (fromDate.isEmpty()) {
      Matcher rd = REL_DATE_QUERY_RE.matcher(work);
      if (rd.find()) {
        String tok = rd.group(0).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if ("today".equals(tok) || "hari ini".equals(tok) || "harini".equals(tok) || "今天".equals(tok)) {
          fromDate = refToday.toString();
          untilDate = fromDate;
        } else if ("yesterday".equals(tok)
            || "semalam".equals(tok)
            || "smlm".equals(tok)
            || "昨天".equals(tok)
            || "昨日".equals(tok)) {
          fromDate = refToday.minusDays(1).toString();
          untilDate = fromDate;
        } else if ("this week".equals(tok) || "minggu ini".equals(tok) || "这周".equals(tok)) {
          fromDate = refToday.minusDays(refToday.getDayOfWeek().getValue() - 1L).toString();
          untilDate = refToday.toString();
        } else if ("last week".equals(tok) || "minggu lepas".equals(tok) || "上周".equals(tok)) {
          LocalDate startThis = refToday.minusDays(refToday.getDayOfWeek().getValue() - 1L);
          fromDate = startThis.minusWeeks(1).toString();
          untilDate = startThis.minusDays(1).toString();
        } else if ("this month".equals(tok)
            || "bulan ini".equals(tok)
            || "这个月".equals(tok)
            || "本月".equals(tok)) {
          YearMonth ym = YearMonth.from(refToday);
          fromDate = ym.atDay(1).toString();
          untilDate = ym.atEndOfMonth().toString();
        } else if ("last month".equals(tok)
            || "bulan lepas".equals(tok)
            || "上个月".equals(tok)
            || "上月".equals(tok)) {
          YearMonth ym = YearMonth.from(refToday).minusMonths(1);
          fromDate = ym.atDay(1).toString();
          untilDate = ym.atEndOfMonth().toString();
        }
        work = (work.substring(0, rd.start()) + " " + work.substring(rd.end())).trim();
      } else {
        Matcher zm = ZH_MONTH_RE.matcher(work);
        if (zm.find()) {
          int mo = Integer.parseInt(zm.group(1));
          int yr = mo > refToday.getMonthValue() ? refToday.getYear() - 1 : refToday.getYear();
          YearMonth ym = YearMonth.of(yr, mo);
          fromDate = ym.atDay(1).toString();
          untilDate = ym.atEndOfMonth().toString();
          work = (work.substring(0, zm.start()) + " " + work.substring(zm.end())).trim();
        }
      }
    }

    String low = raw.toLowerCase(Locale.ROOT);
    boolean taxReliefQuery =
        low.contains("tax relief")
            || low.contains("lhdn")
            || low.contains("pelepasan cukai")
            || low.contains("potongan cukai")
            || low.contains("deductible")
            || low.contains("e-filing")
            || low.contains("报税")
            || low.contains("扣税")
            || low.contains("退税")
            || low.contains("所得税减免");

    if (hardKind.isEmpty()) {
      if (low.contains("refund") || low.contains("bayaran balik") || low.contains("pulangan") || low.contains("退款") || low.contains("退钱")) {
        kindBoost = "refund";
      } else if (low.contains("split") || low.contains("kongsi") || low.contains("平分") || low.contains("垫付") || low.contains("aa")) {
        kindBoost = "shared";
      } else if (low.contains("gaji") || low.contains("salary") || low.contains("工资") || low.contains("薪水") || low.contains("income")) {
        kindBoost = "income";
      } else if (low.contains("transfer") || low.contains("pindah") || low.contains("转账") || low.contains("充值")) {
        kindBoost = "transfer";
      }
    }

    String semText = work.replaceAll("\\s+", " ").trim();
    return new ParsedQuery(
        raw, semText, minMinor, maxMinor, fromDate, untilDate, hardKind, kindBoost, accountHint, taxReliefQuery);
  }

  private static long toMinorSafe(String s) {
    try {
      return Ledger.minor(s);
    } catch (Exception ignored) {
      return -1L;
    }
  }

  private static String normalizeAccountAlias(String raw) {
    String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    if (s.equals("tng") || s.contains("touch")) return "touch";
    if (s.equals("mae") || s.contains("maybank")) return "maybank";
    if (s.equals("tunai") || s.equals("现金")) return "cash";
    if (s.equals("spay")) return "shopeepay";
    if (s.equals("gx")) return "gxbank";
    return s;
  }
}
