package app.moneylens;
import org.json.*;
import java.util.regex.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
/** Extracts suggestions; never commits OCR text directly to the ledger. */
public final class ReceiptParser {
 private static final Pattern AMOUNT=Pattern.compile("(?i)(?:(?:RM|MYR)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2}))(?![0-9]|\\.[0-9])");
 private static final Pattern STANDALONE_AMOUNT=Pattern.compile("(?i)^\\s*(?:(?:RM|MYR)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2}))\\s*$");
 private static final Pattern GENERIC_HEADER=Pattern.compile("(?i)^(?:(?:simplified\\s+)?tax\\s+invoice|invoice|official\\s+receipt|cash\\s+bill|sales\\s+receipt|customer\\s+copy|merchant\\s+copy|duplicate|reprint|resit\\s+rasmi|invois\\s+cukai|credit\\s+card\\s+(?:statement|payment\\s+receipt)|toll\\s+payment|road\\s+tax|cukai\\s+jalan|resit\\s+bayaran|tarikan\\s+kasir|slip\\s+transaksi|slip\\s+bayaran|tanda\\s+bayar|items?(?:\\s+paid)?|itemized(?:\\s+charges)?|bill\\s+details|late\\s+payment\\s+fee|thank\\s+you.*|terima\\s+kasih.*|welcome(?:\\s+to)?)$");
 private static final Pattern WELCOME_PREFIX=Pattern.compile("(?i)^welcome\\s+to\\s+(.{2,})$");
 private static final Pattern NOISE_META=Pattern.compile("(?i)^(?:sst|gst|ssm|reg(?:\\.|istration)?\\s*(?:no|id)|roc|rob|co\\.?\\s*reg|company\\s*(?:no|reg)|tin|msic|brn|tel|hp|phone|fax|hotline|call|email|web|www|http|date|tarikh|time|masa|cashier|staff|server|waiter|table|meja|pax|order|bill|receipt|rcv|rdc|ref|terminal|pos|shift|batch|trace|approval|items?(?:\\s+paid)?|itemized|customer\\s*(?:name|id)?|client|subscriber|service|description|desc|qty|quantity|amount|payment\\s+(?:of|for)|due\\s+date|invoice\\s+no|job|produk|barang|harga|perkhidmatan|penyertaan|bill\\s+details|late\\s+payment\\s+fee)\\b[:.#\\s-]*.*");
 private static final Pattern ADDRESS_LINE=Pattern.compile("(?i)^(?:[0-9]{1,4}[a-z]?(?:-[0-9]{1,4})?[,\\s]+)?(?:no\\.?|lot|plot|level|lvl|floor|flr|unit|suite|block|blk|ground\\s+floor|g-|lg-|ug-|b1-|jalan|jln|lorong|lrg|persiaran|lebuh|taman|tmn|bandar|seksyen|section|kampung|kg\\.?|kompleks|pusat\\s+(?:bandar|komersial|membeli))\\b.*");
 private static final Pattern STREET_TOKEN=Pattern.compile("(?i)\\b(?:jalan|jln|lorong|lrg|persiaran|lebuhraya|lebuh)\\b");
 private static final Pattern BIZ_SUFFIX=Pattern.compile("(?i).*\\b(?:sdn\\.?\\s*bhd|bhd|berhad|enterprise|trading|services|solutions|holdings|kindergarten|tadika|taska|school|sekolah|klinik|clinic|farmasi|pharmacy|restoran|restaurant|cafe|café|mart|store|grocer|supermarket|motors?|workshop|bengkel|resources|ventures)\\b\\.?$");
 private static final Pattern ITEM_LINE=Pattern.compile("(?i)^(?:[-*•]\\s+.*|(?:[0-9]{1,2}[.)]\\s+|1\\s+(?!Malaysia)[A-Za-z]).*|.*\\b[xX×]\\s*[0-9]{1,3}\\s*$|(?:ptptn\\s+repayment|spaylater|grabpaylater|atome\\s+bnpl|shopee\\s+order|lazada\\s+order|i-saraan).*)$");
 private static final Pattern POSTCODE_STATE=Pattern.compile("(?i)\\b[0-9]{5},?\\s+(?:kuala\\s+lumpur|selangor|petaling|subang|shah\\s+alam|klang|kajang|puchong|ampang|cheras|penang|pulau\\s+pinang|johor|melaka|perak|ipoh|kedah|alor\\s+setar|pahang|kuantan|kelantan|terengganu|negeri\\s+sembilan|seremban|sabah|sarawak|kuching|kota\\s+kinabalu|putrajaya|cyberjaya|malaysia)\\b");
 private static final Pattern PHONE_OR_EMAIL=Pattern.compile("(?i)(?:@|\\b(?:0[1-9]-?[0-9]{6,8}|\\+?60[0-9-]{7,11})\\b|https?://|www\\.)");
 private static final Pattern STORE_HINT=Pattern.compile("(?i)\\b(?:sdn\\.?\\s*bhd\\.?|bhd|berhad|plt|llp|enterprise|trading|holdings|resources|services|solutions|restoran|restaurant|kedai|warung|kopitiam|bistro|cafe|café|coffee|zus|chagee|bakery|farmasi|pharmacy|obat|ubat|klinik|clinic|hospital|dental|optometrist|optical|speedmart|kk\\s*mart|mynews|familymart|cu\\s*mart|emart24|econsave|mydin|watsons|guardian|mart|supermarket|hypermarket|pasaraya|pasar|grocer|mini\\s*market|petrol|gentari|jomcharge|chargev|ev\\s+charging|station|hardware|carpentry|maintenance|electronics|marketplace|store|bookshop|bookstore|kindergarten|tadika|plaza\\s+tol|toll\\s+plaza|condominium|condo)\\b");
 private static final Pattern BODY_SECTION=Pattern.compile("(?i)^(?:items?(?:\\s+paid)?|itemized(?:\\s+charges)?|bill\\s+details|subtotal|qty|quantity|description)\\b.*");
 private static final String MONTHS="januari|january|jan|februari|february|feb|march|mac|mar|april|apr|may|mei|june|jun|julai|july|jul|ogos|august|aug|september|sept|sep|oktober|october|oct|okt|november|nov|disember|december|dis|dec";
 private static final Pattern MONTH_DATE=Pattern.compile("(?i)\\b([0-9]{1,2})(?:st|nd|rd|th)?[-/\\s]+("+MONTHS+")[-/\\s,]+(20[0-9]{2}|[0-9]{2})\\b");
 private static final Pattern MONTH_FIRST_DATE=Pattern.compile("(?i)\\b("+MONTHS+")\\s+([0-9]{1,2})(?:st|nd|rd|th)?[,\\s]+(20[0-9]{2}|[0-9]{2})\\b");
 private static final Pattern YMD_MONTH_DATE=Pattern.compile("(?i)\\b(20[0-9]{2})[-/\\s]+("+MONTHS+")[-/\\s]+([0-9]{1,2})(?:st|nd|rd|th)?\\b");
 private static final Pattern NUM_DMY_SPACED=Pattern.compile("\\b([0-9]{1,2})[-/.\\s]+([0-9]{1,2})[-/.\\s]+(20[0-9]{2})\\b");
 private static final Pattern LABELED_DMY_COMPACT=Pattern.compile("(?i)(?:date|tarikh)\\s*[:.]?\\s*([0-9]{2})[-/.\\s]*([0-9]{2})[-/.\\s]*(20[0-9]{2})\\b");
 private static final Pattern NUM_DMY_SHORT=Pattern.compile("\\b([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{2})\\b");

