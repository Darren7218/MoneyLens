package app.moneylens;
import org.json.*;
import java.util.Locale;
import java.util.regex.*;
import java.time.LocalDate;

/** Payment notification parser supporting TNG eWallet, ShopeePay, SPayLater, Atome, Boost PayFlex, GXBank, AEON Bank, Ryt Bank, DuitNow QR, FPX, JomPAY, BNPL, PTPTN and Malaysian bank formats. */
public final class PaymentParser {
  /** Curated registry of verified Google Play packages for Malaysian banks, e-wallets, BNPL & payment apps. */
  public static final String[][] KNOWN_APPS={
    {"my.com.tngdigital.ewallet","Touch 'n Go eWallet","wallet"},
    {"com.maybank2u.life","MAE by Maybank2u","bank"},
    {"com.shopee.my","Shopee (SPayLater / ShopeePay)","bnpl"},
    {"com.shopee.pay.my","ShopeePay MY","bnpl"},
    {"com.atome.my","Atome BNPL","bnpl"},
    {"com.grabtaxi.passenger","Grab (GrabPay / PayLater)","bnpl"},
    {"my.com.myboost","Boost / Boost PayFlex","bnpl"},
    {"co.myboostbank.app","Boost Bank","bank"},
    {"my.com.gxbank.mobile","GXBank","bank"},
    {"my.com.aeonbank.app","AEON Bank","bank"},
    {"my.com.rytbank","Ryt Bank","bank"},
    {"my.com.setel.mobile","Setel","wallet"},
    {"com.bigpay.mobile","BigPay","wallet"},
    {"com.cimb2u.octo","CIMB OCTO MY","bank"},
    {"my.com.publicbank.mypb","MyPB by Public Bank","bank"},
    {"my.com.rhb.mobilebank","RHB Mobile Banking","bank"},
    {"my.com.hongleong.connect","HLB Connect","bank"},
    {"com.ambank.ambankmobile","AmOnline","bank"},
    {"my.com.bankislam.bimbmobile","BIMB Mobile","bank"},
    {"com.uob.tmrwmy","UOB TMRW Malaysia","bank"},
    {"com.ocbc.malaysia","OCBC Malaysia","bank"},
    {"com.hsbc.hsbcmalaysia","HSBC Malaysia","bank"},
    {"com.sc.mobile.my","SC Mobile Malaysia","bank"},
    {"my.com.bsn.mybsn","myBSN","bank"},
    {"com.deliveryhero.foodpanda","foodpanda Malaysia","delivery"},
    {"com.lazada.android","Lazada (LazPayLater)","bnpl"},
    {"com.zhiliaoapp.musically","TikTok (TikTok Shop / PayLater)","bnpl"},
    {"com.kfit.fave","Fave / FavePay","wallet"},
    {"com.shopback.app","ShopBack","wallet"}
  };
  private static final String[][] KNOWN_APP_ALIASES={
    {"com.shopee.shopeepay","ShopeePay MY","bnpl"},
    {"com.grab.passenger","Grab (GrabPay / PayLater)","bnpl"},
    {"com.cimb.octomy","CIMB OCTO MY","bank"},
    {"com.pbb.mypb","MyPB by Public Bank","bank"},
    {"my.com.maybank2u.m2umobile","Maybank2u","bank"},
    {"com.rhbgroup.rhbmobilebanking","RHB Mobile Banking","bank"},
    {"com.touchngo.ewallet","Touch 'n Go eWallet","wallet"}
  };
  public static String[] knownAppInfo(String pkg){
    if(pkg==null||pkg.isEmpty())return null;
    for(String[] row:KNOWN_APPS)if(row[0].equals(pkg))return row;
    for(String[] row:KNOWN_APP_ALIASES)if(row[0].equals(pkg))return row;
    return null;
  }
  public static String knownAppLabel(String pkg){
    String[] info=knownAppInfo(pkg);
    return info!=null?info[1]:pkg;
  }

