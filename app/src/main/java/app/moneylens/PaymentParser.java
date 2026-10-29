package app.moneylens;
import org.json.*;
import java.util.Locale;
import java.util.regex.*;
import java.time.LocalDate;

/** Payment notification parser supporting TNG eWallet, toll deductions, DuitNow QR, FPX, JomPAY, BNPL, PTPTN and Malaysian bank formats. */
public final class PaymentParser {
  private static final String NUM="([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])";
  private static final Pattern AMOUNT=Pattern.compile("(?i)(?:RM|MYR)[\\s\\u00A0]*"+NUM);
  private static final Pattern TNG_PAID=Pattern.compile("(?i)\\byou\\s+have\\s+paid\\s+(?:RM|MYR)\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s+(?:at|to)\\s+([^\\r\\n]+)");
  private static final Pattern LABELED_MERCHANT=Pattern.compile("(?i)\\b(?:merchant|seller|biller|payee|paid\\s+to|payment\\s+to)\\s*:\\s*([\\p{L}0-9][\\p{L}0-9 &'._/()#,+-]{1,79})");
  private static final Pattern MERCHANT=Pattern.compile("(?i)\\b(?:at|to|from|kepada|di)\\s+([\\p{L}0-9 &'._/()#,+-]{2,80})");
  private static final Pattern VERBLESS_PREP=Pattern.compile("(?i)(?:RM|MYR)\\s*"+NUM+"\\s*(?:(?:at|to|di|kepada)\\s+|[@\\-\\u2013\\u2014]\\s*)([\\p{L}0-9][\\p{L}0-9 &'._/()#,+-]{1,79})");
  private static final Pattern VERBLESS_CAPS=Pattern.compile("(?:RM|MYR)\\s*"+NUM+"\\s+([A-Z0-9][A-Z0-9&'._/()#+-]*(?:[ ]+[A-Z0-9&'._/()#+-]+){0,6})");
  /** Security advice footers ("Never share your TAC/OTP") are removed before any filtering. */
  private static final Pattern FOOTER=Pattern.compile("(?i)\\b(?:never|do\\s+not|don'?t|jangan)\\s+(?:share|disclose|reveal|give|kongsi|dedahkan|berikan)\\b[^.!\\n]*");
  private static final Pattern EXCLUDE_BEFORE=Pattern.compile("(?i)\\b(?:balance|bal|baki|limit|cashback|cash\\s+back|reward|rewards|points|pts|rebate|earned|earn|available|avail|remaining|outstanding|saved|worth|up\\s+to|min(?:imum)?\\s+spend)\\b[^0-9]*$");
  private static final Pattern EXCLUDE_AFTER=Pattern.compile("(?i)^\\s*(?:cashback|cash\\s+back|reward|rewards|points|pts|rebate|back)\\b");
  private static final Pattern PAY_BEFORE=Pattern.compile("(?i)\\b(?:paid|pay|payment|repayment|spent|charged|deducted|debited|purchase|purchased|amount|total|of|transfer|transferred|received|credited|refund|refunded|bayaran|dibayar|potongan|sebanyak|deduction|sale)\\b[^0-9a-z]*$");
  private static final Pattern PAY_AFTER=Pattern.compile("(?i)^\\s*(?:(?:at|to|di|kepada|has\\s+been|was|deducted|charged|paid|debited)\\b|[@\\-\\u2013\\u2014])");
  public static final String LOANS="Loans, BNPL & Credit";