 public static JSONObject parse(String text)throws Exception{
  if(text==null)text="";
  String[] lines=text.split("\\r?\\n");
  if(lines.length<=2){
   String flat=text.replace('\u00A0',' ').trim();
   flat=flat.replaceAll("\\s*\\|\\s*","\n");
   flat=flat.replaceAll("(?i)\\s+(?=(?:sub\\s*-?\\s*total|sub\\s+ttl|sst|gst|service\\s+(?:tax|charge)|rounding(?:\\s+adjustment)?|runding|rundingan|penggenapan|grand\\s+total|total\\s+payable|net(?:t)?\\s+(?:total|amount|payable)|amount\\s+(?:paid|due|payable)|jumlah\\s+(?:perlu\\s+)?dibayar|jumlah\\s+(?:bayaran|bersih|besar)|total\\s+(?:sales|incl|amount|rm|myr)|total|tendered|tunai|change|baki|thank\\s+you|terima\\s+kasih|invoice\\s+no|ref(?:erence)?\\s+no|no\\.?\\s*rujukan|ssm(?:\\s+reg)?|tarikh|date|tel)(?:\\b|:))","\n");
   flat=flat.replaceAll("(?i),\\s+(?=(?:sub\\s*-?\\s*total|sst|rounding|runding|grand\\s+total|total|jumlah|tarikh|date|no\\.?\\s*rujukan|item)(?:\\b|:))","\n");
   flat=flat.replaceAll("(?i)\\s+(?=[0-9]{1,4}(?:,|\\s+)(?:jalan|jln|lorong|lot|unit|level|persiaran|taman)\\b|\\b(?:unit|lot|no\\.?|jalan|jln)\\s+[0-9]{1,4}\\b|\\b[0-9]{12}\\b)","\n");
   flat=flat.replaceAll("(?i)\\s+(?=[0-9]{1,2}[.)]\\s+(?:[0-9]{1,2}[x×]|\\p{L})|item\\s*:)","\n");
   lines=flat.split("\\r?\\n");
  }
  String merchant=extractMerchant(lines);

  boolean hasRoundingLine=false;
  for(String line:lines){
   String low=line.toLowerCase(Locale.ROOT);
   if((low.contains("rounding")||low.contains("penggenapan"))&&!low.contains("after rounding")&&!low.contains("rounded")&&!low.contains("jumlah genap")){
    hasRoundingLine=true;break;
   }
  }

  long best=0;int score=-1,bestLineIdx=-1;boolean ambiguous=false;
  for(int i=0;i<lines.length;i++){
   String line=lines[i];
   int s=scoreTotalLine(line);
   if(s<0)continue;
   List<Long> found=extractPositiveAmounts(line);
   if(found.isEmpty()&&s>=3&&i+1<lines.length){
    Matcher nextStandalone=STANDALONE_AMOUNT.matcher(lines[i+1]);
    if(nextStandalone.find()){
     long n=parseMinor(amountGroup(nextStandalone));
     if(n>0){found.add(n);i++;}
    }
   }
   for(long n:found){
    if(s>score){best=n;score=s;bestLineIdx=i;ambiguous=false;}
    else if(s==score&&n!=best){
     if(hasRoundingLine&&Math.abs(n-best)<=4&&((n%5==0)!=(best%5==0))){
      if(n%5==0){best=n;bestLineIdx=i;}
      ambiguous=false;
     }else{
      ambiguous=true;
     }
    }
   }
  }
  if(score<3&&!ambiguous){
   long resolved=resolveSingleOrCashDiff(lines);
   if(resolved>0){best=resolved;score=3;}
  }else if(score>=3&&!ambiguous&&best>0&&bestLineIdx>=0){
   long postTax=0,tendered=-1,change=-1;
   for(int i=0;i<lines.length;i++){
    String low=lines[i].toLowerCase(Locale.ROOT);
    List<Long> vals=extractPositiveAmounts(lines[i]);
    if(low.contains("tunai")||low.contains("tendered")||low.contains("cash")){
     if(!vals.isEmpty()&&tendered<0)tendered=vals.get(vals.size()-1);
    }else if(low.contains("baki")||low.contains("change")){
     Matcher am=AMOUNT.matcher(lines[i]);
     while(am.find()){
      String raw=amountGroup(am);
      long c=raw.replace(",","").matches("0+(?:\\.0+)?")?0:parseMinor(raw);
      if(c>=0)change=c;
     }
    }else if(i>bestLineIdx&&(low.contains("cukai")||low.contains("sst")||low.contains("service tax")||low.contains("service charge"))&&!vals.isEmpty()){
     postTax+=vals.get(vals.size()-1);
    }
   }
   if(postTax>0&&tendered>0&&change>=0&&tendered-change==best+postTax){
    best=tendered-change;
   }
  }

  String date=extractDate(text);
  String overrideText=Pattern.compile("(?i)\\b(?:late\\s+payment\\s+fee|caj\\s+lewat\\s+bayar|hire\\s+purchase\\s+installment|road\\s+tax|cukai\\s+jalan)\\b").matcher(text).find()?text:"";
  String[] cs=PaymentParser.classifyRulesOnly(merchant,"Receipt scan",overrideText);
  if(cs[0].equals("Miscellaneous")&&!text.trim().isEmpty()){
   cs=PaymentParser.classify(merchant,"Receipt scan",text);
  }else if(cs[0].equals("Miscellaneous")){
   cs=PaymentParser.classify(merchant,"Receipt scan","");
  }
  JSONObject out=new JSONObject()
   .put("id",Ledger.id())
   .put("merchant",merchant)
   .put("amount",ambiguous||score<3?0:best)
   .put("kind","expense")
   .put("category",cs[0])
   .put("date",date)
   .put("source","Receipt scan")
   .put("state","review")
   .put("revision",1)
   .put("reason",ambiguous||score<3?"OCR found no unambiguous total. Enter the receipt total and check all details.":"Suggested from receipt text. Verify the total, date, merchant and account.");
  if(!cs[1].isEmpty())out.put("subcategory",cs[1]);
  return out;
 }

