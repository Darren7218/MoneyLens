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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Contrastive Pure-JDK INT8 Bi-Encoder (MLMC v1) for Malaysian merchant canonicalization
 * and zero-shot pairwise entity resolution. Zero Android framework imports.
 */
public final class MerchantCanonicalizer {
  public static final class Result {
    public final String canonicalName;
    public final String defaultCategory;
    public final String defaultSubcategory;
    public final double similarity;
    public final double margin;
    public final boolean matchedCatalog;

    Result(String canonicalName, String defaultCategory, String defaultSubcategory,
           double similarity, double margin, boolean matchedCatalog) {
      this.canonicalName = canonicalName;
      this.defaultCategory = defaultCategory;
      this.defaultSubcategory = defaultSubcategory;
      this.similarity = similarity;
      this.margin = margin;
      this.matchedCatalog = matchedCatalog;
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
    final int numCatalog;
    final float brandMatchThreshold;
    final float brandMatchMargin;
    final float pairSameThreshold;
    final QMatrix emb;
    final QMatrix w1;
    final QMatrix w2;
    final float[] b1;
    final float[] ln1W;
    final float[] ln1B;
    final float[] b2;
    final String[] catalogNames;
    final String[] catalogCats;
    final String[] catalogSubs;
    final float[] catalogUnitEmbs; // row-major [numCatalog * embDim], L2-normalized

    Model(int numBuckets, int embDim, int hiddenDim, int numCatalog,
          float brandMatchThreshold, float brandMatchMargin, float pairSameThreshold,
          QMatrix emb, QMatrix w1, QMatrix w2,
          float[] b1, float[] ln1W, float[] ln1B, float[] b2,
          String[] catalogNames, String[] catalogCats, String[] catalogSubs,
          QMatrix catalogQ) {
      this.numBuckets = numBuckets;
      this.embDim = embDim;
      this.hiddenDim = hiddenDim;
      this.numCatalog = numCatalog;
      this.brandMatchThreshold = brandMatchThreshold;
      this.brandMatchMargin = brandMatchMargin;
      this.pairSameThreshold = pairSameThreshold;
      this.emb = emb;
      this.w1 = w1;
      this.w2 = w2;
      this.b1 = b1;
      this.ln1W = ln1W;
      this.ln1B = ln1B;
      this.b2 = b2;
      this.catalogNames = catalogNames;
      this.catalogCats = catalogCats;
      this.catalogSubs = catalogSubs;
      this.catalogUnitEmbs = new float[numCatalog * embDim];
      for (int r = 0; r < numCatalog; r++) {
        int base = r * embDim;
        double normSq = 0.0;
        double[] row = new double[embDim];
        float sc = catalogQ.scales[r];
        for (int c = 0; c < embDim; c++) {
          double v = catalogQ.q[base + c] * (double) sc;
          row[c] = v;
          normSq += v * v;
        }
        double denom = Math.max(1e-6, Math.sqrt(normSq));
        for (int c = 0; c < embDim; c++) {
          this.catalogUnitEmbs[base + c] = (float) (row[c] / denom);
        }
      }
    }
  }

