package app.moneylens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Pure-JDK Linear-Chain CRF + Viterbi line-level field tagger for Malaysian receipts and LHDN e-Invoices.
 * Synced bit-for-bit with Colab Receipt Vision Foundry v1; zero Android framework imports.
 */
public final class ReceiptFieldTagger {
  public static final int LABEL_MERCHANT = 0;
  public static final int LABEL_HEADER_META = 1;
  public static final int LABEL_DATE = 2;
  public static final int LABEL_ITEM = 3;
  public static final int LABEL_SUBTOTAL = 4;
  public static final int LABEL_TAX_SST = 5;
  public static final int LABEL_ROUNDING = 6;
  public static final int LABEL_GRAND_TOTAL = 7;
  public static final int LABEL_PAYMENT_TENDER = 8;
  public static final int LABEL_LHDN_META = 9;
  public static final int LABEL_OTHER = 10;

  public static final class TaggedLines {
    public final String[] lines;
    public final int[] labelIds;
    public final String[] labelNames;
    public final double[][] lineProbs;
    public final double sequenceScore;

    TaggedLines(String[] lines, int[] labelIds, String[] labelNames, double[][] lineProbs, double sequenceScore) {
      this.lines = lines;
      this.labelIds = labelIds;
      this.labelNames = labelNames;
      this.lineProbs = lineProbs;
      this.sequenceScore = sequenceScore;
    }
  }

  private static final class FeatureWeights {
    final int[] labelIdx;
    final float[] weights;

    FeatureWeights(int[] labelIdx, float[] weights) {
      this.labelIdx = labelIdx;
      this.weights = weights;
    }
  }

  private static final class Model {
    final String[] labels;
    final double[] start;
    final double[] end;
    final double[][] transitions;
    final Map<String, FeatureWeights> features;

    Model(String[] labels, double[] start, double[] end, double[][] transitions, Map<String, FeatureWeights> features) {
      this.labels = labels;
      this.start = start;
      this.end = end;
      this.transitions = transitions;
      this.features = features;
    }
  }

  private static final Pattern FLAT_SECTION_SPLIT_RE = Pattern.compile(
      "(?i)(?:\\.{3,}|[ \\t]+\\|[ \\t]+|"
      + "(?<=[0-9A-Za-z):%])[ \\t]+(?=(?:"
      + "(?:simplified\\s+)?tax\\s+invoice|80mm\\s+thermal\\s+receipt|"
      + "sub\\s*-?\\s*total|sub\\s+ttl|sst(?:\\s*[0-9]+%)?|service\\s+(?:tax|charge)|"
      + "(?:5[- ]?sen\\s+)?rounding(?:\\s+adj(?:ustment)?)?|runding|penggenapan|"
      + "final\\s+amount(?:\\s*\\(rounded\\))?|grand\\s+total|total\\s+payable|"
      + "net(?:t)?\\s+(?:total|amount|payable)|amount\\s+(?:paid|due|payable)|"
      + "jumlah\\s+(?:perlu\\s+)?dibayar|jumlah\\s+(?:bayaran|bersih|besar)|"
      + "total\\s+(?:sales|incl|amount|rm|myr)|total(?:\\s*:|\\s+(?:rm|myr)\\b)|"
      + "duitnow\\s*qr|tendered|tunai|change\\b|baki\\b|"
      + "tin\\s*:|invoice(?:\\s+(?:no|ref))?\\s*:|date\\s*:|tarikh\\s*:|ssm(?:\\s+reg)?\\s*:|"
      + "payee\\s*:|merchant\\s*:|lhdn\\s+bucket\\s*:|item\\s*:|amount\\s*:|1\\.\\s+[A-Za-z]"
      + ")))");

  private static final Pattern OCR_RN1 = Pattern.compile("(?i)\\brn[1il]\\b");
  private static final Pattern OCR_TOTAL = Pattern.compile("(?i)\\bt[0o]t[a4][1il]\\b");
  private static final Pattern OCR_SUBTOTAL = Pattern.compile("(?i)\\bsubt[0o]t[a4][1il]\\b");
  private static final Pattern OCR_JUMLAH = Pattern.compile("(?i)\\bj[u\\u00fc]m[1il][a4]h\\b");
  private static final Pattern OCR_PAYABLE = Pattern.compile("(?i)\\bp[a4]y[a4]b[1il]e\\b");
  private static final Pattern OCR_SST = Pattern.compile("(?i)\\b[5s]st\\b");
  private static final Pattern OCR_ROUNDING = Pattern.compile("(?i)\\br[0o]unding\\b");
  private static final Pattern OCR_TUNAI = Pattern.compile("(?i)\\btun[a4][1il]\\b");
  private static final Pattern OCR_BAKI = Pattern.compile("(?i)\\bb[a4]k[1il]\\b");