  public static JSONObject parse(String text,String source)throws Exception{
    if(text==null||text.length()>4000)return null;
    String normalized=text.replace('\u00A0',' ').trim();
    String core=FOOTER.matcher(normalized).replaceAll(" ").trim();
    String low=core.toLowerCase(Locale.ROOT);
    boolean completed=low.matches("(?s).*\\b(you have paid|paid|successful|successfully|completed|approved|deducted|charged|debited|credited|received|berjaya|diterima|dibayar|authori[sz]ed a payment|spent)\\b.*");
    if(low.matches("(?s).*\\b(declined|failed|unsuccessful|gagal|rejected)\\b.*"))return null;
    boolean otpWord=low.matches("(?s).*\\b(otp|tac|verification\\s+code|kod\\s+pengesahan|one.?time\\s+(?:pin|password|passcode)|security\\s+code)\\b.*");
    if(otpWord&&!completed&&low.matches("(?s).*(?<![0-9.,])[0-9]{4,8}(?![0-9.,]).*"))return null;
    if(!completed&&low.matches("(?s).*\\b(voucher|promo|promotion|discount|offer|deal|deals|lucky\\s+draw|win|redeem|claim\\s+now|get\\s+up\\s+to|enjoy|limited\\s+time|flash\\s+sale)\\b.*"))return null;
    if(!completed&&low.matches("(?s).*\\b(secure2u|authorise|authorize|approve|approval)\\b.*"))return null;
    if(low.matches("(?s).*\\b(pending|processing|requested|scheduled|pre.?authori[sz]ation|awaiting)\\b.*"))return null;
    boolean trigger=low.matches("(?s).*\\b(paid|pay|payment|repayment|instal?ment|purchase|spent|debited|deducted|deduction|charged|charge|sale|credited|received|refund|refunded|transfer|transferred|withdrawal|top.?up|reload|reloaded|toll|paydirect|rfid|duitnow|fpx|jompay|giro|ibft|autodebit|paywave|contactless|bayaran|dibayar|diterima|transaksi|potongan|transaction|spaylater|paylater|ptptn)\\b.*");
    Matcher vp=VERBLESS_PREP.matcher(core),vc=VERBLESS_CAPS.matcher(core);
    boolean prep=vp.find(),caps=!prep&&vc.find();
    if(!trigger&&!prep&&!caps)return null;

    Matcher m=AMOUNT.matcher(core);JSONArray candidates=new JSONArray();long primary=0,firstIncluded=0;int count=0;boolean primaryFound=false;
    while(m.find()){
      long v;try{v=Ledger.minor(m.group(1).replace(",",""));}catch(IllegalArgumentException ignored){continue;}
      count++;candidates.put(v);
      String before=core.substring(Math.max(0,m.start()-30),m.start()),after=core.substring(m.end(),Math.min(core.length(),m.end()+16));
      if(EXCLUDE_BEFORE.matcher(before).find()||EXCLUDE_AFTER.matcher(after).find())continue;
      if(firstIncluded==0)firstIncluded=v;
      if(!primaryFound&&(PAY_BEFORE.matcher(before).find()||PAY_AFTER.matcher(after).find())){primary=v;primaryFound=true;}
    }
    if(count==0)return null;
    long amount=primaryFound?primary:firstIncluded;
    if(amount<=0)return null;

    String kind=low.contains("refund")?"refund":low.matches("(?s).*\\b(credited|received|diterima)\\b.*")?"income":"expense";
    String reason=count==1?"Recorded from a notification. Check the account, amount and type.":"Several amounts were found; MoneyLens used RM "+java.math.BigDecimal.valueOf(amount,2).toPlainString()+" as the payment. Edit it if that is wrong.";
    if(low.matches("(?s).*\\b(transfer|top.?up|reload|reloaded|withdrawal)\\b.*")&&!low.matches("(?s).*\\b(duitnow\\s+qr|fpx|jompay|paid|purchase|spent|sale)\\b.*")){
      kind="transfer";reason="This may be a transfer between your own accounts. Choose both accounts.";
    }

    String merchant="";
    Matcher tng=TNG_PAID.matcher(core);
    if(tng.find())merchant=cleanMerchant(tng.group(1));
    if(!validMerchant(merchant)){
      Matcher lbl=LABELED_MERCHANT.matcher(core);
      if(lbl.find()){String c=cleanMerchant(lbl.group(1));if(validMerchant(c))merchant=c;}
    }
    if(!validMerchant(merchant)){
      merchant="";Matcher mm=MERCHANT.matcher(core);
      while(mm.find()){String c=cleanMerchant(mm.group(1));if(validMerchant(c)){merchant=c;break;}}
    }
    if(merchant.isEmpty()&&prep){String c=cleanMerchant(vp.group(2));if(validMerchant(c))merchant=c;}
    if(merchant.isEmpty()&&caps){String c=cleanMerchant(vc.group(2));if(validMerchant(c))merchant=c;}
    if(merchant.isEmpty()&&low.matches("(?s).*\\b(toll|paydirect|rfid)\\b.*"))merchant="TNG Toll";
    if(merchant.isEmpty())merchant="Detected payment";

    String[] cs=kind.equals("income")?new String[]{"Other income",""}:classify(merchant,source,low);
    JSONObject d=new JSONObject().put("id",Ledger.id()).put("amount",amount).put("primaryAmount",amount).put("kind",kind).put("merchant",merchant).put("category",cs[0]).put("date",LocalDate.now().toString()).put("source",source).put("state","review").put("reason",reason).put("amountCandidates",candidates).put("adapter","generic-5").put("revision",1).put("created",System.currentTimeMillis());
    if(!cs[1].isEmpty())d.put("subcategory",cs[1]);
    return d;
  }

