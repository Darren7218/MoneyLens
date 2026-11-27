package app.moneylens;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Compact Pure-JDK INT8 Neural Notification Gate (MLNG v1) for multilingual (EN/BM/Manglish/ZH)
 * completed-payment vs trap disambiguation. Zero Android framework imports.
 */
public final class NotificationGate {
  public static final String[] LABELS = {"reject", "expense", "income", "refund", "transfer", "reload"};

  public static final class Prediction {
    public final float[] logits;
    public final float[] probs;
    public final int bestClass;
    public final String bestKind;
    public final float rejectProb;
    public final float captureProb;
    public final String bestNonRejectKind;
    public final float bestNonRejectProb;
    public final float rejectThreshold;
    public final float overrideCaptureThreshold;
    public final float overrideRejectThreshold;

    Prediction(float[] logits, float[] probs, int bestClass, String bestKind,
               float rejectProb, float captureProb, String bestNonRejectKind, float bestNonRejectProb,
               float rejectThreshold, float overrideCaptureThreshold, float overrideRejectThreshold) {
      this.logits = logits;
      this.probs = probs;
      this.bestClass = bestClass;
      this.bestKind = bestKind;
      this.rejectProb = rejectProb;
      this.captureProb = captureProb;
      this.bestNonRejectKind = bestNonRejectKind;
      this.bestNonRejectProb = bestNonRejectProb;
      this.rejectThreshold = rejectThreshold;
      this.overrideCaptureThreshold = overrideCaptureThreshold;
      this.overrideRejectThreshold = overrideRejectThreshold;
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
    final int structDim;
    final int hiddenDim;
    final int numClasses;
    final float temperature;
    final float rejectThreshold;
    final float overrideCaptureThreshold;
    final float overrideRejectThreshold;
    final QMatrix emb;
    final QMatrix w1;
    final float[] b1;
    final float[] ln1W;
    final float[] ln1B;
    final QMatrix w2;
    final float[] b2;

    Model(int numBuckets, int embDim, int structDim, int hiddenDim, int numClasses,
          float temperature, float rejectThreshold, float overrideCaptureThreshold, float overrideRejectThreshold,
          QMatrix emb, QMatrix w1, float[] b1, float[] ln1W, float[] ln1B, QMatrix w2, float[] b2) {
      this.numBuckets = numBuckets;
      this.embDim = embDim;
      this.structDim = structDim;
      this.hiddenDim = hiddenDim;
      this.numClasses = numClasses;
      this.temperature = temperature;
      this.rejectThreshold = rejectThreshold;
      this.overrideCaptureThreshold = overrideCaptureThreshold;
      this.overrideRejectThreshold = overrideRejectThreshold;
      this.emb = emb;
      this.w1 = w1;
      this.b1 = b1;
      this.ln1W = ln1W;
      this.ln1B = ln1B;
      this.w2 = w2;
      this.b2 = b2;
    }
  }