  private static final Pattern GW_LEAD_RE = Pattern.compile(
      "^(?:fpx|dnqr|duitnow\\s*qr|duitnow|paynet|ipay88|razerms|razer\\s*ms|molpay|"
      + "billplz|senangpay|gkash|stripe|curlec|2c2p|revpay|kiple|shopeepay|shopeep|"
      + "spaylater|spay|atome|payflex|mydebit|tngd|tng|grab|pay|pos|qr|visa|mc|sp|cimbl)\\s*\\*\\s*");
  private static final Pattern SSM_PAREN_RE = Pattern.compile("\\(\\s*(?:[0-9]{5,8}-[a-z]|[0-9]{12}|m)\\s*\\)");
  private static final Pattern GLUED_AMT_RE = Pattern.compile("(?:(?:rmyr|myr|(?<![a-z])rm)\\s*[0-9,]+(?:\\.[0-9]{1,2})?|[0-9,]+\\.[0-9]{2}(?:\\s*(?:rmyr|myr|rm))?)");
  private static final Pattern LONG_DIGITS_RE = Pattern.compile("[0-9]{8,}");
  private static final Pattern DATE_NOISE_RE = Pattern.compile(
      "\\b(?:[0-3]?[0-9][/-](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[/-](?:20)?[0-9]{2})?"
      + "|(?:0[1-9]|[12][0-9]|3[01])[/-](?:0[1-9]|1[0-2])(?:[/-](?:20)?[0-9]{2})?"
      + "|[0-3]?[0-9][/-][01]?[0-9][/-](?:20)?[0-9]{2}|20[0-9]{2}[/-][01]?[0-9][/-][0-3]?[0-9])\\b");
  private static final Pattern CORP_LOC_NOISE_RE = Pattern.compile(
      "\\b(?:sdn\\.?[\\s/-]*bhd\\.?|sdn\\.?[\\s/-]*bh\\.?|sdnbhd|sdnbh|s/b|sendirian\\s+berhad|berhad|bhd\\.?|plt|llp|"
      + "rmyr|myr|rm|rem|charge|payment|"
      + "malaysia|mys|klcc|trx|pj|kl|jb|bngs|bngk|bngklaut)\\b");
  private static final Pattern GLUED_TAIL_RE = Pattern.compile("([a-z0-9]{4,})(?:sdnbhd|sdnbh|rmyr|myr|klcc|trx)(?=$|[^a-z0-9])");
  private static final Pattern OCR_ZERO_RE = Pattern.compile("(?<=[a-z])0(?=[a-z])");
  private static final Pattern OCR_ONE_RE = Pattern.compile("(?<=[a-z])1(?=[a-z])");
  private static final Pattern DIGIT_RUN_RE = Pattern.compile("[0-9]{2,}");
  private static final Pattern OCR_DIGIT_SWAP_RE = Pattern.compile("[A-Za-z][015][A-Za-z]");
  private static final Pattern TRUNC_SFX_RE = Pattern.compile("(?i)(?:\\bsd[nm]?\\s*bh?$|\\bs/b$|\\s+20[0-9]{6,}$)");
  private static final Pattern BRANCH_CODE_SFX_RE = Pattern.compile("(?i)-(?:trx|kj|pj|kl|jb|pg|sj|sbj|klcc|mv|usj|kk|kch|my)\\b");
  private static final Set<String> GENERIC_ENTITY_WORDS = new HashSet<>(Arrays.asList(
      "klinik", "clinic", "poliklinik", "pergigian", "dental", "keluarga", "restoran", "restaurant",
      "kedai", "kopi", "kopitiam", "kafe", "cafe", "farmasi", "pharmacy", "bengkel", "kereta",
      "servis", "motors", "auto", "pasar", "mini", "mart", "market", "supermarket", "tadika",
      "taska", "pusat", "tuisyen", "tuition", "hospital", "medical", "specialist", "center",
      "centre", "store", "shop", "enterprise", "trading", "resources", "services", "solutions",
      "holdings", "ventures", "global", "group", "maybank", "cimb", "public", "bank", "hong",
      "leong", "ambank", "rhb", "takaful", "insurance", "insurans", "allianz", "great", "eastern",
      "aia", "prudential", "unifi", "tnb", "tenaga", "nasional", "petronas", "shell", "caltex",
      "housing", "loan", "credit", "card", "financing", "saving", "savings", "account", "payment",
      "transfer", "duitnow", "jompay", "fpx", "online", "digital", "mobile", "network", "zakat",
      "kitchen", "hardware", "runcit", "dobi", "salun", "rambut", "roti", "elektrik",
      "bunga", "cermin", "mata", "haiwan", "tayar", "soup"
  ));

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;
  private static volatile boolean disabled = false;
  private static volatile java.util.function.Supplier<InputStream> streamSupplier = null;