  private static boolean validMerchant(String c){
    return c!=null&&c.length()>=2&&!c.matches("(?i)^(your\\s+account|your\\s+ewallet|your\\s+card|your|you|account|ewallet|e-wallet|card|wallet|anyone|others|[0-9]{1,2}:[0-9]{2}.*|[0-9 /.:-]+)$");
  }

  private static String cleanMerchant(String raw){
    if(raw==null)return "";
    String s=raw.replaceAll("(?i)\\s*\\(?\\s*biller\\s*code.*","");
    s=s.replaceAll("(?i)\\s+(?:is\\s+successful|was\\s+successful|successful|successfully|berjaya|on\\s+[0-9]{1,4}[/.-][0-9]{1,2}|on\\s+[0-9]{1,2}\\s+[a-z]{3}|at\\s+[0-9]{1,2}[:.][0-9]{2}|for\\s+ref|ref\\b|reference|via\\s+secure2u|via\\s+duitnow|via\\s+fpx|via\\s+jompay|using\\s+touch|using\\s+your|with\\s+your|from\\s+your|available\\s+balance|avail\\.?\\s*bal|remaining|balance|baki|limit|cashback|earn\\s+[0-9]|reward|points|thank|terima\\s+kasih|never|do\\s+not|jangan|txn|transaction\\s+id|trx).*","");
    s=s.replaceAll("[.!]\\s+(?=\\p{Lu}\\p{Ll}).*","").trim();
    s=s.replaceAll("[.,;:!\\-\\u2013\\u2014\\s]+$","").replaceAll("^[.,;:!\\-\\u2013\\u2014\\s]+","").trim();
    if(s.length()>80)s=s.substring(0,80).trim();
    return s;
  }

  private static boolean has(String hay,String alternatives){
    return Pattern.compile("(?<![\\p{L}0-9])(?:"+alternatives+")(?![\\p{L}0-9])").matcher(hay).find();
  }

