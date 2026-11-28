package app.moneylens;
import org.json.*;
import java.util.regex.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
/** Extracts suggestions; never commits OCR text directly to the ledger. */
public final class ReceiptParser {
 private static final Pattern AMOUNT=Pattern.compile("(?i)(?:(?:RM|MYR|RN1)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2}))(?![0-9]|\\.[0-9])");
 private static final Pattern DEC_AMOUNT=Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2})(?![0-9]|\\.[0-9])");
 private static final Pattern STANDALONE_AMOUNT=Pattern.compile("(?i)^\\s*(?:(?:RM|MYR|RN1)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)|([0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2}))\\s*$");
 private static final Pattern GENERIC_HEADER=Pattern.compile("(?i)^(?:(?:simplified\\s+)?tax\\s+invoice|lhdn\\s+e-?invoice(?:\\s+receipt)?|80mm\\s+thermal\\s+receipt|invoice|official\\s+receipt|cash\\s+bill|sales\\s+receipt|customer\\s+copy|merchant\\s+copy|duplicate|reprint|resit\\s+rasmi|invois\\s+cukai|credit\\s+card\\s+(?:statement|payment\\s+receipt)|toll\\s+payment|road\\s+tax|cukai\\s+jalan|resit\\s+bayaran|tarikan\\s+kasir|slip\\s+transaksi|slip\\s+bayaran|tanda\\s+bayar|items?(?:\\s+paid)?|itemized(?:\\s+charges)?|bill\\s+details|late\\s+payment\\s+fee|thank\\s+you.*|terima\\s+kasih.*|welcome(?:\\s+to)?)$");
 private static final Pattern WELCOME_PREFIX=Pattern.compile("(?i)^welcome\\s+to\\s+(.{2,})$");
 private static final Pattern SSM_LEAD_PREFIX=Pattern.compile("(?i)^(?:ssm(?:\\s+reg(?:\\.|\\s*no)?)?|co\\.?\\s*reg(?:\\s*no)?)\\s*[:#-]?\\s*(?:\\([0-9]{5,8}-[A-Za-z0-9]\\)|[0-9]{10,12})[:\\s-]+(.+)$");
 private static final Pattern TRAILING_TRUNC_REG=Pattern.compile("(?i)(?:\\s*\\([0-9]{4,8}(?:-[A-Za-z0-9]?)?|\\s+20[0-9]{4,10}[!?.]*|\\s+sdn\\.?\\s*bh|\\s+sdn\\.?\\s*bhd\\s*[0-9]|sdnbhd)\\s*$");
 private static final Pattern NOISE_META=Pattern.compile("(?i)^(?:sst|gst|ssm|reg(?:\\.|istration)?\\s*(?:no|id)|roc|rob|co\\.?\\s*reg|company\\s*(?:no|reg)|tin|myinvois|einvoice|e-invoice|lhdn\\s+(?:bucket|tax\\s+relief)|msic|brn|tel|hp|phone|fax|hotline|call|email|web|www|http|date|tarikh|time|masa|cashier|staff|server|waiter|table|meja|pax|order|bill|receipt|rcv|rdc|ref|terminal|pos|shift|batch|trace|approval|items?(?:\\s+paid)?|itemized|customer\\s*(?:name|id)?|client|subscriber|service|description|desc|qty|quantity|amount|payment\\s+(?:of|for)|due\\s+date|invoice\\s+no|job|produk|barang|harga|perkhidmatan|penyertaan|payee|bill\\s+details|late\\s+payment\\s+fee)\\b[:.#\\s-]*.*");
 private static final Pattern ADDRESS_LINE=Pattern.compile("(?i)^(?:[0-9]{1,4}[a-z]?(?:-[0-9]{1,4})?[,\\s]+)?(?:no\\.?|lot|plot|level|lvl|floor|flr|unit|suite|block|blk|ground\\s+floor|g-|lg-|ug-|b1-|jalan|jln|lorong|lrg|persiaran|lebuh|taman|tmn|bandar|seksyen|section|kampung|kg\\.?|kompleks|pusat\\s+(?:bandar|komersial|membeli))\\b.*");
 private static final Pattern STREET_TOKEN=Pattern.compile("(?i)\\b(?:jalan|jln|lorong|lrg|persiaran|lebuhraya|lebuh)\\b");
 private static final Pattern BIZ_SUFFIX=Pattern.compile("(?i).*\\b(?:sdn\\.?\\s*bhd|bhd|berhad|enterprise|trading|services|solutions|holdings|kindergarten|tadika|taska|school|sekolah|klinik|clinic|farmasi|pharmacy|restoran|restaurant|cafe|café|mart|store|grocer|supermarket|motors?|workshop|bengkel|resources|ventures)\\b\\.?$");
 private static final Pattern ITEM_LINE=Pattern.compile("(?i)^(?:[-*•]\\s+.*|(?:[0-9]{1,2}[.)]\\s+|1\\s+(?!Malaysia)[A-Za-z]).*|.*\\b[xX×]\\s*[0-9]{1,3}\\s*$|(?:ptptn\\s+repayment|spaylater|grabpaylater|atome\\s+bnpl|shopee\\s+order|lazada\\s+order|i-saraan).*)$");
 private static final Pattern POSTCODE_STATE=Pattern.compile("(?i)\\b[0-9oOlIeE]{5},?\\s*(?:kuala\\s*lumpur|selangor|petaling|subang|shah\\s*alam|klang|kajang|puchong|ampang|cheras|penang|pulau\\s*pinang|johor|melaka|perak|ipoh|kedah|alor\\s*setar|pahang|kuantan|kelantan|terengganu|negeri\\s*sembilan|seremban|sabah|sarawak|kuching|kota\\s*kinabalu|putrajaya|cyberjaya|malaysia)\\b");
 private static final Pattern PHONE_OR_EMAIL=Pattern.compile("(?i)(?:@|\\b(?:0[1-9]-?[0-9]{6,8}|\\+?60[0-9-]{7,11})\\b|https?://|www\\.)");
 private static final Pattern STORE_HINT=Pattern.compile("(?i)\\b(?:sdn\\.?\\s*bhd\\.?|bhd|berhad|plt|llp|enterprise|trading|holdings|resources|services|solutions|restoran|restaurant|kedai|warung|warong|gerai|medan\\s+selera|hawker|mamak|kandar|penyet|lemak|kopitiam|bistro|cafe|café|kafe|coffee|kopi|pelita|q\\s*bistro|oriental\\s+kopi|oldtown|tealive|zus|chagee|mixue|gigi|starbucks|mcdonald|kfc|burger\\s+king|marrybrown|subway|bakery|farmasi|pharmacy|obat|ubat|klinik|clinic|hospital|dental|optometrist|optical|speedmart|kk\\s*(?:super\\s*)?mart|mynews|familymart|cu\\s*mart|emart24|bilabila|econsave|mydin|lotus|aeon|giant|jaya\\s+grocer|village\\s+grocer|segi\\s+fresh|hero\\s*market|tf\\s+value|watsons|guardian|mr\\.?\\s*diy|ecoshop|daiso|popular|uniqlo|padini|mart|supermarket|hypermarket|pasaraya|pasar|grocer|mini\\s*market|petrol|petronas|shell|caltex|bhpetrol|setel|gentari|jomcharge|chargev|ev\\s+charging|station|hardware|carpentry|maintenance|electronics|marketplace|store|bookshop|bookstore|kindergarten|tadika|plaza\\s+tol|toll\\s+plaza|condominium|condo)\\b");
 private static final Pattern BODY_SECTION=Pattern.compile("(?i)^(?:items?(?:\\s+paid)?|itemized(?:\\s+charges)?|bill\\s+details|subtotal|qty|quantity|description)\\b.*");
 private static final String MONTHS="januari|january|jan|februari|february|feb|march|mac|mar|april|apr|may|mei|june|jun|julai|july|jul|ogos|august|aug|september|sept|sep|oktober|october|oct|okt|november|nov|disember|december|dis|dec";
 private static final Pattern MONTH_DATE=Pattern.compile("(?i)\\b([0-9]{1,2})(?:st|nd|rd|th)?[-/\\s]+("+MONTHS+")[-/\\s,]+(20[0-9]{2}|[0-9]{2})(?=[^0-9]|[0-9]{2}:|$)");
 private static final Pattern MONTH_FIRST_DATE=Pattern.compile("(?i)\\b("+MONTHS+")\\s+([0-9]{1,2})(?:st|nd|rd|th)?[,\\s]+(20[0-9]{2}|[0-9]{2})(?=[^0-9]|[0-9]{2}:|$)");
 private static final Pattern YMD_MONTH_DATE=Pattern.compile("(?i)\\b(20[0-9]{2})[-/\\s]+("+MONTHS+")[-/\\s]+([0-9]{1,2})(?:st|nd|rd|th)?\\b");
 private static final Pattern NUM_DMY_SPACED=Pattern.compile("\\b([0-9]{1,2})[-/.\\s]+([0-9]{1,2})[-/.\\s]+(20[0-9]{2})(?=[^0-9]|[0-9]{2}:|$)");
 private static final Pattern LABELED_DMY_COMPACT=Pattern.compile("(?i)(?:date|tarikh)\\s*[:.]?\\s*([0-9]{2})[-/.\\s]*([0-9]{2})[-/.\\s]*(20[0-9]{2})(?=[^0-9]|[0-9]{2}:|$)");
 private static final Pattern NUM_DMY_SHORT=Pattern.compile("\\b([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{2})(?=[^0-9]|[0-9]{2}:|$)");
 private static final Pattern LHDN_TIN_EXTRACT=Pattern.compile("(?i)\\bT[1I]N[:#?\\s]*([A-Z]{0,3}[0-9]{9,14})(?=\\b|Inv|SSM|Tel|Date|$)");
 private static final Pattern LHDN_INV_EXTRACT=Pattern.compile("(?i)\\b(?:MyInV[0o]is\\s*UUID|Inv[0o]ice(?:\\s*(?:No|Ref))?)\\s*[:#]\\s*([A-Za-z0-9-]{6,36})(?=\\b|MyIn|TIN|$)");

 public static JSONObject parse(String text)throws Exception{
  if(text==null)text="";
  String[] lines=splitReceiptLines(text);
  ReceiptFieldTagger.TaggedLines tagged=ReceiptFieldTagger.tag(lines);
  String merchant=extractMerchant(lines,tagged);

  boolean hasRoundingLine=false;
  for(String line:lines){
   String low=normTotalText(line);
   if((low.contains("rounding")||low.contains("penggenapan")||low.contains("5-sen round")||low.contains("5 sen round"))&&!low.contains("after rounding")&&!low.contains("rounded")&&!low.contains("jumlah genap")){
    hasRoundingLine=true;break;
   }
  }

  long subtotalMinor=0L,sstMinor=0L;
  if(tagged!=null){
   for(int i=0;i<lines.length;i++){
    int lbl=tagged.labelIds[i];
    if(lbl==ReceiptFieldTagger.LABEL_SUBTOTAL){
     List<Long> sa=extractPreferredAmounts(lines[i]);
     if(!sa.isEmpty())subtotalMinor=sa.get(sa.size()-1);
    }else if(lbl==ReceiptFieldTagger.LABEL_TAX_SST){
     for(long v:extractPreferredAmounts(lines[i]))sstMinor+=v;
    }
   }
  }

  long best=0;int score=-1,bestLineIdx=-1;boolean ambiguous=false;
  for(int i=0;i<lines.length;i++){
   String line=lines[i];
   int s=scoreTotalLine(line);
   if(s<3)continue;
   List<Long> found=extractTotalLineAmounts(line,s);
   if(found.isEmpty()&&i+1<lines.length){
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
  long tendered=-1,change=-1;
  Pattern cashTenderRe=Pattern.compile("(?i)\\b(?:tunai|cash|tendered)\\b[^0-9\\r\\n]{0,20}(?:RM|MYR|RN1)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
  for(int i=0;i<lines.length;i++){
   String low=normTotalText(lines[i]);
   Matcher ctm=cashTenderRe.matcher(lines[i]);
   if(ctm.find()){
    long tVal=parseMinor(ctm.group(1));
    if(tVal>0L&&tendered<0L)tendered=tVal;
   }
   if(low.contains("baki")||low.contains("change")){
    Matcher am=AMOUNT.matcher(lines[i]);
    while(am.find()){
     String raw=amountGroup(am);
     long c=raw.replace(",","").matches("0+(?:\\.0+)?")?0:parseMinor(raw);
     if(c>=0)change=c;
    }
   }
  }
  long cashNet=(tendered>0L&&change>=0L&&tendered>change)?(tendered-change):0L;
  if(cashNet>0L){
   if(ambiguous||score<3||best<=0L){
    best=cashNet;score=Math.max(score,4);ambiguous=false;
   }else if(hasRoundingLine&&Math.abs(best-cashNet)<=4L&&best%5L!=0L&&cashNet%5L==0L){
    best=cashNet;
   }else if((best%10L==0L)&&(best/10L==cashNet/10L||best/100L==cashNet/100L)&&cashNet!=best){
    best=cashNet;
   }else if((best==cashNet*100L||best==cashNet*10L||best*10L==cashNet||best*100L==cashNet)&&(subtotalMinor<=0L||Math.abs((subtotalMinor+sstMinor)-cashNet)<=5L)){
    best=cashNet;score=Math.max(score,4);ambiguous=false;
   }
  }

  if(score<3&&!ambiguous){
   long resolved=resolveSingleOrCashDiff(lines);
   if(resolved>0){best=resolved;score=3;}
  }else if(score>=3&&!ambiguous&&best>0&&bestLineIdx>=0){
   long postTax=0;
   for(int i=0;i<lines.length;i++){
    String low=normTotalText(lines[i]);
    List<Long> vals=extractPreferredAmounts(lines[i]);
    if(i>bestLineIdx&&(low.contains("cukai")||low.contains("sst")||low.contains("service tax")||low.contains("service charge"))&&!vals.isEmpty()){
     postTax+=vals.get(vals.size()-1);
    }
   }
   if(postTax>0&&tendered>0&&change>=0&&tendered-change==best+postTax){
    best=tendered-change;
   }
  }

  // Fusion with Linear-Chain CRF GRAND_TOTAL predictions (preserving strict ambiguity rejection)
  if(tagged!=null&&subtotalMinor>0L){
   long expectedSum=subtotalMinor+sstMinor;
   long roundedSum=hasRoundingLine?((expectedSum+2L)/5L)*5L:expectedSum;
   if(ambiguous&&roundedSum>0L&&(sstMinor>0L||hasRoundingLine||cashNet==roundedSum)){
    best=roundedSum;score=Math.max(score,4);ambiguous=false;
   }
  }
  if(!ambiguous&&tagged!=null){
   List<Long> crfAmts=new ArrayList<>();
   List<Integer> crfIdxs=new ArrayList<>();
   List<Double> crfProbs=new ArrayList<>();
   for(int i=0;i<lines.length;i++){
    if(tagged.labelIds[i]!=ReceiptFieldTagger.LABEL_GRAND_TOTAL)continue;
    List<Long> found=extractTotalLineAmounts(lines[i],5);
    int srcIdx=i;
    if(found.isEmpty()&&i+1<lines.length){
     Matcher ns=STANDALONE_AMOUNT.matcher(lines[i+1]);
     if(ns.find()){
      long n=parseMinor(amountGroup(ns));
      if(n>0){found.add(n);srcIdx=i+1;}
     }
    }
    if(!found.isEmpty()){
     crfAmts.add(found.get(found.size()-1));
     crfIdxs.add(srcIdx);
     crfProbs.add(tagged.lineProbs[i][ReceiptFieldTagger.LABEL_GRAND_TOTAL]);
    }
   }
   if(!crfAmts.isEmpty()){
    Set<Long> uniq=new LinkedHashSet<>(crfAmts);
    if(best<=0||score<4){
     if(uniq.size()==1){
      best=crfAmts.get(0);score=Math.max(score,3);bestLineIdx=crfIdxs.get(0);
     }else{
      long lastAmt=crfAmts.get(crfAmts.size()-1),firstAmt=crfAmts.get(0);
      boolean roundedDiff=hasRoundingLine&&Math.abs(lastAmt-firstAmt)<=4&&(lastAmt%5==0);
      boolean sumMatch=subtotalMinor>0&&Math.abs(lastAmt-(subtotalMinor+sstMinor))<=4;
      if(score<3&&(roundedDiff||sumMatch)){
       best=lastAmt;score=4;bestLineIdx=crfIdxs.get(crfIdxs.size()-1);
      }
     }
    }
    if(score==3&&!ambiguous&&crfAmts.size()==1){
     long cand=crfAmts.get(0);int cIdx=crfIdxs.get(0);double cProb=crfProbs.get(0);
     String nl=normTotalText(lines[cIdx]);
     boolean highKw=nl.contains("final")||nl.contains("rounded")||nl.contains("bersih")||nl.contains("net");
     boolean sumMatch=subtotalMinor>0&&Math.abs(cand-(subtotalMinor+sstMinor))<=4&&Math.abs(best-(subtotalMinor+sstMinor))>4;
     if(cand!=best&&cProb>=0.70&&(highKw||sumMatch)){
      best=cand;bestLineIdx=cIdx;
     }
    }
   }
   // Recover truncated decimal grand total (e.g. "MYR 1747." or "Rm 1230,45") from non-cash payment tender
   if(best>0&&best%100L==0L){
    for(int i=Math.max(0,bestLineIdx+1);i<lines.length;i++){
     String pl=normTotalText(lines[i]);
     if(pl.contains("duitnow")||pl.contains("ewallet")||pl.contains("tng")||pl.contains("mae")||pl.contains("visa")||pl.contains("master")||pl.contains("mydebit")||pl.contains("paid via")){
      List<Long> pa=extractPreferredAmounts(lines[i]);
      if(!pa.isEmpty()){
       long tender=pa.get(pa.size()-1);
       if(tender/100L==best/100L&&tender!=best){best=tender;break;}
      }
     }
    }
   }
   // Arithmetic outlier guard, missing-total item-sum recovery, & CRF subtotal + SST recovery
   long itemSumMinor=0L;int itemLineCount=0;
   if(tagged!=null){
    for(int i=0;i<lines.length;i++){
     if(tagged.labelIds[i]==ReceiptFieldTagger.LABEL_ITEM){
      List<Long> ia=extractPreferredAmounts(lines[i]);
      if(!ia.isEmpty()){
       long iv=ia.get(ia.size()-1);
       if(iv>0L&&iv<Ledger.MAX){itemSumMinor+=iv;itemLineCount++;}
      }
     }
    }
   }
   long arithRef=0L;
   if(subtotalMinor>0L){
    long expectedSum=subtotalMinor+sstMinor;
    long roundedSum=hasRoundingLine?((expectedSum+2L)/5L)*5L:expectedSum;
    arithRef=roundedSum;
    if(best>0L&&arithRef>0L&&(best>=arithRef*5L||best*5L<=arithRef)
      &&(itemLineCount==0||Math.abs(itemSumMinor-subtotalMinor)<=Math.max(50L,subtotalMinor/10L))){
     best=arithRef;score=Math.max(score,4);ambiguous=false;
    }else if(best>0L&&((best%100L==0L&&roundedSum/100L==best/100L)||(best%10L==0L&&best/10L==roundedSum/10L))&&roundedSum!=best){
     best=roundedSum;
    }else if(best>0L&&hasRoundingLine&&Math.abs(best-expectedSum)<=4L&&best!=roundedSum&&best%5L!=0L){
     best=roundedSum;
    }else if((best<=0L||score<3)&&expectedSum>0L&&(sstMinor>0L||hasRoundingLine||crfAmts.isEmpty())){
     best=roundedSum;
     score=Math.max(score,3);
    }
   }
   if(!ambiguous&&(best<=0L||score<3)&&arithRef==0L&&itemLineCount>=2&&itemSumMinor>0L&&score<3){
    long itemTotal=itemSumMinor+sstMinor;
    best=hasRoundingLine?((itemTotal+2L)/5L)*5L:itemTotal;
    score=3;
   }
  }

  String date=extractDate(text,lines,tagged);
  String[] cs=classifyReceipt(merchant,text,lines,tagged);
  MerchantCanonicalizer.Result canon=MerchantCanonicalizer.canonicalize(merchant,"");
  boolean hasCanon=canon!=null&&canon.matchedCatalog&&canon.similarity>=0.62;
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
  if(hasCanon)out.put("canonicalMerchant",canon.canonicalName);
  attachStructuredMetadata(out,lines,tagged,subtotalMinor,sstMinor);
  return out;
 }

 private static final Pattern RECEIPT_OVERRIDE_RE=Pattern.compile("(?i)\\b(?:late\\s+payment\\s+fee|caj\\s+lewat\\s+bayar|hire\\s+purchase\\s+installment|road\\s+tax|cukai\\s+jalan)\\b");
 private static final Pattern GENERIC_SME_HEADER_RE=Pattern.compile("(?i)(?:^(?:gerai|kedai|warung|restoran|bengkel|pasar\\s+mini|klinik|farmasi|pusat|syarikat|perniagaan)\\b|\\b(?:sdn\\.?\\s*bhd|enterprise|trading|services)\\b)");
 private static final Pattern KNOWN_CHAIN_RE=Pattern.compile("(?i)\\b(?:petronas|shell|caltex|bhpetrol|setel|gentari|tnb|tenaga\\s+nasional|unifi|maxis|celcom|digi|astro|ikea|watsons|guardian|caring|99\\s*speedmart|7-?eleven|familymart|aeon|lotus|mydin|jaya\\s+grocer|village\\s+grocer|mcdonald|kfc|starbucks|zus|tealive|popular|uniqlo|decathlon|taylor'?s|sunway|kpj|gleneagles|pantai|grab|shopee|lazada|mynews)\\b");

 private static String cleanItemText(String rawLine){
  return rawLine
   .replaceAll("(?i)(?:RM|MYR|RN1)\\s*[0-9,]+(?:\\.[0-9]{1,2})?"," ")
   .replaceAll("[0-9,]+\\.[0-9]{2}"," ")
   .replaceFirst("(?i)^(?:[0-9]{1,2}[.)]\\s+|[-*•]\\s+|[0-9]+\\s*[xX×]\\s+)","")
   .replaceAll("(?i)\\s*[xX×]\\s*[0-9]+\\b","")
   .replaceAll("\\s+"," ").trim();
 }

 private static String[] classifyReceipt(String merchant,String text,String[] lines,ReceiptFieldTagger.TaggedLines tagged){
  String overrideText=RECEIPT_OVERRIDE_RE.matcher(text).find()?text:"";
  String[] merchRules=PaymentParser.classifyRulesOnly(merchant,"Receipt scan",overrideText);
  StringBuilder itemBuf=new StringBuilder();
  int itemCount=0;
  if(tagged!=null){
   for(int i=0;i<lines.length;i++){
    if(tagged.labelIds[i]==ReceiptFieldTagger.LABEL_ITEM){
     String cleaned=cleanItemText(lines[i]);
     if(cleaned.length()>=2){
      if(itemBuf.length()>0)itemBuf.append(' ');
      itemBuf.append(cleaned);
      itemCount++;
     }
    }
   }
  }
  String itemsStr=itemBuf.toString().trim();
  if(!merchRules[0].equals("Miscellaneous")){
   boolean genericSme=GENERIC_SME_HEADER_RE.matcher(merchant).find()&&!KNOWN_CHAIN_RE.matcher(merchant).find();
   if(genericSme&&itemsStr.length()>=4&&itemCount>=2){
    String[] itemRules=PaymentParser.classifyRulesOnly(itemsStr,"Receipt scan",itemsStr);
    String[] itemCls=!itemRules[0].equals("Miscellaneous")?itemRules:PaymentParser.classify(itemsStr,"Receipt scan",itemsStr);
    if(!itemCls[0].equals("Miscellaneous")&&!itemCls[0].equals(merchRules[0])){
     boolean strongConf=!itemRules[0].equals("Miscellaneous");
     if(!strongConf){
      MlClassifier.Prediction ml=MlClassifier.predict(itemsStr,itemsStr);
      MlNeuralReranker.Prediction nn=MlNeuralReranker.predict(itemsStr,itemsStr,ml);
      strongConf=(ml!=null&&!"Miscellaneous".equals(ml.category)&&ml.probability>=0.55)
        ||(nn!=null&&!"Miscellaneous".equals(nn.category)&&nn.probability>=0.55);
     }
     if(strongConf)return itemCls;
    }
   }
   return merchRules;
  }
  String mlCtx=text.trim().isEmpty()?"":text
    .replaceAll("(?i)\\bT[1I]N\\s*[:#?]?\\s*[A-Z0-9-]{6,20}","")
    .replaceAll("(?i)\\bMyInV[0o]is\\s*UUID\\s*[:#]?\\s*[A-Za-z0-9-]+","")
    .replaceAll("(?i)\\bInv[0o]ice(?:\\s*(?:No|Ref))?\\s*[:#]\\s*[A-Za-z0-9-]+","")
    .replaceAll("(?i)\\bTel\\s*[:#]?\\s*[0-9+-][0-9\\s-]{6,15}","")
    .trim();
  String[] fullCls=PaymentParser.classify(merchant,"Receipt scan",mlCtx);
  if(!fullCls[0].equals("Miscellaneous")||itemsStr.length()<4)return fullCls;
  String[] itemRules=PaymentParser.classifyRulesOnly(itemsStr,"Receipt scan",itemsStr);
  return !itemRules[0].equals("Miscellaneous")?itemRules:PaymentParser.classify(itemsStr,"Receipt scan",itemsStr);
 }

 private static void attachStructuredMetadata(JSONObject out,String[] lines,ReceiptFieldTagger.TaggedLines tagged,long subtotalMinor,long sstMinor)throws Exception{
  if(subtotalMinor>0L)out.put("subtotalMinor",subtotalMinor);
  if(sstMinor>0L)out.put("sstMinor",sstMinor);
  if(tagged!=null){
   JSONArray items=new JSONArray();
   String lhdnTin="",lhdnInv="";
   for(int i=0;i<lines.length;i++){
    int lbl=tagged.labelIds[i];
    if(lbl==ReceiptFieldTagger.LABEL_ITEM){
     List<Long> ia=extractPreferredAmounts(lines[i]);
     if(!ia.isEmpty()){
      long itemAmt=ia.get(ia.size()-1);
      String itemName=cleanItemText(lines[i]);
      if(itemName.length()>=2&&itemAmt>0L){
       items.put(new JSONObject().put("name",itemName).put("amount",itemAmt));
      }
     }
    }
    if(lbl==ReceiptFieldTagger.LABEL_LHDN_META||lbl==ReceiptFieldTagger.LABEL_HEADER_META){
     if(lhdnTin.isEmpty()){
      Matcher tm=LHDN_TIN_EXTRACT.matcher(lines[i]);
      if(tm.find())lhdnTin=tm.group(1).toUpperCase(Locale.ROOT);
     }
     if(lhdnInv.isEmpty()){
      Matcher im=LHDN_INV_EXTRACT.matcher(lines[i]);
      if(im.find())lhdnInv=im.group(1);
     }
    }
   }
   if(lhdnTin.isEmpty()){
    for(String ln:lines){Matcher tm=LHDN_TIN_EXTRACT.matcher(ln);if(tm.find()){lhdnTin=tm.group(1).toUpperCase(Locale.ROOT);break;}}
   }
   if(lhdnInv.isEmpty()){
    for(String ln:lines){Matcher im=LHDN_INV_EXTRACT.matcher(ln);if(im.find()){lhdnInv=im.group(1);break;}}
   }
   if(items.length()>0)out.put("items",items);
   if(!lhdnTin.isEmpty())out.put("lhdnTin",lhdnTin);
   if(!lhdnInv.isEmpty())out.put("lhdnInvoiceRef",lhdnInv);
  }
 }

 private static String extractMerchant(String[] lines,ReceiptFieldTagger.TaggedLines tagged){
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
   Matcher ssmLead=SSM_LEAD_PREFIX.matcher(candidate);
   if(ssmLead.matches()&&ssmLead.group(1).replaceAll("[^\\p{L}]","").length()>=3){
    candidate=ssmLead.group(1).trim();
   }
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
   if(tagged!=null&&i<tagged.labelIds.length){
    int lbl=tagged.labelIds[i];
    double prob=tagged.lineProbs[i][lbl];
    if(lbl==ReceiptFieldTagger.LABEL_MERCHANT&&!NOISE_META.matcher(candidate).matches()&&!isAddr){
     s+=(int)Math.round(35.0*prob);
    }else if(prob>=0.65&&(lbl==ReceiptFieldTagger.LABEL_HEADER_META||lbl==ReceiptFieldTagger.LABEL_DATE||lbl==ReceiptFieldTagger.LABEL_ITEM||lbl==ReceiptFieldTagger.LABEL_LHDN_META||lbl==ReceiptFieldTagger.LABEL_SUBTOTAL||lbl==ReceiptFieldTagger.LABEL_TAX_SST||lbl==ReceiptFieldTagger.LABEL_GRAND_TOTAL||lbl==ReceiptFieldTagger.LABEL_PAYMENT_TENDER)){
     s-=(int)Math.round(22.0*prob);
    }
   }
   if(s>bestScore){
    bestScore=s;
    String cleaned=candidate.trim();
    for(int pass=0;pass<2;pass++){
     String next=TRAILING_TRUNC_REG.matcher(cleaned).replaceAll("").trim();
     if(next.equals(cleaned)||next.length()<2)break;
     cleaned=next;
    }
    String stripped=PaymentParser.stripSsmSuffix(cleaned.length()>=2?cleaned:candidate);
    String afterSsm=TRAILING_TRUNC_REG.matcher(stripped).replaceAll("").trim();
    bestMerchant=afterSsm.length()>=2?afterSsm:stripped;
   }
  }
  if(bestMerchant.isEmpty())bestMerchant="Receipt";
  if(bestMerchant.length()>120)bestMerchant=bestMerchant.substring(0,120).trim();
  return bestMerchant;
 }

 private static final Pattern SCORE5_KW_RE=Pattern.compile("(?i)(?:final\\s+(?:total|amount)|total\\s+paid|total\\s+after\\s+rounding|rounded\\s+total|total\\s+rounded|nett?\\s+total|nett?\\s+amount|total\\s+payable|nett?\\s+payable|amount\\s+payable|payable\\s*\\((?:rm|myr)\\)|amount\\s+paid|amount\\s+due|total\\s+due|balance\\s+due|total\\s+incl\\.?\\s*sst|total\\s+inclusive\\s+of\\s+sst|grand\\s+total|jumlah\\s+perlu\\s+dibayar|jumlah\\s+bayaran|jumlah\\s+dibayar|jumlah\\s+bersih|jumlah\\s+genap|jumlah\\s+besar|应付金额|实付金额|实收金额|总计|總計|\\bt[a-z]{2,5}\\s*[a-z]*p[a-z]*y[a-z]*bl\\b|\\b[a-z]*p[a-z]*y[a-z]*bl\\s*\\((?:rm|myr)\\)|\\b[a-z]*pbyl\\s*\\((?:rm|myr)\\))");
 private static final Pattern SCORE4_KW_RE=Pattern.compile("(?i)(?:total\\s+sales|total\\s+incl|total\\s+amount|total\\s+myr|total\\s+rm|total\\s*\\((?:rm|myr)\\)|jumlah|合计|合計|总额|總額)");
 private static final Pattern TOTAL_WORD_RE=Pattern.compile("(?<![\\p{L}0-9])(total|tot|bayaran)(?![\\p{L}0-9])");

 private static String normTotalText(String s){
  return ReceiptFieldTagger.ocrNormText(s)
   .replace("am0unt","amount")
   .replace("jumlahbersih","jumlah bersih")
   .replace("chnage","change")
   .replace("sbtotal","subtotal");
 }

 private static String[] splitReceiptLines(String text){
  String[] split=text.replace('\u00A0',' ').split("\\r?\\n");
  List<String> raw=new ArrayList<>();
  boolean hasLongMultiAmt=false;
  for(String ln:split){
   String t=ln.trim();
   if(!t.isEmpty()){
    raw.add(t);
    if(t.length()>140){
     Matcher dm=DEC_AMOUNT.matcher(t);
     int c=0;
     while(dm.find()){if(++c>=2){hasLongMultiAmt=true;break;}}
    }
   }
  }
  if(raw.size()<=2||hasLongMultiAmt){
   List<String> out=new ArrayList<>();
   for(String ln:raw){
    if(raw.size()<=2||ln.length()>140){
     String flat=ln
      .replaceAll("\\s*\\|\\s*","\n")
      .replaceAll("\\s*\\.{3,}\\s*","\n")
      .replaceAll("\\s*-{3,}\\s*","\n")
      .replaceAll("(?i)[ \\t]+-[ \\t]+(?=(?:20[0-9]{2}|[0-9]{2}[/-]|tin\\b|ssm\\b|sst\\b|myr\\b|rm\\b|total\\b|subtotal\\b|lhdn\\b|jalan\\b|lot\\b|invoice\\b|date\\b|tarikh\\b))","\n")
      .replaceAll("(?i)(?:[,.;:]?[ \\t]+|(?<=[0-9]))(?=(?:sub\\s*-?\\s*total|sub\\s+ttl|sbtotal|sub\\s*:|sst(?:\\s*@|\\s*[0-9]|[:\\s])|service\\s+(?:tax|charge)|(?<!5[- ]sen\\s)(?<!after\\s)(?:(?:5[- ]?sen\\s+)?round(?:ing)?(?:\\s+(?:adj|adjustment|off))?|runding|penggenapan)|final\\s+(?:total|amount)|grand\\s+total|total\\s+payable|net(?:t)?\\s+(?:total|amount|payable)|(?<!final\\s)(?<!net\\s)(?<!nett\\s)amount\\s+(?:paid|due|payable)|jumlah\\s+(?:kecil|perlu|bayaran|dibayar|bersih|genap|besar)|jumlah(?=:|[ \\t]+(?:rm|myr))|(?<!final\\s)(?<!grand\\s)(?<!sub\\s)(?<!net\\s)(?<!nett\\s)(?<!rounded\\s)(?:total\\s*(?::|\\b(?:after|incl|sales|amount|myr|rm|due|payable))|total\\b(?=[ \\t]+(?:rm|myr|[0-9])))|tot:|duitnow\\s*qr|cash(?:\\s+(?:paid|tendered))?|tunai|tendered|change\\b|chnage\\b|baki\\b|balance\\s+due|tin\\s*:|ssm(?:\\s+reg)?\\s*:|invoice(?:\\s+(?:no|ref))?\\s*:|receipt(?:\\s+no)?\\s*:|date\\s*:|tarikh\\s*:|no\\.?\\s*(?:rujukan|pelanggan)|ref(?:erence)?(?:\\s+no)?\\s*:|lhdn\\s+bucket|item\\s*:|items\\s*:|(?<!final\\s)(?<!net\\s)(?<!nett\\s)(?<!total\\s)amount\\s*:|price\\s*:|payee\\s*:|[0-9]{1,4}(?:,|[ \\t]+)(?:jalan|jln|lorong|lot|unit|level|persiaran|taman)\\b|(?:unit|lot|no\\.?|jalan|jln)\\s+[0-9]{1,4}\\b|20[0-9]{10}\\b|[0-9]{1,2}[.)]\\s+(?:[0-9]{1,2}[xX\\u00d7]|\\p{L})))","\n")
      .replaceAll("(?i)(?<![ \\t])(?<!date:)(?<!tarikh:)(?<!date)(?<!tarikh)[ \\t]+(?=(?:20[0-9]{2}[-/.][0-9]{1,2}[-/.][0-9]{1,2}|[0-9]{1,2}[-/.][0-9]{1,2}[-/.]20[0-9]{2}|[0-9]{1,2}[ \\t]+(?:januari|january|jan|februari|february|feb|march|mac|mar|april|apr|may|mei|june|jun|julai|july|jul|ogos|august|aug|september|sept|sep|oktober|october|oct|okt|november|nov|disember|december|dis|dec)[ \\t]+20[0-9]{2})\\b)","\n");
     for(String p:flat.split("\\r?\\n")){
      String pt=p.trim();
      if(!pt.isEmpty())out.add(pt);
     }
    }else{
     out.add(ln);
    }
   }
   if(!out.isEmpty())return out.toArray(new String[0]);
  }
  return raw.toArray(new String[0]);
 }

 private static int scoreTotalLine(String line){
  String low=normTotalText(line).trim();
  if(low.isEmpty())return 0;
  // Exclude pure negative/non-total lines unless a higher-priority Score-5 Grand Total phrase is also on a merged OCR line
  boolean hasScore5=SCORE5_KW_RE.matcher(low).find();
  if(hasScore5)return 5;
  if(low.contains("subtotal")||low.contains("sub total")||low.contains("sub-total")||low.contains("sub ttl")||low.contains("jumlah kecil")||low.matches(".*\\bsub\\s*:.*")||low.contains("before rounding")||low.contains("unrounded")||low.contains("before tax")||low.contains("excl"))return -1;
  if((low.contains("rounding")||low.contains("penggenapan")||low.contains("5-sen round")||low.contains("5 sen round"))&&!low.contains("after rounding")&&!low.contains("rounded")&&!low.contains("jumlah genap"))return -1;
  if(low.contains("change")||low.contains("baki")||low.contains("cash tendered")||low.contains("cash paid")||low.contains("tendered")||low.contains("tunai")||low.contains("discount")||low.contains("diskaun")||low.contains("you saved")||low.contains("total saving")||low.contains("total qty")||low.contains("total item"))return -1;
  if(SCORE4_KW_RE.matcher(low).find())return 4;
  if(TOTAL_WORD_RE.matcher(low).find())return 3;
  return 0;
 }

 private static List<Long> extractTotalLineAmounts(String line,int score){
  if(score>=4){
   String low=normTotalText(line);
   if(low.contains("tunai")||low.contains("cash")||low.contains("tendered")||low.contains("subtotal")||low.contains("sub-total")||low.contains("sub total")||low.contains("rounding")||low.contains("penggenapan")||low.contains("baki")||low.contains("change")){
    String cleaned=line
     .replaceAll("(?i)\\b(?:tunai|cash|tendered|subtotal|sub\\s*-?\\s*total|baki|change|sst(?:\\s*[0-9]+%)?)\\b[^0-9\\r\\n]{0,16}(?:RM|MYR|RN1)?\\s*[0-9,]+(?:\\.[0-9]{1,2})?"," ")
     .replaceAll("(?i)(?<!after\\s)\\b(?:rounding(?:\\s+adj)?|penggenapan|5[- ]?sen\\s+round(?:ing)?)\\b[^0-9+\\-\\r\\n]{0,28}(?:[+\\-]\\s*)?(?:RM|MYR|RN1)?\\s*(?:0\\.[0-9]{1,2}|[0-9,]+(?:\\.[0-9]{1,2})?)"," ");
    List<Long> sub=extractPreferredAmounts(cleaned);
    if(!sub.isEmpty())return sub;
   }
  }
  return extractPreferredAmounts(line);
 }

 private static List<Long> extractPreferredAmounts(String line){
  Matcher dec=DEC_AMOUNT.matcher(line);
  List<Long> decOut=new ArrayList<>();
  while(dec.find()){
   long n=parseMinor(dec.group(1));
   if(n>0)decOut.add(n);
  }
  if(!decOut.isEmpty())return decOut;
  return extractPositiveAmounts(line);
 }

 private static long resolveSingleOrCashDiff(String[] lines){
  Set<Long> charges=new LinkedHashSet<>();
  long tendered=-1,change=-1,tenderPay=0L;
  for(String line:lines){
   String low=normTotalText(line);
   List<Long> vals=extractPreferredAmounts(line);
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
   if((low.contains("duitnow")||low.contains("paid via")||low.contains("tng")||low.contains("mae")||low.contains("visa"))&&!vals.isEmpty()){
    tenderPay=vals.get(vals.size()-1);
   }
   if(scoreTotalLine(line)<0||low.contains("sst")||low.contains("cukai")||low.contains("tax")||low.contains("service charge")||low.contains("ip address"))continue;
   charges.addAll(vals);
  }
  if(charges.size()==1)return charges.iterator().next();
  if(charges.isEmpty()&&tendered>0&&change>=0&&tendered>change)return tendered-change;
  if(tenderPay>0&&charges.contains(tenderPay))return tenderPay;
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

 private static String normalizeDateText(String raw){
  return String.valueOf(raw)
   .replaceAll("(?i)\\b0ct\\b","Oct")
   .replaceAll("(?i)\\b0gos\\b","Ogos")
   .replaceAll("(?i)\\b0kt\\b","Okt")
   .replaceAll("(?i)\\bn0v\\b","Nov")
   .replaceAll("(?i)\\bd1s\\b","Dis")
   .replaceAll("(?i)\\b[j1]an\\b","Jan");
 }

 private static String extractDate(String fullText,String[] lines,ReceiptFieldTagger.TaggedLines tagged){
  if(tagged!=null){
   for(int i=0;i<lines.length;i++){
    if(tagged.labelIds[i]==ReceiptFieldTagger.LABEL_DATE){
     String d=extractDateFromString(normalizeDateText(lines[i]));
     if(d!=null)return d;
    }
   }
  }
  String d=extractDateFromString(normalizeDateText(fullText));
  return d!=null?d:LocalDate.now().toString();
 }

 private static String extractDateFromString(String text){
  Matcher dm=Pattern.compile("\\b(20[0-9]{2})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})(?=[^0-9]|[0-9]{2}:|$)").matcher(text);
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
  return null;
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