  // Exact shared regexes matching train script byte-for-byte
  private static final Pattern FOOTER_RE = Pattern.compile(
      "(?i)\\b(?:never|do\\s+not|don'?t|jangan)\\s+(?:share|disclose|reveal|give|kongsi|dedahkan|berikan)\\b[^.!\\n]*");
  private static final Pattern RE_RM_AMT = Pattern.compile("(?:rm|myr)\\s*[0-9]+|[0-9]+(?:\\.[0-9]{1,2})?\\s*(?:rm|myr)");
  private static final Pattern RE_AMT_ALL = Pattern.compile("(?:rm|myr)\\s*[0-9,]+(?:\\.[0-9]{1,2})?");
  private static final Pattern RE_FX_AMT = Pattern.compile("\\b(?:sgd|usd|thb|jpy|idr|eur|gbp|aud|cny|hkd|twd|krw|vnd)\\s*[0-9]");
  private static final Pattern RE_OTP_DIGITS = Pattern.compile(
      "(?:otp|tac|pac|code|kod|pin)[^0-9]{0,12}[0-9]{4,8}\\b|\\b[0-9]{4,8}[^0-9]{0,12}(?:otp|tac|pac|code|kod)\\b");
  private static final Pattern RE_OTP_WORD = Pattern.compile(
      "\\b(?:otp|tac|pac|3ds|3d-?secure|secure2u|one-?time\\s+(?:pin|password|passcode)|verification\\s+code|kod\\s+pengesahan|kod\\s+keselamatan|authori[sz]e|approve|sahkan)\\b");
  private static final Pattern RE_COMPLETED = Pattern.compile(
      "\\b(?:paid|berjaya|deducted|debited|charged|credited|dikreditkan|received|diterima|completed|settled|processed|successful|successfully|selesai|dibayar|refunded|dikembalikan|dipulangkan|approved\\s+via|verified\\s+via|verified|authori[sz]ed\\s+(?:a\\s+payment|via))\\b");
  private static final Pattern RE_FAIL = Pattern.compile(
      "\\b(?:failed|declined|unsuccessful|gagal|rejected|tidak\\s+berjaya|tidak\\s+diluluskan|insufficient\\s+(?:funds|balance)|baki\\s+tidak\\s+mencukupi|unable\\s+to\\s+process)\\b");
  private static final Pattern RE_CANCEL = Pattern.compile("\\b(?:cancelled|canceled|dibatalkan)\\b");
  private static final Pattern RE_REFUND = Pattern.compile(
      "\\b(?:refund|refunded|reversal|reversed|chargeback|bayaran\\s+balik|pulangan|dikembalikan|dipulangkan)\\b");
  private static final Pattern RE_PROMO = Pattern.compile(
      "\\b(?:voucher|baucar|kupon|promo|promosi|promotion|discount|diskaun|tawaran|offer|deal|deals|lucky\\s+draw|cabutan|win|redeem|claim\\s+now|cashback|rebate|points|rewards|min\\s+spend|minimum\\s+spend)\\b");
  private static final Pattern RE_BILL_DUE = Pattern.compile(
      "\\b(?:reminder|peringatan|due\\s+(?:on|soon|tomorrow|today)|tarikh\\s+akhir|tamat\\s+tempoh|minimum\\s+payment|bayaran\\s+minimum|statement\\s+(?:balance|ready|is\\s+ready)|penyata\\s+(?:bulanan|anda)|avoid\\s+late\\s+fee)\\b");
  private static final Pattern RE_PENDING = Pattern.compile(
      "\\b(?:pending|processing|awaiting|requested|scheduled|menunggu|sedang\\s+diproses|tertunda|hold)\\b");
  private static final Pattern RE_MOVE = Pattern.compile(
      "\\b(?:transfer|transferred|top-?up|reload|reloaded|tambah\\s+nilai|pindahan|withdrawal)\\b");
  private static final Pattern RE_INCOME = Pattern.compile(
      "\\b(?:salary|gaji|credited|dikreditkan|received|diterima|menerima|from|daripada|incoming)\\b");

  private static final String[] CJK_OTP_WORDS = {"验证码", "动态口令", "动态码", "安全码"};
  private static final String[] CJK_COMPLETED_WORDS = {"已支付", "付款成功", "支付成功", "到账成功", "转账成功", "已消费", "扣款", "已退款", "退款成功", "退款到账", "收到"};
  private static final String[] CJK_FAIL_WORDS = {"失败", "未扣款", "余额不足", "被拒绝"};
  private static final String[] CJK_CANCEL_WORDS = {"已取消"};
  private static final String[] CJK_REFUND_WORDS = {"退款"};
  private static final String[] CJK_PROMO_WORDS = {"优惠券", "代金券", "促销", "立减"};
  private static final String[] CJK_BILL_DUE_WORDS = {"账单提醒", "最低还款", "到期还款", "可用余额", "账户余额"};
  private static final String[] CJK_PENDING_WORDS = {"待处理", "预授权", "处理中", "冻结中"};
  private static final String[] CJK_MOVE_WORDS = {"转账", "充值"};
  private static final String[] CJK_INCOME_WORDS = {"收到", "工资", "薪水", "转来", "汇款"};
  private static final Set<String> SRC_IGNORE = new HashSet<>(Arrays.asList("com", "my", "app", "life", "android", "mobile", "co"));