  private MerchantCanonicalizer() {}

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
    for (String res : new String[]{"/moneylens_merchant_canon.bin.gz", "/moneylens_merchant_canon.bin"}) {
      try (InputStream cp = MerchantCanonicalizer.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {}
    }
    for (String candidate : new String[]{
        "app/src/main/assets/moneylens_merchant_canon.bin.gz",
        "app/src/main/assets/moneylens_merchant_canon.bin",
        "src/main/assets/moneylens_merchant_canon.bin.gz",
        "src/main/assets/moneylens_merchant_canon.bin",
        "../app/src/main/assets/moneylens_merchant_canon.bin.gz",
        "../app/src/main/assets/moneylens_merchant_canon.bin"
    }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static Model parseBinaryModel(DataInputStream dis) throws Exception {
    byte[] magic = new byte[4];
    dis.readFully(magic);
    if (magic[0] != 'M' || magic[1] != 'L' || magic[2] != 'M' || magic[3] != 'C') {
      throw new IllegalArgumentException("Bad MLMC magic");
    }
    int version = dis.readInt();
    if (version != 1) throw new IllegalArgumentException("Unsupported MLMC version: " + version);
    int numBuckets = dis.readInt();
    int embDim = dis.readInt();
    int hiddenDim = dis.readInt();
    int numCatalog = dis.readInt();
    float brandTh = dis.readFloat();
    float brandMg = dis.readFloat();
    float pairTh = dis.readFloat();

    QMatrix emb = readQuantizedMatrix(dis);
    QMatrix w1 = readQuantizedMatrix(dis);
    QMatrix w2 = readQuantizedMatrix(dis);
    float[] b1 = readFloatArray(dis, hiddenDim);
    float[] ln1W = readFloatArray(dis, hiddenDim);
    float[] ln1B = readFloatArray(dis, hiddenDim);
    float[] b2 = readFloatArray(dis, embDim);

    String[] names = new String[numCatalog];
    String[] cats = new String[numCatalog];
    String[] subs = new String[numCatalog];
    for (int i = 0; i < numCatalog; i++) names[i] = dis.readUTF();
    for (int i = 0; i < numCatalog; i++) cats[i] = dis.readUTF();
    for (int i = 0; i < numCatalog; i++) subs[i] = dis.readUTF();
    QMatrix catQ = readQuantizedMatrix(dis);

    return new Model(
        numBuckets, embDim, hiddenDim, numCatalog,
        brandTh, brandMg, pairTh,
        emb, w1, w2, b1, ln1W, ln1B, b2,
        names, cats, subs, catQ);
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

  public static String normalizeSurface(String raw) {
    if (raw == null) return "";
    String s = raw.toLowerCase(Locale.ROOT).trim();
    if (s.isEmpty()) return "";
    String normD = Normalizer.normalize(s, Normalizer.Form.NFKD);
    StringBuilder nfkdSb = new StringBuilder(normD.length());
    for (int i = 0; i < normD.length(); i++) {
      char ch = normD.charAt(i);
      if (Character.getType(ch) != Character.NON_SPACING_MARK) nfkdSb.append(ch);
    }
    s = nfkdSb.toString();
    for (int i = 0; i < 2; i++) {
      String ns = GW_LEAD_RE.matcher(s).replaceFirst("").trim();
      if (ns.equals(s)) break;
      s = ns;
    }
    s = SSM_PAREN_RE.matcher(s).replaceAll(" ");
    s = GLUED_AMT_RE.matcher(s).replaceAll(" ");
    s = LONG_DIGITS_RE.matcher(s).replaceAll(" ");
    s = DATE_NOISE_RE.matcher(s).replaceAll(" ");
    s = CORP_LOC_NOISE_RE.matcher(s).replaceAll(" ");
    s = GLUED_TAIL_RE.matcher(s).replaceAll("$1");
    s = OCR_ZERO_RE.matcher(s).replaceAll("o");
    s = OCR_ONE_RE.matcher(s).replaceAll("i");
    StringBuilder out = new StringBuilder(s.length());
    boolean prevSpace = true;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        out.append(ch);
        prevSpace = false;
      } else if (!prevSpace) {
        out.append(' ');
        prevSpace = true;
      }
    }
    return out.toString().trim();
  }

  private static boolean isAsciiAlnum(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
  }

  private static boolean isAsciiAlnumToken(String w) {
    if (w.isEmpty()) return false;
    for (int i = 0; i < w.length(); i++) if (!isAsciiAlnum(w.charAt(i))) return false;
    return true;
  }

  private static boolean isAsciiAlnum4Plus(String w) {
    return w.length() >= 4 && isAsciiAlnumToken(w);
  }

  static Set<String> extractFeatures(String raw) {
    String norm = normalizeSurface(raw);
    Set<String> feats = new HashSet<>();
    if (norm.isEmpty()) return feats;

    List<String> allTokens = new ArrayList<>();
    for (String part : norm.split(" ")) {
      if (!part.isEmpty()) {
        allTokens.add(part);
        if (allTokens.size() >= 12) break;
      }
    }
    List<String> tokens = new ArrayList<>();
    for (String part : allTokens) {
      if (part.length() >= 2) tokens.add(part);
    }
    if (tokens.isEmpty()) tokens = allTokens;

    for (int i = 0; i < tokens.size(); i++) {
      String tok = tokens.get(i);
      feats.add("w:" + tok);
      if (i + 1 < tokens.size()) feats.add("b:" + tok + " " + tokens.get(i + 1));
      String padded = " " + tok + " ";
      int plen = padded.length();
      int minN = tok.length() <= 3 ? 2 : 3;
      for (int n = minN; n <= 4; n++) {
        if (plen < n) break;
        for (int st = 0; st <= plen - n; st++) feats.add("c:" + padded.substring(st, st + n));
      }
      if (isAsciiAlnum4Plus(tok)) {
        StringBuilder sk = new StringBuilder(16);
        for (int j = 0; j < tok.length() && sk.length() < 16; j++) {
          char c = tok.charAt(j);
          if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') sk.append(c);
        }
        int sl = sk.length();
        if (sl >= 3) {
          String sks = sk.toString();
          feats.add("sk:" + sks);
          if (sl >= 4) {
            for (int st = 0; st <= sl - 4; st++) feats.add("sk4:" + sks.substring(st, st + 4));
          }
        }
      }
    }

    StringBuilder fullAlnumSb = new StringBuilder(norm.length());
    for (int i = 0; i < norm.length(); i++) {
      char c = norm.charAt(i);
      if (isAsciiAlnum(c)) fullAlnumSb.append(c);
    }
    String fullAlnumAll = fullAlnumSb.toString();
    String faStr = fullAlnumAll.length() > 24 ? fullAlnumAll.substring(0, 24) : fullAlnumAll;
    if (faStr.length() >= 2) feats.add("fa:" + faStr);
    if (faStr.length() >= 3) {
      for (int st = 0; st <= faStr.length() - 3; st++) feats.add("fa3:" + faStr.substring(st, st + 3));
    }
    if (faStr.length() >= 4) {
      for (int st = 0; st <= faStr.length() - 4; st++) feats.add("fa4:" + faStr.substring(st, st + 4));
    }

    StringBuilder fsk = new StringBuilder(20);
    for (int i = 0; i < fullAlnumAll.length() && fsk.length() < 20; i++) {
      char c = fullAlnumAll.charAt(i);
      if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') fsk.append(c);
    }
    if (fsk.length() >= 4) {
      String fs = fsk.toString();
      feats.add("fsk:" + fs);
      for (int st = 0; st <= fs.length() - 4; st++) feats.add("fsk4:" + fs.substring(st, st + 4));
    }

    List<String> asciiToks = new ArrayList<>();
    for (String t : allTokens) {
      if (isAsciiAlnumToken(t)) asciiToks.add(t);
    }
    StringBuilder hskSb = new StringBuilder(20);
    for (int ti = 0; ti < asciiToks.size() && hskSb.length() < 20; ti++) {
      String t = asciiToks.get(ti);
      hskSb.append(t.charAt(0));
      for (int j = 1; j < t.length() && hskSb.length() < 20; j++) {
        char c = t.charAt(j);
        if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') hskSb.append(c);
      }
    }
    String hsk = hskSb.toString();
    if (hsk.length() >= 2) {
      for (int st = 0; st <= hsk.length() - 2; st++) feats.add("hb:" + hsk.substring(st, st + 2));
    }
    if (hsk.length() >= 3) {
      for (int st = 0; st <= hsk.length() - 3; st++) feats.add("hs:" + hsk.charAt(st) + hsk.charAt(st + 2));
      feats.add("hsk:" + hsk);
      if (hsk.length() <= 10) feats.add("abbr:" + hsk);
      for (int st = 0; st <= hsk.length() - 3; st++) feats.add("hsk3:" + hsk.substring(st, st + 3));
    }
    if (hsk.length() >= 4) {
      for (int st = 0; st <= hsk.length() - 4; st++) feats.add("hsk4:" + hsk.substring(st, st + 4));
    }
    if (asciiToks.size() == 1 && asciiToks.get(0).length() >= 3 && asciiToks.get(0).length() <= 14) {
      feats.add("abbr:" + asciiToks.get(0));
    }
    if (asciiToks.size() >= 2) {
      StringBuilder iniSb = new StringBuilder(8);
      for (int i = 0; i < asciiToks.size() && i < 8; i++) iniSb.append(asciiToks.get(i).charAt(0));
      if (iniSb.length() >= 3) feats.add("abbr:" + iniSb.toString());

      StringBuilder firstIniSb = new StringBuilder(16);
      firstIniSb.append(asciiToks.get(0));
      for (int i = 1; i < asciiToks.size() && i < 6; i++) firstIniSb.append(asciiToks.get(i).charAt(0));
      if (firstIniSb.length() >= 3 && firstIniSb.length() <= 14) feats.add("abbr:" + firstIniSb.toString());

      StringBuilder pre2Sb = new StringBuilder(12);
      for (int i = 0; i < asciiToks.size() && i < 6; i++) {
        String t = asciiToks.get(i);
        pre2Sb.append(t, 0, Math.min(2, t.length()));
      }
      if (pre2Sb.length() >= 4) feats.add("abbr:" + pre2Sb.toString());

      StringBuilder pre4Sb = new StringBuilder(16);
      for (int i = 0; i < asciiToks.size() && i < 4; i++) {
        String t = asciiToks.get(i);
        pre4Sb.append(t, 0, Math.min(4, t.length()));
      }
      if (pre4Sb.length() >= 5) {
        String pre4 = pre4Sb.toString();
        feats.add("abbr:" + pre4);
        for (int st = 0; st <= pre4.length() - 4; st++) feats.add("fa4:" + pre4.substring(st, st + 4));
      }
    }

    char prevCjk = 0;
    for (int i = 0; i < norm.length(); i++) {
      char ch = norm.charAt(i);
      if (ch >= '\u4e00' && ch <= '\u9fff') {
        feats.add("z:" + ch);
        if (prevCjk != 0) feats.add("z2:" + prevCjk + ch);
        prevCjk = ch;
      }
    }
    return feats;
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

  public static float[] encode(String raw) {
    if (disabled) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    Set<String> feats = extractFeatures(raw);
    float[] zero = new float[m.embDim];
    if (feats.isEmpty()) return zero;

    Map<Integer, Double> bucketWeights = new HashMap<>(feats.size() * 2);
    for (String fk : feats) {
      int h = fnv1a32(fk);
      int bucket = (h & 0x7FFFFFFF) % m.numBuckets;
      double sign = ((h >>> 31) == 0) ? 1.0 : -1.0;
      double next = bucketWeights.getOrDefault(bucket, 0.0) + sign;
      if (next == 0.0) bucketWeights.remove(bucket);
      else bucketWeights.put(bucket, next);
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
    double d0 = Math.max(1e-6, Math.sqrt(n0));
    float[] e0 = new float[m.embDim];
    for (int d = 0; d < m.embDim; d++) e0[d] = (float) (acc0[d] / d0);

    float[] h = matVecAdd(m.w1, e0, m.b1);
    layerNormInPlace(h, m.ln1W, m.ln1B);
    for (int i = 0; i < h.length; i++) h[i] = geluTanh(h[i]);

    float[] delta = matVecAdd(m.w2, h, m.b2);
    double n1 = 0.0;
    float[] e1 = new float[m.embDim];
    for (int i = 0; i < m.embDim; i++) {
      float val = e0[i] + delta[i];
      e1[i] = val;
      n1 += (double) val * val;
    }
    double d1 = Math.max(1e-6, Math.sqrt(n1));
    for (int i = 0; i < m.embDim; i++) e1[i] = (float) (e1[i] / d1);
    return e1;
  }

  public static double similarity(String a, String b) {
    float[] ea = encode(a);
    float[] eb = encode(b);
    if (ea == null || eb == null) return 0.0;
    double dot = 0.0;
    for (int i = 0; i < ea.length; i++) dot += (double) ea[i] * eb[i];
    return dot;
  }

  private static Set<String> extractDigitRuns(String norm) {
    Set<String> out = new HashSet<>();
    Matcher m = DIGIT_RUN_RE.matcher(norm);
    while (m.find()) {
      String tok = m.group();
      if (!tok.matches("20[0-9]{2}")) out.add(tok);
    }
    return out;
  }

  private static String skel(String word) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') sb.append(c);
    }
    return sb.toString();
  }

  private static boolean hasVowel(String w) {
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') return true;
    }
    return false;
  }

