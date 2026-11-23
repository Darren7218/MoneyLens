package app.moneylens;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java INT8 symmetric per-row quantized SubwordResNet Cascaded Neural Reranker (MLRR v1).
 * Synced bit-for-bit with Colab Foundry v5; zero Android framework imports.
 */
public final class MlNeuralReranker {
  public static final class Prediction {
    public final String category;
    public final String subcategory;
    public final double probability;
    public final double margin;
    public final double categoryProbability;
    public final double categoryMargin;
    public final double miscProbability;
    public final int activeSignals;
    final float[] rawLogits;
    final float[] scaledLogits;
    final double[] classProbs;

    Prediction(String category, String subcategory, double probability, double margin,
               double categoryProbability, double categoryMargin, double miscProbability,
               int activeSignals, float[] rawLogits, float[] scaledLogits, double[] classProbs) {
      this.category = category;
      this.subcategory = subcategory;
      this.probability = probability;
      this.margin = margin;
      this.categoryProbability = categoryProbability;
      this.categoryMargin = categoryMargin;
      this.miscProbability = miscProbability;
      this.activeSignals = activeSignals;
      this.rawLogits = rawLogits;
      this.scaledLogits = scaledLogits;
      this.classProbs = classProbs;
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
    final float oodMiscThreshold;
    final String[] classPairs;
    final String[] categories;
    final String[] subcategories;
    final String[] uniqueCategories;
    final int[] classToCategory;
    final Map<String, Integer> classPairToIdx;
    final int miscClassIdx;
    final QMatrix emb;
    final QMatrix w1;
    final QMatrix w2;
    final QMatrix wOut;
    final float[] b1;
    final float[] ln1W;
    final float[] ln1B;
    final float[] b2;
    final float[] ln2W;
    final float[] ln2B;
    final float[] bOut;

    Model(int numBuckets, int embDim, int structDim, int hiddenDim, int numClasses,
          float temperature, float oodMiscThreshold, String[] classPairs,
          QMatrix emb, QMatrix w1, QMatrix w2, QMatrix wOut,
          float[] b1, float[] ln1W, float[] ln1B, float[] b2, float[] ln2W, float[] ln2B, float[] bOut) {
      this.numBuckets = numBuckets;
      this.embDim = embDim;
      this.structDim = structDim;
      this.hiddenDim = hiddenDim;
      this.numClasses = numClasses;
      this.temperature = temperature;
      this.oodMiscThreshold = oodMiscThreshold;
      this.classPairs = classPairs;
      this.categories = new String[numClasses];
      this.subcategories = new String[numClasses];
      this.classToCategory = new int[numClasses];
      this.classPairToIdx = new HashMap<>(numClasses * 2);
      Map<String, Integer> catIdxMap = new HashMap<>();
      java.util.ArrayList<String> catList = new java.util.ArrayList<>();
      int miscIdx = -1;
      for (int i = 0; i < numClasses; i++) {
        String pair = classPairs[i];
        this.classPairToIdx.put(pair, i);
        int sp = pair.indexOf("||");
        if (sp >= 0) {
          categories[i] = pair.substring(0, sp);
          subcategories[i] = pair.substring(sp + 2);
        } else {
          categories[i] = pair;
          subcategories[i] = "";
        }
        if ("Miscellaneous".equals(categories[i])) miscIdx = i;
        Integer cid = catIdxMap.get(categories[i]);
        if (cid == null) {
          cid = catList.size();
          catIdxMap.put(categories[i], cid);
          catList.add(categories[i]);
        }
        this.classToCategory[i] = cid;
      }
      this.uniqueCategories = catList.toArray(new String[0]);
      this.miscClassIdx = miscIdx;
      this.emb = emb;
      this.w1 = w1;
      this.w2 = w2;
      this.wOut = wOut;
      this.b1 = b1;
      this.ln1W = ln1W;
      this.ln1B = ln1B;
      this.b2 = b2;
      this.ln2W = ln2W;
      this.ln2B = ln2B;
      this.bOut = bOut;
    }
  }