  private static final LinkedHashMap<String, Prediction> SCORE_CACHE =
      new LinkedHashMap<String, Prediction>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Prediction> eldest) {
          return size() > 256;
        }
      };

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;
  private static volatile boolean disabled = false;
  private static volatile java.util.function.Supplier<InputStream> streamSupplier = null;

  private NotificationGate() {}

  public static void clearCacheForParity() {
    synchronized (SCORE_CACHE) {
      SCORE_CACHE.clear();
    }
  }

  public static boolean isLoaded() {
    return activeModel != null;
  }

  public static synchronized void setDisabled(boolean off) {
    disabled = off;
    clearCacheForParity();
  }

  public static synchronized void setStreamSupplier(java.util.function.Supplier<InputStream> supplier) {
    streamSupplier = supplier;
    if (activeModel == null) autoLoadAttempted = false;
  }

  public static synchronized void trim() {
    activeModel = null;
    autoLoadAttempted = false;
    clearCacheForParity();
  }

  public static synchronized void clear() {
    activeModel = null;
    autoLoadAttempted = false;
    disabled = false;
    streamSupplier = null;
    clearCacheForParity();
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
      autoLoadAttempted = true;
      disabled = false;
      clearCacheForParity();
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
      } catch (Exception ignored) {}
    }
    for (String res : new String[]{"/moneylens_notif_gate.bin.gz", "/moneylens_notif_gate.bin"}) {
      try (InputStream cp = NotificationGate.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {}
    }
    for (String candidate : new String[]{
        "app/src/main/assets/moneylens_notif_gate.bin.gz",
        "app/src/main/assets/moneylens_notif_gate.bin",
        "src/main/assets/moneylens_notif_gate.bin.gz",
        "src/main/assets/moneylens_notif_gate.bin",
        "../app/src/main/assets/moneylens_notif_gate.bin.gz",
        "../app/src/main/assets/moneylens_notif_gate.bin"
    }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static Model parseBinaryModel(DataInputStream dis) throws Exception {
    byte[] magic = new byte[4];
    dis.readFully(magic);
    if (magic[0] != 'M' || magic[1] != 'L' || magic[2] != 'N' || magic[3] != 'G') {
      throw new IllegalArgumentException("Bad MLNG magic");
    }
    int version = dis.readInt();
    if (version != 1) throw new IllegalArgumentException("Unsupported MLNG version: " + version);
    int numBuckets = dis.readInt();
    int embDim = dis.readInt();
    int structDim = dis.readInt();
    int hiddenDim = dis.readInt();
    int numClasses = dis.readInt();
    float temp = dis.readFloat();
    float rejTh = dis.readFloat();
    float ovCapTh = dis.readFloat();
    float ovRejTh = dis.readFloat();

    QMatrix emb = readQuantizedMatrix(dis);
    QMatrix w1 = readQuantizedMatrix(dis);
    float[] b1 = readFloatArray(dis, hiddenDim);
    float[] ln1W = readFloatArray(dis, hiddenDim);
    float[] ln1B = readFloatArray(dis, hiddenDim);
    QMatrix w2 = readQuantizedMatrix(dis);
    float[] b2 = readFloatArray(dis, numClasses);
    return new Model(
        numBuckets, embDim, structDim, hiddenDim, numClasses,
        temp, rejTh, ovCapTh, ovRejTh,
        emb, w1, b1, ln1W, ln1B, w2, b2);
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

  static int fnv1a32(String s) {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    int h = 0x811c9dc5;
    for (byte b : bytes) {
      h ^= (b & 0xFF);
      h *= 0x01000193;
    }
    return h;
  }

  private static boolean containsAny(String low, String[] keys) {
    for (String k : keys) if (low.contains(k)) return true;
    return false;
  }

  private static boolean isDigitsOnly(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  private static float[] matVecAdd(QMatrix w, float[] x, float[] b) {
    float[] out = new float[w.rows];
    for (int r = 0; r < w.rows; r++) {
      int base = r * w.cols;
      double acc = 0.0;
      float sc = w.scales[r];
      for (int c = 0; c < w.cols; c++) {
        acc += (w.q[base + c] * (double) sc) * x[c];
      }
      out[r] = (float) (b[r] + acc);
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
    double inner = 0.7978845608 * (v + 0.044715 * v * v * v);
    return (float) (0.5 * v * (1.0 + Math.tanh(inner)));
  }

  /** Scores a notification text + source package and returns calibrated class probabilities, or null if unavailable. */
  public static Prediction score(String text, String source) {
    if (disabled || text == null || text.trim().isEmpty()) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    String norm = text.replace('\u00A0', ' ').trim();
    String srcStr = source == null ? "" : source;
    String cacheKey = srcStr + "\u0000" + norm;
    synchronized (SCORE_CACHE) {
      Prediction cached = SCORE_CACHE.get(cacheKey);
      if (cached != null) return cached;
    }

    Matcher fm = FOOTER_RE.matcher(norm);
    String core = fm.find() ? fm.replaceAll(" ").trim() : norm;
    String low = core.toLowerCase(Locale.ROOT);
    String srcLow = srcStr.toLowerCase(Locale.ROOT);

    List<String> words = new ArrayList<>();
    List<Character> cjk = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < low.length(); i++) {
      char ch = low.charAt(i);
      if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
        cur.append(ch);
      } else {
        if (cur.length() > 0) {
          words.add(cur.toString());
          cur.setLength(0);
        }
        if (ch >= '\u4e00' && ch <= '\u9fff' && cjk.size() < 32) {
          cjk.add(ch);
        }
      }
    }
    if (cur.length() > 0) words.add(cur.toString());

    int lim = Math.min(28, words.size());
    String[] normToks = new String[lim];
    boolean[] isNum = new boolean[lim];
    for (int i = 0; i < lim; i++) {
      String w = words.get(i);
      boolean num = isDigitsOnly(w);
      isNum[i] = num;
      normToks[i] = (num && w.length() >= 4) ? "#num4" : w;
    }

    Set<String> feats = new HashSet<>();
    for (int i = 0; i < lim; i++) {
      String tok = normToks[i];
      feats.add("w:" + tok);
      if (i + 1 < lim) feats.add("b:" + tok + "_" + normToks[i + 1]);
      if (!isNum[i] && i < 24 && tok.length() >= 3) {
        int len = tok.length();
        for (int j = 0; j <= len - 3; j++) feats.add("g3:" + tok.substring(j, j + 3));
        for (int j = 0; j <= len - 4; j++) feats.add("g4:" + tok.substring(j, j + 4));
      }
    }
    for (int i = 0; i < cjk.size(); i++) {
      char c1 = cjk.get(i);
      feats.add("cu:" + c1);
      if (i + 1 < cjk.size()) feats.add("cb:" + c1 + cjk.get(i + 1));
    }

    StringBuilder scur = new StringBuilder();
    for (int i = 0; i < srcLow.length(); i++) {
      char ch = srcLow.charAt(i);
      if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
        scur.append(ch);
      } else if (scur.length() > 0) {
        String sw = scur.toString();
        scur.setLength(0);
        if (sw.length() >= 2 && !SRC_IGNORE.contains(sw)) feats.add("src:" + sw);
      }
    }
    if (scur.length() >= 2) {
      String sw = scur.toString();
      if (!SRC_IGNORE.contains(sw)) feats.add("src:" + sw);
    }

    float[] sf = new float[m.structDim];
    sf[0] = RE_RM_AMT.matcher(low).find() ? 1.0f : 0.0f;
    Matcher allAmt = RE_AMT_ALL.matcher(low);
    int amtCnt = 0;
    while (allAmt.find() && amtCnt < 2) amtCnt++;
    sf[1] = amtCnt >= 2 ? 1.0f : 0.0f;
    sf[2] = RE_FX_AMT.matcher(low).find() ? 1.0f : 0.0f;
    sf[3] = RE_OTP_DIGITS.matcher(low).find() ? 1.0f : 0.0f;
    sf[4] = (RE_OTP_WORD.matcher(low).find() || containsAny(low, CJK_OTP_WORDS)) ? 1.0f : 0.0f;
    sf[5] = (RE_COMPLETED.matcher(low).find() || containsAny(low, CJK_COMPLETED_WORDS)) ? 1.0f : 0.0f;
    sf[6] = (RE_FAIL.matcher(low).find() || containsAny(low, CJK_FAIL_WORDS)) ? 1.0f : 0.0f;
    sf[7] = (RE_CANCEL.matcher(low).find() || containsAny(low, CJK_CANCEL_WORDS)) ? 1.0f : 0.0f;
    sf[8] = (RE_REFUND.matcher(low).find() || containsAny(low, CJK_REFUND_WORDS)) ? 1.0f : 0.0f;
    sf[9] = (RE_PROMO.matcher(low).find() || containsAny(low, CJK_PROMO_WORDS)) ? 1.0f : 0.0f;
    sf[10] = (RE_BILL_DUE.matcher(low).find() || containsAny(low, CJK_BILL_DUE_WORDS)) ? 1.0f : 0.0f;
    sf[11] = (RE_PENDING.matcher(low).find() || containsAny(low, CJK_PENDING_WORDS)) ? 1.0f : 0.0f;
    sf[12] = (RE_MOVE.matcher(low).find() || containsAny(low, CJK_MOVE_WORDS)) ? 1.0f : 0.0f;
    sf[13] = (RE_INCOME.matcher(low).find() || containsAny(low, CJK_INCOME_WORDS)) ? 1.0f : 0.0f;
    sf[14] = !cjk.isEmpty() ? 1.0f : 0.0f;
    sf[15] = Math.min(1.0f, low.codePointCount(0, low.length()) / 200.0f);

    Map<Integer, Double> bucketWeights = new HashMap<>(feats.size() * 2);
    for (String fk : feats) {
      int h = fnv1a32(fk);
      int bucket = (h & 0x7FFFFFFF) % m.numBuckets;
      double sign = ((h >>> 31) == 0) ? 1.0 : -1.0;
      double next = bucketWeights.getOrDefault(bucket, 0.0) + sign;
      if (next == 0.0) bucketWeights.remove(bucket);
      else bucketWeights.put(bucket, next);
    }

    double[] acc0 = new double[m.embDim];
    for (Map.Entry<Integer, Double> bw : bucketWeights.entrySet()) {
      int b = bw.getKey();
      double w = bw.getValue() * m.emb.scales[b];
      int base = b * m.embDim;
      for (int d = 0; d < m.embDim; d++) acc0[d] += w * m.emb.q[base + d];
    }
    double n0 = 0.0;
    for (int d = 0; d < m.embDim; d++) n0 += acc0[d] * acc0[d];
    double d0 = Math.max(1e-6, Math.sqrt(n0));

    float[] x = new float[m.embDim + m.structDim];
    for (int d = 0; d < m.embDim; d++) x[d] = (float) (acc0[d] / d0);
    System.arraycopy(sf, 0, x, m.embDim, m.structDim);

    float[] h = matVecAdd(m.w1, x, m.b1);
    layerNormInPlace(h, m.ln1W, m.ln1B);
    for (int i = 0; i < h.length; i++) h[i] = geluTanh(h[i]);

    float[] logits = matVecAdd(m.w2, h, m.b2);
    double temp = Math.max(0.05, m.temperature);
    double maxL = -1e18;
    double[] scaled = new double[m.numClasses];
    for (int i = 0; i < m.numClasses; i++) {
      scaled[i] = logits[i] / temp;
      if (scaled[i] > maxL) maxL = scaled[i];
    }
    double sumE = 0.0;
    double[] exps = new double[m.numClasses];
    for (int i = 0; i < m.numClasses; i++) {
      exps[i] = Math.exp(scaled[i] - maxL);
      sumE += exps[i];
    }
    float[] probs = new float[m.numClasses];
    int bestClass = 0;
    int bestNonReject = 1;
    for (int i = 0; i < m.numClasses; i++) {
      probs[i] = (float) (exps[i] / sumE);
      if (probs[i] > probs[bestClass]) bestClass = i;
      if (i >= 1 && probs[i] > probs[bestNonReject]) bestNonReject = i;
    }

    float rejectProb = probs[0];
    float captureProb = 1.0f - rejectProb;
    Prediction pred = new Prediction(
        logits, probs, bestClass, LABELS[bestClass],
        rejectProb, captureProb, LABELS[bestNonReject], probs[bestNonReject],
        m.rejectThreshold, m.overrideCaptureThreshold, m.overrideRejectThreshold);

    synchronized (SCORE_CACHE) {
      SCORE_CACHE.put(cacheKey, pred);
    }
    return pred;
  }
}