 private static String extractMerchant(String[] lines){
  String bestMerchant="Receipt";
  int bestScore=0;
  int inspected=0;
  boolean afterBody=false;
  for(int i=0;i<lines.length&&inspected<12;i++){
   String raw=lines[i].trim();
   if(raw.isEmpty())continue;
   inspected++;
   if(inspected>1&&BODY_SECTION.matcher(raw).matches())afterBody=true;
   Matcher wm=WELCOME_PREFIX.matcher(raw);
   String candidate=(wm.matches()?wm.group(1):raw).replaceFirst("(?i)^(?:nota|merchant|store|shop)\\s*:\\s*","").replaceAll("\\s*\\|\\s*[0-9]{5}.*$","").trim();
   if(candidate.length()<2||candidate.matches("[0-9\\W_]+"))continue;
   if(GENERIC_HEADER.matcher(candidate).matches())continue;
   boolean isAddr=ADDRESS_LINE.matcher(candidate).matches()&&!(inspected<=2&&BIZ_SUFFIX.matcher(candidate).matches());
   boolean isPost=POSTCODE_STATE.matcher(candidate).find();
   int s=10+Math.max(0,10-inspected);
   if(candidate.matches(".*\\p{L}{2,}.*"))s+=6;
   if(NOISE_META.matcher(candidate).matches())s-=50;
   if(ITEM_LINE.matcher(candidate).matches())s-=50;
   if(afterBody)s-=35;
   if(isAddr)s-=45;
   if(isPost)s-=45;
   if(inspected>=2&&!BIZ_SUFFIX.matcher(candidate).matches()&&STREET_TOKEN.matcher(candidate).find())s-=30;
   if(PHONE_OR_EMAIL.matcher(candidate).find())s-=35;
   if(AMOUNT.matcher(candidate).find())s-=35;
   if(!isAddr&&!isPost){
    if(STORE_HINT.matcher(candidate).find())s+=25;
    String[] cls=PaymentParser.classifyRulesOnly(candidate,"Receipt scan","");
    if(!cls[0].equals("Miscellaneous"))s+=25;
   }
   if(s>bestScore){
    bestScore=s;
    bestMerchant=PaymentParser.stripSsmSuffix(candidate);
   }
  }
  if(bestMerchant.isEmpty())bestMerchant="Receipt";
  if(bestMerchant.length()>120)bestMerchant=bestMerchant.substring(0,120).trim();
  return bestMerchant;
 }