  private static final String[] GATEWAY_LITS = {
      "PAY*", "DNQR*", "IPAY88*", "TNGD*", "FPX*", "SP*",
      "RAZERMS*", "MYDEBIT*", "SHOPEEPAY*", "GRAB*", "POS*", "MAE*"
  };
  private static final Pattern FX_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:USD|SGD|THB|JPY|EUR|GBP|AUD|IDR|KRW|HKD|CNY|FX|RATE)(?![A-Z0-9])");
  private static final Pattern LHDN_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:TIN|LHDN|MYINVOIS|SSM|E-INVOICE|SST|CUKAI|ZAKAT|PCB)(?![A-Z0-9])");
  private static final Pattern RECEIPT_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:SUBTOTAL|GRAND\\s+TOTAL|ROUNDING|CHANGE|BAKI)(?![A-Z0-9])");
  private static final Pattern BNPL_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:SPAYLATER|GRABPAYLATER|ATOME|PTPTN|INSTALLMENT|ANSURAN|LOAN|PINJAMAN)(?![A-Z0-9])");
  private static final Pattern P2P_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:SPLIT|KONGSI|SHARE|BAYAR\\s+BALIK|TRANSFER\\s+TO|DUITNOW\\s+TRANSFER|P2P)(?![A-Z0-9])");
  private static final Pattern MALAY_CUE_RE = Pattern.compile("(?<![A-Z0-9])(?:BAYARAN|PEMBAYARAN|TRANSAKSI|JUMLAH|KEDAI|KLINIK|RESTORAN|TARIKH|KEPADA|BAKI)(?![A-Z0-9])");

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;
  private static volatile boolean disabled = false;
  private static volatile java.util.function.Supplier<InputStream> streamSupplier = null;

  private MlNeuralReranker() {}

  public static boolean isLoaded() {
    return activeModel != null;
  }

  public static double getOodMiscThreshold() {
    Model m = activeModel;
    return m != null ? m.oodMiscThreshold : 0.30;
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
  }

  public static synchronized void clear() {
    activeModel = null;
    autoLoadAttempted = false;
    disabled = false;
    streamSupplier = null;
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
    for (String res : new String[]{"/moneylens_reranker.bin.gz", "/moneylens_reranker.bin"}) {
      try (InputStream cp = MlNeuralReranker.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {}
    }
    for (String candidate : new String[]{
        "app/src/main/assets/moneylens_reranker.bin.gz",
        "app/src/main/assets/moneylens_reranker.bin",
        "src/main/assets/moneylens_reranker.bin.gz",
        "src/main/assets/moneylens_reranker.bin",
        "../app/src/main/assets/moneylens_reranker.bin.gz",
        "../app/src/main/assets/moneylens_reranker.bin"
    }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static Model parseBinaryModel(DataInputStream dis) throws Exception {
    byte[] magic = new byte[4];
    dis.readFully(magic);
    if (magic[0] != 'M' || magic[1] != 'L' || magic[2] != 'R' || magic[3] != 'R') {
      throw new IllegalArgumentException("Bad MLRR magic");
    }
    int version = dis.readInt();
    if (version != 1) throw new IllegalArgumentException("Unsupported MLRR version: " + version);
    int numBuckets = dis.readInt();
    int embDim = dis.readInt();
    int structDim = dis.readInt();
    int hiddenDim = dis.readInt();
    int numClasses = dis.readInt();
    float temperature = dis.readFloat();
    float oodMiscThreshold = dis.readFloat();

    String[] classPairs = new String[numClasses];
    for (int i = 0; i < numClasses; i++) classPairs[i] = dis.readUTF();

    QMatrix emb = readQuantizedMatrix(dis);
    QMatrix w1 = readQuantizedMatrix(dis);
    QMatrix w2 = readQuantizedMatrix(dis);
    QMatrix wOut = readQuantizedMatrix(dis);

    float[] b1 = readFloatArray(dis, hiddenDim);
    float[] ln1W = readFloatArray(dis, hiddenDim);
    float[] ln1B = readFloatArray(dis, hiddenDim);
    float[] b2 = readFloatArray(dis, hiddenDim);
    float[] ln2W = readFloatArray(dis, hiddenDim);
    float[] ln2B = readFloatArray(dis, hiddenDim);
    float[] bOut = readFloatArray(dis, numClasses);
    return new Model(
        numBuckets, embDim, structDim, hiddenDim, numClasses,
        temperature, oodMiscThreshold, classPairs,
        emb, w1, w2, wOut, b1, ln1W, ln1B, b2, ln2W, ln2B, bOut);
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

  private static boolean isAsciiAlnum4Plus(String w) {
    int len = w.length();
    if (len < 4) return false;
    for (int i = 0; i < len; i++) {
      char c = w.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (!ok) return false;
    }
    return true;
  }

  private static void emitSkeletons(String w, String prefix, boolean include3, Map<String, Integer> counts) {
    if (!isAsciiAlnum4Plus(w)) return;
    StringBuilder sb = new StringBuilder(16);
    for (int i = 0; i < w.length() && sb.length() < 16; i++) {
      char c = w.charAt(i);
      if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') sb.append(c);
    }
    int sl = sb.length();
    if (sl < 3) return;
    String skel = sb.toString();
    addCapped(counts, prefix + "sk:" + skel);
    if (include3) {
      for (int i = 0; i <= sl - 3; i++) addCapped(counts, prefix + "sk3:" + skel.substring(i, i + 3));
    }
    if (sl >= 4) {
      for (int i = 0; i <= sl - 4; i++) addCapped(counts, prefix + "sk4:" + skel.substring(i, i + 4));
    }
  }

  private static void addCapped(Map<String, Integer> counts, String key) {
    int v = counts.getOrDefault(key, 0);
    if (v < 3) counts.put(key, v + 1);
  }

  private static Map<String, Integer> extractMerchantBag(String merchStr) {
    Map<String, Integer> mc = new HashMap<>();
    List<String> words = MlClassifier.tokenizeWords(merchStr);
    for (int i = 0; i < words.size(); i++) {
      String w = words.get(i);
      addCapped(mc, "mw:" + w);
      if (i + 1 < words.size()) addCapped(mc, "mb:" + w + " " + words.get(i + 1));
      String padded = " " + w + " ";
      int plen = padded.length();
      for (int n = 3; n <= 5; n++) {
        if (plen < n) break;
        for (int st = 0; st <= plen - n; st++) addCapped(mc, "mc:" + padded.substring(st, st + n));
      }
      emitSkeletons(w, "m", true, mc);
    }
    char prevCjk = 0;
    for (int i = 0; i < merchStr.length(); i++) {
      char ch = merchStr.charAt(i);
      if (ch >= '\u4e00' && ch <= '\u9fff') {
        addCapped(mc, "mz:" + ch);
        if (prevCjk != 0) addCapped(mc, "mz2:" + prevCjk + ch);
        prevCjk = ch;
      }
    }
    return mc;
  }

  private static Map<String, Integer> extractTextBag(String textStr) {
    Map<String, Integer> tc = new HashMap<>();
    List<String> allWords = MlClassifier.tokenizeWords(textStr);
    int nw = Math.min(120, allWords.size());
    for (int i = 0; i < nw; i++) {
      String w = allWords.get(i);
      addCapped(tc, "tw:" + w);
      if (i + 1 < nw) addCapped(tc, "tb:" + w + " " + allWords.get(i + 1));
      if (i < 60) {
        String padded = " " + w + " ";
        int plen = padded.length();
        for (int n = 3; n <= 5; n++) {
          if (plen < n) break;
          for (int st = 0; st <= plen - n; st++) addCapped(tc, "tc:" + padded.substring(st, st + n));
        }
      }
      if (i < 40) emitSkeletons(w, "t", false, tc);
    }
    char prevCjk = 0;
    int cjkCount = 0;
    for (int i = 0; i < textStr.length() && cjkCount < 80; i++) {
      char ch = textStr.charAt(i);
      if (ch >= '\u4e00' && ch <= '\u9fff') {
        addCapped(tc, "tz:" + ch);
        if (prevCjk != 0) addCapped(tc, "tz2:" + prevCjk + ch);
        prevCjk = ch;
        cjkCount++;
      }
    }
    return tc;
  }

  private static float[] embedBag(Map<String, Integer> bag, Model m) {
    float[] out = new float[m.embDim];
    if (bag.isEmpty()) return out;
    Map<Integer, Double> bucketWeights = new HashMap<>(bag.size() * 2);
    for (Map.Entry<String, Integer> e : bag.entrySet()) {
      int h = fnv1a32(e.getKey());
      int bucket = (h & 0x7FFFFFFF) % m.numBuckets;
      double sign = ((h >>> 31) == 0) ? 1.0 : -1.0;
      double next = bucketWeights.getOrDefault(bucket, 0.0) + sign * e.getValue();
      if (next == 0.0) bucketWeights.remove(bucket);
      else bucketWeights.put(bucket, next);
    }
    if (bucketWeights.isEmpty()) return out;

    double[] acc = new double[m.embDim];
    QMatrix emb = m.emb;
    for (Map.Entry<Integer, Double> bw : bucketWeights.entrySet()) {
      int b = bw.getKey();
      double w = bw.getValue() * emb.scales[b];
      int base = b * m.embDim;
      for (int d = 0; d < m.embDim; d++) acc[d] += w * emb.q[base + d];
    }
    double normSq = 0.0;
    for (int d = 0; d < m.embDim; d++) normSq += acc[d] * acc[d];
    double denom = Math.max(1e-6, Math.sqrt(normSq));
    for (int d = 0; d < m.embDim; d++) out[d] = (float) (acc[d] / denom);
    return out;
  }

  static float[] extractStructFeatures(String merchant, String text, MlClassifier.Prediction stage1) {
    String merchStr = merchant == null ? "" : merchant;
    String textStr = text == null ? "" : text;
    String comb = merchStr + "\n" + textStr;
    String combUp = comb.toUpperCase(Locale.ROOT);
    float[] st = new float[16];

    for (String g : GATEWAY_LITS) {
      if (combUp.contains(g)) {
        st[0] = 1.0f;
        break;
      }
    }
    st[1] = FX_CUE_RE.matcher(combUp).find() ? 1.0f : 0.0f;
    st[2] = LHDN_CUE_RE.matcher(combUp).find() ? 1.0f : 0.0f;
    int nlCount = 0;
    for (int i = 0; i < textStr.length(); i++) if (textStr.charAt(i) == '\n') nlCount++;
    st[3] = (nlCount >= 3 || RECEIPT_CUE_RE.matcher(combUp).find()) ? 1.0f : 0.0f;
    st[4] = BNPL_CUE_RE.matcher(combUp).find() ? 1.0f : 0.0f;
    st[5] = P2P_CUE_RE.matcher(combUp).find() ? 1.0f : 0.0f;
    for (int i = 0; i < comb.length(); i++) {
      char ch = comb.charAt(i);
      if (ch >= '\u4e00' && ch <= '\u9fff') {
        st[6] = 1.0f;
        break;
      }
    }
    st[7] = MALAY_CUE_RE.matcher(combUp).find() ? 1.0f : 0.0f;
    st[8] = Math.min(1.0f, merchStr.length() / 40.0f);
    st[9] = Math.min(1.0f, textStr.length() / 300.0f);

    int asciiLetters = 0, consonants = 0, digits = 0;
    for (int i = 0; i < merchStr.length(); i++) {
      char ch = merchStr.charAt(i);
      if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
        asciiLetters++;
        char lc = (ch <= 'Z') ? (char) (ch + 32) : ch;
        if (lc != 'a' && lc != 'e' && lc != 'i' && lc != 'o' && lc != 'u') consonants++;
      } else if (ch >= '0' && ch <= '9') {
        digits++;
      }
    }
    st[10] = asciiLetters > 0 ? (consonants / (float) asciiLetters) : 0.0f;
    st[11] = merchStr.length() > 0 ? (digits / (float) merchStr.length()) : 0.0f;
    if (stage1 != null) {
      st[12] = 1.0f;
      st[13] = (float) stage1.probability;
      st[14] = (float) stage1.margin;
      st[15] = Math.min(1.0f, stage1.activeSignals / 25.0f);
    }
    return st;
  }

  private static float[] matVecAdd(QMatrix mat, float[] x, float[] bias) {
    float[] out = new float[mat.rows];
    int cols = mat.cols;
    for (int r = 0; r < mat.rows; r++) {
      int base = r * cols;
      double sum = 0.0;
      for (int c = 0; c < cols; c++) sum += mat.q[base + c] * x[c];
      out[r] = (float) (sum * mat.scales[r] + bias[r]);
    }
    return out;
  }

  private static void layerNormInPlace(float[] v, float[] w, float[] b) {
    int n = v.length;
    double mean = 0.0;
    for (int i = 0; i < n; i++) mean += v[i];
    mean /= n;
    double var = 0.0;
    for (int i = 0; i < n; i++) {
      double d = v[i] - mean;
      var += d * d;
    }
    var /= n;
    double invStd = 1.0 / Math.sqrt(var + 1e-5);
    for (int i = 0; i < n; i++) {
      v[i] = (float) (((v[i] - mean) * invStd) * w[i] + b[i]);
    }
  }

  private static float geluTanh(float x) {
    double v = x;
    double inner = 0.7978845608 * (v + 0.044715 * v * v * v);
    return (float) (0.5 * v * (1.0 + Math.tanh(inner)));
  }

  public static Prediction predict(String merchant, String text, MlClassifier.Prediction stage1) {
    if (disabled) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    String merchStr = merchant == null ? "" : merchant;
    String textStr = text == null ? "" : text;
    Map<String, Integer> mBag = extractMerchantBag(merchStr);
    Map<String, Integer> tBag = extractTextBag(textStr);
    int activeSignals = mBag.size() + tBag.size();

    float[] em = embedBag(mBag, m);
    float[] et = embedBag(tBag, m);
    float[] st = extractStructFeatures(merchStr, textStr, stage1);

    int inDim = m.embDim * 3 + m.structDim + m.numClasses;
    float[] x = new float[inDim];
    int p = 0;
    System.arraycopy(em, 0, x, p, m.embDim);
    p += m.embDim;
    System.arraycopy(et, 0, x, p, m.embDim);
    p += m.embDim;
    for (int i = 0; i < m.embDim; i++) x[p + i] = em[i] * et[i];
    p += m.embDim;
    System.arraycopy(st, 0, x, p, m.structDim);
    p += m.structDim;
    if (stage1 != null && stage1.classProbs != null && stage1.classNames != null) {
      for (int i = 0; i < stage1.classNames.length; i++) {
        Integer idx = m.classPairToIdx.get(stage1.classNames[i]);
        if (idx != null) x[p + idx] = (float) stage1.classProbs[i];
      }
    }

    float[] h1 = matVecAdd(m.w1, x, m.b1);
    layerNormInPlace(h1, m.ln1W, m.ln1B);
    for (int i = 0; i < h1.length; i++) h1[i] = geluTanh(h1[i]);

    float[] h2 = matVecAdd(m.w2, h1, m.b2);
    layerNormInPlace(h2, m.ln2W, m.ln2B);
    for (int i = 0; i < h2.length; i++) h2[i] = h1[i] + geluTanh(h2[i]);

    float[] rawLogits = matVecAdd(m.wOut, h2, m.bOut);
    float[] scaledLogits = new float[m.numClasses];
    double maxLogit = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < m.numClasses; i++) {
      float sl = rawLogits[i] / m.temperature;
      scaledLogits[i] = sl;
      if (sl > maxLogit) maxLogit = sl;
    }

    double sumExp = 0.0;
    double[] exps = new double[m.numClasses];
    for (int i = 0; i < m.numClasses; i++) {
      double e = Math.exp(scaledLogits[i] - maxLogit);
      exps[i] = e;
      sumExp += e;
    }
    if (sumExp <= 0.0) return null;

    double[] probs = new double[m.numClasses];
    double[] catProbs = new double[m.uniqueCategories.length];
    for (int i = 0; i < m.numClasses; i++) {
      double pr = exps[i] / sumExp;
      probs[i] = pr;
      catProbs[m.classToCategory[i]] += pr;
    }

    int bestCatIdx = 0;
    double bestCatProb = -1.0, secondCatProb = 0.0;
    for (int c = 0; c < catProbs.length; c++) {
      double cp = catProbs[c];
      if (cp > bestCatProb) {
        secondCatProb = bestCatProb < 0.0 ? 0.0 : bestCatProb;
        bestCatProb = cp;
        bestCatIdx = c;
      } else if (cp > secondCatProb) {
        secondCatProb = cp;
      }
    }

    int bestIdx = 0, globalBestIdx = 0;
    double bestLogitInCat = Double.NEGATIVE_INFINITY;
    double globalBestLogit = Double.NEGATIVE_INFINITY;
    double secondLogitOverall = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < m.numClasses; i++) {
      if (scaledLogits[i] > globalBestLogit) {
        globalBestLogit = scaledLogits[i];
        globalBestIdx = i;
      }
      if (m.classToCategory[i] == bestCatIdx && scaledLogits[i] > bestLogitInCat) {
        bestLogitInCat = scaledLogits[i];
        bestIdx = i;
      }
    }
    for (int i = 0; i < m.numClasses; i++) {
      if (i != bestIdx && scaledLogits[i] > secondLogitOverall) {
        secondLogitOverall = scaledLogits[i];
      }
    }
    double bestProb = probs[bestIdx];
    double secondProb = secondLogitOverall == Double.NEGATIVE_INFINITY
        ? 0.0
        : probs[findIndex(scaledLogits, bestIdx, (float) secondLogitOverall)];
    double subMargin = globalBestIdx == bestIdx ? Math.max(0.0, bestProb - secondProb) : bestProb;
    double miscProb = m.miscClassIdx >= 0 ? probs[m.miscClassIdx] : 0.0;

    return new Prediction(
        m.categories[bestIdx],
        m.subcategories[bestIdx],
        bestProb,
        subMargin,
        bestCatProb,
        Math.max(0.0, bestCatProb - secondCatProb),
        miscProb,
        activeSignals,
        rawLogits,
        scaledLogits,
        probs);
  }

  private static int findIndex(float[] logits, int excludeIdx, float targetLogit) {
    for (int i = 0; i < logits.length; i++) {
      if (i != excludeIdx && logits[i] == targetLogit) return i;
    }
    return excludeIdx;
  }
}