  static boolean hasDistinctEntityConflict(String rawA, String rawB) {
    String na = normalizeSurface(rawA);
    String nb = normalizeSurface(rawB);
    if (na.isEmpty() || nb.isEmpty()) return false;
    if (na.equals(nb)) return false;

    Set<String> numsA = extractDigitRuns(na);
    Set<String> numsB = extractDigitRuns(nb);
    if (!numsA.isEmpty() && !numsB.isEmpty()) {
      boolean overlap = false;
      for (String n : numsA) if (numsB.contains(n)) { overlap = true; break; }
      if (!overlap) return true;
    }

    String[] toksA = na.split(" ");
    String[] toksB = nb.split(" ");
    int fullWordsA = 0, fullWordsB = 0;
    List<String> distA = new ArrayList<>();
    List<String> distB = new ArrayList<>();
    for (String t : toksA) {
      if (t.length() >= 4 && hasVowel(t)) {
        fullWordsA++;
        if (!GENERIC_ENTITY_WORDS.contains(t)) distA.add(t);
      }
    }
    for (String t : toksB) {
      if (t.length() >= 4 && hasVowel(t)) {
        fullWordsB++;
        if (!GENERIC_ENTITY_WORDS.contains(t)) distB.add(t);
      }
    }
    if (fullWordsA >= 2 && fullWordsB >= 2 && !distA.isEmpty() && !distB.isEmpty()) {
      for (String a : distA) {
        String ska = skel(a);
        for (String b : distB) {
          if (a.equals(b) || a.contains(b) || b.contains(a)) return false;
          String skb = skel(b);
          if (ska.length() >= 3 && skb.length() >= 3 && (ska.contains(skb) || skb.contains(ska))) return false;
        }
      }
      return true;
    }
    return false;
  }