 private static int scoreTotalLine(String line){
  String low=line.toLowerCase(Locale.ROOT).trim();
  if(low.isEmpty())return 0;
  if(low.contains("subtotal")||low.contains("sub total")||low.contains("sub-total")||low.contains("sub ttl")||low.contains("before rounding")||low.contains("unrounded")||low.contains("before tax")||low.contains("excl"))return -1;
  if((low.contains("rounding")||low.contains("penggenapan"))&&!low.contains("after rounding")&&!low.contains("rounded")&&!low.contains("jumlah genap"))return -1;
  if(low.contains("change")||low.contains("baki")||low.contains("cash tendered")||low.contains("tendered")||low.contains("tunai")||low.contains("discount")||low.contains("diskaun")||low.contains("you saved")||low.contains("total saving")||low.contains("total qty")||low.contains("total item"))return -1;
  if(low.contains("total after rounding")||low.contains("rounded total")||low.contains("total rounded")||low.contains("nett total")||low.contains("net total")||low.contains("net amount")||low.contains("nett amount")||low.contains("total payable")||low.contains("net payable")||low.contains("amount payable")||low.contains("payable (rm)")||low.contains("payable (myr)")||low.contains("amount paid")||low.contains("amount due")||low.contains("total due")||low.contains("balance due")||low.contains("total incl. sst")||low.contains("total incl sst")||low.contains("total inclusive of sst")||low.contains("grand total")||low.contains("jumlah perlu dibayar")||low.contains("jumlah bayaran")||low.contains("jumlah dibayar")||low.contains("jumlah bersih")||low.contains("jumlah genap")||low.contains("jumlah besar")||low.contains("应付金额")||low.contains("实付金额")||low.contains("实收金额")||low.contains("总计")||low.contains("總計")||Pattern.compile("\\b(?:t[a-z]{2,5}\\s*[a-z]*p[a-z]*y[a-z]*bl|[a-z]*p[a-z]*y[a-z]*bl\\s*\\((?:rm|myr)\\)|[a-z]*pbyl\\s*\\((?:rm|myr)\\))").matcher(low).find())return 5;
  if(low.contains("total sales")||low.contains("total incl")||low.contains("total amount")||low.contains("total myr")||low.contains("total rm")||low.contains("total (rm)")||low.contains("total (myr)")||low.contains("jumlah")||low.contains("合计")||low.contains("合計")||low.contains("总额")||low.contains("總額"))return 4;
  if(Pattern.compile("(?<![\\p{L}0-9])(total|bayaran)(?![\\p{L}0-9])").matcher(low).find())return 3;
  return 0;
 }