  private static final Pattern AMT_RE = Pattern.compile(
      "(?i)(?:(?:rm|myr|rn1)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,7}(?:\\.[0-9]{1,2})?)"
      + "|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,7}\\.[0-9]{2}))(?![0-9]|\\.[0-9])");
  private static final Pattern STANDALONE_AMT_RE = Pattern.compile(
      "(?i)^\\s*(?:(?:rm|myr|rn1)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,7}(?:\\.[0-9]{1,2})?)"
      + "|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,7}\\.[0-9]{2}))\\s*$");
  private static final Pattern DEC_AMT_RE = Pattern.compile("[0-9]+\\.[0-9]{2}");
  private static final Pattern HAS_RM_RE = Pattern.compile("(?i)\\b(?:rm|myr)\\s*[0-9]");
  private static final Pattern NEG_AMT_RE = Pattern.compile("(?i)(?:-\\s*(?:rm|myr)?\\s*[0-9]{1,5}\\.[0-9]{2}|\\(\\s*[0-9]{1,5}\\.[0-9]{2}\\s*\\))");
  private static final Pattern PCT_RE = Pattern.compile("\\b(?:6|8|10|0)\\s*%");
  private static final Pattern DATE_LINE_RE = Pattern.compile(
      "(?i)(?:\\b20[0-9]{2}[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12][0-9]|3[01])\\b|"
      + "\\b(?:0?[1-9]|[12][0-9]|3[01])[-/. ](?:0?[1-9]|1[0-2]|jan|feb|mar|mac|apr|may|mei|jun|jul|aug|ogos|sep|sept|oct|okt|nov|dec|dis)[-/. ,]+(?:20)?[0-9]{2}\\b|"
      + "\\b(?:date|tarikh)\\s*:)");
  private static final Pattern TIN_SSM_RE = Pattern.compile(
      "(?i)(?:\\b(?:tin|ssm|roc|rob|brn|uuid|myinvois|einvoice|e-invoice|lhdn|msic|gst\\s*(?:reg|id)|sst\\s*(?:reg|id))\\b"
      + "|\\b20[0-9]{10}\\b|\\b[0-9]{6,8}-[A-Za-z]\\b|\\b(?:C|IG|D|E)[0-9]{10,12}\\b)");
  private static final Pattern PHONE_URL_RE = Pattern.compile("(?i)(?:\\b(?:tel|hp|fax|hotline|call)\\b|@|https?://|www\\.|\\b0[1-9]-?[0-9]{6,8}\\b)");
  private static final Pattern ADDR_RE = Pattern.compile(
      "(?i)(?:\\b(?:jalan|jln|lorong|lrg|persiaran|lebuh|taman|tmn|bandar|seksyen|section|"
      + "kuala lumpur|selangor|petaling|subang|shah alam|klang|kajang|puchong|penang|johor|melaka|"
      + "perak|ipoh|pahang|kuantan|kelantan|terengganu|seremban|sabah|sarawak|kuching|kota kinabalu|putrajaya|cyberjaya)\\b"
      + "|\\b[0-9]{5}\\b)");
  private static final Pattern BIZ_SFX_RE = Pattern.compile(
      "(?i)\\b(?:sdn\\.?\\s*bhd|bhd|berhad|plt|llp|enterprise|trading|services|solutions|holdings|"
      + "klinik|clinic|farmasi|pharmacy|restoran|restaurant|kopitiam|cafe|kafe|mart|grocer|"
      + "supermarket|hypermarket|pasaraya|workshop|bengkel|resources|ventures|hospital|specialist)\\b");
  private static final Pattern TOTAL_KW_RE = Pattern.compile(
      "(?i)\\b(?:total|jumlah|grand\\s+total|final\\s+amount|nett?\\s+(?:amount|total|payable)|amount\\s+(?:paid|due|payable))\\b|总计|總計|合计|合計|应付|实付");
  private static final Pattern SUB_TAX_KW_RE = Pattern.compile("(?i)\\b(?:subtotal|sub\\s*total|sub\\s+ttl|sst|gst|cukai|service\\s+(?:tax|charge)|tax)\\b");
  private static final Pattern BODY_START_RE = Pattern.compile("(?i)^(?:qty|item|desc|description|produk|barang)\\b|(?:\\b[0-9]+\\s*[xX\\u00d7]\\s*[A-Za-z])");
  private static final Pattern CJK_RE = Pattern.compile("[\\u4e00-\\u9fff]");