  /** Keyword heuristics for Malaysian merchants. Returns {category, subcategory}. */
  public static String[] classify(String merchant,String source,String text){
    String m=String.valueOf(merchant).toLowerCase(Locale.ROOT),hay=m+" "+String.valueOf(source).toLowerCase(Locale.ROOT)+" "+String.valueOf(text).toLowerCase(Locale.ROOT);
    if(has(hay,"sspn|ptptn sspn"))return new String[]{"Investments & savings","SSPN Savings"};
    if(has(hay,"ptptn|perbadanan tabung pendidikan"))return new String[]{LOANS,"PTPTN Study Loan"};
    if(has(hay,"spaylater|shopee paylater|grabpaylater|grab paylater|paylater|pay later|atome|payflex"))return new String[]{LOANS,"BNPL (SPayLater / GrabPayLater / Atome)"};
    if(has(hay,"rfid|paydirect|toll|tol|plaza tol|lebuhraya|expressway|highway")||has(m,"duke|plus|ldp|kesas|mex|smart|sprint|npe|akleh|besraya|lekas|guthrie|nkve|suke|silk"))return new String[]{"Transport","Toll (TNG / RFID / PayDirect)"};
    if(has(hay,"petronas|shell|petron|caltex|bhpetrol|bhp|setel|petrol|fuel|ev charging|chargev|gentari"))return new String[]{"Transport","Petrol & EV Charging"};
    if(has(hay,"grabfood|grab food|foodpanda|shopeefood|shopee food|beepit"))return new String[]{"Food & drinks","Food Delivery (GrabFood / Foodpanda)"};
    if(has(m,"grab|grabcar|airasia ride|maxim|indrive|mycar")||has(hay,"grabcar|airasia ride|e-hailing|ehailing|indrive"))return new String[]{"Transport","E-Hailing (Grab / AirAsia Ride)"};
    if(has(hay,"parking|flexiparking|jompark"))return new String[]{"Transport","Parking"};
    if(has(hay,"mrt|lrt|rapidkl|rapid kl|ktm|monorail|myrapid|rapidbus|rapid bus"))return new String[]{"Transport","Public Transit (MRT / LRT / Bus)"};
    if(has(hay,"99 speedmart|speedmart|7-eleven|7 eleven|7eleven|familymart|family mart|kk mart|kkmart|mynews|emart24"))return new String[]{"Groceries","Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"};
    if(has(hay,"lotus's|lotus|lotuss|mydin|giant|aeon|jaya grocer|village grocer|tesco|econsave|nsk|hero market|cold storage|mercato|supermarket|hypermarket|pasaraya"))return new String[]{"Groceries","Supermarket & Hypermarket"};
    if(has(hay,"mamak|nasi|kopitiam|kedai makan|warung|hawker|gerai|pelita|kandar|roti canai|medan selera|food court"))return new String[]{"Food & drinks","Hawker & Mamak"};
    if(has(hay,"mcdonald|mcdonalds|mcd|kfc|burger king|marrybrown|subway|pizza hut|domino|dominos|texas chicken|a&w|4fingers|wendy's|wendys"))return new String[]{"Food & drinks","Fast Food"};
    if(has(hay,"starbucks|tealive|zus|chagee|coffee bean|gigi coffee|kenangan|luckin|boba|bubble tea|coolblog|chatime|mixue|coffee|kopi"))return new String[]{"Food & drinks","Coffee, Tea & Drinks"};
    if(has(hay,"restoran|restaurant|cafe|café|bistro|kitchen|eatery|sushi|steak|bakery|dim sum|bbq|seafood|food"))return new String[]{"Food & drinks","Restaurant & Cafe"};
    if(has(hay,"tnb|tenaga nasional|mytnb"))return new String[]{"Bills & utilities","Electricity (TNB)"};
    if(has(hay,"air selangor|syabas|indah water|iwk|ranhill|air kelantan|air johor|saj|pbapp|air perak"))return new String[]{"Bills & utilities","Water & Sewerage (Air Selangor / IWK)"};
    if(has(hay,"unifi|time fibre|time internet|maxis fibre|celcom fibre|tm net"))return new String[]{"Bills & utilities","Home Internet & WiFi (Unifi / Time)"};
    if(has(hay,"maxis|hotlink|celcom|digi|celcomdigi|umobile|u mobile|tune talk|xox|redone|yes 5g|yoodo"))return new String[]{"Bills & utilities","Mobile Postpaid & Prepaid"};
    if(has(hay,"netflix|spotify|astro|disney|youtube premium|icloud|google one|apple\\.com|viu|iqiyi|chatgpt|openai"))return new String[]{"Bills & utilities","Streaming & App Subscriptions"};
    if(has(hay,"decathlon|sports direct|jd sports|anytime fitness|celebrity fitness|chi fitness|gym|badminton|futsal"))return new String[]{"Health & medical","Gym, Sports & Fitness"};
    if(has(hay,"watsons|guardian|caring pharmacy|big pharmacy|aa pharmacy|alpro|pharmacy|farmasi"))return new String[]{"Health & medical","Pharmacy & Medicine"};
    if(has(hay,"klinik|clinic|hospital|gleneagles|pantai hospital|sunway medical|kpj|columbia asia|dental|pergigian"))return new String[]{"Health & medical","Clinic & GP"};
    if(has(hay,"prudential|prubsn|great eastern|aia|allianz|etiqa|zurich|manulife|fwd|takaful"))return new String[]{"Insurance & Takaful","Life Insurance / Takaful"};
    if(has(hay,"popular|kinokuniya|mph bookstores|bookxcess|tsutaya"))return new String[]{"Education","Books & Stationery"};
    if(has(hay,"prs|private retirement"))return new String[]{"Investments & savings","PRS (Private Retirement Scheme)"};
    if(has(hay,"asnb|asb|tabung haji"))return new String[]{"Investments & savings","ASB / Tabung Haji"};
    if(has(hay,"zakat|fitrah|ppz|lembaga zakat|maiwp"))return new String[]{"Family & gifts","Zakat & Fitrah"};
    if(has(hay,"lhdn|hasil|cp38|pcb|cukai pendapatan"))return new String[]{"Taxes & government","LHDN Income Tax / PCB"};
    if(has(hay,"shopee|lazada|tiktok shop|tiktok|zalora|taobao|temu|shein|amazon"))return new String[]{"Shopping","Online Marketplace (Shopee / Lazada / TikTok)"};
    return new String[]{"Miscellaneous","Auto-Captured (Uncategorized)"};
  }
}