 private static long resolveSingleOrCashDiff(String[] lines){
  Set<Long> charges=new LinkedHashSet<>();
  long tendered=-1,change=-1;
  for(String line:lines){
   String low=line.toLowerCase(Locale.ROOT);
   List<Long> vals=extractPositiveAmounts(line);
   if(low.contains("tunai")||low.contains("tendered")||low.contains("cash")){
    if(!vals.isEmpty()&&tendered<0)tendered=vals.get(0);
    continue;
   }
   if(low.contains("baki")||low.contains("change")){
    Matcher am=AMOUNT.matcher(line);
    if(am.find()&&change<0){
     String raw=amountGroup(am);
     long c=raw.replace(",","").matches("0+(?:\\.0+)?")?0:parseMinor(raw);
     if(c>=0)change=c;
    }
    continue;
   }
   if(scoreTotalLine(line)<0||low.contains("sst")||low.contains("cukai")||low.contains("tax")||low.contains("service charge")||low.contains("ip address"))continue;
   charges.addAll(vals);
  }
  if(charges.size()==1)return charges.iterator().next();
  if(charges.isEmpty()&&tendered>0&&change>=0&&tendered>change)return tendered-change;
  return 0;
 }

 private static String amountGroup(Matcher m){
  String a=m.group(1);
  return a!=null?a:m.group(2);
 }