  private static volatile Model activeModel = null;
  private static volatile boolean autoLoadAttempted = false;
  private static volatile boolean disabled = false;
  private static volatile java.util.function.Supplier<InputStream> streamSupplier = null;

  private ReceiptFieldTagger() {}

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
      String json;
      try (InputStream src = isGzip ? new GZIPInputStream(pb) : pb) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32768);
        byte[] chunk = new byte[8192];
        int n;
        while ((n = src.read(chunk)) >= 0) buf.write(chunk, 0, n);
        json = buf.toString(StandardCharsets.UTF_8.name());
      }
      activeModel = parseModel(json);
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
    for (String res : new String[]{"/moneylens_receipt_tagger.json.gz", "/moneylens_receipt_tagger.json"}) {
      try (InputStream cp = ReceiptFieldTagger.class.getResourceAsStream(res)) {
        if (cp != null && loadFromStream(cp)) return;
      } catch (Exception ignored) {}
    }
    for (String candidate : new String[]{
        "app/src/main/assets/moneylens_receipt_tagger.json.gz",
        "app/src/main/assets/moneylens_receipt_tagger.json",
        "src/main/assets/moneylens_receipt_tagger.json.gz",
        "src/main/assets/moneylens_receipt_tagger.json",
        "../app/src/main/assets/moneylens_receipt_tagger.json.gz",
        "../app/src/main/assets/moneylens_receipt_tagger.json"
    }) {
      if (loadFromPath(Paths.get(candidate))) return;
    }
  }

  private static Model parseModel(String rawJson) throws Exception {
    JSONObject root = new JSONObject(rawJson);
    JSONArray lblArr = root.getJSONArray("labels");
    int c = lblArr.length();
    String[] labels = new String[c];
    for (int i = 0; i < c; i++) labels[i] = lblArr.getString(i);

    JSONArray sArr = root.getJSONArray("start");
    JSONArray eArr = root.getJSONArray("end");
    JSONArray tArr = root.getJSONArray("transitions");
    double[] start = new double[c];
    double[] end = new double[c];
    double[][] transitions = new double[c][c];
    for (int i = 0; i < c; i++) {
      start[i] = sArr.getDouble(i);
      end[i] = eArr.getDouble(i);
      JSONArray row = tArr.getJSONArray(i);
      for (int j = 0; j < c; j++) transitions[i][j] = row.getDouble(j);
    }

    JSONObject featsObj = root.getJSONObject("features");
    Map<String, FeatureWeights> featMap = new HashMap<>(Math.max(16, featsObj.length() * 2));
    Iterator<String> keys = featsObj.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONArray pairs = featsObj.getJSONArray(key);
      int m = pairs.length();
      int[] lIdx = new int[m];
      float[] w = new float[m];
      for (int j = 0; j < m; j++) {
        JSONArray pw = pairs.getJSONArray(j);
        lIdx[j] = pw.getInt(0);
        w[j] = (float) pw.getDouble(1);
      }
      featMap.put(key, new FeatureWeights(lIdx, w));
    }
    return new Model(labels, start, end, transitions, featMap);
  }

  /** Normalizes common OCR thermal typos in receipt keywords before feature extraction or total scoring. */
  public static String ocrNormText(String s) {
    if (s == null) return "";
    String out = s.toLowerCase(Locale.ROOT);
    out = OCR_RN1.matcher(out).replaceAll("rm");
    out = OCR_TOTAL.matcher(out).replaceAll("total");
    out = OCR_SUBTOTAL.matcher(out).replaceAll("subtotal");
    out = OCR_JUMLAH.matcher(out).replaceAll("jumlah");
    out = OCR_PAYABLE.matcher(out).replaceAll("payable");
    out = OCR_SST.matcher(out).replaceAll("sst");
    out = OCR_ROUNDING.matcher(out).replaceAll("rounding");
    out = OCR_TUNAI.matcher(out).replaceAll("tunai");
    out = OCR_BAKI.matcher(out).replaceAll("baki");
    return out;
  }

  /** Splits flat single-line or ellipsis/pipe-delimited receipts cleanly (parity with Python normalize_lines). */
  public static String[] normalizeLines(String text) {
    if (text == null || text.isEmpty()) return new String[0];
    String[] split = text.replace('\u00A0', ' ').split("\\r?\\n");
    List<String> rawLines = new ArrayList<>();
    boolean hasLongMultiAmtLine = false;
    for (String ln : split) {
      String t = ln.trim();
      if (!t.isEmpty()) {
        rawLines.add(t);
        if (t.length() > 170) {
          Matcher dm = DEC_AMT_RE.matcher(t);
          int cnt = 0;
          while (dm.find()) {
            cnt++;
            if (cnt >= 2) {
              hasLongMultiAmtLine = true;
              break;
            }
          }
        }
      }
    }
    if (rawLines.size() <= 2 || hasLongMultiAmtLine) {
      List<String> expanded = new ArrayList<>();
      for (String ln : rawLines) {
        if (rawLines.size() <= 2 || ln.length() > 170) {
          for (String part : FLAT_SECTION_SPLIT_RE.split(ln, -1)) {
            String p = stripEdgeDelim(part);
            if (!p.isEmpty()) expanded.add(p);
          }
        } else {
          expanded.add(ln);
        }
      }
      if (!expanded.isEmpty()) return expanded.toArray(new String[0]);
    }
    return rawLines.toArray(new String[0]);
  }

  private static String stripEdgeDelim(String s) {
    int a = 0, b = s.length();
    while (a < b) {
      char ch = s.charAt(a);
      if (ch == ' ' || ch == '|' || ch == '\t' || ch == '.') a++;
      else break;
    }
    while (b > a) {
      char ch = s.charAt(b - 1);
      if (ch == ' ' || ch == '|' || ch == '\t' || ch == '.') b--;
      else break;
    }
    return s.substring(a, b);
  }

  static List<Long> parseLineAmounts(String line) {
    List<Long> out = new ArrayList<>();
    Matcher m = AMT_RE.matcher(line);
    while (m.find()) {
      String raw = m.group(1) != null ? m.group(1) : m.group(2);
      try {
        BigDecimal d = new BigDecimal(raw.replace(",", ""));
        long minor = d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_EVEN).longValueExact();
        if (minor >= 1L && minor < 50_000_000L) out.add(minor);
      } catch (Exception ignored) {}
    }
    return out;
  }

  static List<String> tokenizeLine(String normLine) {
    List<String> out = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < normLine.length(); i++) {
      char ch = normLine.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        buf.append(ch);
      } else if (buf.length() > 0) {
        String tok = buf.toString();
        if (tok.length() >= 2 && !allDigits(tok)) out.add(tok);
        buf.setLength(0);
      }
    }
    if (buf.length() > 0) {
      String tok = buf.toString();
      if (tok.length() >= 2 && !allDigits(tok)) out.add(tok);
    }
    return out;
  }

  private static boolean allDigits(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }

  /** Exact parity with Python extract_line_features(). */
  static List<List<String>> extractLineFeatures(String[] lines) {
    int L = lines.length;
    if (L == 0) return Collections.emptyList();
    List<List<Long>> lineAmts = new ArrayList<>(L);
    long maxAmt = 0L;
    Set<Long> uniqueAmts = new HashSet<>();
    for (String ln : lines) {
      List<Long> a = parseLineAmounts(ln);
      lineAmts.add(a);
      for (long v : a) {
        if (v > maxAmt) maxAmt = v;
        uniqueAmts.add(v);
      }
    }
    List<Long> sortedAmts = new ArrayList<>(uniqueAmts);
    Collections.sort(sortedAmts, Collections.reverseOrder());
    long secondMax = sortedAmts.size() >= 2 ? sortedAmts.get(1) : 0L;

    String[] normLines = new String[L];
    boolean[] hasTotalKw = new boolean[L];
    boolean[] hasSubTax = new boolean[L];
    for (int i = 0; i < L; i++) {
      String nl = ocrNormText(lines[i]);
      normLines[i] = nl;
      hasTotalKw[i] = TOTAL_KW_RE.matcher(nl).find() && !nl.contains("subtotal") && !nl.contains("sub total");
      hasSubTax[i] = SUB_TAX_KW_RE.matcher(nl).find();
    }

    boolean seenSub = false, seenBody = false, seenTotal = false;
    long prevSubAmt = 0L, prevTaxAmt = 0L;

    List<List<String>> result = new ArrayList<>(L);
    for (int i = 0; i < L; i++) {
      String raw = lines[i];
      String nl = normLines[i];
      List<Long> amts = lineAmts.get(i);
      long lineMax = 0L;
      for (long v : amts) if (v > lineMax) lineMax = v;

      List<String> f = new ArrayList<>(32);
      f.add("f:bias");
      int posBin = Math.min(9, (i * 10) / Math.max(1, L));
      f.add("f:pos_" + posBin);
      if (i == 0) f.add("f:first1");
      if (i < 3) f.add("f:first3");
      if (i == L - 1) f.add("f:last1");
      if (i >= Math.max(0, L - 3)) f.add("f:last3");

      if (HAS_RM_RE.matcher(nl).find()) f.add("f:has_rm");
      if (amts.isEmpty()) f.add("f:amt_0");
      else if (amts.size() == 1) f.add("f:amt_1");
      else f.add("f:amt_2p");
      if (STANDALONE_AMT_RE.matcher(raw).matches()) f.add("f:standalone_amt");
      if (NEG_AMT_RE.matcher(raw).find()) f.add("f:has_neg_amt");
      if (!amts.isEmpty() && lineMax == maxAmt && maxAmt > 0L) f.add("f:is_max_amt");
      else if (!amts.isEmpty() && secondMax > 0L && lineMax == secondMax) f.add("f:is_2nd_max");

      if (!amts.isEmpty() && maxAmt > 0L) {
        if (lineMax == maxAmt) f.add("f:ratio_eq1");
        else if (lineMax * 4L >= maxAmt * 3L) f.add("f:ratio_hi");
        else if (lineMax * 4L >= maxAmt) f.add("f:ratio_mid");
        else f.add("f:ratio_lo");
      }
      if (!amts.isEmpty() && (lineMax % 5L == 0L)) f.add("f:is_5sen");
      if (!amts.isEmpty() && seenSub && prevSubAmt > 0L && lineMax >= prevSubAmt) f.add("f:amt_ge_sub");
      if (!amts.isEmpty() && seenSub && prevSubAmt > 0L) {
        long expSum = prevSubAmt + prevTaxAmt;
        if (Math.abs(lineMax - expSum) <= 4L) f.add("f:amt_eq_sum");
      }

      if (PCT_RE.matcher(raw).find()) f.add("f:has_pct");
      if (DATE_LINE_RE.matcher(nl).find()) f.add("f:has_date");
      if (TIN_SSM_RE.matcher(raw).find()) f.add("f:has_tin_ssm");
      if (PHONE_URL_RE.matcher(raw).find()) f.add("f:has_phone_url");
      if (ADDR_RE.matcher(nl).find()) f.add("f:has_addr");
      if (BIZ_SFX_RE.matcher(nl).find()) f.add("f:has_biz_sfx");

      boolean anyLetter = false, allUpper = true;
      for (int c = 0; c < raw.length(); c++) {
        char ch = raw.charAt(c);
        if (Character.isLetter(ch)) {
          anyLetter = true;
          if (!Character.isUpperCase(ch)) { allUpper = false; break; }
        }
      }
      if (anyLetter && allUpper) f.add("f:all_caps");
      int lnLen = raw.length();
      if (lnLen <= 14) f.add("f:len_s");
      else if (lnLen <= 34) f.add("f:len_m");
      else f.add("f:len_l");
      if (CJK_RE.matcher(raw).find()) f.add("f:has_cjk");

      if (seenBody) f.add("f:after_body");
      if (!seenTotal) f.add("f:before_total");
      if (i > 0 && hasTotalKw[i - 1]) f.add("f:prev_total_kw");
      if (i > 0 && hasSubTax[i - 1]) f.add("f:prev_sub_or_tax");
      if (i > 0 && !lineAmts.get(i - 1).isEmpty()) f.add("f:prev_has_amt");
      if (i + 1 < L && STANDALONE_AMT_RE.matcher(lines[i + 1]).matches()) f.add("f:next_standalone_amt");
      if (i + 1 < L && !lineAmts.get(i + 1).isEmpty()) f.add("f:next_has_amt");

      List<String> toks = tokenizeLine(nl);
      Set<String> seenFeat = new HashSet<>(f);
      for (int j = 0; j < toks.size(); j++) {
        String k = "w:" + toks.get(j);
        if (seenFeat.add(k)) f.add(k);
        if (j + 1 < toks.size()) {
          String bk = "b:" + toks.get(j) + "_" + toks.get(j + 1);
          if (seenFeat.add(bk)) f.add(bk);
        }
      }
      for (int c = 0; c < raw.length(); c++) {
        char ch = raw.charAt(c);
        if (ch >= '\u4e00' && ch <= '\u9fff') {
          String zk = "z:" + ch;
          if (seenFeat.add(zk)) f.add(zk);
        }
      }
      result.add(f);

      if (nl.contains("subtotal") || nl.contains("sub total") || nl.contains("sub-total")) {
        seenSub = true;
        seenBody = true;
        if (!amts.isEmpty()) prevSubAmt = amts.get(amts.size() - 1);
      } else if (hasSubTax[i]) {
        if (!amts.isEmpty()) prevTaxAmt += amts.get(amts.size() - 1);
      } else if (BODY_START_RE.matcher(nl).find() || (!amts.isEmpty() && i >= 2 && !DATE_LINE_RE.matcher(nl).find() && !hasTotalKw[i])) {
        seenBody = true;
      }
      if (hasTotalKw[i]) seenTotal = true;
    }
    return result;
  }

  /** Tags normalized lines using exact Linear-Chain CRF Viterbi decoding. Returns null if model is unavailable. */
  public static TaggedLines tag(String[] lines) {
    if (disabled || lines == null || lines.length == 0) return null;
    ensureLoaded();
    Model m = activeModel;
    if (m == null) return null;

    int L = lines.length;
    int C = m.labels.length;
    List<List<String>> feats = extractLineFeatures(lines);
    double[][] emits = new double[L][C];
    double[][] probs = new double[L][C];
    for (int t = 0; t < L; t++) {
      for (String fk : feats.get(t)) {
        FeatureWeights fw = m.features.get(fk);
        if (fw == null) continue;
        for (int j = 0; j < fw.labelIdx.length; j++) {
          emits[t][fw.labelIdx[j]] += fw.weights[j];
        }
      }
      double mx = Double.NEGATIVE_INFINITY;
      for (int c = 0; c < C; c++) if (emits[t][c] > mx) mx = emits[t][c];
      double sum = 0.0;
      for (int c = 0; c < C; c++) {
        double e = Math.exp(emits[t][c] - mx);
        probs[t][c] = e;
        sum += e;
      }
      if (sum > 0.0) for (int c = 0; c < C; c++) probs[t][c] /= sum;
    }

    double[] dp = new double[C];
    int[][] back = new int[L][C];
    for (int c = 0; c < C; c++) dp[c] = m.start[c] + emits[0][c];

    double[] nextDp = new double[C];
    for (int t = 1; t < L; t++) {
      for (int nextC = 0; nextC < C; nextC++) {
        double best = Double.NEGATIVE_INFINITY;
        int bestPrev = 0;
        for (int prevC = 0; prevC < C; prevC++) {
          double cand = dp[prevC] + m.transitions[prevC][nextC];
          if (cand > best) {
            best = cand;
            bestPrev = prevC;
          }
        }
        nextDp[nextC] = best + emits[t][nextC];
        back[t][nextC] = bestPrev;
      }
      System.arraycopy(nextDp, 0, dp, 0, C);
    }

    double bestEnd = Double.NEGATIVE_INFINITY;
    int cur = 0;
    for (int c = 0; c < C; c++) {
      double cand = dp[c] + m.end[c];
      if (cand > bestEnd) {
        bestEnd = cand;
        cur = c;
      }
    }

    int[] path = new int[L];
    String[] names = new String[L];
    for (int t = L - 1; t >= 0; t--) {
      path[t] = cur;
      names[t] = m.labels[cur];
      if (t > 0) cur = back[t][cur];
    }
    return new TaggedLines(lines, path, names, probs, bestEnd);
  }
}
