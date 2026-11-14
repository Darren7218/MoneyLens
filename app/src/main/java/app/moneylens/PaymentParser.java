package app.moneylens;
import org.json.*;
import java.util.Locale;
import java.util.regex.*;
import java.time.LocalDate;

/** Payment notification parser supporting TNG eWallet, toll deductions, DuitNow QR, FPX, JomPAY, BNPL, PTPTN and Malaysian bank formats. */
public final class PaymentParser {
  private static final String NUM="([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])";
  private static final Pattern AMOUNT=Pattern.compile("(?i)(?:RM|MYR)[\\s\\u00A0]*"+NUM);
  private static final Pattern FOREIGN_AMT=Pattern.compile("(?i)\\b(SGD|USD|THB|JPY|IDR|EUR|GBP|AUD|CNY|HKD|TWD|KRW|VND)[\\s\\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])");
  private static final Pattern TNG_PAID=Pattern.compile("(?i)\\byou\\s+have\\s+paid\\s+(?:RM|MYR)\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s+(?:at|to)\\s+([^\\r\\n]+)");
  private static final Pattern PURCHASE_AT=Pattern.compile("(?i)\\bfor\\s+(?:your\\s+)?purchase\\s+(?:at|from)\\s+([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern MALAY_PAID=Pattern.compile("(?im)(?:^|\\b)(?:pembayaran|bayaran)(?:\\s+telah)?\\s+berjaya(?:[ \\t]+(?:dibuat[ \\t]+)?(?:kepada|ke)\\b|[ \\t]*!)[ \\t:\\-!]*([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern LABELED_MERCHANT=Pattern.compile("(?im)(?:^|\\b)(?:merchant(?:\\s+name)?|seller|biller(?:\\s+name)?|payee|recipient|paid\\s+to|payment\\s+to|pembayaran(?:\\s+telah\\s+berjaya)?\\s+(?:dibuat\\s+)?kepada|bayaran\\s+kepada|dibayar\\s+kepada|penerima|peniaga|penyertaan|perkhidmatan|perniagaan|nota\\s+pembayaran)\\s*:\\s*[\\r\\n\\t ]*([\\p{L}0-9#][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern MERCHANT=Pattern.compile("(?i)\\b(?:at|to|from|kepada|di|dari)\\s+([\\p{L}0-9 &'._/()#,+*-]{2,80})");
  private static final Pattern VERBLESS_PREP=Pattern.compile("(?i)(?:RM|MYR)\\s*"+NUM+"(?:\\s+(?:at|to|di|kepada)\\s+|[ \\t]*[@\\-\\u2013\\u2014][ \\t]*)([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern VERBLESS_CAPS=Pattern.compile("(?:RM|MYR)\\s*"+NUM+"\\s+([A-Z0-9][A-Z0-9&'._/()#+*-]*(?:[ ]+[A-Z0-9&'._/()#+*-]+){0,6})");
  /** Security advice footers ("Never share your TAC/OTP") are removed before any filtering. */
  private static final Pattern FOOTER=Pattern.compile("(?i)\\b(?:never|do\\s+not|don'?t|jangan)\\s+(?:share|disclose|reveal|give|kongsi|dedahkan|berikan)\\b[^.!\\n]*");
  private static final Pattern EXCLUDE_BEFORE=Pattern.compile("(?i)\\b(?:balance|bal|baki|limit|cashback|cash\\s+back|reward|rewards|points|pts|rebate|earned|earn|available|avail|remaining|outstanding|saved|worth|up\\s+to|min(?:imum)?\\s+spend)\\b[^0-9]*$");
  private static final Pattern EXCLUDE_AFTER=Pattern.compile("(?i)^\\s*(?:cashback|cash\\s+back|reward|rewards|points|pts|rebate|back)\\b");
  private static final Pattern TOTAL_BEFORE=Pattern.compile("(?i)\\b(?:total|jumlah(?:\\s+(?:perlu\\s+)?dibayar|\\s+bayaran|\\s+keseluruhan)?|grand\\s+total|amount\\s+paid|bayaran\\s+jumlah)\\b[^0-9a-z\\r\\n]*$");
  private static final Pattern PAY_BEFORE=Pattern.compile("(?i)\\b(?:paid|pay|payment|repayment|spent|charged|deducted|debited|purchase|purchased|amount|total|of|transfer|transferred|received|credited|refund|refunded|bayaran|dibayar|potongan|sebanyak|deduction|sale)\\b[^0-9a-z]*$");
  private static final Pattern PAY_AFTER=Pattern.compile("(?i)^(?:\\s*(?:at|to|di|kepada|has\\s+been|was|deducted|charged|paid|debited)\\b|[ \\t]*[@\\-\\u2013\\u2014])");
  public static final String LOANS="Loans, BNPL & Credit";

  public static JSONObject parse(String text,String source)throws Exception{
    if(text==null||text.length()>4000)return null;
    String normalized=text.replace('\u00A0',' ').trim();
    String core=FOOTER.matcher(normalized).replaceAll(" ").trim();
    String low=core.toLowerCase(Locale.ROOT);
    boolean completed=low.matches("(?s).*\\b(you have paid|paid|successful|successfully|completed|approved|deducted|charged|debited|credited|received|berjaya|diterima|dibayar|membayar|authori[sz]ed a payment|spent|payment\\s+(?:of|to|confirmation))\\b.*");
    if(low.matches("(?s).*\\b(declined|failed|unsuccessful|gagal|rejected)\\b.*"))return null;
    boolean otpWord=low.matches("(?s).*\\b(otp|tac|verification\\s+code|kod\\s+pengesahan|one.?time\\s+(?:pin|password|passcode)|security\\s+code)\\b.*");
    if(otpWord&&!completed&&low.matches("(?s).*(?<![0-9.,])[0-9]{4,8}(?![0-9.,]).*"))return null;
    if(!completed&&low.matches("(?s).*\\b(voucher|promo|promotion|discount|offer|deal|deals|lucky\\s+draw|win|redeem|claim\\s+now|get\\s+up\\s+to|enjoy|limited\\s+time|flash\\s+sale)\\b.*"))return null;
    if(!completed&&low.matches("(?s).*\\b(secure2u|authorise|authorize|approve|approval)\\b.*"))return null;
    if(low.matches("(?s).*\\b(pending|processing|requested|scheduled|pre.?authori[sz]ation|awaiting)\\b.*"))return null;
    boolean trigger=low.matches("(?s).*\\b(paid|pay|payment|repayment|instal?ment|purchase|spent|debited|deducted|deduction|charged|charge|sale|credited|received|refund|refunded|transfer|transferred|withdrawal|top.?up|reload|reloaded|toll|paydirect|rfid|duitnow|fpx|jompay|giro|ibft|autodebit|paywave|contactless|bayaran|pembayaran|dibayar|membayar|diterima|transaksi|potongan|transaction|spaylater|paylater|ptptn)\\b.*");
    Matcher vp=VERBLESS_PREP.matcher(core),vc=VERBLESS_CAPS.matcher(core);
    boolean prep=vp.find(),caps=!prep&&vc.find();
    if(!trigger&&!prep&&!caps)return null;

    Matcher m=AMOUNT.matcher(core);JSONArray candidates=new JSONArray();long primary=0,firstIncluded=0,totalMatch=0;int count=0;boolean primaryFound=false,totalFound=false;
    while(m.find()){
      long v;try{v=Ledger.minor(m.group(1).replace(",",""));}catch(IllegalArgumentException ignored){continue;}
      count++;candidates.put(v);
      String before=core.substring(Math.max(0,m.start()-30),m.start()),after=core.substring(m.end(),Math.min(core.length(),m.end()+16));
      if(EXCLUDE_BEFORE.matcher(before).find()||EXCLUDE_AFTER.matcher(after).find())continue;
      if(firstIncluded==0)firstIncluded=v;
      if(TOTAL_BEFORE.matcher(before).find()){totalMatch=v;totalFound=true;}
      if(!primaryFound&&(PAY_BEFORE.matcher(before).find()||PAY_AFTER.matcher(after).find())){primary=v;primaryFound=true;}
    }
    String origCur="",origAmt="";
    Matcher fm=FOREIGN_AMT.matcher(core);
    if(fm.find()){
      try{
        String candAmt=fm.group(2).replace(",","");
        if(new java.math.BigDecimal(candAmt).signum()>0){
          origCur=fm.group(1).toUpperCase(Locale.ROOT);
          origAmt=candAmt;
        }
      }catch(Exception ignored){}
    }
    if(count==0&&!origCur.isEmpty()&&completed){
      long fxMinor=Math.max(1L,new java.math.BigDecimal(origAmt).multiply(java.math.BigDecimal.valueOf(defaultFxRate(origCur))).movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).longValue());
      count=1;firstIncluded=fxMinor;candidates.put(fxMinor);
    }
    if(count==0)return null;
    long amount=totalFound?totalMatch:(primaryFound?primary:firstIncluded);
    if(amount<=0)return null;

    boolean outgoingCue=P_OUTGOING_CUE.matcher(low).find();
    String kind=low.contains("refund")?"refund":(!outgoingCue&&P_INCOME_CUE.matcher(low).find())?"income":"expense";
    String reason=count==1?"Recorded from a notification. Check the account, amount and type.":"Several amounts were found; MoneyLens used RM "+java.math.BigDecimal.valueOf(amount,2).toPlainString()+" as the payment. Edit it if that is wrong.";
    if(!kind.equals("income")&&P_TRANSFER_CUE.matcher(low).find()&&!P_NON_TRANSFER_CUE.matcher(low).find()){
      kind="transfer";reason="This may be a transfer between your own accounts. Choose both accounts.";
    }

    String merchant="";
    Matcher lbl=LABELED_MERCHANT.matcher(core);
    while(lbl.find()){String c=cleanMerchant(lbl.group(1));if(validMerchant(c)){merchant=c;break;}}
    if(!validMerchant(merchant)){
      Matcher pm=PURCHASE_AT.matcher(core);
      if(pm.find()){String c=cleanMerchant(pm.group(1));if(validMerchant(c))merchant=c;}
    }
    if(!validMerchant(merchant)){
      Matcher tng=TNG_PAID.matcher(core);
      if(tng.find()){String c=cleanMerchant(tng.group(1));if(validMerchant(c))merchant=c;}
    }
    if(!validMerchant(merchant)){
      Matcher mp=MALAY_PAID.matcher(core);
      if(mp.find()){String c=cleanMerchant(mp.group(1));if(validMerchant(c))merchant=c;}
    }
    if(!validMerchant(merchant)){
      merchant="";Matcher mm=MERCHANT.matcher(core);
      while(mm.find()){String c=cleanMerchant(mm.group(1));if(validMerchant(c)){merchant=c;break;}}
    }
    if(merchant.isEmpty()&&prep){String c=cleanMerchant(vp.group(2));if(validMerchant(c))merchant=c;}
    if(merchant.isEmpty()&&caps){String c=cleanMerchant(vc.group(2));if(validMerchant(c))merchant=c;}
    if(!validMerchant(merchant)&&core.contains("\n")){
      String[] lines=core.split("\\r?\\n");
      for(int i=0;i<lines.length&&i<8;i++){
        String ln=lines[i].trim();
        if(ln.isEmpty()||ln.endsWith(":"))continue;
        String lowLn=ln.toLowerCase(Locale.ROOT);
        if(P_LINE_SKIP1.matcher(lowLn).matches()||P_LINE_SKIP2.matcher(lowLn).matches()||P_LINE_SKIP3.matcher(lowLn).matches())continue;
        String c=cleanMerchant(ln);
        if(validMerchant(c)){merchant=c;break;}
      }
    }
    if(merchant.isEmpty()&&P_TOLL_CUE.matcher(low).find())merchant="TNG Toll";
    if(merchant.isEmpty())merchant="Detected payment";

    String[] cs=kind.equals("income")?new String[]{"Other income",""}:classify(merchant,source,low);
    JSONObject d=new JSONObject().put("id",Ledger.id()).put("amount",amount).put("primaryAmount",amount).put("kind",kind).put("merchant",merchant).put("category",cs[0]).put("date",LocalDate.now().toString()).put("source",source).put("state","review").put("reason",reason).put("amountCandidates",candidates).put("adapter","generic-5").put("revision",1).put("created",System.currentTimeMillis());
    if(!cs[1].isEmpty())d.put("subcategory",cs[1]);
    if(!origCur.isEmpty()&&kind.equals("expense")){
      d.put("originalCurrency",origCur).put("originalAmount",origAmt).put("exchangeRate",java.math.BigDecimal.valueOf(amount,2).divide(new java.math.BigDecimal(origAmt),8,java.math.RoundingMode.HALF_UP).toPlainString());
    }
    if(kind.equals("transfer")){
      if(P_RELOAD_CUE.matcher(low).find())d.put("reload",true);
      Matcher w=P_WALLET_HINT.matcher(core);
      if(w.find())d.put("transferHint",w.group(1));
    }
    return d;
  }

  public static double defaultFxRate(String cur){
    switch(String.valueOf(cur).toUpperCase(Locale.ROOT)){
      case "SGD":return 3.30;
      case "USD":return 4.45;
      case "EUR":return 4.80;
      case "GBP":return 5.65;
      case "AUD":return 2.90;
      case "CNY":return 0.62;
      case "HKD":return 0.57;
      case "THB":return 0.13;
      case "TWD":return 0.14;
      case "JPY":return 0.029;
      case "KRW":return 0.0032;
      case "IDR":return 0.00028;
      case "VND":return 0.00018;
      default:return 1.0;
    }
  }

  private static boolean validMerchant(String c){
    return c!=null&&c.length()>=2
      &&!c.matches("(?i)^(?:rm|myr)\\s*[0-9,]+(?:\\.[0-9]{1,2})?.*")
      &&!c.matches("(?i)^(your\\s+account|your\\s+ewallet|your\\s+card|your|you|account|ewallet|e-wallet|card|wallet|anyone|others|#[0-9a-z-]+|[0-9]{1,2}:[0-9]{2}.*|[0-9 /.:-]+)$")
      &&!c.matches("(?i)^(?:[0-9]{1,2}(?:st|nd|rd|th)?[-/\\s.]+(?:[0-9]{1,2}|[a-z]{3,10})[-/\\s.,]+(?:20)?[0-9]{2}|20[0-9]{2}[-/][0-9]{1,2}[-/][0-9]{1,2})$")
      &&!c.matches("(?i)^(?:lot|no\\.?|unit|level|lvl|block|blk|floor|flr|jalan|jln|lorong|lrg)\\s*[#0-9a-z-]{1,6}$")
      &&!c.matches("(?i)^(?:bayar\\s+dengan|bayaran|pembayaran|transaksi|payment|transaction|notifikasi|notis|nota(?:\\s+pembayaran)?|berita(?:\\s+bayaran)?|carta\\s+kredit|kad\\s+kredit)(?:\\s+(?:berjaya|successful|completed|approved|confirmed|dibahalkan|dibatalkan|prs))?$");
  }

  static String stripSsmSuffix(String raw){
    if(raw==null)return "";
    String s=raw.trim();
    String noReg=s.replaceAll("(?i)(?:\\s*\\(\\s*(?:[0-9]{5,8}-[A-Za-z]|[0-9]{12}|co\\.?\\s*reg[^)]*)\\s*\\))+\\s*$","").trim();
    if(noReg.length()>=2)s=noReg;
    String noCorp=s.replaceFirst("(?i)(?:,?\\s+(?:sdn\\.?\\s*bhd\\.?|sendirian\\s+berhad|bhd\\.?|berhad|plt|llp))+$","").trim();
    if(noCorp.length()>=2)s=noCorp;
    return s;
  }

  private static String cleanMerchant(String raw){
    if(raw==null)return "";
    String s=raw.replaceAll("(?i)\\s*\\(?\\s*biller\\s*code.*","");
    s=s.replaceFirst("(?i)^(?:penyertaan|perkhidmatan|perniagaan|nota\\s+pembayaran)\\s*:\\s*","");
    s=s.replaceFirst("(?i)^(?:(?:boost|tng|grab|grabpay|shopee|shopeepay|mae|maybank)\\s+)?(?:for\\s+)?your\\s+purchase\\s+(?:at|from)\\s+","");
    s=s.replaceAll("(?i)\\s+(?:has\\s+been\\s+(?:received|completed|paid)|is\\s+successful|was\\s+successful|successful|successfully|berjaya|credited\\s+to|debited\\s+from|paid\\s+from|(?:sebanyak|berjumlah)\\s+(?:rm|myr)|(?:on|pada)\\s+[0-9]{1,4}[/.-][0-9]{1,2}|(?:on|pada)\\s+[0-9]{1,2}\\s+[a-z]{3}|(?:at|pukul)\\s+[0-9]{1,2}[:.][0-9]{2}|for\\s+ref|ref\\b|reference|via\\s+secure2u|via\\s+duitnow|via\\s+fpx|via\\s+jompay|via\\s+(?:maybank(?:2u)?|mae|tng(?:\\s*e?wallet)?|touch\\s*'?n\\s*go|grabpay|boost(?:\\s*bank)?|shopeepay|rhb|cimb(?:\\s*clicks)?|public\\s*bank|hong\\s*leong|ambank|bank\\s+islam|gxbank|aeon\\s*bank|ryt\\s*bank|setel|qr\\s*pay)|using\\s+touch|using\\s+your|with\\s+your|from\\s+your|available\\s+balance|avail\\.?\\s*bal|remaining|balance|baki|limit|cashback|earn\\s+[0-9]|reward|points|thank|terima\\s+kasih|never|do\\s+not|jangan|txn|transaction\\s+id|trx).*","");
    s=s.replaceAll("[.!]\\s+(?=(?:RM|MYR)\\s*[0-9]|\\p{Lu}\\p{Ll}).*","").trim();
    s=s.replaceAll("[.,;:!\\-\\u2013\\u2014\\s]+$","").replaceAll("^[.,;:!\\-\\u2013\\u2014\\s]+","").trim();
    String noGw=s.replaceFirst("(?i)^(?:fpx|dnqr|duitnow\\s*qr|duitnow|paynet|ipay88|razerms|razer\\s*ms|molpay|billplz|senangpay|gkash|stripe|curlec|2c2p|revpay|kiple|shopeepay)\\s*(?:[*_/:-]+\\s*)","").trim();
    if(noGw.length()>=2)s=noGw;
    String noWalletGw=s.replaceFirst("(?i)^(?:tngd?|grab|spay|pyt|sq|pp)\\s*[*_/:]+\\s*","").trim();
    if(noWalletGw.length()>=2)s=noWalletGw;
    String noAddr=s.replaceAll("(?i)\\s*,\\s*(?:no\\.?\\s*[0-9]|lot\\s+[0-9]|jalan\\b|jln\\b|unit\\b|level\\b|lvl\\b|block\\b|blk\\b|persiaran\\b|taman\\b|bandar\\b|seksyen\\b).*","").trim();
    if(noAddr.length()>=2)s=noAddr;
    String noPost=s.replaceAll("(?i),\\s*[\\p{L} ]{2,25}\\s+[0-9]{5}\\b.*","").trim();
    if(noPost.length()>=2)s=noPost;
    s=stripSsmSuffix(s);
    s=s.replaceAll("[.,;:!\\-\\u2013\\u2014\\s]+$","").replaceAll("^[.,;:!\\-\\u2013\\u2014\\s]+","").trim();
    if(s.length()>80)s=s.substring(0,80).trim();
    return s;
  }

  private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> HAS_PATTERNS=new java.util.concurrent.ConcurrentHashMap<>();
  private static final Pattern P_OUTGOING_CUE=Pattern.compile("\\b(for\\s+your\\s+(?:[a-z0-9\\s]{0,30}?\\b)?(?:purchase|investment|sspn|prs|loan|repayment)|your\\s+purchase|purchase\\s+at|from\\s+fpx\\s+to|paid\\s+amount|payment\\s+for|payment\\s+method|payment\\s+to|paid\\s+to|order\\s+(?:id|no)|item|qty|invoice)\\b");
  private static final Pattern P_INCOME_CUE=Pattern.compile("\\b(credited|received|diterima)\\b");
  private static final Pattern P_TRANSFER_CUE=Pattern.compile("\\b(transfer(?:red|ring)?|top.?up|reload|reloaded|withdrawal)\\b");
  private static final Pattern P_NON_TRANSFER_CUE=Pattern.compile("\\b(duitnow\\s+qr|fpx|jompay|paid|purchase|spent|sale)\\b");
  private static final Pattern P_LINE_SKIP1=Pattern.compile("^(?:notifikasi|notis|nota(?:\\s+pembayaran)?|makluman|berita(?:\\s+bayaran)?|pengesahan|amaran|alert|notice|notification|bayar(?:\\s+dengan|\\s+menggunakan)?|bayaran|pembayaran|transaksi|payment|transaction|kad\\s+kredit|carta\\s+kredit|credit\\s+card|no\\.?\\s*kad|item|barang|produk|harga|perkhidmatan|penyertaan|angsuran|terima\\s+kasih|thank\\s+you|sila\\s+semak).*");
  private static final Pattern P_LINE_SKIP2=Pattern.compile("^(?:rm|myr)?\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s*$");
  private static final Pattern P_LINE_SKIP3=Pattern.compile("^(?:tarikh|date|time|masa|kod\\b|code|ref\\b|reference|rujukan|txn|transaction|order|invoice|barang|item|baki|tunai|cash|balance|cukai|sst|gst|service|subtotal|jumlah|total|contact|hotline|tel|hp|phone|no\\.|lot\\b|jalan\\b|jln\\b|lorong\\b|unit\\b|level\\b|[0-9]+,\\s*(?:jalan|jln|lorong)|[0-9]{1,2}(?:st|nd|rd|th)?[\\s/-]+(?:[0-9]{1,2}|[a-z]{3,10})[\\s/-]+20[0-9]{2}|20[0-9]{2}-[0-9]{2}-[0-9]{2}).*");
  private static final Pattern P_TOLL_CUE=Pattern.compile("\\b(toll|paydirect|rfid)\\b");
  private static final Pattern P_RELOAD_CUE=Pattern.compile("\\b(top.?up|reload|reloaded)\\b");
  private static final Pattern P_WALLET_HINT=Pattern.compile("(?i)\\b(tng|touch\\s*'?n\\s*go|grabpay|boost(?:\\s*bank)?|shopeepay|bigpay|mae|gxbank|aeon\\s*bank|ryt\\s*bank|setel)\\b");
  private static final Pattern FAST_LIKELY_CURRENCY=Pattern.compile("(?i)(?:\\b(?:rm|myr|sgd|usd|eur|gbp|aud|cny|hkd|thb|twd|jpy|krw|idr|vnd)[.:\\s]*[0-9]|[0-9.,]+\\s*(?:rm|myr|sgd|usd|eur|gbp|aud|cny|hkd|thb|twd|jpy|krw|idr|vnd)\\b|\\$[0-9])");
  private static final Pattern FAST_LIKELY_CUE=Pattern.compile("(?i)\\b(?:paid|pay|payment|bayar|bayaran|pembayaran|spent|berbelanja|purchase|pembelian|debited|credited|received|diterima|charged|transferred|transfer|pindahan|duitnow|fpx|jompay|qr\\s*pay|top.?up|reload|reloaded|tambah\\s+nilai|refund|pulangan|transaksi|transaction|resit|receipt|invois|invoice|jumlah|total|subtotal|amount|amaun|cukai|tax|toll|tol|rfid|paydirect|spaylater|atome|grab|tng|mae|fave|boost|setel|bigpay|gx|gxbank|aeon|ryt|gentari|ptptn|bnpl|bil|bill)\\b");
  private static final Pattern FAST_LIKELY_OTP=Pattern.compile("(?i)\\b(?:tac|otp|pac|one[- ]time\\s+password|verification\\s+code|kod\\s+pengesahan|passcode)\\b");

  /** Ultra-fast zero-allocation pre-filter that drops non-payment noise before decrypting Vault or loading ML. */
  public static boolean isLikelyPayment(String text){
    if(text==null)return false;
    int len=text.length();
    if(len<4)return false;
    boolean hasDigit=false;
    for(int i=0;i<len;i++){
      char c=text.charAt(i);
      if(c>='0'&&c<='9'){hasDigit=true;break;}
    }
    if(!hasDigit)return false;
    if(FAST_LIKELY_CURRENCY.matcher(text).find())return true;
    return !FAST_LIKELY_OTP.matcher(text).find()&&FAST_LIKELY_CUE.matcher(text).find();
  }

  static boolean has(String hay,String alternatives){
    if(hay==null||hay.isEmpty())return false;
    Pattern p=HAS_PATTERNS.get(alternatives);
    if(p==null){
      p=Pattern.compile("(?<![\\p{L}0-9])(?:"+alternatives+")(?![\\p{L}0-9])");
      Pattern prev=HAS_PATTERNS.putIfAbsent(alternatives,p);
      if(prev!=null)p=prev;
    }
    return p.matcher(hay).find();
  }

  /** Rule-only Malaysian merchant classifier (used during receipt header scoring and as stage-1 of classify). */
  public static String[] classifyRulesOnly(String merchant,String source,String text){
    String m=String.valueOf(merchant).toLowerCase(Locale.ROOT);
    String body=String.valueOf(text).replaceAll("(?im)^\\s*(?:(?:no\\.?|lot|unit|block|blk)\\s*[#0-9a-z-]+,?\\s*)?(?:jalan|jln|lorong|lrg|persiaran|taman|tmn|bandar|seksyen|[0-9]{5})\\b.*$"," ").toLowerCase(Locale.ROOT);
    String hay=m+" "+String.valueOf(source).toLowerCase(Locale.ROOT)+" "+body;
    if(has(hay,"sspn|ptptn sspn"))return new String[]{"Investments & savings","SSPN Savings"};
    if(has(hay,"prs|private retirement|simpanan persaraan"))return new String[]{"Investments & savings","PRS (Private Retirement Scheme)"};
    if(has(hay,"ptptn|perbadanan tabung pendidikan"))return new String[]{LOANS,"PTPTN Study Loan"};
    if(has(hay,"spaylater|shopee paylater|grabpaylater|grab paylater|paylater|pay later|atome|payflex"))return new String[]{LOANS,"BNPL (SPayLater / GrabPayLater / Atome)"};
    if(has(hay,"credit\\s+card\\s+(?:statement|bill|payment)|penyata\\s+kad\\s+kredit"))return new String[]{LOANS,"Credit Card Bill"};
    if(has(hay,"personal\\s+loan|pinjaman\\s+peribadi"))return new String[]{LOANS,"Personal Loan"};
    if(has(hay,"home\\s+loan|housing\\s+loan|mortgage|pembiayaan\\s+rumah|pinjaman\\s+perumahan"))return new String[]{"Housing & property","Home Loan / Mortgage"};
    if(has(m,"credit services|aeon credit|courts|singer|senheng|senq|chan furniture")&&has(hay,"hire\\s+purchase|ansuran|instal?ment"))return new String[]{LOANS,"Hire Purchase / Installment"};
    if(has(hay,"car\\s+loan|auto\\s+loan|hire\\s+purchase|sewa\\s+beli|pinjaman\\s+kereta|a(?:ng|ns)uran\\s+kenderaan"))return new String[]{"Transport","Car Loan Installment"};
    if(has(hay,"bank transfer fee|transfer fee|ibt fee|rentas fee|meps fee|atm fee|duitnow overseas fee"))return new String[]{"Fees & charges","Bank & Transfer Fee"};
    if(has(hay,"late payment fee|late charge|late reconnection fee|late penalty|late\\s+payment\\s+(?:of|for)\\b|caj\\s+lewat"))return new String[]{"Fees & charges","Late Payment Fee"};
    if(has(hay,"card\\s+annual\\s+fee|annual\\s+(?:card\\s+)?fee|annual\\s+membership\\s+fee"))return new String[]{"Fees & charges","Card Annual Fee"};
    if(has(hay,"foreign\\s+currency\\s+fee|overseas\\s+(?:fee|markup|conversion)\\s*fee|cross[- ]border\\s+(?:fee|markup)|fx\\s+(?:fee|conversion\\s+fee|markup)|currency\\s+conversion\\s+fee"))return new String[]{"Fees & charges","Foreign Currency Fee"};
    if(has(hay,"rfid|paydirect|toll|tol|plaza tol|lebuhraya|expressway|highway|smart\\s*tag|smarttag|smart\\s*tunnel|plus\\s+(?:highway|expressway|lebuhraya|toll)|(?:highway|expressway|lebuhraya|plaza\\s+tol)\\s+plus")||has(m,"duke|ldp|kesas|mex|sprint|npe|akleh|besraya|lekas|guthrie|nkve|suke|silk|lpt|wce|skve|latar|grand saga|ekve|dash"))return new String[]{"Transport","Toll (TNG / RFID / PayDirect)"};
    if(has(hay,"petronas|shell|petron|caltex|bhpetrol|bhp|setel|petrol|fuel|ron95|ron97|diesel|ev charging|chargev|gentari|jomcharge|chargesini|chargsv|chargenow|tesla supercharger|dc handal|shell recharge|evconnection|kineta|tnb electron"))return new String[]{"Transport","Petrol & EV Charging"};
    if(has(hay,"grabfood|grab food|foodpanda|shopeefood|shopee food|beepit|airasia food|misirakyat|lalamove food"))return new String[]{"Food & drinks","Food Delivery (GrabFood / Foodpanda)"};
    if(has(m,"grab|grabcar|airasia ride|maxim|indrive|mycar|ride|bolt|bolt ride")||has(hay,"grabcar|airasia ride|e-hailing|ehailing|indrive|bolt\\s+(?:ride|my|malaysia)"))return new String[]{"Transport","E-Hailing (Grab / AirAsia Ride)"};
    if(has(hay,"parking|flexiparking|flexi parking|jompark|jomparking|setel parking|tng parking|parkhere|smart selangor parking|parkir|autopay|valet parking"))return new String[]{"Transport","Parking"};
    if(has(hay,"mrt|lrt|rapidkl|rapid kl|ktm|ktmb|ets|monorail|myrapid|rapidbus|rapid bus|klia ekspres|klia transit|rapid penang|prasarana|my50"))return new String[]{"Transport","Public Transit (MRT / LRT / Bus)"};
    if(has(hay,"home\\s+(?:repair|maintenance|improvement)|pembaikan\\s+(?:dalam\\s+)?rumah|window\\s+replacement|kitchen\\s+sink|plumbing|roof\\s+repair|wiring\\s+repair"))return new String[]{"Housing & property","Home Repair & Maintenance"};
    if(has(hay,"mr\\.?\\s*diy|mr\\.?\\s*dollar|ecoshop|eco-shop|eco\\s+shop|muji|living cabin|objet|noko|daiso|kaison|ninso|hardware|carpentry|wooden\\s+chairs?|kitchen\\s+drawers?|home\\s*&\\s*living"))return new String[]{"Shopping","Home & Living"};
    if(has(hay,"perodua service|proton service|honda service|toyota service|tyre|tayar|(?:auto|car|motor|tyre|tayar|vehicle|kereta|repair)\\s*workshop|workshop\\s*(?:kereta|motor|auto)|bengkel|bateriku|car wash|carwash|pusat servis|servis kereta|auto service|engine oil"))return new String[]{"Transport","Vehicle Maintenance & Service"};
    if(has(hay,"(auto|car|motor|vehicle)\\s*(insurance|takaful|insurans|cover|coverage)|(insurance|takaful|insurans)\\s*(kereta|motor)|road\\s*(?:&|and)\\s*auto\\s*cover")||(has(hay,"road\\s*tax|roadtax|cukai\\s*jalan")&&has(hay,"insurance|takaful|insurans|cover|policy|polisi|motor\\s*service|motorway"))||has(hay,"bjak|policystreet"))return new String[]{"Transport","Road Tax & Motor Insurance"};
    if(has(hay,"jpj|mysikap|myeg|puspakom|road tax|roadtax|cukai jalan|summon|summons|saman|traffic fine|compound"))return new String[]{"Taxes & government","JPJ / Renewals / Summons"};
    if(has(hay,"99\\s*spe[e3]dmart|spe[e3]dmart|7-eleven|7 eleven|7eleven|7-caf[eé]|family\\s*mart|familymart|kk\\s*mart|kkmart|kk super mart|mynews|emart\\s*24|bilabila(?:\\s*mart)?|bila-bila|cu\\s*mart|cu-mart|mix store|kedai runcit|minimart|mini market"))return new String[]{"Groceries","Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"};
    if(has(hay,"lotus's|lotus|lotuss|mydin|giant|aeon|jaya grocer|village grocer|tesco|econsave|nsk|hero market|heromarket|cold storage|mercato|segi fresh|tf value|qra|ben's independent|everrise|servay|st rosyam|rosyam mart|redtick|checkers|supermarket|hypermarket|pasaraya"))return new String[]{"Groceries","Supermarket & Hypermarket"};
    if(has(hay,"wet market|pasar pagi|pasar malam|pasar borong|pasar basah|pasar baru|pasar awam|pasar tani|fresh produce|butcher|fishmonger"))return new String[]{"Groceries","Wet Market & Fresh Produce"};
    if(has(hay,"mamak|nasi|kopitiam|kedai makan|warung|hawker|gerai|pelita|q bistro|kandar|roti canai|medan selera|food court|bak kut teh|char kuey teow|yong tau foo|madam kwan|oriental kopi|thong kee|hock kee|yut kee|nz curry|ali muthu|village park|murni discovery"))return new String[]{"Food & drinks","Hawker & Mamak"};
    if(has(hay,"mcdonald|mcdonalds|mcd|kfc|burger king|marrybrown|subway|pizza hut|domino|dominos|texas chicken|a&w|4fingers|wendy's|wendys|jollibee|popeyes|us pizza|nando's|nandos|kenny rogers|kyochon|shake shack|richeese|wingstop|myburgerlab|woodfire|five guys|kgb burger"))return new String[]{"Food & drinks","Fast Food"};
    if(has(hay,"starbucks|tealive|zus|chagee|coffee bean|gigi coffee|kenangan|luckin|boba|bubble tea|coolblog|chatime|mixue|ai-cha|aicha|koppiku|hwc coffee|cotti coffee|san francisco coffee|bask bear|richiamo|oldtown|old town|gong cha|beutea|boost juice|llaollao|secret recipe|coffee|kopi"))return new String[]{"Food & drinks","Coffee, Tea & Drinks"};
    if(has(hay,"beryl'?s|famous amos|auntie anne'?s|inside scoop|lavender bakery|rt pastry|royce'?|garrett popcorn|komugi|dunkin'?|krispy kreme|mix\\.com\\.my"))return new String[]{"Food & drinks","Groceries & Snacks"};
    if(has(hay,"restoran|restaurant|cafe|café|bistro|eatery|sushi|sushiro|haidilao|sukiya|suki-ya|seoul garden|din tai fung|steak|bakery|dim sum|bbq|seafood|papparich|pappa rich|nanyang|boat noodle|chicken rice shop|teppanyaki|kenny hills|antipodean|dookki|yakiniku like|serai|bar\\.b\\.q plaza|christine'?s bakery|nippori|two sons|food"))return new String[]{"Food & drinks","Restaurant & Cafe"};
    if(has(hay,"tnb|tenaga nasional|mytnb|sesb|sarawak energy|elektrik|tarif elektrik|bil elektrik|electricity(?:\\s+bill)?|power\\s+supply"))return new String[]{"Bills & utilities","Electricity (TNB)"};
    if(has(hay,"air selangor|syabas|indah water|iwk|ranhill|air kelantan|air johor|saj|pbapp|lembaga air perak|sada|satu water|syarikat air melaka|samb melaka|syarikat air negeri sembilan|sains air|bil air|pembetungan|water.*sewerage"))return new String[]{"Bills & utilities","Water & Sewerage (Air Selangor / IWK)"};
    if(has(hay,"unifi fibre|unifi|time fibre|time internet|maxis fibre|celcom fibre|digi fibre|astro fibre|tm net|starlink|allo broadband|home internet|fibre internet|broadband"))return new String[]{"Bills & utilities","Home Internet & WiFi (Unifi / Time)"};
    if(has(hay,"maxis|hotlink|celcom|digi|celcomdigi|umobile|u mobile|tune talk|xox|onexox|redone|yes 5g|yes5g|yoodo|mobile postpaid|postpaid plan|cellular"))return new String[]{"Bills & utilities","Mobile Postpaid & Prepaid"};
    if(has(hay,"netflix|spotify|astro|sooka|disney|youtube|icloud|google one|apple\\.com|viu|iqiyi|chatgpt|openai|claude|anthropic|perplexity|cursor|github copilot|copilot pro|gemini advanced|nordvpn|expressvpn|microsoft 365|adobe|canva"))return new String[]{"Bills & utilities","Streaming & App Subscriptions"};
    if(has(hay,"condo maintenance|sinking fund|management corporation|jmb"))return new String[]{"Bills & utilities","Condo Maintenance & Sinking Fund"};
    if(has(hay,"decathlon|sports direct|al-ikhsan|alikhsan|jd sports|foot locker|anytime fitness|celebrity fitness|chi fitness|fitness first|babel|babel fit|flyproject|badminton|futsal|pickleball|padel|hyrox|gym|sports"))return new String[]{"Health & medical","Gym, Sports & Fitness"};
    if(has(hay,"watsons|guardian|caring pharmacy|big pharmacy|aa pharmacy|alpro|health lane|multicare|constant pharmacy|pharmacy|farmasi|panadol|obat|ubat"))return new String[]{"Health & medical","Pharmacy & Medicine"};
    if(has(hay,"dental|dentist|pergigian|orthodontic|optometrist|optical|focus point|owndays|mog eyewear|a-look|eyecare|q\\s*&\\s*m dental|tiew dental|lenskart"))return new String[]{"Health & medical","Dental & Optical"};
    if(has(hay,"pathlab|bp healthcare|bp diagnostic|gribbles|health screening|full medical|medical checkup|medical check-up"))return new String[]{"Health & medical","Full Medical Checkup (LHDN)"};
    if(has(hay,"hospital|gleneagles|pantai|sunway medical|kpj|columbia asia|prince court|sjmc|subang jaya medical|thomson hospital|assunta|tung shin|institut jantung|ijn|avisena|medical cent(?:er|re)|pusat perubatan|specialist cent(?:er|re)"))return new String[]{"Health & medical","Hospital & Specialist"};
    if(has(hay,"klinik|clinic|poliklinik|polyclinic|qualitas|mediviron|mediklinik|careclinics|megaklinik|doctoroncall"))return new String[]{"Health & medical","Clinic & GP"};
    if(has(hay,"prudential|prubsn|great eastern|aia|allianz|etiqa|zurich|manulife|fwd|[\\p{L}0-9]*takaful"))return new String[]{"Insurance & Takaful","Life Insurance / Takaful"};
    if(!has(hay,"cinema|cinemas|cineplex|wayang")&&has(hay,"popular|kinokuniya|mph bookstore|mph bookstores|bookxcess|tsutaya|eslite|smiggle|typo|cziplee|bookstore|bookshop|kedai\\s+buku|alat\\s+tulis|books|stationery"))return new String[]{"Education","Books & Stationery"};
    if(has(hay,"tuition|university|universiti|monash|taylor's|taylors|sunway university|utar|uitm|mmu|inti|apu|coursera|udemy|upskill|tuisyen|online course|course enrollment|bootcamp|42 kuala lumpur"))return new String[]{"Education","Higher Education & Tuition"};
    if(has(hay,"school fee|yuran sekolah|tadika|taska|kindergarten|preschool|childcare fee"))return new String[]{"Education","School & Childcare Fees"};
    if(has(hay,"mothercare|chicco|anakku|poney|babysitter|pengasuh|nanny\\s+allowance|baby\\s+store"))return new String[]{"Family & gifts","Childcare & Family Support"};
    if(has(hay,"epf|kwsp|i-saraan|isaraan"))return new String[]{"Investments & savings","EPF Voluntary (i-Saraan)"};
    if(has(hay,"asnb|asb|tabung haji|thijari"))return new String[]{"Investments & savings","ASB / Tabung Haji"};
    if(has(hay,"rakuten trade|moomoo|webull|luno|stashaway|wahed|versa|kdi invest|fsmone|goinvest|tng go\\+|etf|crypto"))return new String[]{"Investments & savings","Stocks, ETF & Crypto"};
    if(has(hay,"zakat|fitrah|ppz|lembaga zakat|maiwp"))return new String[]{"Family & gifts","Zakat & Fitrah"};
    if(has(hay,"charity|donation|derma|sumbangan|tabung kebajikan|mercy malaysia|makna|yayasan|wwf malaysia"))return new String[]{"Family & gifts","Charity & Donations"};
    if(has(hay,"lhdn|hasil|cp38|cp500|pcb|cukai pendapatan|tax agency|harbour tax|income tax|byrhasil|mytax"))return new String[]{"Taxes & government","LHDN Income Tax / PCB"};
    if(has(hay,"stamp duty|duti setem|government services|govt services|govt service tax|e-stamping"))return new String[]{"Taxes & government","Government Service & Stamp Duty"};
    if(has(hay,"cukai pintu|cukai tanah|cukai taksiran|dbkl|mbpj|mbsj|mpsj|mpkj"))return new String[]{"Housing & property","Cukai Pintu & Cukai Tanah"};
    if(has(hay,"coway|cuckoo|lg puricare|ikea|ssf|hooga|akemi"))return new String[]{"Housing & property","Furniture & Appliances"};
    if(has(hay,"machines|switch|senheng|senq|harvey norman|all it|tmt|thundermatch|challenger|courts|xiaomi|samsung|apple store|sony|dyson|anker|laptop|smartphone|iphone|ipad"))return new String[]{"Shopping","Electronics & Gadgets"};
    if(has(hay,"uniqlo|padini|brands outlet|h&m|zara|cotton on|parkson|isetan|seibu|sogo|bata|charles & keith|christy ng|bonia|carlo rino|skechers|nike|adidas|puma|love bonito"))return new String[]{"Shopping","Clothing, Bags & Footwear"};
    if(has(hay,"sephora|innisfree|bath & body|l'occitane|loccitane|the body shop|kiehl's|kiehls|sasa|olive young|skintific"))return new String[]{"Shopping","Beauty, Skincare & Grooming"};
    if(has(hay,"gsc|golden screen|tgv|mmcineplex|mbo|dadi cinema|ticket2u|cloudjoi|golive asia|cinema"))return new String[]{"Entertainment & travel","Movies, Concerts & Events"};
    if(has(hay,"airasia|malaysia airlines|batik air|malindo|firefly|scoot|klook|trip\\.com|expedia|redbus|easybook|maswings"))return new String[]{"Entertainment & travel","Flights & Transport"};
    if(has(hay,"agoda|booking\\.com|airbnb|traveloka|hotel|resort"))return new String[]{"Entertainment & travel","Hotel & Holiday Stay"};
    if(has(hay,"steam|playstation|ps5|psn|nintendo|eshop|xbox|valorant|codashop|razer gold|garena|hoyoverse|pop\\s*mart|popmart|miniso|toy world|hobbies"))return new String[]{"Entertainment & travel","Games & Hobbies"};
    if(has(hay,"shopee|lazada|tiktok shop|tiktok|zalora|taobao|temu|shein|amazon|pg mall"))return new String[]{"Shopping","Online Marketplace (Shopee / Lazada / TikTok)"};
    return new String[]{"Miscellaneous","Auto-Captured (Uncategorized)"};
  }

  /** Hybrid rules + hierarchical offline sparse TF-IDF classifier for Malaysian merchants. Returns {category, subcategory}. */
  public static String[] classify(String merchant,String source,String text){
    String[] ruleHit=classifyRulesOnly(merchant,source,text);
    if(!ruleHit[0].equals("Miscellaneous"))return ruleHit;
    boolean hasRichBody=text!=null&&text.trim().length()>=20;
    MlClassifier.Prediction ml=MlClassifier.predict(merchant,text);
    if(ml!=null&&ml.activeSignals>=3&&(hasRichBody
        ?(ml.probability>=0.08||(ml.categoryProbability>=0.22&&ml.categoryMargin>=0.06))
        :((ml.probability>=0.24&&ml.margin>=0.06)||(ml.categoryProbability>=0.24&&ml.categoryMargin>=0.08)))){
      return new String[]{ml.category,ml.subcategory};
    }
    return ruleHit;
  }
}