  public static boolean sameEntity(String a, String b) {
    if (a == null || b == null) return false;
    String na = normalizeSurface(a);
    String nb = normalizeSurface(b);
    if (na.isEmpty() || nb.isEmpty()) return false;
    if (na.equals(nb)) return true;
    if (hasDistinctEntityConflict(a, b)) return false;
    if (disabled) return false;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return false;
    double sim = similarity(a, b);
    return sim >= m.pairSameThreshold;
  }

  public static Result canonicalize(String rawMerchant, String contextText) {
    if (disabled || rawMerchant == null || rawMerchant.trim().isEmpty()) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null || m.numCatalog == 0) return null;

    String query = rawMerchant.trim();
    if ("Detected payment".equalsIgnoreCase(query) && contextText != null && !contextText.trim().isEmpty()) {
      query = contextText.trim();
    }
    float[] qEmb = encode(query);
    if (qEmb == null) return null;
    double qNormSq = 0.0;
    for (float v : qEmb) qNormSq += v * v;
    if (qNormSq < 0.1) return null;

    int bestIdx = -1;
    double bestSim = -2.0, secondSim = -2.0;
    for (int i = 0; i < m.numCatalog; i++) {
      int base = i * m.embDim;
      double dot = 0.0;
      for (int d = 0; d < m.embDim; d++) dot += (double) qEmb[d] * m.catalogUnitEmbs[base + d];
      if (dot > bestSim) {
        secondSim = bestSim;
        bestSim = dot;
        bestIdx = i;
      } else if (dot > secondSim) {
        secondSim = dot;
      }
    }
    if (bestIdx < 0) return null;
    double margin = secondSim > -1.5 ? Math.max(0.0, bestSim - secondSim) : bestSim;
    String candName = m.catalogNames[bestIdx];
    boolean conflict = hasDistinctEntityConflict(query, candName);
    boolean matched = !conflict && bestSim >= m.brandMatchThreshold && margin >= m.brandMatchMargin;
    return new Result(
        candName,
        m.catalogCats[bestIdx],
        m.catalogSubs[bestIdx],
        bestSim,
        margin,
        matched);
  }

  public static boolean isNoisyTruncatedSurface(String raw) {
    if (raw == null) return true;
    String s = raw.trim();
    if (s.length() < 3 || "Detected payment".equalsIgnoreCase(s)) return true;
    if (s.contains("*") || TRUNC_SFX_RE.matcher(s).find() || OCR_DIGIT_SWAP_RE.matcher(s).find() || BRANCH_CODE_SFX_RE.matcher(s).find()) return true;
    for (String tok : s.split("[^A-Za-z]+")) {
      if (tok.length() >= 4) {
        int vowels = 0;
        for (int i = 0; i < tok.length(); i++) {
          char c = Character.toLowerCase(tok.charAt(i));
          if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') vowels++;
        }
        if (vowels == 0 && !tok.matches("(?i)lhdn|kwsp|hsbc|ocbc|mbsb|myeg|ptptn|ktmb")) return true;
        if (tok.length() >= 5 && (vowels / (double) tok.length()) < 0.20) return true;
      }
    }
    return false;
  }
}