 private static List<Long> extractPositiveAmounts(String line){
  List<Long> out=new ArrayList<>();
  Matcher m=AMOUNT.matcher(line);
  while(m.find()){
   long n=parseMinor(amountGroup(m));
   if(n>0)out.add(n);
  }
  return out;
 }

 private static long parseMinor(String raw){
  try{return Ledger.minor(raw.replace(",",""));}catch(IllegalArgumentException ignored){return -1;}
 }

 private static String extractDate(String text){
  String date=LocalDate.now().toString();
  Matcher dm=Pattern.compile("\\b(20[0-9]{2})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})\\b").matcher(text);
  try{
   if(dm.find())return LocalDate.of(Integer.parseInt(dm.group(1)),Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(3))).toString();
   dm=YMD_MONTH_DATE.matcher(text);
   if(dm.find()){int mon=monthNum(dm.group(2));if(mon>0)return LocalDate.of(Integer.parseInt(dm.group(1)),mon,Integer.parseInt(dm.group(3))).toString();}
   dm=NUM_DMY_SPACED.matcher(text);
   while(dm.find()){
    int d=Integer.parseInt(dm.group(1)),m=Integer.parseInt(dm.group(2)),y=Integer.parseInt(dm.group(3));
    if(m>=1&&m<=12&&d>=1&&d<=31){try{return LocalDate.of(y,m,d).toString();}catch(Exception ignored){}}
   }
   dm=MONTH_DATE.matcher(text);
   if(dm.find()){
    int yr=Integer.parseInt(dm.group(3));if(yr<100)yr+=2000;
    int mon=monthNum(dm.group(2));
    if(mon>0)return LocalDate.of(yr,mon,Integer.parseInt(dm.group(1))).toString();
   }
   dm=MONTH_FIRST_DATE.matcher(text);
   if(dm.find()){
    int yr=Integer.parseInt(dm.group(3));if(yr<100)yr+=2000;
    int mon=monthNum(dm.group(1));
    if(mon>0)return LocalDate.of(yr,mon,Integer.parseInt(dm.group(2))).toString();
   }
   dm=LABELED_DMY_COMPACT.matcher(text);
   if(dm.find()){
    int d=Integer.parseInt(dm.group(1)),m=Integer.parseInt(dm.group(2)),y=Integer.parseInt(dm.group(3));
    if(m>=1&&m<=12&&d>=1&&d<=31)return LocalDate.of(y,m,d).toString();
   }
   dm=NUM_DMY_SHORT.matcher(text);
   if(dm.find()){
    int yr=2000+Integer.parseInt(dm.group(3));
    return LocalDate.of(yr,Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(1))).toString();
   }
  }catch(Exception ignored){}
  return date;
 }

 private static int monthNum(String abbr){
  switch(abbr.toLowerCase(Locale.ROOT)){
   case "jan":case "januari":case "january":return 1;
   case "feb":case "februari":case "february":return 2;
   case "mar":case "mac":case "march":return 3;
   case "apr":case "april":return 4;
   case "may":case "mei":return 5;
   case "jun":case "june":return 6;
   case "jul":case "julai":case "july":return 7;
   case "aug":case "ogos":case "august":return 8;
   case "sep":case "sept":case "september":return 9;
   case "oct":case "okt":case "oktober":case "october":return 10;
   case "nov":case "november":return 11;
   case "dec":case "dis":case "disember":case "december":return 12;
   default:return 0;
  }
 }
}
