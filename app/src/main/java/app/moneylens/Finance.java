package app.moneylens;
import org.json.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.*;
import java.util.*;

/** Deterministic calendar and report calculations; no Android or network dependencies. */
public final class Finance {
 public static LocalDate anchored(YearMonth m,int day){return m.atDay(Math.min(day,m.lengthOfMonth()));}
 public static LocalDate start(LocalDate date,int day){LocalDate s=anchored(YearMonth.from(date),day);return date.isBefore(s)?anchored(YearMonth.from(date).minusMonths(1),day):s;}
 public static LocalDate next(LocalDate s,int day){return anchored(YearMonth.from(s).plusMonths(1),day);}
 public static LocalDate advance(LocalDate d,String frequency,int anchor){switch(frequency){case "daily":return d.plusDays(1);case "weekly":return d.plusWeeks(1);case "everyXDays":return d.plusDays(Math.max(1,anchor));case "yearly":return anchored(YearMonth.from(d).plusYears(1),anchor);default:return anchored(YearMonth.from(d).plusMonths(1),anchor);}}
 public static long balance(JSONObject s,String account,String through)throws Exception{long total=0;JSONArray rows=s.getJSONArray("transactions");for(int i=0;i<rows.length();i++){JSONObject t=rows.getJSONObject(i);if(t.optBoolean("voided")||t.getString("date").compareTo(through)>0)continue;JSONArray p=t.getJSONArray("postings");for(int j=0;j<p.length();j++){JSONObject e=p.getJSONObject(j);if(e.getString("account").equals(account))total=Math.addExact(total,e.getLong("amount"));}}return total;}
 public static long income(JSONObject s,LocalDate from,LocalDate until)throws Exception{long n=0;JSONArray tx=s.getJSONArray("transactions");for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(!t.optBoolean("voided")&&t.getString("kind").equals("income")&&t.getString("date").compareTo(from.toString())>=0&&t.getString("date").compareTo(until.toString())<0)n+=t.getLong("amount");}return n;}
 public static long spend(JSONObject s,String category,LocalDate from,LocalDate until)throws Exception{long total=0;JSONArray rows=s.getJSONArray("transactions");for(int i=0;i<rows.length();i++){JSONObject t=rows.getJSONObject(i);String d=t.getString("date");if(t.optBoolean("voided")||d.compareTo(from.toString())<0||d.compareTo(until.toString())>=0)continue;JSONArray p=t.getJSONArray("postings");for(int j=0;j<p.length();j++){JSONObject e=p.getJSONObject(j);if(e.getString("account").startsWith("expense:")&&(category.isEmpty()||e.getString("account").equals("expense:"+category)))total=Math.addExact(total,e.getLong("amount"));}}return total;}
 public static JSONObject budget(JSONObject s,JSONObject b,LocalDate on)throws Exception{
  int day=s.getJSONObject("settings").optInt("cycleDay",1);LocalDate current=start(on,day),end=next(current,day),first=start(LocalDate.parse(b.optString("createdDate",current.toString())),day);if(first.isAfter(current))first=current;
  long carry=0,limit=b.getLong("limit");boolean rollover=b.optBoolean("rollover");JSONArray history=b.optJSONArray("history");int count=0;
  for(LocalDate cycle=first;!cycle.isAfter(current)&&count++<1200;cycle=next(cycle,day)){
   long effective=limit;boolean roll=rollover;if(history!=null)for(int j=0;j<history.length();j++){JSONObject h=history.getJSONObject(j);if(h.getString("start").compareTo(cycle.toString())<=0){effective=h.getLong("limit");roll=h.optBoolean("rollover");}}
   long available=Math.addExact(effective,roll?carry:0);if(cycle.equals(current)){long spent=spend(s,b.getString("category"),current,end);return new JSONObject().put("id",b.getString("id")).put("category",b.getString("category")).put("limit",effective).put("carry",roll?carry:0).put("available",available).put("spent",spent).put("remaining",available-spent).put("start",current.toString()).put("end",end.toString());}
   carry=Math.max(0,available-spend(s,b.getString("category"),cycle,next(cycle,day)));
  }throw new IllegalArgumentException("Budget history exceeds 100 years.");
 }
 public static JSONObject report(JSONObject s,LocalDate on)throws Exception{
  int day=s.getJSONObject("settings").optInt("cycleDay",1);LocalDate from=start(on,day),until=next(from,day),prev=anchored(YearMonth.from(from).minusMonths(1),day);int elapsed=(int)ChronoUnit.DAYS.between(from,on)+1;LocalDate prevUntil=prev.plusDays(elapsed);if(prevUntil.isAfter(from))prevUntil=from;
  long liquid=0,debt=0,owed=0,commitments=0,reserved=0,budgetRemaining=0;JSONArray budgets=new JSONArray(),accounts=s.getJSONArray("accounts"),billRows=s.getJSONArray("bills"),goalRows=s.getJSONArray("goals");
  for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);long n=balance(s,a.getString("id"),on.toString());if(a.getString("type").equals("card"))debt+=Math.max(0,-n);else liquid+=n;}
  for(int i=0;i<billRows.length();i++){JSONObject b=billRows.getJSONObject(i);if(!b.optBoolean("paid")&&!b.optBoolean("cancelled")&&!b.optString("kind").equals("transfer")&&b.getString("date").compareTo(until.toString())<0)commitments+=b.getLong("amount");}
  for(int i=0;i<goalRows.length();i++)reserved+=goalRows.getJSONObject(i).optLong("saved");
  JSONArray b=s.getJSONArray("budgets");for(int i=0;i<b.length();i++){JSONObject row=budget(s,b.getJSONObject(i),on);budgets.put(row);budgetRemaining+=Math.max(0,row.getLong("remaining"));}
  JSONArray tx=s.getJSONArray("transactions"),receivables=new JSONArray();for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.getString("kind").equals("shared"))continue;long remaining=balance(s,"receivable:"+t.getString("id"),on.toString());if(remaining>0){owed+=remaining;receivables.put(new JSONObject().put("id",t.getString("id")).put("person",t.optString("person")).put("merchant",t.optString("merchant")).put("remaining",remaining));}}
  JSONArray months=new JSONArray();for(int i=5;i>=0;i--){LocalDate m=anchored(YearMonth.from(from).minusMonths(i),day),e=next(m,day);months.put(new JSONObject().put("start",m.toString()).put("spent",spend(s,"",m,e)).put("income",income(s,m,e)));}
  return new JSONObject().put("start",from.toString()).put("end",until.toString()).put("spent",spend(s,"",from,on.plusDays(1))).put("previousComparable",spend(s,"",prev,prevUntil)).put("budgets",budgets).put("dailyAllowance",budgetRemaining/Math.max(1,ChronoUnit.DAYS.between(on,until))).put("liquid",liquid).put("cardReserve",debt).put("billsReserve",commitments).put("goalReserve",reserved).put("availableToSpend",Math.max(0,liquid-debt-commitments-reserved)).put("owed",owed).put("receivables",receivables).put("months",months).put("subscriptions",subscriptions(s)).put("taxEstimate",calculateTax(s,null,on.getYear()));
 }
 public static JSONObject calculateTax(JSONObject s,JSONObject override,int year)throws Exception{
  JSONObject prof=override!=null?override:s.getJSONObject("settings").optJSONObject("taxProfile");
  if(prof==null)prof=new JSONObject();
  String yearPrefix=String.valueOf(year);
  long ledMedical=0,ledLifestyle=0,ledSports=0,ledEducation=0,ledLifeIns=0,ledMedIns=0,ledSspnPrs=0,ledZakat=0,ledPcb=0;
  JSONArray tx=s.getJSONArray("transactions"),claimableItems=new JSONArray();
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!t.optString("kind").equals("expense")||!t.optString("date","").startsWith(yearPrefix))continue;
   long amt=t.getLong("amount");
   String cat=t.optString("category",""),sub=t.optString("subcategory",""),low=(cat+" "+sub+" "+t.optString("merchant","")+" "+t.optString("note","")).toLowerCase(Locale.ROOT);
   String bucket="";
   if(cat.startsWith("Health")||low.contains("clinic")||low.contains("hospital")||low.contains("medical")||low.contains("pharmacy")||low.contains("dental")){
    if(low.contains("gym")||low.contains("sport")||low.contains("fitness")||low.contains("badminton")||low.contains("futsal")){ledSports+=amt;bucket="Sports & Gym";}
    else{ledMedical+=amt;bucket="Medical & Health";}
   }else if(low.contains("gym")||low.contains("sport")||low.contains("decathlon")){ledSports+=amt;bucket="Sports & Gym";}
   if(cat.equals("Education")||low.contains("tuition")||low.contains("university")||low.contains("upskill")||low.contains("course")){ledEducation+=amt;if(bucket.isEmpty())bucket="Education";}
   if(low.contains("unifi")||low.contains("time fibre")||low.contains("maxis")||low.contains("celcom")||low.contains("digi")||low.contains("yes 5g")||low.contains("umobile")||low.contains("book")||low.contains("popular")||low.contains("kinokuniya")||low.contains("laptop")||low.contains("smartphone")||sub.contains("Internet")||sub.contains("Mobile")||sub.contains("Books")||sub.contains("Electronics")){ledLifestyle+=amt;if(bucket.isEmpty())bucket="Lifestyle (Tech / Books / WiFi)";}
   if(cat.equals("Insurance & Takaful")||low.contains("takaful")||low.contains("prudential")||low.contains("great eastern")||low.contains("aia")||low.contains("allianz")||low.contains("etiqa")){
    if(sub.contains("Medical")||sub.contains("Education")||low.contains("medical card")||low.contains("health")){ledMedIns+=amt;if(bucket.isEmpty())bucket="Medical & Edu Insurance";}
    else{ledLifeIns+=amt;if(bucket.isEmpty())bucket="Life Insurance / Takaful";}
   }
   if(low.contains("sspn")||low.contains("prs")||low.contains("ev charging")){ledSspnPrs+=amt;if(bucket.isEmpty())bucket="SSPN / PRS / EV";}
   if(low.contains("zakat")||low.contains("fitrah")){ledZakat+=amt;if(bucket.isEmpty())bucket="Zakat / Fitrah Rebate";}
   if(low.contains("lhdn")||low.contains("pcb")||low.contains("cp38")||sub.contains("LHDN")){ledPcb+=amt;if(bucket.isEmpty())bucket="LHDN / PCB Paid";}
   if(!bucket.isEmpty()&&claimableItems.length()<50){
    claimableItems.put(new JSONObject().put("id",t.optString("id")).put("date",t.optString("date")).put("merchant",t.optString("merchant",cat)).put("category",cat).put("subcategory",sub).put("bucket",bucket).put("amount",amt));
   }
  }
  double baseMonthly=prof.optDouble("monthlyBaseSalary",0);
  double annualBonus=prof.optDouble("annualBonus",0);
  double otherIncome=prof.optDouble("otherIncome",0);
  double epfRate=prof.optDouble("epfRate",11.0);
  double monthlyPcb=prof.optDouble("monthlyPcb",0);
  long grossSen=Math.round((baseMonthly*12.0+annualBonus+otherIncome)*100.0);
  long autoEpfSen=Math.min(4000_00L,Math.round(grossSen*(epfRate/100.0)));
  long individualSen=9000_00L;
  long epfSen=Math.min(4000_00L,prof.has("epfRelief")?Math.round(prof.optDouble("epfRelief",0)*100.0):autoEpfSen);
  long lifeInsSen=Math.min(3000_00L,Math.round(prof.optDouble("lifeInsurance",ledLifeIns/100.0)*100.0));
  long medInsSen=Math.min(3000_00L,Math.round(prof.optDouble("medicalInsurance",ledMedIns/100.0)*100.0));
  long socsoSen=Math.min(350_00L,Math.round(prof.optDouble("socso",baseMonthly>0?350.0:0.0)*100.0));
  long lifestyleSen=Math.min(2500_00L,Math.round(prof.optDouble("lifestyle",ledLifestyle/100.0)*100.0));
  long sportsSen=Math.min(1000_00L,Math.round(prof.optDouble("sports",ledSports/100.0)*100.0));
  long medicalSen=Math.min(10000_00L,Math.round(prof.optDouble("medical",ledMedical/100.0)*100.0));
  long educationSen=Math.min(7000_00L,Math.round(prof.optDouble("education",ledEducation/100.0)*100.0));
  long sspnPrsSen=Math.min(8000_00L,Math.round(prof.optDouble("sspnPrs",ledSspnPrs/100.0)*100.0));
  long familySen=Math.min(20000_00L,Math.round(prof.optDouble("familyRelief",0.0)*100.0));
  long totalReliefsSen=individualSen+epfSen+lifeInsSen+medInsSen+socsoSen+lifestyleSen+sportsSen+medicalSen+educationSen+sspnPrsSen+familySen;
  long chargeableSen=Math.max(0L,grossSen-totalReliefsSen);
  long[][] tiers={
   {0L,5000_00L,0L},
   {5000_00L,20000_00L,1L},
   {20000_00L,35000_00L,3L},
   {35000_00L,50000_00L,6L},
   {50000_00L,70000_00L,11L},
   {70000_00L,100000_00L,19L},
   {100000_00L,400000_00L,25L},
   {400000_00L,600000_00L,26L},
   {600000_00L,2000000_00L,28L},
   {2000000_00L,Long.MAX_VALUE,30L}
  };
  long grossTaxSen=0L;
  JSONArray brackets=new JSONArray();
  for(long[] tier:tiers){
   if(chargeableSen<=tier[0])break;
   long taxableInTier=Math.min(chargeableSen,tier[1])-tier[0];
   long taxInTier=Math.round(taxableInTier*(tier[2]/100.0));
   grossTaxSen+=taxInTier;
   brackets.put(new JSONObject().put("from",tier[0]).put("to",tier[1]==Long.MAX_VALUE?-1:tier[1]).put("rate",tier[2]).put("taxable",taxableInTier).put("tax",taxInTier));
  }
  long statutoryRebateSen=(chargeableSen>0&&chargeableSen<=35000_00L)?400_00L:0L;
  long zakatSen=Math.round(prof.optDouble("zakat",ledZakat/100.0)*100.0);
  long totalRebateSen=statutoryRebateSen+zakatSen;
  long netTaxSen=Math.max(0L,grossTaxSen-totalRebateSen);
  long monthlyTaxSen=Math.round(netTaxSen/12.0);
  long annualPcbSen=monthlyPcb>0?Math.round(monthlyPcb*12.0*100.0):ledPcb;
  long pcbDiffSen=netTaxSen-annualPcbSen;
  double effectiveRate=grossSen>0?(netTaxSen*100.0/grossSen):0.0;
  long mGrossSen=Math.round(baseMonthly*100.0);
  long mEpfSen=Math.round(mGrossSen*(epfRate/100.0));
  long mSocsoSen=Math.round(socsoSen/12.0);
  long mPcbSen=monthlyPcb>0?Math.round(monthlyPcb*100.0):monthlyTaxSen;
  long mTakeHomeSen=Math.max(0L,mGrossSen-mEpfSen-mSocsoSen-mPcbSen);
  JSONObject monthlyPaycheck=new JSONObject().put("grossMonthly",mGrossSen).put("epfMonthly",mEpfSen).put("socsoMonthly",mSocsoSen).put("pcbMonthly",mPcbSen).put("netTakeHomeMonthly",mTakeHomeSen);
  JSONObject ledgerDetected=new JSONObject().put("medical",ledMedical).put("lifestyle",ledLifestyle).put("sports",ledSports).put("education",ledEducation).put("lifeInsurance",ledLifeIns).put("medicalInsurance",ledMedIns).put("sspnPrs",ledSspnPrs).put("zakat",ledZakat).put("pcb",ledPcb);
  return new JSONObject()
   .put("year",year)
   .put("annualGross",grossSen)
   .put("totalReliefs",totalReliefsSen)
   .put("chargeableIncome",chargeableSen)
   .put("grossTax",grossTaxSen)
   .put("statutoryRebate",statutoryRebateSen)
   .put("zakatRebate",zakatSen)
   .put("netTaxPayable",netTaxSen)
   .put("monthlyTaxEquivalent",monthlyTaxSen)
   .put("annualPcbPaid",annualPcbSen)
   .put("pcbDifference",pcbDiffSen)
   .put("effectiveRate",BigDecimal.valueOf(effectiveRate).setScale(2,RoundingMode.HALF_UP).toPlainString())
   .put("brackets",brackets)
   .put("ledgerDetected",ledgerDetected)
   .put("claimableItems",claimableItems)
   .put("monthlyPaycheck",monthlyPaycheck);
 }
 public static JSONArray subscriptions(JSONObject s)throws Exception{
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();JSONArray tx=s.getJSONArray("transactions"),out=new JSONArray();for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.getString("kind").equals("expense")||t.optString("merchant").trim().isEmpty())continue;String k=t.getString("merchant").toLowerCase(Locale.ROOT)+"|"+t.getString("account");groups.computeIfAbsent(k,x->new ArrayList<>()).add(t);}
  for(List<JSONObject> rows:groups.values()){rows.sort(Comparator.comparing(x->x.optString("date")));if(rows.size()<3)continue;JSONObject a=rows.get(rows.size()-3),b=rows.get(rows.size()-2),c=rows.get(rows.size()-1);long gap1=ChronoUnit.DAYS.between(LocalDate.parse(a.getString("date")),LocalDate.parse(b.getString("date"))),gap2=ChronoUnit.DAYS.between(LocalDate.parse(b.getString("date")),LocalDate.parse(c.getString("date")));boolean weekly=gap1>=6&&gap1<=8&&gap2>=6&&gap2<=8,monthly=gap1>=26&&gap1<=33&&gap2>=26&&gap2<=33;if((weekly||monthly)&&a.getLong("amount")==c.getLong("amount")&&b.getLong("amount")==c.getLong("amount")){boolean exists=false;JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++)if(bills.getJSONObject(i).optString("name").equalsIgnoreCase(c.getString("merchant")))exists=true;if(!exists)out.put(new JSONObject().put("name",c.getString("merchant")).put("amount",c.getLong("amount")).put("account",c.getString("account")).put("category",c.getString("category")).put("frequency",weekly?"weekly":"monthly").put("date",(weekly?LocalDate.parse(c.getString("date")).plusWeeks(1):LocalDate.parse(c.getString("date")).plusMonths(1)).toString()));}}
  return out;
 }
 public static JSONObject suggest(JSONObject s,JSONObject draft)throws Exception{
  String pkg=draft.optString("package",""),sourceLabel=draft.optString("source",""),merchant=draft.optString("merchant","").trim(),merchantLow=merchant.toLowerCase(Locale.ROOT);
  long amt=draft.optLong("amount")>0?draft.optLong("amount"):draft.optLong("primaryAmount",0);
  JSONArray sources=s.optJSONArray("sources"),accounts=s.optJSONArray("accounts"),tx=s.optJSONArray("transactions");
  if(sources!=null&&!pkg.isEmpty()){
   for(int i=0;i<sources.length();i++){
    JSONObject src=sources.getJSONObject(i);
    if(pkg.equals(src.optString("package"))&&!src.optString("account").isEmpty()&&hasAccount(accounts,src.getString("account"))){
     draft.put("account",src.getString("account"));break;
    }
   }
  }
  if(draft.optString("account").isEmpty()&&accounts!=null){
   String matchedAcct=matchAccountByName(accounts,sourceLabel,pkg);
   if(!matchedAcct.isEmpty())draft.put("account",matchedAcct);
  }
  if(draft.optString("category").isEmpty()||draft.optString("category").equals("Other")){
   String[] cs=PaymentParser.classify(merchant,sourceLabel+" "+pkg,"");
   draft.put("category",cs[0]);
   if(draft.optString("subcategory").isEmpty()&&!cs[1].isEmpty())draft.put("subcategory",cs[1]);
  }
  JSONObject exactMatch=null,merchantMatch=null,fallbackMerchantMatch=null;
  String sourceHistAccount="";
  if(tx!=null){
   for(int i=tx.length()-1;i>=0;i--){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided")||Arrays.asList("opening","adjustment").contains(t.optString("kind")))continue;
    if(sourceHistAccount.isEmpty()&&!pkg.isEmpty()&&pkg.equals(t.optString("sourcePackage"))&&hasAccount(accounts,t.optString("account")))sourceHistAccount=t.getString("account");
    String tm=t.optString("merchant","").trim();
    if(!merchantLow.isEmpty()&&!merchantLow.equals("detected payment")&&tm.equalsIgnoreCase(merchant)){
     if(fallbackMerchantMatch==null)fallbackMerchantMatch=t;
     String tCat=t.optString("category","Miscellaneous");
     if(merchantMatch==null&&!tCat.equals("Miscellaneous")&&!tCat.equals("Other"))merchantMatch=t;
     if(amt>0&&t.optLong("amount")==amt&&exactMatch==null)exactMatch=t;
    }
   }
  }
  if(merchantMatch==null)merchantMatch=fallbackMerchantMatch;
  if(exactMatch!=null){
   String c=exactMatch.optString("category","Miscellaneous");
   if((c.equals("Miscellaneous")||c.equals("Other"))&&merchantMatch!=null)c=merchantMatch.optString("category",c);
   draft.put("category",c).put("historyMatch","exact").put("needsNote",false);
   if(!exactMatch.optString("subcategory").isEmpty())draft.put("subcategory",exactMatch.getString("subcategory"));
   if(draft.optString("account").isEmpty()&&hasAccount(accounts,exactMatch.optString("account")))draft.put("account",exactMatch.getString("account"));
   if(!exactMatch.optString("note").isEmpty()&&draft.optString("note").isEmpty())draft.put("note",exactMatch.getString("note"));
  }else if(merchantMatch!=null){
   draft.put("category",merchantMatch.optString("category","Miscellaneous")).put("historyMatch","merchant").put("needsNote",true);
   if(!merchantMatch.optString("subcategory").isEmpty())draft.put("subcategory",merchantMatch.getString("subcategory"));
   if(draft.optString("account").isEmpty()&&hasAccount(accounts,merchantMatch.optString("account")))draft.put("account",merchantMatch.getString("account"));
  }else{
   if(draft.optString("category").isEmpty()||draft.optString("category").equals("Other"))draft.put("category","Miscellaneous");
   draft.put("historyMatch","none").put("needsNote",true);
  }
  JSONArray rules=s.getJSONArray("rules");List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<rules.length();i++)sorted.add(rules.getJSONObject(i));sorted.sort((a,b)->Integer.compare(b.optInt("priority"),a.optInt("priority")));
  for(JSONObject rule:sorted){if(!rule.optBoolean("enabled",true))continue;String needle=rule.optString("match").toLowerCase(Locale.ROOT),source=rule.optString("source");if(!needle.isEmpty()&&merchantLow.contains(needle)&&(source.isEmpty()||source.equals(pkg))){draft.put("category",rule.getString("category"));if(!rule.optString("subcategory").isEmpty())draft.put("subcategory",rule.getString("subcategory"));if(!rule.optString("account").isEmpty())draft.put("account",rule.getString("account"));draft.put("ruleId",rule.getString("id"));if(!draft.optString("historyMatch").equals("exact"))draft.put("historyMatch","rule");break;}}
  if(draft.optString("account").isEmpty()&&!sourceHistAccount.isEmpty())draft.put("account",sourceHistAccount);
  return draft;
 }
 private static boolean hasAccount(JSONArray accounts,String id)throws JSONException{
  if(accounts==null||id==null||id.isEmpty())return false;
  for(int i=0;i<accounts.length();i++)if(id.equals(accounts.getJSONObject(i).optString("id")))return true;
  return false;
 }
 public static String matchAccountByName(JSONArray accounts,String label,String pkg)throws JSONException{
  if(accounts==null)return "";
  String hay=(String.valueOf(label)+" "+String.valueOf(pkg)).toLowerCase(Locale.ROOT);
  String[][] aliases={
   {"tng","touch","ewallet"},
   {"maybank","mae","m2u"},
   {"cimb","octo"},
   {"public bank","pbe","pb engage"},
   {"rhb"},
   {"hong leong","hlb"},
   {"ambank","amonline"},
   {"bank islam","bimb"},
   {"gxbank","gx bank"},
   {"grab","grabpay"},
   {"shopee","spaylater","shopeepay"},
   {"boost"}
  };
  for(int i=0;i<accounts.length();i++){
   JSONObject a=accounts.getJSONObject(i);String name=a.optString("name","").toLowerCase(Locale.ROOT).trim();
   if(name.isEmpty())continue;
   if(hay.contains(name))return a.getString("id");
   for(String[] group:aliases){
    boolean srcHas=false,acctHas=false;
    for(String k:group){if(hay.contains(k))srcHas=true;if(name.contains(k))acctHas=true;}
    if(srcHas&&acctHas)return a.getString("id");
   }
  }
  return "";
 }
}