  private static final String NUM="([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])";
  private static final Pattern AMOUNT=Pattern.compile("(?i)(?:(?:RM|MYR)[\\s\\u00A0]*"+NUM+"|(?<![0-9:/-])"+NUM+"[\\s\\u00A0]*(?:RM|MYR)(?![A-Za-z]))");
  private static final Pattern FOREIGN_AMT=Pattern.compile("(?i)\\b(SGD|USD|THB|JPY|IDR|EUR|GBP|AUD|CNY|HKD|TWD|KRW|VND)[\\s\\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])");
  private static final Pattern TNG_PAID=Pattern.compile("(?i)\\byou\\s+have\\s+paid\\s+(?:RM|MYR)\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s+(?:at|to)\\s+([^\\r\\n]+)");
  private static final Pattern PURCHASE_AT=Pattern.compile("(?i)\\bfor\\s+(?:your\\s+)?(?:recent\\s+)?(?:purchase|order|transaction|payment)\\s+(?:at|to|from)\\s+([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern MALAY_PAID=Pattern.compile("(?im)(?:^|\\b)(?:pembayaran|bayaran)(?:\\s+telah)?\\s+berjaya(?:[ \\t]+(?:dibuat[ \\t]+)?(?:kepada|ke|di)\\b|[ \\t]*!)[ \\t:\\-!]*([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern LABELED_MERCHANT=Pattern.compile("(?im)(?:^|\\b)(?:merchant(?:\\s+name)?|seller|store|outlet|shop|vendor|biller(?:\\s+name)?|payee|recipient|paid\\s+to|payment\\s+to|pembayaran(?:\\s+telah\\s+berjaya)?\\s+(?:dibuat\\s+)?kepada|bayaran\\s+kepada|dibayar\\s+kepada|penerima|peniaga|gerai|kedai|penyertaan|perkhidmatan|perniagaan|nota\\s+pembayaran)\\s*:\\s*[\\r\\n\\t ]*([\\p{L}0-9#][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern MERCHANT_TO=Pattern.compile("(?i)\\b(?:at|to|kepada|di)\\s+([\\p{L}0-9 &'._/()#,+*-]{2,80})");
  private static final Pattern MERCHANT_FOR=Pattern.compile("(?i)\\bfor\\s+(?:your\\s+)?(?:(?:recent|monthly|install?ment|bnpl)\\s+)?([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern REFUND_FROM=Pattern.compile("(?i)\\b(?:refund(?:ed)?|reversal|reversed|chargeback|bayaran\\s+balik(?!\\s+(?:pinjaman|loan|ptptn|hutang|pembiayaan|ansuran|kredit))|pulangan(?:\\s+(?:bayaran|wang|tunai|dana))?)\\b[^\\r\\n]{0,48}?\\b(?:from|dari|daripada|at|di)\\s+([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern MERCHANT_REFUND_PREFIX=Pattern.compile("(?i)^(?:refund(?:ed)?|reversal|reversed|chargeback|bayaran\\s+balik(?!\\s+(?:pinjaman|loan|ptptn|hutang|pembiayaan|ansuran|kredit))|pulangan(?:\\s+(?:bayaran|wang|tunai))?)\\s*(?:of|for|from|at|to|dari|daripada|[-:])\\s+");
  private static final Pattern MERCHANT_FROM=Pattern.compile("(?i)\\b(?:from|dari(?:pada)?)\\s+([\\p{L}0-9 &'._/()#,+*-]{2,80})");
  private static final Pattern LEADING_SUBJECT=Pattern.compile("(?im)^\\s*([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,60})\\s+has\\s+(?:deducted|charged|debited|received)\\b");
  private static final Pattern VERBLESS_PREP=Pattern.compile("(?i)(?:RM|MYR)\\s*"+NUM+"\\)?(?:\\s+(?:at|to|di|kepada)\\s+|[ \\t]*[@\\-\\u2013\\u2014][ \\t]*)([\\p{L}0-9][\\p{L}0-9 &'._/()#,+*-]{1,79})");
  private static final Pattern VERBLESS_CAPS=Pattern.compile("(?:RM|MYR)\\s*"+NUM+"\\s+([A-Z0-9][A-Z0-9&'._/()#+*-]*(?:[ ]+[A-Z0-9&'._/()#+*-]+){0,6})");
  private static final Pattern GATEWAY_PREFIX=Pattern.compile("(?im)(?:^|\\b)(?:pay|dnqr|fpx|shopeepay|shopeep|spaylater|spay|ipay88|razerms|tngd?|mydebit|cimbl|visa|mc|pos|qr|grab)\\*\\s*([\\p{L}0-9][^\\r\\n]{1,70})");
  private static final Pattern CJK_COMPLETED=Pattern.compile("(?:已支付|付款成功|支付成功|到账成功|转账成功|已完成|完成支付|成功完成|收到|扣款|已消费|已退款|退款成功|退款到账|已退回)");
  private static final Pattern CJK_TRIGGER=Pattern.compile("(?:已支付|付款成功|支付成功|到账成功|转账成功|已完成|完成支付|成功完成|收到|扣款|已消费|令吉|已退款|退款成功|退款到账|已退回)");
  /** Security advice footers ("Never share your TAC/OTP") are removed before any filtering. */
  private static final Pattern FOOTER=Pattern.compile("(?i)\\b(?:never|do\\s+not|don'?t|jangan)\\s+(?:share|disclose|reveal|give|kongsi|dedahkan|berikan)\\b[^.!\\n]*");
  private static final Pattern EXCLUDE_BEFORE=Pattern.compile("(?i)\\b(?:balance|bal|baki|limit|cashback|cash\\s+back|reward|rewards|points|pts|rebate|earned|earn|available|avail|remaining|outstanding|saved|worth|up\\s+to|min(?:imum)?\\s+spend)\\b[^0-9]*$");
  private static final Pattern EXCLUDE_AFTER=Pattern.compile("(?i)^\\s*(?:cashback|cash\\s+back|reward|rewards|points|pts|rebate|back)\\b");
  private static final Pattern TOTAL_BEFORE=Pattern.compile("(?i)\\b(?:total|jumlah(?:\\s+(?:perlu\\s+)?dibayar|\\s+bayaran|\\s+keseluruhan)?|grand\\s+total|amount\\s+paid|bayaran\\s+jumlah)\\b[^0-9a-z\\r\\n]*$");
  private static final Pattern PAY_BEFORE=Pattern.compile("(?i)\\b(?:paid|pay|payment|repayment|spent|charged|deducted|debited|purchase|purchased|amount|total|of|transfer|transferred|received|credited|refund|refunded|bayaran|dibayar|potongan|sebanyak|deduction|sale)\\b[^0-9a-z]*$");
  private static final Pattern PAY_AFTER=Pattern.compile("(?i)^(?:\\s*(?:at|to|di|kepada|has\\s+been|was|deducted|charged|paid|debited|processed|confirmed|settled)\\b|[ \\t]*[@\\-\\u2013\\u2014])");
  private static final Pattern P_HARD_FAIL_EN=Pattern.compile("\\b(declined|failed|unsuccessful|gagal|rejected|tidak\\s+berjaya|tidak\\s+diluluskan|baki\\s+tidak\\s+mencukupi|insufficient\\s+(?:funds|balance)|unable\\s+to\\s+process)\\b");
  private static final Pattern P_HARD_FAIL_CJK=Pattern.compile("(?:扣款失败|付款失败|支付失败|未扣款|余额不足|已被拒绝|交易失败)");
  private static final Pattern P_CANCELLED_EN=Pattern.compile("\\b(cancelled|canceled|dibatalkan)\\b");
  private static final Pattern P_CANCELLED_CJK=Pattern.compile("(?:已取消|交易已取消)");
  private static final Pattern P_REFUND_DONE_EN=Pattern.compile("\\b(refund(?:ed)?|credited(?:\\s+back)?|pulangan(?:\\s+(?:bayaran|wang|tunai|dana))?|bayaran\\s+balik(?!\\s+(?:pinjaman|loan|ptptn|hutang|pembiayaan|ansuran|kredit))|dikembalikan|dipulangkan)\\b");
  private static final Pattern P_REFUND_DONE_CJK=Pattern.compile("(?:已退款|退款成功|退款到账)");
  private static final Pattern P_OTP_CODE_PROMPT=Pattern.compile("(?:\\b(?:otp|tac|pac|3ds|3d.?secure)\\b[:\\s-]*(?:is\\s+|no\\.?\\s*)?[0-9]{4,8}\\b|\\b[0-9]{4,8}\\s+(?:is\\s+your|untuk\\s+pengesahan)\\s+(?:otp|tac|3ds|code|kod)\\b|\\b(?:enter|key\\s+in|masukkan)\\s+(?:your\\s+)?(?:otp|tac|3ds|code|kod)\\b|\\b(?:kod\\s+pengesahan|kod\\s+keselamatan|pengesahan\\s+pembayaran|sila\\s+sahkan|sahkan\\s+pembayaran)\\b)");
  private static final Pattern P_OTP_CODE_CJK=Pattern.compile("(?:验证码|动态口令|动态码|安全码)");
  private static final Pattern P_OTP_ANY=Pattern.compile("\\b(otp|tac|3ds|3d.?secure|one.?time\\s+(?:pin|password|passcode)|verification\\s+code|security\\s+code|kod\\s+pengesahan|kod\\s+keselamatan|pengesahan\\s+pembayaran|sila\\s+sahkan|sahkan\\s+pembayaran)\\b");
  private static final Pattern P_OTP_PASSIVE_DONE=Pattern.compile("\\b(?:(?:approved|verified|authenticated|authori[sz]ed)\\s+(?:via|with|by)\\s+(?:3ds|3d.?secure|secure2u|otp|tac)|(?:3ds|3d.?secure|tac|otp|secure2u)\\s+(?:verified|authenticated|approved|authori[sz]ed))\\b");
  private static final Pattern P_STRONG_COMPLETED=Pattern.compile("(?:\\byou have paid\\b|\\bpaid\\b|\\bsuccessful\\b|\\bsuccessfully\\b|\\bsucceeded\\b|\\bcompleted\\b|\\bconfirmed\\b|\\bsettled\\b|\\bprocessed\\b|\\bapproved\\b|\\bdonated\\b|\\bdeducted\\b|\\bcharged\\b|\\bdebited\\b|\\bcredited\\b|\\bdikreditkan\\b|\\breceived\\b|\\brepaid\\b|(?<!tidak\\s)\\bberjaya\\b|\\bditerima\\b|\\bmenerima\\b|\\bdibayar\\b|\\bmembayar\\b|\\bselesai\\b|\\blulus\\b|(?<!not\\s)(?<!pre-)(?<!pre\\s)\\bauthori[sz]ed\\s+(?:a\\s+payment|via\\s+(?:3ds|3d.?secure|secure2u|otp|tac))\\b|\\bspent\\b|\\brefunded\\b|\\breversed\\b|\\bchargeback\\b|\\bcredited\\s+back\\b|\\bdikembalikan\\b|\\bdipulangkan\\b|\\bbayaran\\s+balik\\b|\\bpulangan\\s+(?:bayaran|wang|tunai|dana)\\b|\\brefund\\s+(?:of\\s+(?:rm|myr)|from|processed|completed|issued|approved)\\b)");
  private static final Pattern P_PROMO_EN=Pattern.compile("\\b(voucher|baucar|kupon|promo|promosi|promotion|discount|diskaun|tawaran|offer|deal|deals|lucky\\s+draw|cabutan\\s+bertuah|win|redeem|claim\\s+now|get\\s+up\\s+to|enjoy|limited\\s+time|flash\\s+sale|min(?:imum)?\\s+spend|perbelanjaan\\s+minimum|menanti\\s+anda|untuk\\s+dapatkan)\\b");
  private static final Pattern P_PROMO_CJK=Pattern.compile("(?:优惠券|代金券|促销|立减|满[0-9]+减)");
  private static final Pattern P_SPEND_PROMO_TRAP=Pattern.compile("\\b(?:belanja|spend)\\b[^\\r\\n]*?\\b(?:untuk\\s+dapatkan|to\\s+(?:get|earn|win|receive)|rebat|cashback|rebate|voucher|baucar)\\b");
  private static final Pattern P_PAYMENT_PREP_HINT=Pattern.compile("\\bpayment\\s+(?:of|to|confirmation)\\b");
  private static final Pattern P_OTP_WORD=Pattern.compile("\\b(secure2u|authorise|authorize|approve|approval)\\b");
  private static final Pattern P_PENDING_EN=Pattern.compile("\\b(pending|processing|pre.?authori[sz]ation|awaiting|pegangan\\s+sementara|sedang\\s+diproses|pra.?kebenaran|tertunda)\\b");
  private static final Pattern P_PENDING_CJK=Pattern.compile("(?:待处理|预授权|处理中|冻结中)");
  private static final Pattern P_PENDING_REQ=Pattern.compile("\\b(requested|scheduled|menunggu)\\b");
  private static final Pattern P_BILL_REMINDER_EN=Pattern.compile("\\b(peringatan\\s+bil|tarikh\\s+akhir|akan\\s+tamat\\s+tempoh|bayaran\\s+minimum|penyata\\s+(?:bulanan|anda)|monthly\\s+statement|statement\\s+balance|statement\\b[^\\r\\n]*?\\bis\\s+ready|payment\\s+reminder|bill\\s+reminder|minimum\\s+payment|pay\\s+before\\s+due|avoid\\s+late\\s+fee|elak(?:kan)?\\s+caj\\s+lewat|(?:will\\s+be|is)\\s+due\\s+on|due\\s+(?:tomorrow|today|soon|in\\s+[0-9]+\\s+days?))\\b");
  private static final Pattern P_BILL_REMINDER_CJK=Pattern.compile("(?:账单提醒|最低还款|到期还款|可用余额|账户余额)");
  private static final Pattern P_REFUND_GUARD_WORD=Pattern.compile("\\b(?:refund|pulangan|bayaran\\s+balik)\\b");
  private static final Pattern P_REFUND_GUARD_REQ=Pattern.compile("\\b(?:request(?:ed)?|permohonan|dimohon|policy|polisi)\\b");
  private static final Pattern P_REFUND_GUARD_OK=Pattern.compile("\\b(?:approved|completed|processed|credited|dikembalikan|dipulangkan|lulus|selesai)\\b");
  private static final Pattern P_TRIGGER=Pattern.compile("\\b(paid|pay|payment|repayment|repaid|instal?ment|ansuran|purchase|pembelian|dibeli|spent|debited|debit|deducted|deduct|deduction|charged|charge|sale|credited|dikreditkan|received|refund|refunded|reversal|reversed|chargeback|bayaran\\s+balik|pulangan|dikembalikan|dipulangkan|pembatalan\\s+transaksi|transfer|transferred|withdrawal|top.?up|reload|reloaded|toll|paydirect|rfid|duitnow|fpx|jompay|giro|ibft|auto.?debit|paywave|contactless|qrpay|qr\\s*pay|bayar|bayaran|pembayaran|dibayar|membayar|diterima|menerima|transaksi|potongan|selesai|transaction|processed|completed|settled|donated|fx\\s*rate|exchange\\s+rate|currency\\s+conversion|gxbank|aeon\\s*bank|ryt\\s*bank|boost\\s*bank|shopeepay|spaylater|lazpaylater|paylater|payflex|atome|grabpay|grabpaylater|bnpl|setel|ptptn|lhdn|tax\\s+relief)\\b");
  private static final Pattern P_NON_REFUND_EN=Pattern.compile("\\b(?:non.?refundable|not\\s+refundable|no\\s+refund|tidak\\s+boleh\\s+dikembalikan|ptptn|pinjaman|hutang|loan\\s+repayment)\\b");
  private static final Pattern P_NON_REFUND_CJK=Pattern.compile("(?:不可退款|不能退款|不退不换|恕不退款)");
  private static final Pattern P_RF_STRIP_TAIL=Pattern.compile("(?i)\\s+(?:to|ke)\\s+(?:touch\\s*'?n\\s*go|tng|mae|maybank2u?|cimb|rhb|hlb|gxbank|shopeepay|boost|bigpay|aeon\\s*bank|ryt\\s*bank|wallet|e.?wallet|account|akaun|dompet|bank|card|kad).*");
  private static final Pattern P_GW_STRIP_TAIL=Pattern.compile("(?i)(?:,|\\s*(?:rmyr|myr|rm)(?=\\s*[0-9])|\\b(?:rmyr|myr|rm)\\b|[0-9]+\\.[0-9]{2}|\\b[0-9]{2}[/-][0-9]{2}(?:[/-][0-9]{2,4})?\\b|\\s+(?:charge|paid|completed|approved|confirmed)\\b).*");
  private static final Pattern P_BAD_MF=Pattern.compile("(?i)^(?:ref|reference|order|payment|your|my|this|that|a)\\b.*");
  private static final Pattern P_BAD_LS=Pattern.compile("(?i)^(?:maybank(?:2u)?|mae|cimb|rhb|public\\s*bank|hong\\s*leong|ambank|bank\\s+islam|gxbank|aeon\\s*bank|ryt\\s*bank|tng|touch\\s*'?n\\s*go|boost|grab|shopee|shopeepay|atome|your\\s+bank|system)$");
  private static final Pattern P_CJK_MERCH_PAID=Pattern.compile("(?:已支付|付款成功|支付成功|已消费|扣款)\\s*(?:RM|MYR)\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s+([\\p{L}0-9][\\p{L}0-9 .,'&_-]{1,50})");
  private static final Pattern P_LINE_SPLIT=Pattern.compile("\\r?\\n");
  public static final String LOANS="Loans, BNPL & Credit";

  public static JSONObject parse(String text,String source)throws Exception{
    if(text==null||text.length()>4000)return null;
    String normalized=text.replace('\u00A0',' ').trim();
    Matcher fm0=FOOTER.matcher(normalized);
    String core=fm0.find()?fm0.replaceAll(" ").trim():normalized;
    String low=core.toLowerCase(Locale.ROOT);
    if(P_HARD_FAIL_EN.matcher(low).find()||P_HARD_FAIL_CJK.matcher(core).find())return null;
    if((P_CANCELLED_EN.matcher(low).find()||P_CANCELLED_CJK.matcher(core).find())
      &&!(P_REFUND_DONE_EN.matcher(low).find()||P_REFUND_DONE_CJK.matcher(core).find()))return null;
    boolean strongCompleted=P_STRONG_COMPLETED.matcher(low).find()||CJK_COMPLETED.matcher(core).find();
    if(P_OTP_CODE_PROMPT.matcher(low).find()||P_OTP_CODE_CJK.matcher(core).find())return null;
    if(P_OTP_ANY.matcher(low).find()&&!(strongCompleted&&P_OTP_PASSIVE_DONE.matcher(low).find()))return null;
    boolean promoWord=P_PROMO_EN.matcher(low).find()||P_PROMO_CJK.matcher(core).find();
    boolean spendPromoTrap=P_SPEND_PROMO_TRAP.matcher(low).find();
    if((!strongCompleted&&promoWord)||spendPromoTrap)return null;
    boolean completed=strongCompleted||P_PAYMENT_PREP_HINT.matcher(low).find();
    if(P_OTP_WORD.matcher(low).find()&&!strongCompleted)return null;
    if(P_PENDING_EN.matcher(low).find()||P_PENDING_CJK.matcher(core).find()
      ||(!strongCompleted&&P_PENDING_REQ.matcher(low).find()))return null;
    if(!strongCompleted&&(P_BILL_REMINDER_EN.matcher(low).find()||P_BILL_REMINDER_CJK.matcher(core).find()))return null;
    if(P_REFUND_GUARD_WORD.matcher(low).find()&&P_REFUND_GUARD_REQ.matcher(low).find()&&!P_REFUND_GUARD_OK.matcher(low).find())return null;
    boolean trigger=P_TRIGGER.matcher(low).find()||CJK_TRIGGER.matcher(core).find();
    Matcher vp=VERBLESS_PREP.matcher(core),vc=VERBLESS_CAPS.matcher(core);
    boolean prep=vp.find(),caps=!prep&&vc.find(),gwTrigger=GATEWAY_PREFIX.matcher(core).find();
    if(!trigger&&!prep&&!caps&&!gwTrigger)return null;

    Matcher m=AMOUNT.matcher(core);JSONArray candidates=new JSONArray();long primary=0,firstIncluded=0,totalMatch=0;int count=0;boolean primaryFound=false,totalFound=false;
    while(m.find()){
      String amtTok=m.group(1)!=null?m.group(1):m.group(2);
      long v;try{v=Ledger.minor(amtTok.replace(",",""));}catch(IllegalArgumentException ignored){continue;}
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
    boolean nonRefGuard=P_NON_REFUND_EN.matcher(low).find()||P_NON_REFUND_CJK.matcher(core).find();
    boolean refundCue=!nonRefGuard&&(P_REFUND_CUE.matcher(low).find()||P_REFUND_DONE_CJK.matcher(core).find());
    String kind=refundCue?"refund":(!outgoingCue&&P_INCOME_CUE.matcher(low).find())?"income":"expense";
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
      merchant="";Matcher mm=MERCHANT_TO.matcher(core);
      if(refundCue&&!validMerchant(merchant)){Matcher rf=REFUND_FROM.matcher(core);while(rf.find()){String rawRf=P_RF_STRIP_TAIL.matcher(rf.group(1)).replaceAll("");String c=cleanMerchant(rawRf);if(validMerchant(c)){merchant=c;break;}}}
      if("income".equals(kind)&&!validMerchant(merchant)){Matcher inf=MERCHANT_FROM.matcher(core);while(inf.find()){String c=cleanMerchant(inf.group(1));if(validMerchant(c)){merchant=c;break;}}}
      if(!validMerchant(merchant))while(mm.find()){String c=cleanMerchant(mm.group(1));if(validMerchant(c)){merchant=c;break;}}
    }
    if(!validMerchant(merchant)&&gwTrigger){
      Matcher gw=GATEWAY_PREFIX.matcher(core);
      if(gw.find()){
        String rawGw=P_GW_STRIP_TAIL.matcher(gw.group(1)).replaceAll("");
        String c=cleanMerchant(rawGw);
        if(validMerchant(c))merchant=c;
      }
    }
    if(!validMerchant(merchant)){
      merchant="";Matcher mf=MERCHANT_FOR.matcher(core);
      while(mf.find()){String c=cleanMerchant(mf.group(1));if(validMerchant(c)&&!P_BAD_MF.matcher(c).matches()){merchant=c;break;}}
    }
    if(!validMerchant(merchant)){
      Matcher ls=LEADING_SUBJECT.matcher(core);
      if(ls.find()){
        String c=cleanMerchant(ls.group(1));
        if(validMerchant(c)&&!P_BAD_LS.matcher(c).matches())merchant=c;
      }
    }
    if(!validMerchant(merchant)){
      merchant="";Matcher mf2=MERCHANT_FROM.matcher(core);
      while(mf2.find()){String c=cleanMerchant(mf2.group(1));if(validMerchant(c)){merchant=c;break;}}
    }
    if(!validMerchant(merchant)&&prep){String c=cleanMerchant(vp.group(2));if(validMerchant(c))merchant=c;}
    if(!validMerchant(merchant)&&caps){String c=cleanMerchant(vc.group(2));if(validMerchant(c))merchant=c;}
    if(!validMerchant(merchant)){
      Matcher cjkM=P_CJK_MERCH_PAID.matcher(core);
      if(cjkM.find()){String c=cleanMerchant(cjkM.group(1));if(validMerchant(c))merchant=c;}
    }
    if(!validMerchant(merchant)&&core.contains("\n")){
      String[] lines=P_LINE_SPLIT.split(core);
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
    if(merchant.isEmpty()){
      String srcLow=String.valueOf(source).toLowerCase(Locale.ROOT);
      if(low.contains("spaylater"))merchant="SPayLater";
      else if(low.contains("lazpaylater"))merchant="LazPayLater";
      else if(low.contains("tiktok paylater"))merchant="TikTok PayLater";
      else if(low.contains("payflex"))merchant="Boost PayFlex";
      else if(low.contains("grabpaylater")||low.contains("grab paylater"))merchant="GrabPayLater";
      else if(low.contains("atome")||srcLow.contains("atome"))merchant="Atome";
      else if(low.contains("shopeepay")||srcLow.contains("shopeepay"))merchant="ShopeePay";
      else if(low.contains("shopee")||srcLow.contains("shopee"))merchant="Shopee MY";
      else if(low.contains("foodpanda")||srcLow.contains("foodpanda"))merchant="foodpanda";
      else merchant="Detected payment";
    }

    String origMerchant=merchant;
    MerchantCanonicalizer.Result canon=!"Detected payment".equals(origMerchant)?MerchantCanonicalizer.canonicalize(origMerchant,""):null;
    boolean canonStrong=canon!=null&&canon.matchedCatalog&&canon.similarity>=0.62;
    if(canonStrong&&MerchantCanonicalizer.isNoisyTruncatedSurface(origMerchant)){
      merchant=canon.canonicalName;
    }
    String[] cs=kind.equals("income")?new String[]{"Other income",""}:classify(origMerchant,source,low);
    if(!kind.equals("income")&&canonStrong&&"Miscellaneous".equals(cs[0])){
      String[] ccs=classify(canon.canonicalName,source,low);
      if(!"Miscellaneous".equals(ccs[0]))cs=ccs;
    }
    JSONObject d=new JSONObject().put("id",Ledger.id()).put("amount",amount).put("primaryAmount",amount).put("kind",kind).put("merchant",merchant).put("category",cs[0]).put("date",LocalDate.now().toString()).put("source",source).put("state","review").put("reason",reason).put("amountCandidates",candidates).put("adapter","generic-5").put("revision",1).put("created",System.currentTimeMillis());
    if(canonStrong)d.put("canonicalMerchant",canon.canonicalName).put("canonicalScore",Math.round(canon.similarity*1000.0)/1000.0);
    if(!cs[1].isEmpty())d.put("subcategory",cs[1]);
    if(!origCur.isEmpty()&&kind.equals("expense")){
      d.put("originalCurrency",origCur).put("originalAmount",origAmt).put("exchangeRate",java.math.BigDecimal.valueOf(amount,2).divide(new java.math.BigDecimal(origAmt),8,java.math.RoundingMode.HALF_UP).toPlainString());
    }
    if(kind.equals("transfer")){
      boolean isReload=P_RELOAD_CUE.matcher(low).find();
      if(isReload){
        d.put("reload",true);
        Matcher fb=P_FROM_BANK.matcher(core);
        if(fb.find())d.put("transferFromHint",fb.group(1).trim());
      }
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

  private static final Pattern P_VM1=Pattern.compile("(?i)^(?:rm|myr)\\s*[0-9,]+(?:\\.[0-9]{1,2})?.*");
  private static final Pattern P_VM2=Pattern.compile("(?i)^(?:your\\s+(?:account|bank|card|credit|debit|ewallet|e-wallet|wallet|spaylater|atome|boost|shopeepay|payflex|balance|recent|last|order)|a\\s+(?:monthly|recurring|scheduled)|the\\s+selected\\s+account|account\\s+ending|you|anyone|others)\\b.*");
  private static final Pattern P_VM3=Pattern.compile("(?i)^(your|account|ewallet|e-wallet|card|wallet|#[0-9a-z-]+|[0-9]{1,2}:[0-9]{2}.*|[0-9 /.:-]+)$");
  private static final Pattern P_VM4=Pattern.compile("(?i)^(?:[0-9]{1,2}(?:st|nd|rd|th)?[-/\\s.]+(?:[0-9]{1,2}|[a-z]{3,10})[-/\\s.,]+(?:20)?[0-9]{2}|20[0-9]{2}[-/][0-9]{1,2}[-/][0-9]{1,2})$");
  private static final Pattern P_VM5=Pattern.compile("(?i)^(?:lot|no\\.?|unit|level|lvl|block|blk|floor|flr|jalan|jln|lorong|lrg)\\s*[#0-9a-z-]{1,6}$");
  private static final Pattern P_VM6=Pattern.compile("(?i)^(?:bayar\\s+dengan|bayaran|pembayaran|transaksi|payment|transaction|notifikasi|notis|nota(?:\\s+pembayaran)?|berita(?:\\s+bayaran)?|carta\\s+kredit|kad\\s+kredit)(?:\\s+(?:berjaya|successful|completed|approved|confirmed|dibahalkan|dibatalkan|prs))?$");
  private static final Pattern P_SSM_REG=Pattern.compile("(?i)(?:\\s*\\(\\s*(?:[0-9]{5,8}-[A-Za-z]|[0-9]{12}|co\\.?\\s*reg[^)]*)\\s*\\))+\\s*$");
  private static final Pattern P_SSM_CORP=Pattern.compile("(?i)(?:,?\\s+(?:sdn\\.?\\s*bhd\\.?|sendirian\\s+berhad|bhd\\.?|berhad|plt|llp))+$");
  private static final Pattern P_CM_BILLER=Pattern.compile("(?i)\\s*\\(?\\s*biller\\s*code.*");
  private static final Pattern P_CM_PFX1=Pattern.compile("(?i)^(?:penyertaan|perkhidmatan|perniagaan|nota\\s+pembayaran)\\s*:\\s*");
  private static final Pattern P_CM_PFX2=Pattern.compile("(?i)^(?:(?:boost|tng|grab|grabpay|shopee|shopeepay|spaylater|atome|mae|maybank)\\s+)?(?:for\\s+)?your\\s+(?:recent\\s+)?(?:purchase|order)\\s+(?:at|from)\\s+");
  private static final Pattern P_CM_TAIL=Pattern.compile("(?i)\\s+(?:has\\s+(?:been\\s+)?(?:received|completed|paid|processed|approved|confirmed|settled|succeeded|refunded|reversed|credited)|telah\\s+(?:dikreditkan|dikembalikan|dipulangkan)|(?:is|was)\\s+(?:successful|completed|approved|processed|confirmed|settled)|successful|successfully|succeeded|completed|processed|confirmed|approved|settled|berjaya|credited\\s+to|(?:credited\\s+|refunded\\s+)?(?:to|ke)\\s+(?:(?:your|my)\\s+)?(?:account|akaun|wallet|dompet|e.?wallet|card|kad|bank|mae|tng|touch\\s*'?n\\s*go|shopeepay|gxbank|boost|bigpay)\\b|debited\\s+from|paid\\s+from|(?:fare|order|transaction|purchase)\\s+(?:on|dated)\\b|(?:sebanyak|berjumlah)\\s+(?:rm|myr)|(?:on|pada)\\s+[0-9]{1,4}[/.-][0-9]{1,2}|(?:on|pada)\\s+[0-9]{1,2}\\s+[a-z]{3}|(?:at|pukul)\\s+[0-9]{1,2}[:.][0-9]{2}|for\\s+(?:a|your|my|the|monthly|recurring|install?ment|ref|reference)\\b|ref\\b|reference|(?:via|melalui)\\s+(?:secure2u|duitnow|fpx|jompay|spaylater|shopee\\s*paylater|shopeepay|atome|grabpay(?:later)?|payflex|lazpaylater|tiktok\\s*paylater|maybank(?:2u)?|mae|tng(?:\\s*e?wallet)?|touch\\s*'?n\\s*go|boost(?:\\s*bank|\\s*payflex)?|rhb|cimb(?:\\s*clicks|\\s*octo)?|public\\s*bank|hong\\s*leong|ambank|bank\\s+islam|gxbank|aeon\\s*bank|ryt\\s*bank|setel|bigpay|qr\\s*pay|qrpay)|(?:using|with|by)\\s+(?:touch|your|spaylater|atome|payflex|grabpaylater|shopeepay|boost|credit\\s+card|debit\\s+card)|from\\s+your|available\\s+balance|avail\\.?\\s*bal|remaining|balance|baki|limit|cashback|earn\\s+[0-9]|reward|points|thank|terima\\s+kasih|never|do\\s+not|jangan|txn|transaction\\s+id|trx).*");
  private static final Pattern P_CM_YOURACCT=Pattern.compile("(?i)^(?:your|my)\\s+(?:account|akaun|wallet|dompet|e.?wallet|card|kad|bank|mae|tng|touch\\s*'?n\\s*go|shopeepay|gxbank|boost|bigpay)\\b.*");
  private static final Pattern P_CM_SENT=Pattern.compile("[.!]\\s+(?=(?:RM|MYR)\\s*[0-9]|\\p{Lu}\\p{Ll}).*");
  private static final Pattern P_CM_PUNCT_END=Pattern.compile("[.,;:!\\-\\u2013\\u2014\\s]+$");
  private static final Pattern P_CM_PUNCT_START=Pattern.compile("^[.,;:!\\-\\u2013\\u2014\\s]+");
  private static final Pattern P_CM_NOGW=Pattern.compile("(?i)^(?:fpx|dnqr|duitnow\\s*qr|duitnow|paynet|ipay88|razerms|razer\\s*ms|molpay|billplz|senangpay|gkash|stripe|curlec|2c2p|revpay|kiple|shopeepay|spaylater|atome|payflex|shopeep|cimbl)\\s*(?:[*_/:-]+\\s*)");
  private static final Pattern P_CM_NOWGW=Pattern.compile("(?i)^(?:tngd?|grab|spay|pyt|sq|pp|shopeep|cimbl)\\s*[*_/:]+\\s*");
  private static final Pattern P_CM_NOADDR=Pattern.compile("(?i)\\s*,\\s*(?:no\\.?\\s*[0-9]|lot\\s+[0-9]|jalan\\b|jln\\b|unit\\b|level\\b|lvl\\b|block\\b|blk\\b|persiaran\\b|taman\\b|bandar\\b|seksyen\\b).*");
  private static final Pattern P_CM_NOPOST=Pattern.compile("(?i),\\s*[\\p{L} ]{2,25}\\s+[0-9]{5}\\b.*");
  private static final Pattern P_CLS_BODY_ADDR=Pattern.compile("(?im)^\\s*(?:(?:no\\.?|lot|unit|block|blk)\\s*[#0-9a-z-]+,?\\s*)?(?:jalan|jln|lorong|lrg|persiaran|taman|tmn|bandar|seksyen|[0-9]{5})\\b.*$");
  private static final Pattern P_CLS_STOP=Pattern.compile("\\b(?:payment|paid|to|at|from|of|for|in|on|via|rm|myr|successful|successfully|completed|approved|confirmed|settled|card|transaction|duitnow|fpx|jompay|detected)\\b");
  private static final Pattern P_CLS_NUMS=Pattern.compile("[0-9.,:\\-/]+");
  private static final Pattern P_CLS_WS=Pattern.compile("\\s+");

  private static boolean validMerchant(String c){
    return c!=null&&c.length()>=2
      &&!P_VM1.matcher(c).matches()
      &&!P_VM2.matcher(c).matches()
      &&!P_VM3.matcher(c).matches()
      &&!P_VM4.matcher(c).matches()
      &&!P_VM5.matcher(c).matches()
      &&!P_VM6.matcher(c).matches();
  }

  static String stripSsmSuffix(String raw){
    if(raw==null)return "";
    String s=raw.trim();
    String noReg=P_SSM_REG.matcher(s).replaceAll("").trim();
    if(noReg.length()>=2)s=noReg;
    String noCorp=P_SSM_CORP.matcher(s).replaceFirst("").trim();
    if(noCorp.length()>=2)s=noCorp;
    return s;
  }

  private static String cleanMerchant(String raw){
    if(raw==null)return "";
    String s=P_CM_BILLER.matcher(raw).replaceAll("");
    s=P_CM_PFX1.matcher(s).replaceFirst("");
    s=P_CM_PFX2.matcher(s).replaceFirst("");
    s=MERCHANT_REFUND_PREFIX.matcher(s).replaceFirst("");
    s=P_CM_TAIL.matcher(s).replaceAll("");
    if(P_CM_YOURACCT.matcher(s).matches())return "";
    s=P_CM_SENT.matcher(s).replaceAll("").trim();
    s=P_CM_PUNCT_START.matcher(P_CM_PUNCT_END.matcher(s).replaceAll("")).replaceAll("").trim();
    String noGw=P_CM_NOGW.matcher(s).replaceFirst("").trim();
    if(noGw.length()>=2)s=noGw;
    String noWalletGw=P_CM_NOWGW.matcher(s).replaceFirst("").trim();
    if(noWalletGw.length()>=2)s=noWalletGw;
    String noAddr=P_CM_NOADDR.matcher(s).replaceAll("").trim();
    if(noAddr.length()>=2)s=noAddr;
    String noPost=P_CM_NOPOST.matcher(s).replaceAll("").trim();
    if(noPost.length()>=2)s=noPost;
    s=stripSsmSuffix(s);
    s=P_CM_PUNCT_START.matcher(P_CM_PUNCT_END.matcher(s).replaceAll("")).replaceAll("").trim();
    if(s.length()>80)s=s.substring(0,80).trim();
    return s;
  }

  private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> HAS_PATTERNS=new java.util.concurrent.ConcurrentHashMap<>();
  private static final Pattern P_OUTGOING_CUE=Pattern.compile("(?:rm|myr)\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s+(?:to|at|for)\\s+|\\b(?:for\\s+your\\s+(?:[a-z0-9\\s]{0,30}?\\b)?(?:purchase|investment|sspn|prs|loan|repayment)|your\\s+(?:recent\\s+)?(?:purchase|order)|purchase\\s+at|from\\s+fpx\\s+to|from\\s+your\\s+account|for\\s+(?:your\\s+)?(?:routine\\s+|annual\\s+)?(?:medical|university|education|tuition|school|childcare|insurance|takaful|sspn|prs|epf|lhdn|tax\\s+relief|self\\s+education)|paid\\s+amount|payment\\s+for|payment\\s+method|payment\\s+to|paid\\s+to|order\\s+(?:id|no)|item|qty|invoice)\\b");
  private static final Pattern P_INCOME_CUE=Pattern.compile("\\b(credited|dikreditkan|received|diterima|menerima)\\b");
  private static final Pattern P_REFUND_CUE=Pattern.compile("\\b(refund(?:ed|s)?|reversal|reversed|chargeback|credited\\s+back|pulangan\\s+(?:bayaran|wang|tunai|dana)|bayaran\\s+balik(?!\\s+(?:pinjaman|loan|ptptn|hutang|pembiayaan|ansuran|kredit))|dikembalikan|dipulangkan|pembatalan\\s+transaksi)\\b");
  private static final Pattern P_TRANSFER_CUE=Pattern.compile("\\b(transfer(?:red|ring)?|top.?up|reload|reloaded|withdrawal)\\b");
  private static final Pattern P_NON_TRANSFER_CUE=Pattern.compile("\\b(duitnow\\s+qr|fpx|jompay|paid|purchase|spent|sale)\\b");
  private static final Pattern P_LINE_SKIP1=Pattern.compile("^(?:notifikasi|notis|nota(?:\\s+pembayaran)?|makluman|berita(?:\\s+bayaran)?|pengesahan|amaran|alert|notice|notification|bayar(?:\\s+dengan|\\s+menggunakan)?|bayaran|pembayaran|transaksi|payment|transaction|kad\\s+kredit|carta\\s+kredit|credit\\s+card|no\\.?\\s*kad|item|barang|produk|harga|perkhidmatan|penyertaan|angsuran|terima\\s+kasih|thank\\s+you|sila\\s+semak).*");
  private static final Pattern P_LINE_SKIP2=Pattern.compile("^(?:rm|myr)?\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s*$");
  private static final Pattern P_LINE_SKIP3=Pattern.compile("^(?:tarikh|date|time|masa|kod\\b|code|ref\\b|reference|rujukan|txn|transaction|order|invoice|barang|item|baki|tunai|cash|balance|cukai|sst|gst|service|subtotal|jumlah|total|contact|hotline|tel|hp|phone|no\\.|lot\\b|jalan\\b|jln\\b|lorong\\b|unit\\b|level\\b|[0-9]+,\\s*(?:jalan|jln|lorong)|[0-9]{1,2}(?:st|nd|rd|th)?[\\s/-]+(?:[0-9]{1,2}|[a-z]{3,10})[\\s/-]+20[0-9]{2}|20[0-9]{2}-[0-9]{2}-[0-9]{2}).*");
  private static final Pattern P_TOLL_CUE=Pattern.compile("\\b(toll|paydirect|rfid)\\b");
  private static final Pattern P_RELOAD_CUE=Pattern.compile("\\b(top.?up|reload|reloaded)\\b");
  private static final Pattern P_FROM_BANK=Pattern.compile("(?i)\\b(?:from|via)\\s+(?:your\\s+)?(maybank(?:2u)?|mae|cimb(?:\\s*clicks|\\s*octo)?|public\\s*bank|mypb|rhb|hong\\s*leong|hlb|ambank|bank\\s+islam|bimb|gxbank|aeon\\s*bank|ryt\\s*bank|uob|ocbc|hsbc|scb|bsn|bigpay|tng|grabpay|boost(?:\\s*bank)?|shopeepay)\\b");
  private static final Pattern P_WALLET_HINT=Pattern.compile("(?i)\\b(tng|touch\\s*'?n\\s*go|grabpay(?:later)?|boost(?:\\s*bank)?|payflex|shopeepay|spaylater|atome|bigpay|mae|gxbank|aeon\\s*bank|ryt\\s*bank|setel|lazpaylater|fave)\\b");
  private static final Pattern FAST_LIKELY_CURRENCY=Pattern.compile("(?i)(?:\\b(?:rm|myr|sgd|usd|eur|gbp|aud|cny|hkd|thb|twd|jpy|krw|idr|vnd)[.:\\s]*[0-9]|[0-9.,]+\\s*(?:rm|myr|sgd|usd|eur|gbp|aud|cny|hkd|thb|twd|jpy|krw|idr|vnd)\\b|\\$[0-9])");
  private static final Pattern FAST_LIKELY_CUE=Pattern.compile("(?i)\\b(?:paid|pay|payment|bayar|bayaran|pembayaran|spent|berbelanja|purchase|pembelian|dibeli|debited|credited|dikreditkan|received|diterima|menerima|charged|transferred|transfer|pindahan|duitnow|fpx|jompay|qrpay|qr\\s*pay|top.?up|reload|reloaded|tambah\\s+nilai|refund|refunded|reversal|reversed|chargeback|bayaran\\s+balik|pulangan|dikembalikan|dipulangkan|transaksi|transaction|resit|receipt|invois|invoice|jumlah|total|subtotal|amount|amaun|cukai|tax|toll|tol|rfid|paydirect|shopeepay|spaylater|lazpaylater|payflex|atome|grab|tng|mae|fave|boost|setel|bigpay|gx|gxbank|aeon|ryt|gentari|ptptn|bnpl|bil|bill)\\b");
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
    return !FAST_LIKELY_OTP.matcher(text).find()&&(FAST_LIKELY_CUE.matcher(text).find()||CJK_TRIGGER.matcher(text).find());
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
    String body=P_CLS_BODY_ADDR.matcher(String.valueOf(text)).replaceAll(" ").toLowerCase(Locale.ROOT);
    String hay=m+" "+String.valueOf(source).toLowerCase(Locale.ROOT)+" "+body;
    boolean bnplHint=has(hay,"spaylater|shopee paylater|grabpaylater|grab paylater|lazpaylater|tiktok paylater|payflex|boost payflex|paylater|pay later|atome");
    boolean bnplRepay=bnplHint&&(has(hay,"(?:spaylater|shopee paylater|grabpaylater|grab paylater|lazpaylater|tiktok paylater|payflex|boost payflex|atome|paylater|pay later)\\s+(?:instal?ment|repayment|bill|statement|ansuran|due)|(?:repayment|ansuran|monthly\\s+(?:bill|statement)|bil\\s+(?:bulanan|spaylater|atome|payflex|grabpaylater)|auto-?deduct)\\s+(?:for\\s+|of\\s+|to\\s+)?(?:spaylater|atome|payflex|grabpaylater|lazpaylater|paylater)|paid\\s+your\\s+(?:spaylater|atome|payflex|grabpaylater|paylater)")||has(m,"spaylater|shopee paylater|grabpaylater|grab paylater|lazpaylater|tiktok paylater|payflex|boost payflex|atome|detected payment"));
    if(has(hay,"sspn|ptptn sspn"))return new String[]{"Investments & savings","SSPN Savings"};
    if(has(hay,"prs|private retirement|simpanan persaraan"))return new String[]{"Investments & savings","PRS (Private Retirement Scheme)"};
    if(has(hay,"ptptn|perbadanan tabung pendidikan"))return new String[]{LOANS,"PTPTN Study Loan"};
    if(bnplRepay)return new String[]{LOANS,"BNPL (SPayLater / GrabPayLater / Atome)"};
    if(has(hay,"credit\\s+card\\s+(?:statement|bill|payment)|penyata\\s+kad\\s+kredit"))return new String[]{LOANS,"Credit Card Bill"};
    if(has(hay,"personal\\s+loan|pinjaman\\s+peribadi"))return new String[]{LOANS,"Personal Loan"};
    if(has(hay,"home\\s+loan|housing\\s+loan|mortgage|pembiayaan\\s+rumah|pinjaman\\s+perumahan"))return new String[]{"Housing & property","Home Loan / Mortgage"};
    if(has(hay,"monthly\\s+rent|house\\s+rent|room\\s+rent|apartment\\s+rent|condo\\s+rent|rental\\s+(?:payment|deposit|fee)|sewa\\s+(?:rumah|bilik|kondo|apartment|kedai)|bilik\\s+sewa|rumah\\s+sewa|tenancy(?:\\s+agreement)?|landlord"))return new String[]{"Housing & property","Rent"};
    if(has(hay,"parents?\\s+allowance|elaun\\s+(?:ibu\\s*bapa|mak|ayah|abah|bapa)|mak\\s*&\\s*ayah|ibu\\s*bapa|duit\\s+belanja\\s+(?:mak|ibu|ayah|abah|parents)"))return new String[]{"Family & gifts","Parents Allowance"};
    if(has(hay,"angpao|ang\\s*pow|duit\\s*raya|e-?duit\\s*raya|red\\s*packet|gift\\s+(?:hamper|box|voucher)|wedding\\s+gift|hantaran|flower\\s+bouquet|bloomthis|flowerchimp|happy\\s+bunch|birthday\\s+gift|hamper"))return new String[]{"Family & gifts","Festive (Angpao / Duit Raya) & Gifts"};
    if(has(m,"credit services|aeon credit|courts|singer|senheng|senq|chan furniture")&&has(hay,"hire\\s+purchase|ansuran|instal?ment"))return new String[]{LOANS,"Hire Purchase / Installment"};
    if(has(hay,"car\\s+loan|auto\\s+loan|hire\\s+purchase|sewa\\s+beli|pinjaman\\s+kereta|a(?:ng|ns)uran\\s+kenderaan"))return new String[]{"Transport","Car Loan Installment"};
    if(has(hay,"bank transfer fee|transfer fee|ibt fee|rentas fee|meps fee|atm fee|duitnow overseas fee|bank\\s+service\\s+fee|monthly\\s+account\\s+fee|fall[- ]below\\s+fee|cash\\s+advance\\s+fee|card\\s+replacement\\s+fee|sms\\s+alert\\s+fee"))return new String[]{"Fees & charges","Bank & Transfer Fee"};
    if(has(hay,"late payment fee|late charge|late reconnection fee|late penalty|late\\s+payment\\s+(?:of|for)\\b|caj\\s+lewat"))return new String[]{"Fees & charges","Late Payment Fee"};
    if(has(hay,"card\\s+annual\\s+fee|annual\\s+(?:debit\\s+|credit\\s+|card\\s+)?fee|annual\\s+membership\\s+fee"))return new String[]{"Fees & charges","Card Annual Fee"};
    if(has(hay,"foreign\\s+currency\\s+fee|overseas\\s+(?:fee|markup|conversion)\\s*fee|cross[- ]border\\s+(?:fee|markup)|fx\\s+(?:fee|conversion\\s+fee|markup)|currency\\s+conversion\\s+fee"))return new String[]{"Fees & charges","Foreign Currency Fee"};
    if(has(hay,"rfid|paydirect|toll|tol|plaza tol|lebuhraya|expressway|highway|smart\\s*tag|smarttag|smart\\s*tunnel|plus\\s+(?:highway|expressway|lebuhraya|toll)|(?:highway|expressway|lebuhraya|plaza\\s+tol)\\s+plus")||has(m,"duke|ldp|kesas|mex|sprint|npe|akleh|besraya|lekas|guthrie|nkve|suke|silk|lpt|wce|skve|latar|grand saga|ekve|dash"))return new String[]{"Transport","Toll (TNG / RFID / PayDirect)"};
    if(has(hay,"parking|flexiparking|flexi parking|jompark|jomparking|setel parking|tng parking|parkhere|parksmart|dbkl parking|mbpj parking|sbs parking|smart selangor parking|parkir|autopay|valet parking"))return new String[]{"Transport","Parking"};
    if(has(m,"setel")||has(hay,"petronas|shell(?!\\s+select)|petron|caltex|bhpetrol|bhp|petrol|fuel|ron95|ron97|diesel|ev charging|chargev|chargestart|charge\\s*ev|chargesini|gentari|jomcharge|chargsv|chargenow|tesla supercharger|dc handal|shell recharge|evconnection|kineta|evlution|tnb electron|setel\\s+(?:fuel|petrol|pump|mesra|ev|charg)"))return new String[]{"Transport","Petrol & EV Charging"};
    if(has(hay,"grabfood|grab food|foodpanda|food panda|shopeefood|shopee food|beepit|airasia food|misirakyat|misi delivery|bungkusit|lalamove food|halo express|odee"))return new String[]{"Food & drinks","Food Delivery (GrabFood / Foodpanda)"};
    if(has(m,"grab|grabcar|airasia ride|maxim|indrive|mycar|ride|bolt|bolt ride")||has(hay,"grabcar|airasia ride|e-hailing|ehailing|indrive|bolt\\s+(?:ride|my|malaysia)"))return new String[]{"Transport","E-Hailing (Grab / AirAsia Ride)"};
    if(has(hay,"easymrt|\\berl\\b|komuter|\\bmrt\\b|\\blrt\\b|rapidkl|rapid kl|rapid on-?demand|\\bktm\\b|\\bktmb\\b|\\bets\\b|monorail|myrapid|rapidbus|rapid bus|klia ekspres|klia transit|rapid penang|prasarana|aerobus|skybus|causeway link|\\bgokl\\b|go kl|my50|mycity"))return new String[]{"Transport","Public Transit (MRT / LRT / Bus)"};
    if(has(hay,"home\\s+(?:repair|maintenance|improvement)|pembaikan\\s+(?:dalam\\s+)?rumah|window\\s+replacement|kitchen\\s+sink|plumbing|plumber|tukang\\s+paip|roof\\s+repair|wiring\\s+repair|pendawaian|electrician|aircond\\s+(?:service|servicing|repair|cleaning)|servis\\s+aircond|locksmith|pest\\s+control|rentokil|waterproofing|kaodim|recomn|handyman"))return new String[]{"Housing & property","Home Repair & Maintenance"};
    if(has(hay,"mr\\.?\\s*diy|mr\\.?\\s*dollar|ecoshop|eco-shop|eco\\s+shop|muji|living cabin|objet|noko|daiso|kaison|ninso|hardware|carpentry|wooden\\s+chairs?|kitchen\\s+drawers?|home\\s*&\\s*living"))return new String[]{"Shopping","Home & Living"};
    if(has(hay,"perodua service|proton service|honda service|toyota service|tyre|tayar|(?:auto|car|motor|tyre|tayar|vehicle|kereta|repair)\\s*workshop|workshop\\s*(?:kereta|motor|auto)|bengkel|bateriku|car wash|carwash|pusat servis|servis kereta|auto service|engine oil"))return new String[]{"Transport","Vehicle Maintenance & Service"};
    if(has(hay,"(auto|car|motor|vehicle)\\s*(insurance|takaful|insurans|cover|coverage)|(insurance|takaful|insurans)\\s*(kereta|motor)|road\\s*(?:&|and)\\s*auto\\s*cover")||(has(hay,"road\\s*tax|roadtax|cukai\\s*jalan")&&has(hay,"insurance|takaful|insurans|cover|policy|polisi|motor\\s*service|motorway"))||has(hay,"bjak|policystreet"))return new String[]{"Transport","Road Tax & Motor Insurance"};
    if(has(hay,"jpj|mysikap|myeg|puspakom|road tax|roadtax|cukai jalan|summon|summons|saman|traffic fine|compound"))return new String[]{"Taxes & government","JPJ / Renewals / Summons"};
    if(!has(hay,"car\\s*wash")&&has(hay,"dobiqueen|cleanpro|laundromat|self[- ]?service\\s+laundry|\\bdobi\\b|\\blaundry\\b|detergent|dynamo|breeze|softlan|clorox|toilet\\s+paper|tissue\\s+paper|trash\\s+bag|dishwashing|cleaning\\s+suppl(?:y|ies)|pencuci|sabun\\s+basuh"))return new String[]{"Groceries","Household & Cleaning Supplies"};
    if(has(hay,"99\\s*spe[e3]dmart|spe[e3]dmart|7-eleven|7 eleven|7eleven|7-caf[eé]|sevel|family\\s*mart|familymart|kk\\s*mart|kkmart|kk super mart|mynews|emart\\s*24|bilabila(?:\\s*mart)?|bila-bila|cu\\s*mart|cu-mart|mix store|happy mart|kedai runcit|minimart|mini market|kedai mesra|shell select"))return new String[]{"Groceries","Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"};
    if(has(hay,"lotus's|lotus|lotuss|mydin|giant|aeon(?!\\s*bank|\\s*credit)|aeon\\s*bi?g|jaya grocer|village grocer|tesco|econsave|nsk|hero market|heromarket|cold storage|mercato|segi fresh|tf value(?:-?mart)?|qra|ben's independent|everrise|servay|st rosyam|rosyam mart|redtick|checkers|don don donki|pandamart|grabmart|superseven|pasaraya cs|tmc grocer|maslee|nirwana maju|bataras|ta kiong|sunshine square|billion supermarket|supermarket|hypermarket|pasaraya"))return new String[]{"Groceries","Supermarket & Hypermarket"};
    if(has(hay,"wet market|pasar pagi|pasar malam|pasar borong|pasar basah|pasar baru|pasar awam|pasar tani|pasar besar|fresh produce|butcher|fishmonger|fish market|meat shop|seafood market"))return new String[]{"Groceries","Wet Market & Fresh Produce"};
    if(has(hay,"mamak|nasi|kopitiam|kedai makan|kedai kopi|warung|warong|hawker|gerai|pelita|q bistro|kandar|roti canai|roti bakar|teh tarik|kaya toast|kopi o|medan selera|pusat penjaja|penjaja|food court|foodcourt|bak kut teh|char ku[ae]y teow|yong tau foo|madam kwan|oriental kopi|thong kee|hock kee|yut kee|nz curry|ali muthu|village park|murni discovery|ayam penyet|ayam gepuk|ayam geprek|ayam percik|ayam goreng kunyit|nasi lemak|nasi kerabu|nasi kukus|nasi briyani|nasi campur|nasi dagang|nasi goreng|wan tan|wantan|pan mee|ban mian|lei cha|hokkien mee|curry mee|kolo mee|mee goreng|mee rebus|laksa|cendol|ais kacang|rojak|satay|sate|murtabak|tosai|thosai|apam balik|keropok lekor|pisang goreng|kuih|lok lok|chee cheong fun|chap fan|zap fan|economy rice|mixed rice|tom ?yam|ikan bakar|bakso|claypot|kayu|hameediyah|khulafa|darussalam|syed bistro|mahbub|nirwana|bungkus kaw kaw|ah cheng|kluang station|nam heong|hailam kopitiam|uncle soon"))return new String[]{"Food & drinks","Hawker & Mamak"};
    if(has(hay,"mcdonald|mcdonalds|mcd|kfc|burger king|marrybrown|subway|pizza hut|domino|dominos|texas chicken|a&w|4\\s*fingers|4fingers|wendy's|wendys|jollibee|popeyes|us pizza|nando's|nandos|kenny rogers|kyochon|shake shack|richeese|wingstop|myburgerlab|woodfire|five guys|kgb burger|ramly burger|otai burger|fuel shack|spade'?s burger|chicking|sugarbun|pelicana|chicken rice"))return new String[]{"Food & drinks","Fast Food"};
    if(has(hay,"starbucks|tealive|zus|chagee|coffee bean|gigi coffee|kenangan|luckin|boba|bubble tea|coolblog|chatime|mixue|ai-?cha|aicha|bingxue|wedrink|chizu|koi\\s*th[eé]|the alley|koppiku|hwc coffee|cotti coffee|san francisco coffee|bask bear|richiamo|oldtown|old town|gong cha|gongcha|beutea|boost juice|llaollao|secret recipe|daboba|xing fu tang|tiger sugar|chicha san chen|machimachi|macao imperial|kopi saigon|kopi mesin|niko neko|%\\s*arabica|matcha|coffee|kopi"))return new String[]{"Food & drinks","Coffee, Tea & Drinks"};
    if(has(hay,"beryl'?s|famous amos|auntie anne'?s|inside scoop|lavender bakery|rt pastry|royce'?|garrett popcorn|komugi|dunkin'?|krispy kreme|mix\\.com\\.my|chateraise|rotiboy"))return new String[]{"Food & drinks","Groceries & Snacks"};
    if(has(hay,"restoran|restaurant|cafe|café|bistro|eatery|sushi|sushiro|genki sushi|haidilao|sukiya|suki-ya|seoul garden|din tai fung|steak|steakhouse|kbbq|dakgalbi|ramen|izakaya|omakase|ichiban|yoshinoya|marugame|pepper lunch|mykori|kakigori|pad thai|bakery|dim sum|bbq|seafood|papparich|pappa rich|nanyang|boat noodle|chicken rice shop|teppanyaki|kenny hills|antipodean|dookki|yakiniku like|serai|bar\\.b\\.q plaza|christine'?s bakery|nippori|two sons|food"))return new String[]{"Food & drinks","Restaurant & Cafe"};
    if(has(hay,"tnb|tenaga nasional|mytnb|sesb|sarawak energy|seb cares|elektrik|tarif elektrik|bil elektrik|electricity(?:\\s+bill)?|power\\s+supply"))return new String[]{"Bills & utilities","Electricity (TNB)"};
    if(has(hay,"air selangor|syabas|indah water|iwk|ranhill|air kelantan|air johor|saj|pbapp|lembaga air perak|sada|satu water|syarikat air melaka|samb melaka|syarikat air negeri sembilan|sains air|bil air|pembetungan|water.*sewerage"))return new String[]{"Bills & utilities","Water & Sewerage (Air Selangor / IWK)"};
    if(has(hay,"unifi fibre|unifi|time fibre|time internet|maxis fibre|celcom fibre|digi fibre|astro fibre|tm net|starlink|allo broadband|home internet|fibre internet|broadband"))return new String[]{"Bills & utilities","Home Internet & WiFi (Unifi / Time)"};
    if(has(hay,"maxis|hotlink|celcom|digi|celcomdigi|umobile|u mobile|tune talk|xox|onexox|redone|yes 5g|yes5g|yoodo|mobile postpaid|postpaid plan|cellular"))return new String[]{"Bills & utilities","Mobile Postpaid & Prepaid"};
    if(has(hay,"netflix|spotify|astro|sooka|disney|youtube|icloud|google one|google play|app store|apple music|apple one|amazon prime|prime video|hbo|crunchyroll|apple\\.com|viu|iqiyi|chatgpt|openai|claude|anthropic|perplexity|cursor|github copilot|copilot pro|gemini advanced|midjourney|notion|figma|1password|bitwarden|dropbox|nordvpn|expressvpn|microsoft 365|adobe|canva"))return new String[]{"Bills & utilities","Streaming & App Subscriptions"};
    if(has(hay,"condo maintenance|sinking fund|management corp(?:oration)?|\\bjmb\\b|i-?neighbour|ineighbour|maintenance fee|guardhouse fee|resident association"))return new String[]{"Bills & utilities","Condo Maintenance & Sinking Fund"};
    boolean insBrand=has(hay,"prudential|prubsn|great\\s+eastern|\\baia\\b|allianz|etiqa|zurich|manulife|\\bfwd\\b|hong\\s+leong\\s+assurance|\\bhla\\b|tokio\\s+marine|mcis|ammetlife|sun\\s+life|generali|tune\\s+protect|lonpac|msig|sompo|chubb|takaful|insurance|insurans");
    if(insBrand&&has(hay,"medical\\s*card|health\\s*card|kad\\s*perubatan|hospitali[sz]ation|medi[cs]?afe|prumed|a-?life\\s*medi|e-?medical|(?:health|medical)\\s+(?:insurance|takaful|plan|policy)"))return new String[]{"Insurance & Takaful","Medical & Health Card"};
    if(insBrand&&has(hay,"personal\\s*accident|kemalangan\\s*diri|\\bpa\\s+(?:policy|plan|cover|takaful|insurance)|accident\\s+(?:plan|policy|cover)"))return new String[]{"Insurance & Takaful","Personal Accident"};
    if(insBrand&&has(hay,"education\\s+(?:plan|policy|insurance|takaful)|takaful\\s+pendidikan|child\\s+education|edusafe|smart\\s*edu"))return new String[]{"Insurance & Takaful","Education Insurance"};
    if(has(hay,"decathlon|sports direct|al-ikhsan|alikhsan|jd sports|foot locker|anytime fitness|celebrity fitness|chi fitness|fitness first|babel|babel fit|flyproject|badminton|futsal|pickleball|padel|hyrox|gym|sports"))return new String[]{"Health & medical","Gym, Sports & Fitness"};
    if(has(hay,"watsons|guardian|caring pharmacy|big pharmacy|aa pharmacy|alpro|health lane|multicare|constant pharmacy|georgetown pharmacy|sunny pharmacy|pharmacy|farmasi|panadol|obat|ubat"))return new String[]{"Health & medical","Pharmacy & Medicine"};
    if(has(hay,"dental|dentist|pergigian|orthodontic|optometrist|optical|focus point|owndays|mog eyewear|a-look|eyecare|q\\s*&\\s*m dental|tiew dental|lenskart"))return new String[]{"Health & medical","Dental & Optical"};
    if(has(hay,"pathlab|bp healthcare|bp diagnostic|gribbles|health screening|full medical|medical checkup|medical check-up"))return new String[]{"Health & medical","Full Medical Checkup (LHDN)"};
    if(has(hay,"hospital|gleneagles|pantai|sunway medical|kpj|columbia asia|prince court|sjmc|subang jaya medical|thomson hospital|assunta|tung shin|institut jantung|ijn|avisena|medical cent(?:er|re)|pusat perubatan|specialist cent(?:er|re)"))return new String[]{"Health & medical","Hospital & Specialist"};
    if(has(hay,"klinik|clinic|poliklinik|polyclinic|qualitas|mediviron|mediklinik|careclinics|megaklinik|doctoroncall|\\btcm\\b|chinese physician|physiotherapy|chiropractic"))return new String[]{"Health & medical","Clinic & GP"};
    if(has(hay,"prudential|prubsn|great eastern|\\baia\\b|allianz|etiqa|zurich|manulife|\\bfwd\\b|hong leong assurance|tokio marine|mcis|ammetlife|sun life|generali|tune protect|lonpac|msig|sompo|chubb|[\\p{L}0-9]*takaful"))return new String[]{"Insurance & Takaful","Life Insurance / Takaful"};
    if(!has(hay,"cinema|cinemas|cineplex|wayang")&&has(hay,"popular|kinokuniya|mph bookstore|mph bookstores|bookxcess|tsutaya|eslite|smiggle|typo|cziplee|bookstore|bookshop|kedai\\s+buku|alat\\s+tulis|books|stationery"))return new String[]{"Education","Books & Stationery"};
    if(has(hay,"tuition|university|universiti|college|kolej|monash|taylor's|taylors|sunway university|utar|uitm|mmu|inti|apu|coursera|udemy|edx|pluralsight|datacamp|skillshare|kumon|eye level|upskill|tuisyen|online course|course enrollment|bootcamp|ielts|toefl|42 kuala lumpur"))return new String[]{"Education","Higher Education & Tuition"};
    if(has(hay,"school fee|yuran (?:sekolah|tadika|taska|asuhan|pibg)|pibg|tadika|taska|kindergarten|preschool|montessori|q-?dees|smart reader|genius aulad|real kids|childcare fee|kafa"))return new String[]{"Education","School & Childcare Fees"};
    if(has(hay,"mothercare|chicco|anakku|poney|babysitter|pengasuh|nanny\\s+allowance|baby\\s+store"))return new String[]{"Family & gifts","Childcare & Family Support"};
    if(has(hay,"epf|kwsp|i-saraan|isaraan"))return new String[]{"Investments & savings","EPF Voluntary (i-Saraan)"};
    if(has(hay,"asnb|asb|tabung haji|thijari"))return new String[]{"Investments & savings","ASB / Tabung Haji"};
    if(has(hay,"rakuten trade|moomoo|webull|luno|stashaway|wahed|versa|kdi invest|fsmone|goinvest|tng go\\+|etf|crypto"))return new String[]{"Investments & savings","Stocks, ETF & Crypto"};
    if(has(hay,"zakat|fitrah|ppz|lembaga zakat|maiwp"))return new String[]{"Family & gifts","Zakat & Fitrah"};
    if(has(hay,"charity|donation|donated|donating|derma|sumbangan|tabung kebajikan|mercy malaysia|makna|yayasan|wwf malaysia"))return new String[]{"Family & gifts","Charity & Donations"};
    if(has(hay,"lhdn|hasil|cp38|cp500|pcb|cukai pendapatan|tax agency|harbour tax|income tax|byrhasil|mytax"))return new String[]{"Taxes & government","LHDN Income Tax / PCB"};
    if(has(hay,"stamp duty|duti setem|government services|govt services|govt service tax|e-stamping|imigresen|immigration|myimm|passport|\\bjpn\\b|mykad|\\bptg\\b|e-?tanah|mybayar"))return new String[]{"Taxes & government","Government Service & Stamp Duty"};
    if(has(hay,"cukai pintu|cukai tanah|cukai taksiran|quit rent|assessment (?:bill|tax|rate)|dbkl|mbpj|mbsj|mpsj|mpkj|mbpp|mbsp|mbjb|mpsepang"))return new String[]{"Housing & property","Cukai Pintu & Cukai Tanah"};
    if(has(hay,"coway|cuckoo|lg puricare|ikea|ssf|hooga|akemi"))return new String[]{"Housing & property","Furniture & Appliances"};
    if(has(hay,"machines|switch|senheng|senq|harvey norman|all it|tmt|thundermatch|challenger|courts|xiaomi|samsung|apple store|sony|dyson|anker|laptop|smartphone|iphone|ipad"))return new String[]{"Shopping","Electronics & Gadgets"};
    if(has(hay,"uniqlo|padini|brands outlet|h&m|zara|cotton on|parkson|isetan|seibu|sogo|bata|charles & keith|christy ng|bonia|carlo rino|skechers|nike|adidas|puma|love bonito"))return new String[]{"Shopping","Clothing, Bags & Footwear"};
    if(has(hay,"sephora|innisfree|bath & body|l'occitane|loccitane|the body shop|kiehl's|kiehls|sasa|olive young|skintific|wardah|laneige|sulwhasoo|skincare|cosmetics|barbershop|kedai gunting|barber|hair salon|hairdressing|nail salon|manicure|pedicure|number76|a cut above|apt salon|thai odyssey|healthland|reflexology|\\bspa\\b"))return new String[]{"Shopping","Beauty, Skincare & Grooming"};
    if(has(hay,"gsc|golden screen|tgv|mmcineplex|\\bmbo\\b|dadi cinema|ticket2u|cloudjoi|golive asia|sistic|eventbrite|cinema|sunway lagoon|genting skyworlds|legoland|aquaria klcc|petrosains|escape penang|kidzania|zoo negara|splashmania|theme park|karaoke|loud speaker|red box"))return new String[]{"Entertainment & travel","Movies, Concerts & Events"};
    if(has(hay,"airasia|malaysia airlines|batik air|malindo|firefly|scoot|klook|trip\\.com|expedia|redbus|easybook|maswings"))return new String[]{"Entertainment & travel","Flights & Transport"};
    if(has(hay,"agoda|booking\\.com|airbnb|traveloka|hotel|resort"))return new String[]{"Entertainment & travel","Hotel & Holiday Stay"};
    if(has(hay,"steam|playstation|ps5|psn|nintendo|eshop|xbox|valorant|codashop|razer gold|garena|hoyoverse|pop\\s*mart|popmart|miniso|toy world|hobbies"))return new String[]{"Entertainment & travel","Games & Hobbies"};
    if(bnplHint)return new String[]{LOANS,"BNPL (SPayLater / GrabPayLater / Atome)"};
    if(has(hay,"shopee|lazada|tiktok shop|tiktok|zalora|taobao|temu|shein|amazon|pg mall"))return new String[]{"Shopping","Online Marketplace (Shopee / Lazada / TikTok)"};
    return new String[]{"Miscellaneous","Auto-Captured (Uncategorized)"};
  }

  /** Hybrid rules + 2-stage cascaded offline neural reranker for Malaysian merchants. Returns {category, subcategory}. */
  public static String[] classify(String merchant,String source,String text){
    String[] ruleHit=classifyRulesOnly(merchant,source,text);
    if(!ruleHit[0].equals("Miscellaneous"))return ruleHit;
    String rawM=String.valueOf(merchant).toLowerCase(Locale.ROOT);
    String rawT=String.valueOf(text).toLowerCase(Locale.ROOT);
    String extraBody=rawT;
    if(!rawM.trim().isEmpty())extraBody=extraBody.replace(rawM.trim()," ");
    extraBody=P_CLS_WS.matcher(P_CLS_NUMS.matcher(P_CLS_STOP.matcher(extraBody).replaceAll(" ")).replaceAll(" ")).replaceAll(" ").trim();
    boolean hasRichBody=extraBody.length()>=16;
    String hay=(rawM+" "+rawT).trim();
    boolean hasEduCue=has(hay,"taska|tadika|kindergarten|preschool|childcare|montessori|nursery|school|sekolah|yuran|asuhan|pibg|kafa|kemas|kiddie|kids|children|tuition|tuisyen|academy|akademi|learning|college|kolej|university|universiti|edu|kumon|smart reader|genius|q-?dees|real kids|brainy|daycare|playschool|bootcamp|ielts|toefl|coursera|udemy|edx|pluralsight|datacamp|skillshare|upskill|certif|training|workshop|seminar");
    boolean looksGenericSme=has(rawM,"sdn bhd|enterprise|ventures?|holdings|resources|solutions|supply|works|corp|hub|industries");
    boolean hasUtilCue=has(hay,"tnb|tenaga|syabas|air selangor|iwk|indah water|sewerage|elektrik|electricity|unifi|time fibre|maxis|celcom|digi|astro|starlink|wifi|broadband|postpaid|telekom");
    MlClassifier.Prediction ml=MlClassifier.predict(merchant,text);
    if(ml!=null&&ml.activeSignals>=4&&ml.probability>=0.72&&ml.margin>=0.25&&!ml.category.equals("Miscellaneous")){
      if(("School & Childcare Fees".equals(ml.subcategory)||"Upskilling & Certification".equals(ml.subcategory))&&!hasEduCue)return ruleHit;
      if(looksGenericSme&&"Bills & utilities".equals(ml.category)&&!hasUtilCue)return ruleHit;
      return new String[]{ml.category,ml.subcategory};
    }
    MlNeuralReranker.Prediction nn=MlNeuralReranker.predict(merchant,text,ml);
    if(nn!=null&&nn.activeSignals>=2){
      if(nn.category.equals("Miscellaneous")||nn.miscProbability>=MlNeuralReranker.getOodMiscThreshold()){
        return ruleHit;
      }
      if(("School & Childcare Fees".equals(nn.subcategory)||"Upskilling & Certification".equals(nn.subcategory))&&!hasEduCue)return ruleHit;
      if(looksGenericSme&&"Bills & utilities".equals(nn.category)&&!hasUtilCue)return ruleHit;
      boolean okRich=hasRichBody&&(nn.probability>=0.22||(nn.categoryProbability>=0.34&&nn.categoryMargin>=0.08));
      boolean okShort=!hasRichBody&&((nn.probability>=0.42&&nn.margin>=0.12)||(nn.categoryProbability>=0.52&&nn.categoryMargin>=0.16));
      if(okRich||okShort)return new String[]{nn.category,nn.subcategory};
      return ruleHit;
    }
    if(ml!=null&&ml.activeSignals>=3&&!ml.category.equals("Miscellaneous")&&(hasRichBody
        ?(ml.probability>=0.08||(ml.categoryProbability>=0.22&&ml.categoryMargin>=0.06))
        :((ml.probability>=0.34&&ml.margin>=0.10)||(ml.categoryProbability>=0.44&&ml.categoryMargin>=0.14)))){
      if(("School & Childcare Fees".equals(ml.subcategory)||"Upskilling & Certification".equals(ml.subcategory))&&!hasEduCue)return ruleHit;
      if(looksGenericSme&&"Bills & utilities".equals(ml.category)&&!hasUtilCue)return ruleHit;
      return new String[]{ml.category,ml.subcategory};
    }
    return ruleHit;
  }
}
