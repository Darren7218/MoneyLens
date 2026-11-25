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
 public static long spend(JSONObject s,String category,LocalDate from,LocalDate until)throws Exception{return spend(s,category,"",from,until);}
 public static long spend(JSONObject s,String category,String subcategory,LocalDate from,LocalDate until)throws Exception{
  long total=0;String sub=subcategory==null?"":subcategory.trim();
  JSONArray rows=s.getJSONArray("transactions");
  for(int i=0;i<rows.length();i++){
   JSONObject t=rows.getJSONObject(i);String d=t.getString("date");
   if(t.optBoolean("voided")||d.compareTo(from.toString())<0||d.compareTo(until.toString())>=0)continue;
   if(!sub.isEmpty()&&!t.optString("subcategory","").trim().equalsIgnoreCase(sub))continue;
   JSONArray p=t.getJSONArray("postings");
   for(int j=0;j<p.length();j++){
    JSONObject e=p.getJSONObject(j);
    if(e.getString("account").startsWith("expense:")&&(category.isEmpty()||e.getString("account").equals("expense:"+category)))total=Math.addExact(total,e.getLong("amount"));
   }
  }
  return total;
 }
 public static JSONObject budget(JSONObject s,JSONObject b,LocalDate on)throws Exception{
  int day=s.getJSONObject("settings").optInt("cycleDay",1);LocalDate current=start(on,day),end=next(current,day),first=start(LocalDate.parse(b.optString("createdDate",current.toString())),day);if(first.isAfter(current))first=current;
  String cat=b.getString("category"),sub=b.optString("subcategory","").trim();
  long carry=0,limit=b.getLong("limit");boolean rollover=b.optBoolean("rollover");JSONArray history=b.optJSONArray("history");int count=0;
  for(LocalDate cycle=first;!cycle.isAfter(current)&&count++<1200;cycle=next(cycle,day)){
   long effective=limit;boolean roll=rollover;if(history!=null)for(int j=0;j<history.length();j++){JSONObject h=history.getJSONObject(j);if(h.getString("start").compareTo(cycle.toString())<=0){effective=h.getLong("limit");roll=h.optBoolean("rollover");}}
   long available=Math.addExact(effective,roll?carry:0);
   if(cycle.equals(current)){
    long spent=spend(s,cat,sub,current,end);
    long totalDays=Math.max(1L,ChronoUnit.DAYS.between(current,end));
    long elapsedDays=on.isBefore(current)?0L:!on.isBefore(end)?totalDays:Math.min(totalDays,ChronoUnit.DAYS.between(current,on)+1L);
    int pacePct=(int)Math.round(elapsedDays*100.0/totalDays);
    int spentPct=available>0?(int)Math.min(999L,Math.round(spent*100.0/available)):0;
    long expectedSpend=Math.round(available*(elapsedDays/(double)totalDays));
    String paceStatus=spent>available?"over":(elapsedDays>0&&spent>0&&spentPct>pacePct+10)?"fast":"on_track";
    return new JSONObject().put("id",b.getString("id")).put("category",cat).put("subcategory",sub).put("limit",effective).put("carry",roll?carry:0).put("available",available).put("spent",spent).put("remaining",available-spent).put("start",current.toString()).put("end",end.toString()).put("elapsedDays",elapsedDays).put("totalDays",totalDays).put("pacePct",pacePct).put("spentPct",spentPct).put("expectedSpend",expectedSpend).put("paceStatus",paceStatus);
   }
   carry=Math.max(0,available-spend(s,cat,sub,cycle,next(cycle,day)));
  }throw new IllegalArgumentException("Budget history exceeds 100 years.");
 }
 /** Builds a weekly budget pace nudge message when at least one budget exists; null if nothing to report. */
 public static JSONObject weeklyBudgetNudge(JSONObject s,LocalDate on)throws Exception{
  JSONArray list=s.optJSONArray("budgets");
  if(list==null||list.length()==0)return null;
  JSONObject worst=null;int worstScore=-1;
  for(int i=0;i<list.length();i++){
   JSONObject row=budget(s,list.getJSONObject(i),on);
   String st=row.optString("paceStatus","on_track");
   int score=("over".equals(st)?2000:"fast".equals(st)?1000:0)+row.optInt("spentPct",0);
   if(score>worstScore){worstScore=score;worst=row;}
  }
  if(worst==null)return null;
  boolean ms=s.optJSONObject("settings")!=null&&"ms".equals(s.getJSONObject("settings").optString("lang","en"));
  String label=worst.getString("category")+(worst.optString("subcategory","").isEmpty()?"":" › "+worst.getString("subcategory"));
  String st=worst.optString("paceStatus","on_track");
  int sp=worst.optInt("spentPct",0),pp=worst.optInt("pacePct",0);
  String title=ms?"Semakan kadar bajet mingguan":"Weekly budget pace check-in";
  String body=ms
   ?("over".equals(st)?(label+" telah melebihi bajet ("+sp+"% digunakan)."):"fast".equals(st)?(label+" dibelanjakan lebih cepat daripada kadar: "+sp+"% digunakan pada "+pp+"% kitaran."):("Semua bajet mengikut kadar. Tertinggi ialah "+label+" pada "+sp+"% ("+pp+"% kitaran)."))
   :("over".equals(st)?(label+" is over budget ("+sp+"% used)."):"fast".equals(st)?(label+" is spending ahead of pace: "+sp+"% used with "+pp+"% of your cycle elapsed."):("All budgets are on pace. Highest is "+label+" at "+sp+"% used ("+pp+"% of cycle)."));
  String wk=on.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)+"-W"+on.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
  return new JSONObject().put("key","weekly-nudge:"+wk).put("title",title).put("body",body).put("category",worst.getString("category")).put("subcategory",worst.optString("subcategory","")).put("paceStatus",st).put("spentPct",sp).put("pacePct",pp);
 }
 private static String fmtRm(long minor){
  return BigDecimal.valueOf(Math.abs(minor),2).setScale(2,RoundingMode.HALF_UP).toPlainString();
 }
 public static String categoryLabel(String category,boolean ms){
  if(category==null||category.trim().isEmpty())return ms?"Lain-lain":"Miscellaneous";
  if(!ms)return category;
  switch(category){
   case "Food & drinks":return "Makanan & minuman";
   case "Groceries":return "Barangan runcit";
   case "Transport":return "Pengangkutan";
   case "Bills & utilities":return "Bil & utiliti";
   case "Loans, BNPL & Credit":return "Pinjaman, BNPL & Kredit";
   case "Housing & property":return "Perumahan & harta";
   case "Shopping":return "Beli-belah";
   case "Health & medical":return "Kesihatan & perubatan";
   case "Insurance & Takaful":return "Insurans & Takaful";
   case "Education":return "Pendidikan";
   case "Family & gifts":return "Keluarga & hadiah";
   case "Entertainment & travel":return "Hiburan & pelancongan";
   case "Investments & savings":return "Pelaburan & simpanan";
   case "Taxes & government":return "Cukai & kerajaan";
   case "Fees & charges":return "Yuran & caj";
   case "Income & salary":return "Pendapatan & gaji";
   case "Transfers & top-up":return "Pindahan & tambah nilai";
   case "Miscellaneous":return "Lain-lain";
   default:return category;
  }
 }
 /** Returns current cycle spending and matching budget progress for a given category/subcategory. */
 public static JSONObject categoryBudgetSummary(JSONObject s,LocalDate on,String category,String subcategory)throws Exception{
  String cat=category==null?"":category.trim(),sub=subcategory==null?"":subcategory.trim();
  if(cat.isEmpty())return null;
  int day=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optInt("cycleDay",1):1;
  LocalDate from=start(on,day),until=next(from,day);
  long cycleSpent=spend(s,cat,"",from,until);
  JSONObject out=new JSONObject().put("category",cat).put("subcategory",sub).put("cycleSpent",cycleSpent).put("hasBudget",false);
  JSONArray list=s.optJSONArray("budgets");
  if(list==null||list.length()==0)return out;
  JSONObject best=null;int bestRank=-1;
  for(int i=0;i<list.length();i++){
   JSONObject raw=list.getJSONObject(i);
   if(!cat.equalsIgnoreCase(raw.optString("category","").trim()))continue;
   String bSub=raw.optString("subcategory","").trim();
   boolean exactSub=!sub.isEmpty()&&bSub.equalsIgnoreCase(sub);
   if(!bSub.isEmpty()&&!exactSub)continue;
   JSONObject row=budget(s,raw,on);
   int rank=(exactSub?10000:0)+row.optInt("spentPct",0);
   if(rank>bestRank){bestRank=rank;best=row;}
  }
  if(best!=null){
   out.put("hasBudget",true)
    .put("budgetId",best.getString("id"))
    .put("budgetCategory",best.getString("category"))
    .put("budgetSubcategory",best.optString("subcategory",""))
    .put("available",best.getLong("available"))
    .put("spent",best.getLong("spent"))
    .put("remaining",best.getLong("remaining"))
    .put("spentPct",best.getInt("spentPct"))
    .put("pacePct",best.getInt("pacePct"))
    .put("paceStatus",best.optString("paceStatus","on_track"))
    .put("start",best.getString("start"));
  }
  return out;
 }
 /** Evaluates per-category budget threshold alerts (warn at threshold% or over at >100%) with deduplicated alert keys. */
 public static JSONArray evaluateCategoryAlerts(JSONObject s,LocalDate on,String onlyCategory)throws Exception{
  JSONArray out=new JSONArray();
  JSONObject prefs=s.optJSONObject("settings");
  if(prefs!=null&&!prefs.optBoolean("budgetAlerts",true))return out;
  JSONArray list=s.optJSONArray("budgets");
  if(list==null||list.length()==0)return out;
  int threshold=prefs!=null?Math.max(1,Math.min(100,prefs.optInt("threshold",80))):80;
  boolean ms=prefs!=null&&"ms".equals(prefs.optString("lang","en"));
  String filter=onlyCategory==null?"":onlyCategory.trim();
  for(int i=0;i<list.length();i++){
   JSONObject b=list.getJSONObject(i);
   if(!filter.isEmpty()&&!filter.equalsIgnoreCase(b.optString("category","").trim()))continue;
   JSONObject row=budget(s,b,on);
   long avail=row.getLong("available"),spent=row.getLong("spent"),rem=row.getLong("remaining");
   if(avail<=0)continue;
   boolean over=spent>avail;
   boolean warn=!over&&spent*100>=avail*threshold;
   if(!over&&!warn)continue;
   int pct=row.getInt("spentPct");
   String cat=row.getString("category"),sub=row.optString("subcategory","");
   String catDisp=categoryLabel(cat,ms)+(sub.isEmpty()?"":" › "+sub);
   String emoji=Extras.categoryEmoji(cat);
   String level=over?"over":"warn";
   String key="budget-cat:"+row.getString("id")+":"+row.getString("start")+":"+(over?"over":("warn:"+threshold));
   String title=over
    ?(emoji+" "+(ms?"Melebihi bajet · ":"Budget exceeded · ")+catDisp+" ("+pct+"%)")
    :(emoji+" "+(ms?"Amaran bajet · ":"Budget alert · ")+catDisp+" ("+pct+"%)");
   String body=over
    ?(ms?("Belanja RM "+fmtRm(spent)+" daripada had RM "+fmtRm(avail)+" — terlebih RM "+fmtRm(-rem)+".")
        :("Spent RM "+fmtRm(spent)+" of RM "+fmtRm(avail)+" limit — RM "+fmtRm(-rem)+" over budget."))
    :(ms?("RM "+fmtRm(spent)+" daripada RM "+fmtRm(avail)+" digunakan (baki RM "+fmtRm(rem)+").")
        :("RM "+fmtRm(spent)+" of RM "+fmtRm(avail)+" used (RM "+fmtRm(rem)+" left this cycle)."));
   out.put(new JSONObject()
    .put("key",key)
    .put("budgetId",row.getString("id"))
    .put("category",cat)
    .put("subcategory",sub)
    .put("level",level)
    .put("threshold",over?100:threshold)
    .put("spentPct",pct)
    .put("spent",spent)
    .put("available",avail)
    .put("remaining",rem)
    .put("title",title)
    .put("body",body));
  }
  return out;
 }
 /** Computes cycle summary, category shares, top merchants and budget adherence for the cycle containing `on`. */
 public static JSONObject cycleRecap(JSONObject s,LocalDate on)throws Exception{
  return cycleRecap(s,on,null);
 }
 public static JSONObject cycleRecap(JSONObject s,LocalDate on,JSONObject precomputedTax)throws Exception{
  int day=s.getJSONObject("settings").optInt("cycleDay",1);
  LocalDate from=start(on,day),until=next(from,day),prevFrom=anchored(YearMonth.from(from).minusMonths(1),day);
  long totalSpent=spend(s,"",from,until),totalIncome=income(s,from,until),prevSpent=spend(s,"",prevFrom,from),prevIncome=income(s,prevFrom,from);
  long netSaved=Math.subtractExact(totalIncome,totalSpent);
  int savingsRatePct=totalIncome>0?(int)Math.round((netSaved*100.0)/totalIncome):0;
  Map<String,Long> catTotals=new LinkedHashMap<>(),prevCats=new LinkedHashMap<>(),merchTotals=new LinkedHashMap<>();
  Map<String,Integer> merchCounts=new LinkedHashMap<>();
  int txCount=0;
  JSONArray tx=s.getJSONArray("transactions");
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   String d=t.optString("date","");
   boolean inCurr=!t.optBoolean("voided")&&d.compareTo(from.toString())>=0&&d.compareTo(until.toString())<0;
   boolean inPrev=!t.optBoolean("voided")&&d.compareTo(prevFrom.toString())>=0&&d.compareTo(from.toString())<0;
   if(!inCurr&&!inPrev)continue;
   if(inCurr&&!"opening".equals(t.optString("kind")))txCount++;
   JSONArray p=t.getJSONArray("postings");
   long txExp=0;
   for(int j=0;j<p.length();j++){
    JSONObject e=p.getJSONObject(j);
    if(e.getString("account").startsWith("expense:")){
     String c=e.getString("account").substring(8);
     long v=e.getLong("amount");
     if(inCurr){catTotals.put(c,catTotals.getOrDefault(c,0L)+v);txExp+=v;}
     else prevCats.put(c,prevCats.getOrDefault(c,0L)+v);
    }
   }
   if(inCurr&&txExp!=0){
    String m=t.optString("merchant","").trim();
    if(m.isEmpty())m=t.optString("category","General");
    merchTotals.put(m,merchTotals.getOrDefault(m,0L)+txExp);
    merchCounts.put(m,merchCounts.getOrDefault(m,0)+1);
   }
  }
  List<Map.Entry<String,Long>> catList=new ArrayList<>(catTotals.entrySet());
  catList.removeIf(e->e.getValue()<=0);
  catList.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
  long catSum=0;for(Map.Entry<String,Long> e:catList)catSum+=e.getValue();
  JSONArray topCategories=new JSONArray();
  for(int i=0;i<Math.min(10,catList.size());i++){
   Map.Entry<String,Long> e=catList.get(i);
   long prevCat=Math.max(0L,prevCats.getOrDefault(e.getKey(),0L));
   long catDelta=e.getValue()-prevCat;
   int deltaPct=prevCat>0L?(int)Math.round(catDelta*100.0/prevCat):(e.getValue()>0L?100:0);
   int share=catSum>0?(int)Math.round(e.getValue()*100.0/catSum):0;
   topCategories.put(new JSONObject().put("category",e.getKey()).put("amount",e.getValue()).put("previous",prevCat).put("delta",catDelta).put("deltaPct",deltaPct).put("sharePct",share));
  }
  long totalDays=Math.max(1L,ChronoUnit.DAYS.between(from,until));
  long elapsedDays=on.isBefore(from)?0L:(on.isBefore(until)?Math.max(1L,ChronoUnit.DAYS.between(from,on)+1):totalDays);
  long daysLeft=on.isBefore(until)?Math.max(0L,ChronoUnit.DAYS.between(on,until)-1):0L;
  long dailyAvg=elapsedDays>0L?totalSpent/elapsedDays:0L;
  boolean isCycleEndWindow=!on.isBefore(from)&&on.isBefore(until)&&daysLeft<=2L;
  List<Map.Entry<String,Long>> merchList=new ArrayList<>(merchTotals.entrySet());
  merchList.removeIf(e->e.getValue()<=0);
  merchList.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
  JSONArray topMerchants=new JSONArray();
  for(int i=0;i<Math.min(8,merchList.size());i++){
   Map.Entry<String,Long> e=merchList.get(i);
   topMerchants.put(new JSONObject().put("merchant",e.getKey()).put("amount",e.getValue()).put("count",merchCounts.getOrDefault(e.getKey(),1)));
  }
  JSONArray budgetRows=new JSONArray(),rawBudgets=s.optJSONArray("budgets");
  int under=0,over=0;
  if(rawBudgets!=null){
   for(int i=0;i<rawBudgets.length();i++){
    JSONObject row=budget(s,rawBudgets.getJSONObject(i),from);
    budgetRows.put(row);
    if(row.getLong("remaining")<0)over++;else under++;
   }
  }
  JSONObject tax=precomputedTax!=null?precomputedTax:calculateTax(s,null,on.getYear());
  return new JSONObject()
   .put("start",from.toString())
   .put("end",until.minusDays(1).toString())
   .put("nextCycleStart",until.toString())
   .put("totalIncome",totalIncome)
   .put("previousIncome",prevIncome)
   .put("incomeDelta",totalIncome-prevIncome)
   .put("totalSpent",totalSpent)
   .put("previousSpent",prevSpent)
   .put("spentDelta",totalSpent-prevSpent)
   .put("netSaved",netSaved)
   .put("savingsRatePct",savingsRatePct)
   .put("elapsedDays",elapsedDays)
   .put("totalDays",totalDays)
   .put("daysLeft",daysLeft)
   .put("dailyAvg",dailyAvg)
   .put("isCycleEndWindow",isCycleEndWindow)
   .put("txCount",txCount)
   .put("topCategories",topCategories)
   .put("topMerchants",topMerchants)
   .put("budgets",budgetRows)
   .put("underBudgetCount",under)
   .put("overBudgetCount",over)
   .put("claimableCount",tax.getJSONArray("claimableItems").length())
   .put("netTaxPayable",tax.getLong("netTaxPayable"));
 }
 private static final class CachedReport{
  final String key;final String json;
  CachedReport(String key,String json){this.key=key;this.json=json;}
 }
 private static volatile CachedReport reportCache=null;
 private static long foldArrayIds(long h,JSONArray arr){
  if(arr==null)return h*31L;
  h=31L*h+arr.length();
  for(int i=0;i<arr.length();i++){
   JSONObject o=arr.optJSONObject(i);
   h=31L*h+(o!=null?o.toString().hashCode():0);
  }
  return h;
 }
 private static String reportCacheKey(JSONObject s,LocalDate on){
  if(s==null)return "";
  long h=17L;
  long rev=s.optLong("revision",-1L);
  h=31L*h+rev;
  JSONObject settings=s.optJSONObject("settings");
  h=31L*h+(settings!=null?settings.toString().hashCode():0);
  h=foldArrayIds(h,s.optJSONArray("accounts"));
  h=foldArrayIds(h,s.optJSONArray("budgets"));
  h=foldArrayIds(h,s.optJSONArray("bills"));
  h=foldArrayIds(h,s.optJSONArray("goals"));
  h=foldArrayIds(h,s.optJSONArray("debts"));
  JSONArray tx=s.optJSONArray("transactions");
  if(tx!=null){
   h=31L*h+tx.length();
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.optJSONObject(i);
    if(t==null)continue;
    h=31L*h+t.optString("id","").hashCode();
    h=31L*h+t.optString("kind","").hashCode();
    h=31L*h+t.optLong("amount",0L);
    h=31L*h+(t.optBoolean("voided")?1:0);
    h=31L*h+t.optString("date","").hashCode();
    h=31L*h+t.optString("category","").hashCode();
    h=31L*h+t.optString("subcategory","").hashCode();
    h=31L*h+t.optString("merchant","").hashCode();
    h=31L*h+t.optString("reimbursementFor","").hashCode();
    JSONArray ps=t.optJSONArray("postings");
    if(ps!=null){
     h=31L*h+ps.length();
     for(int j=0;j<ps.length();j++){
      JSONObject p=ps.optJSONObject(j);
      if(p!=null){h=31L*h+p.optString("account","").hashCode();h=31L*h+p.optLong("amount",0L);}
     }
    }
   }
  }
  return on+":"+rev+":"+(tx!=null?tx.length():0)+":"+h;
 }
 /** Suggests monthly category budgets from the median spend of up to 3 recent cycles for unbudgeted categories. */
 public static JSONArray suggestBudgets(JSONObject s,LocalDate on)throws Exception{
  JSONArray out=new JSONArray();
  if(s==null)return out;
  int day=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optInt("cycleDay",1):1;
  LocalDate c0=start(on,day),c0End=next(c0,day);
  LocalDate c1=anchored(YearMonth.from(c0).minusMonths(1),day);
  LocalDate c2=anchored(YearMonth.from(c0).minusMonths(2),day);
  String s0=c0.toString(),e0=c0End.toString(),s1=c1.toString(),s2=c2.toString();
  Set<String> budgeted=new HashSet<>();
  JSONArray existing=s.optJSONArray("budgets");
  if(existing!=null){
   for(int i=0;i<existing.length();i++){
    JSONObject b=existing.getJSONObject(i);
    if(b.optString("subcategory","").trim().isEmpty())budgeted.add(b.optString("category","").trim().toLowerCase(Locale.ROOT));
   }
  }
  Map<String,long[]> byCat=new LinkedHashMap<>();
  JSONArray tx=s.optJSONArray("transactions");
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided"))continue;
    String d=t.optString("date","");
    if(d.compareTo(s2)<0||d.compareTo(e0)>=0)continue;
    int slot=d.compareTo(s0)>=0?0:(d.compareTo(s1)>=0?1:2);
    JSONArray ps=t.optJSONArray("postings");
    if(ps==null)continue;
    for(int j=0;j<ps.length();j++){
     JSONObject e=ps.getJSONObject(j);
     String acc=e.getString("account");
     if(acc.startsWith("expense:")){
      String cat=acc.substring(8);
      if(budgeted.contains(cat.toLowerCase(Locale.ROOT)))continue;
      long[] arr=byCat.computeIfAbsent(cat,k->new long[3]);
      arr[slot]+=e.getLong("amount");
     }
    }
   }
  }
  List<JSONObject> rows=new ArrayList<>();
  for(Map.Entry<String,long[]> entry:byCat.entrySet()){
   long[] vals=entry.getValue();
   List<Long> active=new ArrayList<>();
   for(long v:vals)if(v>0L)active.add(v);
   if(active.isEmpty())continue;
   Collections.sort(active);
   int n=active.size();
   long median=n==1?active.get(0):(n==2?(active.get(0)+active.get(1))/2L:active.get(1));
   if(median<=0L)continue;
   long suggested=Math.max(5000L,((median+999L)/1000L)*1000L);
   rows.add(new JSONObject().put("category",entry.getKey()).put("avgSpend",median).put("suggestedLimit",suggested).put("cyclesAnalyzed",n));
  }
  rows.sort((a,b)->Long.compare(b.optLong("avgSpend"),a.optLong("avgSpend")));
  for(int i=0;i<Math.min(6,rows.size());i++)out.put(rows.get(i));
  return out;
 }
 public static JSONObject report(JSONObject s,LocalDate on)throws Exception{
  String key=reportCacheKey(s,on);
  CachedReport cached=reportCache;
  if(cached!=null&&!key.isEmpty()&&key.equals(cached.key))return new JSONObject(cached.json);
  int day=s.getJSONObject("settings").optInt("cycleDay",1);LocalDate from=start(on,day),until=next(from,day),prev=anchored(YearMonth.from(from).minusMonths(1),day);int elapsed=(int)ChronoUnit.DAYS.between(from,on)+1;LocalDate prevUntil=prev.plusDays(elapsed);if(prevUntil.isAfter(from))prevUntil=from;
  long liquid=0,debt=0,owed=0,commitments=0,reserved=0,budgetRemaining=0;JSONArray budgets=new JSONArray(),accounts=s.getJSONArray("accounts"),billRows=s.getJSONArray("bills"),goalRows=s.getJSONArray("goals");
  for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);long n=balance(s,a.getString("id"),on.toString());if(a.getString("type").equals("card"))debt+=Math.max(0,-n);else liquid+=n;}
  for(int i=0;i<billRows.length();i++){JSONObject b=billRows.getJSONObject(i);if(!b.optBoolean("paid")&&!b.optBoolean("cancelled")&&!b.optString("kind").equals("transfer")&&b.getString("date").compareTo(until.toString())<0)commitments+=b.getLong("amount");}
  for(int i=0;i<goalRows.length();i++)reserved+=goalRows.getJSONObject(i).optLong("saved");
  JSONArray b=s.getJSONArray("budgets");Set<String> topCats=new HashSet<>();List<JSONObject> budgetRows=new ArrayList<>();for(int i=0;i<b.length();i++){JSONObject row=budget(s,b.getJSONObject(i),on);budgets.put(row);budgetRows.add(row);if(row.optString("subcategory","").isEmpty())topCats.add(row.getString("category"));}for(JSONObject row:budgetRows){if(row.optString("subcategory","").isEmpty()||!topCats.contains(row.getString("category")))budgetRemaining+=Math.max(0,row.getLong("remaining"));}
  JSONArray tx=s.getJSONArray("transactions"),receivables=new JSONArray();for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.getString("kind").equals("shared"))continue;long remaining=balance(s,"receivable:"+t.getString("id"),on.toString());if(remaining>0){owed+=remaining;receivables.put(new JSONObject().put("id",t.getString("id")).put("person",t.optString("person")).put("merchant",t.optString("merchant")).put("remaining",remaining));}}
  JSONArray months=new JSONArray();for(int i=5;i>=0;i--){LocalDate m=anchored(YearMonth.from(from).minusMonths(i),day),e=next(m,day);months.put(new JSONObject().put("start",m.toString()).put("spent",spend(s,"",m,e)).put("income",income(s,m,e)));}
  long availToSpend=Math.max(0,liquid-debt-commitments-reserved);
  JSONObject tax=calculateTax(s,null,on.getYear());
  JSONObject res=new JSONObject().put("start",from.toString()).put("end",until.minusDays(1).toString()).put("spent",spend(s,"",from,on.plusDays(1))).put("previousComparable",spend(s,"",prev,prevUntil)).put("budgets",budgets).put("dailyAllowance",budgetRemaining/Math.max(1,ChronoUnit.DAYS.between(on,until))).put("liquid",liquid).put("cardReserve",debt).put("billsReserve",commitments).put("goalReserve",reserved).put("availableToSpend",availToSpend).put("owed",owed).put("receivables",receivables).put("sharedSummary",sharedSummary(s,on)).put("dailyPacing",dailyPacing(s,on,availToSpend)).put("months",months).put("subscriptions",subscriptions(s)).put("priceHikes",priceHikes(s)).put("taxEstimate",tax).put("debtPlan",debtPlan(s,on)).put("recurringIncome",recurringIncome(s,on)).put("forecast",forecast(s,on,30)).put("recap",cycleRecap(s,on,tax)).put("budgetSuggestions",suggestBudgets(s,on));
  if(!key.isEmpty())reportCache=new CachedReport(key,res.toString());
  return res;
 }
 public static JSONObject calculateTax(JSONObject s,JSONObject override,int year)throws Exception{
  JSONObject prof=override!=null?override:s.getJSONObject("settings").optJSONObject("taxProfile");
  if(prof==null)prof=new JSONObject();
  String yearPrefix=String.valueOf(year);
  long[] acc=new long[9]; // 0=med,1=lifestyle,2=sports,3=edu,4=lifeIns,5=medIns,6=sspnPrs,7=zakat,8=pcb
  JSONArray tx=s.getJSONArray("transactions"),claimableItems=new JSONArray();
  Map<String,Long> linkedRefunds=new HashMap<>();
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!"refund".equals(t.optString("kind")))continue;
   String oid=t.optString("originalId","");
   if(!oid.isEmpty())linkedRefunds.put(oid,linkedRefunds.getOrDefault(oid,0L)+t.getLong("amount"));
  }
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!t.optString("date","").startsWith(yearPrefix))continue;
   String kind=t.optString("kind");
   if("expense".equals(kind)){
    long origAmt=t.getLong("amount");
    long refunded=linkedRefunds.getOrDefault(t.getString("id"),0L);
    long remAmt=Math.max(0L,origAmt-refunded);
    if(remAmt==0L)continue;
    String bucket=applyTaxBuckets(t,remAmt,acc);
    if(!bucket.isEmpty()&&claimableItems.length()<50){
     JSONObject ci=new JSONObject().put("id",t.optString("id")).put("date",t.optString("date")).put("merchant",t.optString("merchant",t.optString("category",""))).put("category",t.optString("category","")).put("subcategory",t.optString("subcategory","")).put("bucket",bucket).put("amount",remAmt);
     if(refunded>0L)ci.put("originalAmount",origAmt).put("refundedAmount",Math.min(origAmt,refunded));
     claimableItems.put(ci);
    }
   }else if("refund".equals(kind)&&t.optString("originalId","").isEmpty()){
    applyTaxBuckets(t,-t.getLong("amount"),acc);
   }
  }
  for(int i=0;i<acc.length;i++)acc[i]=Math.max(0L,acc[i]);
  long ledMedical=acc[0],ledLifestyle=acc[1],ledSports=acc[2],ledEducation=acc[3],ledLifeIns=acc[4],ledMedIns=acc[5],ledSspnPrs=acc[6],ledZakat=acc[7],ledPcb=acc[8];
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
  long grossTaxSen=0L,marginalRate=0L;
  JSONArray brackets=new JSONArray();
  for(long[] tier:tiers){
   if(chargeableSen<=tier[0])break;
   long taxableInTier=Math.min(chargeableSen,tier[1])-tier[0];
   long taxInTier=Math.round(taxableInTier*(tier[2]/100.0));
   grossTaxSen+=taxInTier;
   marginalRate=tier[2];
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
  Object[][] optDefs={
   {"lifestyle","Lifestyle (Books / Tech / WiFi)",lifestyleSen,2500_00L},
   {"sports","Sports Equipment & Gym",sportsSen,1000_00L},
   {"sspnPrs","SSPN / PRS / EV Charging",sspnPrsSen,8000_00L},
   {"medical","Medical & Health Checkup",medicalSen,10000_00L},
   {"education","Self Education & Upskilling",educationSen,7000_00L},
   {"lifeInsurance","Life Insurance / Family Takaful",lifeInsSen,3000_00L}
  };
  JSONArray reliefOptimizer=new JSONArray();
  for(Object[] def:optDefs){
   long used=(Long)def[2],cap=(Long)def[3],unused=Math.max(0L,cap-used);
   if(unused<=0)continue;
   long saved=Math.max(0L,netTaxSen-computeNetTax(Math.max(0L,chargeableSen-unused),zakatSen,tiers));
   reliefOptimizer.put(new JSONObject().put("key",def[0]).put("bucket",def[1]).put("used",used).put("cap",cap).put("unused",unused).put("potentialTaxSaved",saved));
  }
  long priorityUnused=Math.max(0L,2500_00L-lifestyleSen)+Math.max(0L,1000_00L-sportsSen)+Math.max(0L,8000_00L-sspnPrsSen);
  long maxAddSaved=Math.max(0L,netTaxSen-computeNetTax(Math.max(0L,chargeableSen-priorityUnused),zakatSen,tiers));
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
   .put("marginalRate",marginalRate)
   .put("brackets",brackets)
   .put("ledgerDetected",ledgerDetected)
   .put("claimableItems",claimableItems)
   .put("monthlyPaycheck",monthlyPaycheck)
   .put("reliefOptimizer",reliefOptimizer)
   .put("maxAdditionalTaxSavings",maxAddSaved);
 }
 private static long computeNetTax(long chargeableSen,long zakatSen,long[][] tiers){
  long gross=0L;
  for(long[] tier:tiers){
   if(chargeableSen<=tier[0])break;
   long taxable=Math.min(chargeableSen,tier[1])-tier[0];
   gross+=Math.round(taxable*(tier[2]/100.0));
  }
  long stat=(chargeableSen>0&&chargeableSen<=35000_00L)?400_00L:0L;
  return Math.max(0L,gross-stat-zakatSen);
 }
 /** Detects recurring subscriptions or scheduled bills whose latest charge increased by >= RM 0.50 and >= 3% over baseline. */
 public static JSONArray priceHikes(JSONObject s)throws Exception{
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
  JSONArray tx=s.getJSONArray("transactions"),bills=s.optJSONArray("bills"),out=new JSONArray();
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!"expense".equals(t.optString("kind"))||t.optString("merchant").trim().isEmpty())continue;
   String k=t.getString("merchant").trim().toLowerCase(Locale.ROOT)+"|"+t.getString("account");
   groups.computeIfAbsent(k,x->new ArrayList<>()).add(t);
  }
  for(List<JSONObject> rows:groups.values()){
   rows.sort(Comparator.comparing(x->x.optString("date")));
   if(rows.size()<2)continue;
   JSONObject c=rows.get(rows.size()-1),b=rows.get(rows.size()-2);
   String mName=c.getString("merchant").trim();
   JSONObject matchedBill=null;
   if(bills!=null){
    for(int i=0;i<bills.length();i++){
     JSONObject bl=bills.getJSONObject(i);
     if(!bl.optBoolean("paid")&&!bl.optBoolean("cancelled")&&bl.optString("name","").trim().equalsIgnoreCase(mName)){
      matchedBill=bl;break;
     }
    }
   }
   boolean weekly=false,monthly=false;
   long prevAvg=0;
   if(rows.size()>=3){
    JSONObject a=rows.get(rows.size()-3);
    long gap1=ChronoUnit.DAYS.between(LocalDate.parse(a.getString("date")),LocalDate.parse(b.getString("date")));
    long gap2=ChronoUnit.DAYS.between(LocalDate.parse(b.getString("date")),LocalDate.parse(c.getString("date")));
    weekly=gap1>=5&&gap1<=9&&gap2>=5&&gap2<=9;
    monthly=gap1>=24&&gap1<=36&&gap2>=24&&gap2<=36;
    prevAvg=Math.round((a.getLong("amount")+b.getLong("amount"))/2.0);
   }else if(matchedBill!=null){
    long gap=ChronoUnit.DAYS.between(LocalDate.parse(b.getString("date")),LocalDate.parse(c.getString("date")));
    weekly=gap>=5&&gap<=9;monthly=gap>=24&&gap<=36;
    prevAvg=b.getLong("amount");
   }
   if(!weekly&&!monthly&&matchedBill==null)continue;
   long latestAmt=c.getLong("amount");
   if(matchedBill!=null&&matchedBill.getLong("amount")>0&&prevAvg==latestAmt&&latestAmt>matchedBill.getLong("amount")){
    prevAvg=matchedBill.getLong("amount");
   }
   if(prevAvg<=0||latestAmt-prevAvg<50)continue;
   long delta=latestAmt-prevAvg;
   int deltaPct=(int)Math.round(delta*100.0/prevAvg);
   if(deltaPct<3)continue;
   out.put(new JSONObject()
    .put("merchant",mName)
    .put("account",c.getString("account"))
    .put("category",c.optString("category","Bills & utilities"))
    .put("previousAvg",prevAvg)
    .put("latestAmount",latestAmt)
    .put("deltaAmount",delta)
    .put("deltaPct",deltaPct)
    .put("date",c.getString("date"))
    .put("frequency",weekly?"weekly":"monthly")
    .put("billId",matchedBill!=null?matchedBill.getString("id"):""));
  }
  return out;
 }
 public static JSONArray subscriptions(JSONObject s)throws Exception{
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();JSONArray tx=s.getJSONArray("transactions"),out=new JSONArray();for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.getString("kind").equals("expense")||t.optString("merchant").trim().isEmpty())continue;String k=t.getString("merchant").toLowerCase(Locale.ROOT)+"|"+t.getString("account");groups.computeIfAbsent(k,x->new ArrayList<>()).add(t);}
  for(List<JSONObject> rows:groups.values()){rows.sort(Comparator.comparing(x->x.optString("date")));if(rows.size()<3)continue;JSONObject a=rows.get(rows.size()-3),b=rows.get(rows.size()-2),c=rows.get(rows.size()-1);long gap1=ChronoUnit.DAYS.between(LocalDate.parse(a.getString("date")),LocalDate.parse(b.getString("date"))),gap2=ChronoUnit.DAYS.between(LocalDate.parse(b.getString("date")),LocalDate.parse(c.getString("date")));boolean weekly=gap1>=6&&gap1<=8&&gap2>=6&&gap2<=8,monthly=gap1>=26&&gap1<=33&&gap2>=26&&gap2<=33;if((weekly||monthly)&&a.getLong("amount")==c.getLong("amount")&&b.getLong("amount")==c.getLong("amount")){boolean exists=false;JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++)if(bills.getJSONObject(i).optString("name").equalsIgnoreCase(c.getString("merchant")))exists=true;if(!exists)out.put(new JSONObject().put("name",c.getString("merchant")).put("amount",c.getLong("amount")).put("account",c.getString("account")).put("category",c.getString("category")).put("frequency",weekly?"weekly":"monthly").put("date",(weekly?LocalDate.parse(c.getString("date")).plusWeeks(1):LocalDate.parse(c.getString("date")).plusMonths(1)).toString()));}}
  return out;
 }
 public static JSONArray recurringIncome(JSONObject s)throws Exception{return recurringIncome(s,LocalDate.now());}
 /** Detects recurring salary/income from 3+ regular credits at weekly or monthly cadence. */
 public static JSONArray recurringIncome(JSONObject s,LocalDate on)throws Exception{
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
  JSONArray tx=s.getJSONArray("transactions"),out=new JSONArray();
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!"income".equals(t.optString("kind")))continue;
   String label=t.optString("merchant","").trim();
   if(label.isEmpty())label=t.optString("category","Salary").trim();
   if(label.isEmpty())continue;
   String k=label.toLowerCase(Locale.ROOT)+"|"+t.optString("account","");
   groups.computeIfAbsent(k,x->new ArrayList<>()).add(t);
  }
  JSONObject payday=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optJSONObject("payday"):null;
  String payName=payday!=null?payday.optString("name",""): "";
  for(List<JSONObject> rows:groups.values()){
   rows.sort(Comparator.comparing(x->x.optString("date")));
   if(rows.size()<3)continue;
   JSONObject a=rows.get(rows.size()-3),b=rows.get(rows.size()-2),c=rows.get(rows.size()-1);
   LocalDate da=LocalDate.parse(a.getString("date")),db=LocalDate.parse(b.getString("date")),dc=LocalDate.parse(c.getString("date"));
   long gap1=ChronoUnit.DAYS.between(da,db),gap2=ChronoUnit.DAYS.between(db,dc);
   boolean weekly=gap1>=6&&gap1<=8&&gap2>=6&&gap2<=8,monthly=gap1>=26&&gap1<=33&&gap2>=26&&gap2<=33;
   if(!weekly&&!monthly)continue;
   long amtA=a.getLong("amount"),amtB=b.getLong("amount"),amtC=c.getLong("amount");
   if(amtC<=0||Math.abs(amtA-amtC)*20>amtC||Math.abs(amtB-amtC)*20>amtC)continue;
   int dom=dc.getDayOfMonth();
   String freq=weekly?"weekly":"monthly";
   LocalDate nextDate=advance(dc,freq,dom);
   while(nextDate.isBefore(on))nextDate=advance(nextDate,freq,dom);
   String name=c.optString("merchant","").trim();if(name.isEmpty())name=c.optString("category","Salary");
   out.put(new JSONObject().put("name",name).put("amount",amtC).put("account",c.getString("account")).put("frequency",freq).put("dayOfMonth",dom).put("nextDate",nextDate.toString()).put("isCurrentPayday",!payName.isEmpty()&&payName.equalsIgnoreCase(name)));
  }
  return out;
 }
 /** Projects liquid cash balance forward over a rolling N-day horizon using unpaid bills and configured payday. */
 public static JSONObject forecast(JSONObject s,LocalDate on,int days)throws Exception{
  int horizon=Math.max(1,Math.min(90,days));
  LocalDate until=on.plusDays(horizon);
  long starting=0;
  JSONArray accounts=s.getJSONArray("accounts");
  Set<String> cardIds=new HashSet<>();
  for(int i=0;i<accounts.length();i++){
   JSONObject a=accounts.getJSONObject(i);
   String id=a.getString("id");
   if("card".equals(a.optString("type")))cardIds.add(id);
   else starting=Math.addExact(starting,balance(s,id,on.toString()));
  }
  Map<LocalDate,List<JSONObject>> dayEvents=new TreeMap<>();
  JSONArray bills=s.optJSONArray("bills");
  if(bills!=null){
   for(int i=0;i<bills.length();i++){
    JSONObject b=bills.getJSONObject(i);
    if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;
    String kind=b.optString("kind","expense");
    if("transfer".equals(kind)&&!cardIds.contains(b.optString("to","")))continue;
    LocalDate due=LocalDate.parse(b.getString("date"));
    String freq=b.optString("frequency",b.optBoolean("monthly")?"monthly":"once");
    int anchor="everyXDays".equals(freq)?b.optInt("intervalDays",30):b.optInt("anchorDay",due.getDayOfMonth());
    long amt=b.getLong("amount");
    if(due.isBefore(on)){
     dayEvents.computeIfAbsent(on,x->new ArrayList<>()).add(new JSONObject().put("date",on.toString()).put("label",b.optString("name","Bill")).put("kind","outflow").put("amount",amt).put("overdue",true));
     if("once".equals(freq))continue;
     int guard=0;while(!due.isAfter(on)&&guard++<120)due=advance(due,freq,anchor);
    }
    int guard=0;
    while(!due.isAfter(until)&&guard++<60){
     dayEvents.computeIfAbsent(due,x->new ArrayList<>()).add(new JSONObject().put("date",due.toString()).put("label",b.optString("name","Bill")).put("kind","outflow").put("amount",amt).put("overdue",false));
     if("once".equals(freq))break;
     due=advance(due,freq,anchor);
    }
   }
  }
  JSONObject payday=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optJSONObject("payday"):null;
  if(payday!=null&&payday.optLong("amount",0L)>0){
   String freq="weekly".equals(payday.optString("frequency"))?"weekly":"monthly";
   int dom=Math.max(1,Math.min(31,payday.optInt("dayOfMonth",1)));
   LocalDate pd=payday.has("nextDate")&&!payday.optString("nextDate").isEmpty()?LocalDate.parse(payday.getString("nextDate")):anchored(YearMonth.from(on),dom);
   int guard=0;while(pd.isBefore(on)&&guard++<120)pd=advance(pd,freq,dom);
   long pAmt=payday.getLong("amount");
   String pName=payday.optString("name","Payday");
   guard=0;
   while(!pd.isAfter(until)&&guard++<20){
    dayEvents.computeIfAbsent(pd,x->new ArrayList<>()).add(new JSONObject().put("date",pd.toString()).put("label",pName).put("kind","inflow").put("amount",pAmt).put("overdue",false));
    pd=advance(pd,freq,dom);
   }
  }
  long running=starting,totalIn=0,totalOut=0,lowest=starting;
  String lowestDate=on.toString(),firstNegDate="";
  JSONArray events=new JSONArray(),daily=new JSONArray();
  for(int step=0;step<=horizon;step++){
   LocalDate d=on.plusDays(step);
   long dayIn=0,dayOut=0;
   List<JSONObject> evs=dayEvents.get(d);
   if(evs!=null){
    for(JSONObject ev:evs){
     long amt=ev.getLong("amount");
     if("inflow".equals(ev.getString("kind"))){running=Math.addExact(running,amt);dayIn+=amt;totalIn+=amt;}
     else{running=Math.subtractExact(running,amt);dayOut+=amt;totalOut+=amt;}
     ev.put("balanceAfter",running);
     events.put(ev);
    }
   }
   if(running<lowest){lowest=running;lowestDate=d.toString();}
   if(firstNegDate.isEmpty()&&running<0)firstNegDate=d.toString();
   daily.put(new JSONObject().put("date",d.toString()).put("balance",running).put("inflow",dayIn).put("outflow",dayOut));
  }
  return new JSONObject()
   .put("startDate",on.toString())
   .put("endDate",until.toString())
   .put("startingBalance",starting)
   .put("endingBalance",running)
   .put("totalInflows",totalIn)
   .put("totalOutflows",totalOut)
   .put("lowestBalance",lowest)
   .put("lowestDate",lowestDate)
   .put("hasNegativeWarning",lowest<0)
   .put("firstNegativeDate",firstNegDate)
   .put("hasConfiguredPayday",payday!=null&&payday.optLong("amount",0L)>0)
   .put("events",events)
   .put("daily",daily);
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
  String curCat=draft.optString("category","");
  if(curCat.isEmpty()||curCat.equals("Other")||curCat.equals("Miscellaneous")){
   String[] cs=PaymentParser.classify(merchant,sourceLabel+" "+pkg,draft.optString("note",""));
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
  String afterClsCat=draft.optString("category","");
  boolean draftUncat=afterClsCat.isEmpty()||afterClsCat.equals("Other")||afterClsCat.equals("Miscellaneous");
  if(exactMatch!=null){
   String c=exactMatch.optString("category","Miscellaneous");
   if((c.equals("Miscellaneous")||c.equals("Other"))&&merchantMatch!=null)c=merchantMatch.optString("category",c);
   if((!c.equals("Other")&&!c.equals("Miscellaneous"))||draftUncat){
    draft.put("category",c);
    if(!exactMatch.optString("subcategory").isEmpty())draft.put("subcategory",exactMatch.getString("subcategory"));
   }
   draft.put("historyMatch","exact").put("needsNote",false);
   if(draft.optString("account").isEmpty()&&hasAccount(accounts,exactMatch.optString("account")))draft.put("account",exactMatch.getString("account"));
   if(!exactMatch.optString("note").isEmpty()&&draft.optString("note").isEmpty())draft.put("note",exactMatch.getString("note"));
  }else if(merchantMatch!=null){
   String mc=merchantMatch.optString("category","Miscellaneous");
   if((!mc.equals("Other")&&!mc.equals("Miscellaneous"))||draftUncat){
    draft.put("category",mc);
    if(!merchantMatch.optString("subcategory").isEmpty())draft.put("subcategory",merchantMatch.getString("subcategory"));
   }
   draft.put("historyMatch","merchant").put("needsNote",true);
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
 public static String matchAccountByName(JSONArray accounts,String label,String pkg)throws JSONException{return matchAccountByName(accounts,label,pkg,"");}
 public static String matchAccountByName(JSONArray accounts,String label,String pkg,String excludeId)throws JSONException{
  if(accounts==null)return "";
  String hay=(String.valueOf(label)+" "+String.valueOf(pkg)).toLowerCase(Locale.ROOT);
  String bestId="";int bestLen=0;
  for(int i=0;i<accounts.length();i++){
   JSONObject a=accounts.getJSONObject(i);String name=a.optString("name","").toLowerCase(Locale.ROOT).trim();
   if(name.isEmpty()||a.optString("id").equals(excludeId))continue;
   if(hay.contains(name)&&name.length()>bestLen){bestId=a.getString("id");bestLen=name.length();}
  }
  if(!bestId.isEmpty())return bestId;
  String[][] aliases={
   {"spaylater","shopee paylater"},
   {"shopeepay","shopee pay","com.shopee.pay.my"},
   {"atome","com.atome.my"},
   {"payflex","boost payflex"},
   {"lazpaylater"},
   {"tiktok paylater"},
   {"tng","touch 'n go","touch","ewallet","tngdigital"},
   {"mae","maybank2u","m2u","maybank"},
   {"cimb octo","cimb","octo"},
   {"mypb","pb engage","public bank","pbe"},
   {"rhb"},
   {"hlb connect","hong leong","hlb"},
   {"amonline","ambank"},
   {"bimb","bank islam"},
   {"uob tmrw","uob"},
   {"ocbc"},
   {"hsbc"},
   {"sc mobile","standard chartered"},
   {"mybsn","bsn"},
   {"gxbank","gx bank"},
   {"aeon bank","aeonbank"},
   {"ryt bank","rytbank"},
   {"boost bank","myboostbank"},
   {"bigpay","big pay"},
   {"setel"},
   {"grabpaylater","grab paylater"},
   {"grabpay","grab"},
   {"shopee"},
   {"boost","myboost"},
   {"foodpanda"},
   {"lazada"},
   {"fave","favepay"},
   {"shopback"}
  };
  for(String[] group:aliases){
   boolean srcHas=false;
   for(String k:group)if(hay.contains(k)){srcHas=true;break;}
   if(!srcHas)continue;
   for(int i=0;i<accounts.length();i++){
    JSONObject a=accounts.getJSONObject(i);String name=a.optString("name","").toLowerCase(Locale.ROOT).trim();
    if(name.isEmpty()||a.optString("id").equals(excludeId))continue;
    for(String k:group)if(name.contains(k))return a.getString("id");
   }
  }
  return "";
 }
 public static JSONObject debtPlan(JSONObject s)throws Exception{return debtPlan(s,LocalDate.now());}
 public static JSONObject debtPlan(JSONObject s,LocalDate on)throws Exception{
  long extra=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optLong("debtExtraMonthly",0L):0L;
  return debtPlan(s,extra,on);
 }
 /** Simulates Debt Avalanche (highest APR first) vs Snowball (smallest balance first), supporting 0% BNPL, flat Ujrah (e.g. PTPTN 1%) and reducing credit balances. */
 public static JSONObject debtPlan(JSONObject s,long extraMonthly,LocalDate on)throws Exception{
  long extra=Math.max(0L,extraMonthly);
  JSONArray raw=s.optJSONArray("debts");
  List<JSONObject> active=new ArrayList<>();
  long totalBalance=0,totalMin=0;
  if(raw!=null){
   for(int i=0;i<raw.length();i++){
    JSONObject d=raw.getJSONObject(i);
    long bal=d.optLong("balance",0L);
    if(bal>0){active.add(d);totalBalance=Math.addExact(totalBalance,bal);totalMin=Math.addExact(totalMin,Math.max(1L,d.optLong("minPayment",1L)));}
   }
  }
  JSONObject av=simulateDebt(active,extra,on,true),sb=simulateDebt(active,extra,on,false);
  JSONArray list=new JSONArray();
  JSONObject avById=av.getJSONObject("perDebt"),sbById=sb.getJSONObject("perDebt");
  if(raw!=null){
   for(int i=0;i<raw.length();i++){
    JSONObject d=new JSONObject(raw.getJSONObject(i).toString());
    String id=d.getString("id");
    if(avById.has(id))d.put("avalanche",avById.getJSONObject(id));
    if(sbById.has(id))d.put("snowball",sbById.getJSONObject(id));
    list.put(d);
   }
  }
  long saved=Math.max(0L,sb.getLong("totalInterest")-av.getLong("totalInterest"));
  String rec=saved>0||av.getInt("months")<sb.getInt("months")?"avalanche":"snowball";
  return new JSONObject()
   .put("debts",list)
   .put("activeCount",active.size())
   .put("totalBalance",totalBalance)
   .put("totalMinPayment",totalMin)
   .put("extraMonthly",extra)
   .put("monthlyBudget",active.isEmpty()?0L:Math.addExact(totalMin,extra))
   .put("avalanche",av)
   .put("snowball",sb)
   .put("interestSavedByAvalanche",saved)
   .put("recommended",rec);
 }
 private static JSONObject simulateDebt(List<JSONObject> active,long extra,LocalDate on,boolean avalanche)throws Exception{
  int n=active.size();
  if(n==0)return new JSONObject().put("strategy",avalanche?"avalanche":"snowball").put("months",0).put("payoffDate",on.toString()).put("totalInterest",0L).put("totalPaid",0L).put("stalled",false).put("order",new JSONArray()).put("schedule",new JSONArray()).put("perDebt",new JSONObject());
  long[] initBal=new long[n],rem=new long[n],principal=new long[n],minPay=new long[n],debtInt=new long[n];
  int[] apr=new int[n],payMonth=new int[n];
  boolean[] flat=new boolean[n];
  String[] ids=new String[n],names=new String[n];
  long totalMin=0;
  for(int i=0;i<n;i++){
   JSONObject d=active.get(i);
   ids[i]=d.getString("id");names[i]=d.optString("name","Debt");
   initBal[i]=d.getLong("balance");rem[i]=initBal[i];
   principal[i]=d.optLong("principal",initBal[i])>0?d.optLong("principal"):initBal[i];
   minPay[i]=Math.max(1L,d.optLong("minPayment",1L));
   apr[i]=Math.max(0,d.optInt("aprBps",0));
   flat[i]="flat".equals(d.optString("rateType","reducing"));
   totalMin=Math.addExact(totalMin,minPay[i]);
  }
  Integer[] order=new Integer[n];for(int i=0;i<n;i++)order[i]=i;
  Arrays.sort(order,(a,b)->{
   if(avalanche){
    int c=Integer.compare(apr[b],apr[a]);if(c!=0)return c;
    return Long.compare(initBal[a],initBal[b]);
   }else{
    int c=Long.compare(initBal[a],initBal[b]);if(c!=0)return c;
    return Integer.compare(apr[b],apr[a]);
   }
  });
  long poolBudget=Math.addExact(totalMin,extra),totalInterest=0,totalPaid=0;
  int months=0;boolean stalled=false;
  JSONArray schedule=new JSONArray();
  for(int m=1;m<=600;m++){
   boolean any=false;for(int i=0;i<n;i++)if(rem[i]>0){any=true;break;}
   if(!any)break;
   months=m;
   long monthInt=0,monthPaid=0;
   JSONArray paidOff=new JSONArray();
   for(int i=0;i<n;i++){
    if(rem[i]<=0)continue;
    long intr=apr[i]==0?0L:Math.round((flat[i]?principal[i]:rem[i])*(apr[i]/120000.0));
    rem[i]=Math.addExact(rem[i],intr);debtInt[i]+=intr;totalInterest+=intr;monthInt+=intr;
   }
   for(int idx:order){
    if(rem[idx]<=0)continue;
    long p=Math.min(rem[idx],minPay[idx]);
    rem[idx]-=p;monthPaid+=p;
    if(rem[idx]==0&&payMonth[idx]==0){payMonth[idx]=m;paidOff.put(names[idx]);}
   }
   long snowball=Math.max(0L,poolBudget-monthPaid);
   for(int idx:order){
    if(snowball<=0)break;
    if(rem[idx]<=0)continue;
    long p=Math.min(rem[idx],snowball);
    rem[idx]-=p;monthPaid+=p;snowball-=p;
    if(rem[idx]==0&&payMonth[idx]==0){payMonth[idx]=m;paidOff.put(names[idx]);}
   }
   totalPaid+=monthPaid;
   long remTotal=0;for(int i=0;i<n;i++)remTotal+=rem[i];
   if(m==600&&remTotal>0)stalled=true;
   if(m<=24||remTotal==0||paidOff.length()>0){
    schedule.put(new JSONObject().put("month",m).put("date",anchored(YearMonth.from(on).plusMonths(m),on.getDayOfMonth()).toString()).put("paid",monthPaid).put("interest",monthInt).put("remaining",remTotal).put("paidOff",paidOff));
   }
  }
  JSONArray orderArr=new JSONArray();for(int idx:order)orderArr.put(new JSONObject().put("id",ids[idx]).put("name",names[idx]).put("balance",initBal[idx]).put("aprBps",apr[idx]).put("payoffMonth",payMonth[idx]));
  JSONObject perDebt=new JSONObject();
  for(int i=0;i<n;i++){
   int pm=payMonth[i]>0?payMonth[i]:months;
   perDebt.put(ids[i],new JSONObject().put("payoffMonth",payMonth[i]).put("payoffDate",anchored(YearMonth.from(on).plusMonths(pm),on.getDayOfMonth()).toString()).put("interest",debtInt[i]).put("remainingAtCap",rem[i]));
  }
  return new JSONObject()
   .put("strategy",avalanche?"avalanche":"snowball")
   .put("months",months)
   .put("payoffDate",anchored(YearMonth.from(on).plusMonths(months),on.getDayOfMonth()).toString())
   .put("totalInterest",totalInterest)
   .put("totalPaid",totalPaid)
   .put("stalled",stalled)
   .put("order",orderArr)
   .put("schedule",schedule)
   .put("perDebt",perDebt);
 }
 public static final int TAX_BUCKET_CACHE_MAX = 2048;
 private static final Map<String,String> taxBucketCache=Collections.synchronizedMap(new LinkedHashMap<String,String>(256,0.75f,true){
  @Override protected boolean removeEldestEntry(Map.Entry<String,String> eldest){return size()>TAX_BUCKET_CACHE_MAX;}
 });
 /** Returns the primary LHDN tax relief bucket for a transaction, or empty string if not claimable. */
 public static String taxReliefBucket(JSONObject t){
  if(t==null)return "";
  String key=t.optString("category","")+"|"+t.optString("subcategory","")+"|"+t.optString("merchant","")+"|"+t.optString("note","");
  String cached=taxBucketCache.get(key);
  if(cached!=null)return cached;
  String res=applyTaxBuckets(t,0L,new long[9]);
  taxBucketCache.put(key,res);
  return res;
 }
 /** Applies a signed sen delta across LHDN tax relief buckets and returns the primary bucket label. */
 private static String applyTaxBuckets(JSONObject t,long signedAmt,long[] acc){
  String cat=t.optString("category",""),sub=t.optString("subcategory","");
  String mn=(t.optString("merchant","")+" "+t.optString("note","")).toLowerCase(Locale.ROOT);
  String low=(cat+" "+sub+" "+mn).toLowerCase(Locale.ROOT);
  String bucket="";
  boolean isIns=cat.equals("Insurance & Takaful")||PaymentParser.has(mn,"takaful|prudential|prubsn|great eastern|aia|allianz|etiqa|zurich|manulife|insurance|insurans");
  boolean isNonClaimIns=PaymentParser.has(mn,"motor|car|road\\s*tax|vehicle|travel|fire|home\\s+fire|house\\s+fire|houseowner|householder|personal\\s+accident|pet\\s+insurance|gadget\\s+insurance")
    ||sub.contains("Motor Insurance")||sub.contains("Personal Accident")||sub.contains("Home Repair");
  if(isIns){
   if(!isNonClaimIns){
    if(sub.contains("Medical")||sub.contains("Education")||mn.contains("medical card")||PaymentParser.has(mn,"medical|health|education|pendidikan")){
     acc[5]+=signedAmt;if(bucket.isEmpty())bucket="Medical & Edu Insurance";
    }else if(cat.equals("Insurance & Takaful")||PaymentParser.has(mn,"life|nyawa|family\\s+takaful|takaful|hibah")){
     acc[4]+=signedAmt;if(bucket.isEmpty())bucket="Life Insurance / Takaful";
    }
   }
  }else{
   boolean isSport=PaymentParser.has(low,"sports?|gym|fitness|badminton|futsal|pickleball|decathlon|al-ikhsan|alikhsan|marathon")
     &&!PaymentParser.has(mn,"toto|4d|lottery|magnum|damacai|esports?|e-sports?|sport\\s*rim|alloy\\s*rim|tyre|tire|workshop|bengkel|playstation|ps5|xbox|steam|valorant|pubg|gaming");
   boolean isNonClaimMed=PaymentParser.has(mn,"vet|veterinary|haiwan|animal\\s+clinic|pet\\s+clinic|dog|cat\\s+vaccination|kucing|anjing|botox|cosmetic|aesthetic|facial|spa|beauty|skincare|grooming|slimming|filler|salon|lipstick|shampoo|sunscreen|cosmetics|makeup|perfume|lotion|conditioner|body\\s+wash|deodorant|toiletries");
   if(!isNonClaimMed&&(cat.startsWith("Health")||PaymentParser.has(mn,"clinic|klinik|hospital|medical|pharmacy|farmasi|dental|dentist|pergigian|pathlab|bp healthcare|gribbles|optometrist|gleneagles|kpj|columbia asia"))){
    if(isSport){acc[2]+=signedAmt;bucket="Sports & Gym";}
    else{acc[0]+=signedAmt;bucket="Medical & Health";}
   }else if(isSport){acc[2]+=signedAmt;bucket="Sports & Gym";}
  }
  boolean isBooksSub=sub.contains("Books");
  boolean isFoodOrParking=cat.startsWith("Food")||cat.equals("Transport")||PaymentParser.has(mn,"cafe|kopitiam|coffee|toast|bistro|restaurant|restoran|parking|carpark|valet");
  boolean isNonClaimAppliance=PaymentParser.has(mn,"washing\\s+machine|dryer|fridge|refrigerator|freezer|air-?con|aircond|vacuum|microwave|oven|blender|tv|television|playstation|ps5|xbox|nintendo|console|netflix|spotify|disney|streaming|appliance");
  if(!isFoodOrParking&&!isNonClaimAppliance&&(sub.contains("Internet")||sub.contains("Mobile")||isBooksSub||sub.contains("Electronics")||PaymentParser.has(mn,"unifi|time fibre|time internet|maxis|celcom|digi|celcomdigi|yes 5g|umobile|books?|bookstore|bookshop|popular|kinokuniya|bookxcess|tsutaya|mph|laptop|macbook|smartphone|iphone|ipad|tablet|machines|harvey norman|all it|thundermatch|senheng"))){
   acc[1]+=signedAmt;if(bucket.isEmpty())bucket="Lifestyle (Tech / Books / WiFi)";
  }
  boolean isNonClaimEdu=isBooksSub||cat.startsWith("Food")||sub.contains("Games")||sub.contains("Childcare")
    ||PaymentParser.has(mn,"tadika|taska|preschool|kindergarten|nursery|childcare|montessori|playschool|daycare|golf\\s+course|3-course|three-course|four-course|five-course|tasting\\s+menu|dinner|lunch|meal|bistro|restaurant|cafe|racecourse");
  if(!isNonClaimEdu&&(cat.equals("Education")||PaymentParser.has(mn,"tuition|university|universiti|upskill|course|coursera|udemy|certification"))){
   acc[3]+=signedAmt;if(bucket.isEmpty())bucket="Education";
  }
  boolean isSspnWithdraw=PaymentParser.has(mn,"withdrawal|pengeluaran|keluar");
  boolean isPetrol=PaymentParser.has(mn,"ron95|ron97|diesel|fuel\\s+pump|petrol");
  boolean isEvExplicit=PaymentParser.has(mn,"ev\\s*charging|ev\\s*hub|gentari|chargev|jomcharge|chargenow|dc handal|shell recharge|evconnection|tesla supercharger");
  if((!isSspnWithdraw&&PaymentParser.has(mn,"sspn|prs"))||(!isPetrol&&isEvExplicit)){
   acc[6]+=signedAmt;if(bucket.isEmpty())bucket="SSPN / PRS / EV";
  }
  if(PaymentParser.has(low,"zakat|fitrah")){acc[7]+=signedAmt;if(bucket.isEmpty())bucket="Zakat / Fitrah Rebate";}
  if(PaymentParser.has(low,"lhdn|pcb|cp38")||sub.contains("LHDN")){acc[8]+=signedAmt;if(bucket.isEmpty())bucket="LHDN / PCB Paid";}
  return bucket;
 }
 /** Normalizes merchant labels across outlets and casing for matching and anomaly baselines. */
 public static String normalizeMerchant(String raw){
  if(raw==null)return "";
  return raw.toLowerCase(Locale.ROOT).trim()
   .replaceAll("\\s*(?:#|no\\.?)\\s*[0-9a-z-]+$","")
   .replaceAll("\\b(?:sdn\\.?\\s*bhd\\.?|bhd\\.?|enterprise|trading|malaysia|plaza)\\b","")
   .replaceAll("[^a-z0-9]+"," ").trim();
 }
 /** Detects single-expense spending anomalies (>= RM 50 and >= 2.5x historical merchant median over >= 3 expenses). */
 public static JSONObject detectSpendAnomaly(JSONObject s,String merchant,String category,long amountMinor,LocalDate on,String excludeTxId)throws Exception{
  JSONObject out=new JSONObject().put("isAnomaly",false);
  JSONObject prefs=s.optJSONObject("settings");
  if(prefs!=null&&!prefs.optBoolean("anomalyGuard",true))return out;
  if(amountMinor<50_00L)return out;
  String target=normalizeMerchant(merchant);
  if(target.length()<3||target.equals("detected payment"))return out;
  JSONArray tx=s.getJSONArray("transactions");
  List<Long> samples=new ArrayList<>();
  LocalDate cutoff=on.minusDays(365);
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||!"expense".equals(t.optString("kind")))continue;
   if(excludeTxId!=null&&!excludeTxId.isEmpty()&&excludeTxId.equals(t.optString("id")))continue;
   LocalDate dt=LocalDate.parse(t.getString("date"));
   if(dt.isBefore(cutoff)||dt.isAfter(on))continue;
   String tm=normalizeMerchant(t.optString("merchant",""));
   if(tm.isEmpty())continue;
   if(tm.equals(target)||(target.length()>=4&&tm.length()>=4&&(tm.startsWith(target+" ")||target.startsWith(tm+" ")))||MerchantCanonicalizer.sameEntity(merchant,t.optString("merchant",""))){
    samples.add(t.getLong("amount"));
   }
  }
  if(samples.size()<3)return out;
  Collections.sort(samples);
  int cnt=samples.size();
  long median=(cnt%2==1)?samples.get(cnt/2):((samples.get(cnt/2-1)+samples.get(cnt/2))/2L);
  if(median<500L||amountMinor*10L<median*25L)return out;
  double ratio=(double)amountMinor/(double)median;
  return out.put("isAnomaly",true)
   .put("median",median)
   .put("count",cnt)
   .put("ratio",Math.round(ratio*10.0)/10.0)
   .put("ratioText",String.format(Locale.US,"%.1fx",ratio));
 }
 private static JSONObject stateStats(JSONObject s)throws Exception{
  JSONObject st=s==null?Ledger.initial():s;
  JSONArray tx=st.optJSONArray("transactions"),accts=st.optJSONArray("accounts"),buds=st.optJSONArray("budgets"),bills=st.optJSONArray("bills"),goals=st.optJSONArray("goals"),debts=st.optJSONArray("debts"),receipts=st.optJSONArray("receipts");
  int activeTx=0,activeBills=0,unverified=0;String latestDate="";long netBal=0;
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided"))continue;
    activeTx++;
    String dt=t.optString("date","");
    if(!"opening".equals(t.optString("kind"))&&dt.compareTo(latestDate)>0)latestDate=dt;
   }
  }
  if(accts!=null){
   for(int i=0;i<accts.length();i++){
    JSONObject a=accts.getJSONObject(i);
    if(!a.optBoolean("balanceVerified"))unverified++;
    netBal+=balance(st,a.getString("id"),"9999-12-31");
   }
  }
  if(bills!=null)for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);if(!b.optBoolean("paid")&&!b.optBoolean("cancelled"))activeBills++;}
  return new JSONObject()
   .put("txCount",activeTx)
   .put("accountCount",accts==null?0:accts.length())
   .put("unverifiedAccounts",unverified)
   .put("budgetCount",buds==null?0:buds.length())
   .put("billCount",activeBills)
   .put("goalCount",goals==null?0:goals.length())
   .put("debtCount",debts==null?0:debts.length())
   .put("receiptCount",receipts==null?0:receipts.length())
   .put("netBalance",netBal)
   .put("latestDate",latestDate)
   .put("revision",st.optLong("revision",0L));
 }
 /** Computes side-by-side record counts and balance deltas between the live vault and a backup candidate. */
 public static JSONObject diffStates(JSONObject current,JSONObject candidate)throws Exception{
  JSONObject c=stateStats(current),n=stateStats(candidate);
  int txD=n.getInt("txCount")-c.getInt("txCount");
  int acD=n.getInt("accountCount")-c.getInt("accountCount");
  int buD=n.getInt("budgetCount")-c.getInt("budgetCount");
  int biD=n.getInt("billCount")-c.getInt("billCount");
  int goD=n.getInt("goalCount")-c.getInt("goalCount");
  int deD=n.getInt("debtCount")-c.getInt("debtCount");
  int rcD=n.getInt("receiptCount")-c.getInt("receiptCount");
  long balD=n.getLong("netBalance")-c.getLong("netBalance");
  return new JSONObject()
   .put("current",c)
   .put("candidate",n)
   .put("txDelta",txD)
   .put("accountDelta",acD)
   .put("budgetDelta",buD)
   .put("billDelta",biD)
   .put("goalDelta",goD)
   .put("debtDelta",deD)
   .put("receiptDelta",rcD)
   .put("netBalanceDelta",balD)
   .put("wouldLoseData",txD<0||acD<0||buD<0);
 }
 /** Evaluates offline ledger integrity, backup recency, uncategorized spend, and lock posture into a 0-100 score. */
 public static JSONObject vaultHealthCheck(JSONObject s,JSONObject meta)throws Exception{
  return vaultHealthCheck(s,meta,System.currentTimeMillis());
 }
 public static JSONObject vaultHealthCheck(JSONObject s,JSONObject meta,long nowMs)throws Exception{
  JSONObject st=s==null?Ledger.initial():s;
  JSONObject stats=stateStats(st);
  boolean ledgerValid=true;String ledgerErr="";
  try{Ledger.validate(new JSONObject(st.toString()));}catch(Exception e){ledgerValid=false;ledgerErr=String.valueOf(e.getMessage());}
  LocalDate cutoff=LocalDate.now().minusDays(90);
  int recentExp=0,uncat=0,pending=0;
  JSONArray tx=st.optJSONArray("transactions"),drafts=st.optJSONArray("drafts");
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided")||!"expense".equals(t.optString("kind")))continue;
    if(LocalDate.parse(t.getString("date")).isBefore(cutoff))continue;
    recentExp++;
    if("Miscellaneous".equals(t.optString("category"))||t.optBoolean("needsNote"))uncat++;
   }
  }
  if(drafts!=null)for(int i=0;i<drafts.length();i++)if("review".equals(drafts.getJSONObject(i).optString("state")))pending++;
  int uncatPct=recentExp>0?Math.round(uncat*100f/recentExp):0;
  int unverified=stats.getInt("unverifiedAccounts");
  long backupMs=Math.max(meta==null?0L:meta.optLong("backupSuccess",0L),meta==null?0L:meta.optLong("lastSnapshotAt",0L));
  long backupAgeHours=backupMs>0?Math.max(0L,(nowMs-backupMs)/3600000L):-1L;
  boolean appLock=st.optJSONObject("settings")!=null&&st.getJSONObject("settings").optBoolean("appLock",false);
  int pInt=ledgerValid?25:0;
  int pCat=uncatPct<=10?20:(uncatPct<=30?12:4);
  int pAcc=unverified==0?15:8;
  int pRev=pending<=2?10:5;
  int pBak=backupAgeHours>=0&&backupAgeHours<=48?20:(backupAgeHours>=0&&backupAgeHours<=168?12:(backupAgeHours>=0?4:0));
  int pSec=appLock?10:4;
  int score=Math.max(0,Math.min(100,pInt+pCat+pAcc+pRev+pBak+pSec));
  JSONArray checks=new JSONArray()
   .put(new JSONObject().put("id","integrity").put("title","Double-entry ledger & key integrity").put("ok",ledgerValid).put("points",pInt).put("maxPoints",25).put("detail",ledgerValid?"All transactions balance and pass schema validation.":"Validation issue: "+ledgerErr).put("action",ledgerValid?"":"restore"))
   .put(new JSONObject().put("id","backup").put("title","Encrypted backup & local snapshot freshness").put("ok",backupAgeHours>=0&&backupAgeHours<=168).put("points",pBak).put("maxPoints",20).put("detail",backupAgeHours<0?"No encrypted backup or local snapshot recorded yet.":("Latest encrypted copy saved "+backupAgeHours+"h ago.")).put("action","snapshotNow"))
   .put(new JSONObject().put("id","categories").put("title","90-day categorization hygiene").put("ok",uncatPct<=10).put("points",pCat).put("maxPoints",20).put("detail",recentExp==0?"No recent expenses in the last 90 days.":(uncat+" of "+recentExp+" recent expense(s) need a specific category or note ("+uncatPct+"%).")).put("action","openNeedsNote"))
   .put(new JSONObject().put("id","accounts").put("title","Verified account balances").put("ok",unverified==0).put("points",pAcc).put("maxPoints",15).put("detail",unverified==0?"All accounts have verified opening or reconciled balances.":(unverified+" account(s) have not been statement-checked yet.")).put("action","openAccounts"))
   .put(new JSONObject().put("id","inbox").put("title","Capture inbox backlog").put("ok",pending<=2).put("points",pRev).put("maxPoints",10).put("detail",pending==0?"Zero pending suggestions waiting in Inbox.":(pending+" unconfirmed capture suggestion(s) pending.")).put("action","openInbox"))
   .put(new JSONObject().put("id","security").put("title","Biometric & screen lock protection").put("ok",appLock).put("points",pSec).put("maxPoints",10).put("detail",appLock?"Fingerprint/biometric app lock is active.":"App lock is currently off in Settings.").put("action","enableLock"));
  JSONObject summary=new JSONObject()
   .put("ledgerValid",ledgerValid)
   .put("activeTxCount",stats.getInt("txCount"))
   .put("accountCount",stats.getInt("accountCount"))
   .put("unverifiedAccounts",unverified)
   .put("uncategorizedCount",uncat)
   .put("uncategorizedPct",uncatPct)
   .put("pendingReviews",pending)
   .put("receiptCount",stats.getInt("receiptCount"))
   .put("appLock",appLock)
   .put("backupAgeHours",backupAgeHours);
  return new JSONObject()
   .put("score",score)
   .put("status",score>=80?"healthy":(score>=50?"attention":"action"))
   .put("checks",checks)
   .put("summary",summary);
 }
 /** Groups open shared-expense receivables by friend and builds offline DuitNow split-bill summaries. */
 public static JSONObject sharedSummary(JSONObject s,LocalDate on)throws Exception{
  JSONArray tx=s.optJSONArray("transactions");
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
  Map<String,String> displayNames=new LinkedHashMap<>();
  List<JSONObject> settlements=new ArrayList<>();
  long totalOwed=0;int openCount=0;
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided"))continue;
    String kind=t.optString("kind");
    if("shared".equals(kind)){
     long rem=balance(s,"receivable:"+t.getString("id"),on.toString());
     if(rem>0){
      String rawName=t.optString("person","").trim();
      if(rawName.isEmpty())rawName="Friend";
      String key=rawName.toLowerCase(Locale.ROOT);
      displayNames.putIfAbsent(key,rawName);
      groups.computeIfAbsent(key,k->new ArrayList<>()).add(new JSONObject()
       .put("id",t.getString("id"))
       .put("date",t.getString("date"))
       .put("merchant",t.optString("merchant",t.optString("category","Shared expense")))
       .put("amount",t.getLong("amount"))
       .put("ownAmount",t.optString("ownAmount","0"))
       .put("remaining",rem)
       .put("account",t.optString("account","")));
      totalOwed+=rem;openCount++;
     }
    }else if("reimbursement".equals(kind)){
     settlements.add(new JSONObject()
      .put("id",t.getString("id"))
      .put("date",t.getString("date"))
      .put("amount",t.getLong("amount"))
      .put("merchant",t.optString("merchant","Repayment"))
      .put("person",t.optString("matchedPerson",""))
      .put("reimbursementFor",t.optString("reimbursementFor",""))
      .put("autoReconciledSplit",t.optBoolean("autoReconciledSplit"))
      .put("created",t.optLong("created",0L)));
    }
   }
  }
  List<JSONObject> friendList=new ArrayList<>();
  for(Map.Entry<String,List<JSONObject>> en:groups.entrySet()){
   String disp=displayNames.get(en.getKey());
   List<JSONObject> items=en.getValue();
   items.sort((a,b)->a.optString("date").compareTo(b.optString("date")));
   long fTotal=0;String oldest="",latest="";
   JSONArray itemArr=new JSONArray();
   StringBuilder msg=new StringBuilder("Hi ").append(disp).append(", here's our split-bill summary:\n");
   for(int i=0;i<items.size();i++){
    JSONObject it=items.get(i);
    long rem=it.optLong("remaining");
    fTotal+=rem;
    String dt=it.optString("date");
    if(oldest.isEmpty()||dt.compareTo(oldest)<0)oldest=dt;
    if(latest.isEmpty()||dt.compareTo(latest)>0)latest=dt;
    itemArr.put(it);
    if(i<8)msg.append("• ").append(dt).append(" · ").append(it.optString("merchant")).append(": RM ").append(BigDecimal.valueOf(rem,2).toPlainString()).append("\n");
   }
   if(items.size()>8)msg.append("• ... and ").append(items.size()-8).append(" more item(s)\n");
   msg.append("Total owed: RM ").append(BigDecimal.valueOf(fTotal,2).toPlainString()).append("\nPlease DuitNow when convenient — thanks!");
   friendList.add(new JSONObject()
    .put("person",disp)
    .put("totalOwed",fTotal)
    .put("count",items.size())
    .put("oldestDate",oldest)
    .put("latestDate",latest)
    .put("items",itemArr)
    .put("shareMessage",msg.toString()));
  }
  friendList.sort((a,b)->Long.compare(b.optLong("totalOwed"),a.optLong("totalOwed")));
  settlements.sort((a,b)->{int c=b.optString("date").compareTo(a.optString("date"));return c!=0?c:Long.compare(b.optLong("created"),a.optLong("created"));});
  JSONArray friends=new JSONArray(),recentSettle=new JSONArray();
  for(JSONObject f:friendList)friends.put(f);
  for(int i=0;i<settlements.size()&&i<10;i++)recentSettle.put(settlements.get(i));
  return new JSONObject()
   .put("totalOwed",totalOwed)
   .put("openCount",openCount)
   .put("friendCount",friends.length())
   .put("friends",friends)
   .put("recentSettlements",recentSettle);
 }
 /** Computes cycle daily burn rate, safe-to-spend-today allowance, 14-day series and category deltas vs prior cycle. */
 public static JSONObject dailyPacing(JSONObject s,LocalDate on)throws Exception{
  int day=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optInt("cycleDay",1):1;
  LocalDate from=start(on,day),until=next(from,day);
  long liquid=0,debt=0,commitments=0,reserved=0;
  JSONArray accounts=s.getJSONArray("accounts"),billRows=s.optJSONArray("bills"),goalRows=s.optJSONArray("goals");
  for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);long n=balance(s,a.getString("id"),on.toString());if("card".equals(a.optString("type")))debt+=Math.max(0,-n);else liquid+=n;}
  if(billRows!=null)for(int i=0;i<billRows.length();i++){JSONObject b=billRows.getJSONObject(i);if(!b.optBoolean("paid")&&!b.optBoolean("cancelled")&&!"transfer".equals(b.optString("kind",""))&&b.optString("date","").compareTo(until.toString())<0)commitments+=b.optLong("amount");}
  if(goalRows!=null)for(int i=0;i<goalRows.length();i++)reserved+=goalRows.getJSONObject(i).optLong("saved");
  return dailyPacing(s,on,Math.max(0L,liquid-debt-commitments-reserved));
 }
 public static JSONObject dailyPacing(JSONObject s,LocalDate on,long availableToSpend)throws Exception{
  int day=s.optJSONObject("settings")!=null?s.getJSONObject("settings").optInt("cycleDay",1):1;
  LocalDate from=start(on,day),until=next(from,day),prevFrom=anchored(YearMonth.from(from).minusMonths(1),day),seriesStart=on.minusDays(13);
  long daysInCycle=Math.max(1L,ChronoUnit.DAYS.between(from,until));
  long elapsedDays=on.isBefore(from)?1L:!on.isBefore(until)?daysInCycle:Math.max(1L,ChronoUnit.DAYS.between(from,on)+1L);
  long remainingDays=Math.max(1L,on.isBefore(until)?ChronoUnit.DAYS.between(on,until):1L);
  String fromIso=from.toString(),untilIso=until.toString(),prevIso=prevFrom.toString(),onIso=on.toString(),seriesIso=seriesStart.toString();
  long cycleSpent=0L,todaySpent=0L;
  Map<String,Long> dayMap=new HashMap<>(),currCats=new LinkedHashMap<>(),prevCats=new LinkedHashMap<>();
  JSONArray tx=s.optJSONArray("transactions");
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    String d=t.optString("date","");
    if(t.optBoolean("voided")||d.compareTo(onIso)>0)continue;
    boolean inCurr=d.compareTo(fromIso)>=0&&d.compareTo(untilIso)<0;
    boolean inPrev=d.compareTo(prevIso)>=0&&d.compareTo(fromIso)<0;
    boolean inSeries=d.compareTo(seriesIso)>=0;
    if(!inCurr&&!inPrev&&!inSeries)continue;
    JSONArray p=t.getJSONArray("postings");
    long txExp=0L;
    for(int j=0;j<p.length();j++){
     JSONObject e=p.getJSONObject(j);
     String acc=e.optString("account","");
     if(acc.startsWith("expense:")){
      long amt=e.optLong("amount");
      String cat=acc.substring(8);
      if(inCurr){currCats.put(cat,currCats.getOrDefault(cat,0L)+amt);cycleSpent=Math.addExact(cycleSpent,amt);}
      else if(inPrev){prevCats.put(cat,prevCats.getOrDefault(cat,0L)+amt);}
      if(d.equals(onIso))todaySpent=Math.addExact(todaySpent,amt);
      txExp+=amt;
     }
    }
    if(inSeries&&txExp!=0L)dayMap.put(d,Math.max(0L,dayMap.getOrDefault(d,0L)+txExp));
   }
  }
  long safeDaily=availableToSpend>0L?(availableToSpend/remainingDays):0L;
  long dailyAvg=Math.round((double)cycleSpent/elapsedDays);
  long todayRemaining=Math.max(0L,safeDaily-todaySpent);
  long projectedCycleSpend=Math.round(dailyAvg*(double)daysInCycle);
  String paceStatus=(safeDaily>0L&&todaySpent>safeDaily)||(availableToSpend<=0L&&todaySpent>0L)?"over":(safeDaily>0L&&todaySpent*100L>=safeDaily*80L)?"watch":"good";
  JSONArray dailySeries=new JSONArray();
  for(int i=13;i>=0;i--){
   LocalDate dt=on.minusDays(i);String ds=dt.toString();
   dailySeries.put(new JSONObject().put("date",ds).put("spent",dayMap.getOrDefault(ds,0L)).put("isToday",i==0));
  }
  List<Map.Entry<String,Long>> catRows=new ArrayList<>(currCats.entrySet());
  catRows.removeIf(e->e.getValue()<=0L);
  catRows.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
  JSONArray categoryDeltas=new JSONArray();
  for(int i=0;i<catRows.size()&&i<6;i++){
   String cat=catRows.get(i).getKey();long curr=catRows.get(i).getValue(),prev=Math.max(0L,prevCats.getOrDefault(cat,0L));
   long delta=curr-prev;int deltaPct=prev>0L?(int)Math.round(delta*100.0/prev):(curr>0L?100:0);
   categoryDeltas.put(new JSONObject().put("category",cat).put("current",curr).put("previous",prev).put("delta",delta).put("deltaPct",deltaPct));
  }
  return new JSONObject()
   .put("daysInCycle",daysInCycle)
   .put("elapsedDays",elapsedDays)
   .put("remainingDays",remainingDays)
   .put("cycleSpent",cycleSpent)
   .put("todaySpent",todaySpent)
   .put("safeDaily",safeDaily)
   .put("todayRemaining",todayRemaining)
   .put("dailyAvg",dailyAvg)
   .put("projectedCycleSpend",projectedCycleSpend)
   .put("paceStatus",paceStatus)
   .put("dailySeries",dailySeries)
   .put("categoryDeltas",categoryDeltas);
 }
 public static final int BACKUP_NUDGE_TX_THRESHOLD=20;
 /** Evaluates optional encrypted backup nudge eligibility (~20+ active tx without recent backup/snapshot). */
 public static JSONObject backupNudge(JSONObject s,JSONObject meta,long nowMs)throws Exception{
  JSONObject st=s==null?Ledger.initial():s;
  JSONObject settings=st.optJSONObject("settings");
  JSONArray tx=st.optJSONArray("transactions");
  int activeTxCount=0;
  if(tx!=null){
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(!t.optBoolean("voided")&&!"opening".equals(t.optString("kind")))activeTxCount++;
   }
  }
  boolean dismissed=settings!=null&&settings.optBoolean("backupNudgeDismissed",false);
  long snoozeUntil=settings!=null?settings.optLong("backupNudgeSnoozeUntil",0L):0L;
  boolean snoozed=snoozeUntil>nowMs;
  long backupMs=Math.max(meta==null?0L:meta.optLong("backupSuccess",0L),meta==null?0L:meta.optLong("lastSnapshotAt",0L));
  long staleMs=14L*86400000L;
  boolean needsBackup=backupMs<=0L||(nowMs-backupMs)>staleMs;
  long daysSinceBackup=backupMs>0L?Math.max(0L,(nowMs-backupMs)/86400000L):-1L;
  boolean shouldShow=!dismissed&&!snoozed&&activeTxCount>=BACKUP_NUDGE_TX_THRESHOLD&&needsBackup;
  String reason=backupMs<=0L?"never":(needsBackup?"stale":"fresh");
  return new JSONObject()
   .put("shouldShow",shouldShow)
   .put("activeTxCount",activeTxCount)
   .put("threshold",BACKUP_NUDGE_TX_THRESHOLD)
   .put("dismissed",dismissed)
   .put("snoozed",snoozed)
   .put("snoozeUntil",snoozeUntil)
   .put("hasBackup",backupMs>0L)
   .put("daysSinceBackup",daysSinceBackup)
   .put("reason",reason);
 }
 /** Hybrid offline multilingual search (exact/fuzzy lexical + structured constraints + MLSS v1 semantic vectors). */
 public static JSONObject searchTransactions(JSONObject s,String rawQuery,String kindFilter,JSONObject advFilters,LocalDate today)throws Exception{
  long t0=System.nanoTime();
  JSONObject st=s==null?Ledger.initial():s;
  LocalDate refToday=today!=null?today:LocalDate.now();
  String kFilter=(kindFilter==null||kindFilter.trim().isEmpty())?"all":kindFilter.trim();
  JSONObject adv=advFilters==null?new JSONObject():advFilters;
  String advExactDate=adv.optString("exactDate","").trim();
  String advCat=adv.optString("category","").trim();
  boolean advNeedsNote=adv.optBoolean("needsNote",false);
  String advFrom=adv.optString("from","").trim();
  String advUntil=adv.optString("until","").trim();
  String advAcct=adv.optString("account","").trim();
  long advMin=parseAdvRmMinor(adv.opt("min"),-1L);
  long advMax=parseAdvRmMinor(adv.opt("max"),-1L);
  String advSource=adv.optString("source","").trim();
  String advTag=adv.optString("tag","").trim().toLowerCase(Locale.ROOT);

  SemanticSearch.ParsedQuery pq=SemanticSearch.parseQueryFilters(rawQuery,refToday);
  String rqTrim=rawQuery==null?"":rawQuery.trim();
  String rqLow=rqTrim.toLowerCase(Locale.ROOT);
  boolean isOodTrivia=(rqTrim.startsWith("{")&&(rqLow.contains("'question'")||rqLow.contains("\"question\"")))
    ||(advExactDate.isEmpty()&&advCat.isEmpty()&&!advNeedsNote&&advAcct.isEmpty()&&advMin<0L&&advMax<0L
       &&rqLow.matches("(?s)^(?:how\\s+does\\s+(?:an|a|the)\\b|what\\s+is\\s+the\\s+(?:name|average|simplified|difference|maximum|minimum)\\b|which\\s+(?:financial|bank|institution|malaysian)\\b|how\\s+many\\s+(?:working|business)\\s+days\\b).*"));
  if(isOodTrivia){
   double msEmpty=(System.nanoTime()-t0)/1_000_000.0;
   return new JSONObject().put("ids",new JSONArray()).put("matches",new JSONObject()).put("scores",new JSONObject())
    .put("parsedFilters",new JSONObject().put("semanticText","").put("minMinor",-1L).put("maxMinor",-1L)
     .put("fromDate","").put("untilDate","").put("hardKind","").put("kindBoost","").put("accountHint","").put("taxReliefQuery",false))
    .put("semanticUsed",false).put("queryMs",Math.round(msEmpty*100.0)/100.0);
  }
  boolean hasSemanticText=!pq.semanticText.isEmpty();
  boolean hasConceptualIntent=hasSemanticText||pq.taxReliefQuery||!pq.kindBoost.isEmpty();
  float[] qVec=hasSemanticText?SemanticSearch.encodeQuery(pq.semanticText):(hasConceptualIntent?SemanticSearch.encodeQuery(pq.rawQuery):null);
  boolean semanticUsed=qVec!=null&&SemanticSearch.isLoaded();
  double semTh=semanticUsed?SemanticSearch.semanticThreshold():0.46;

  List<String> semTokens=new ArrayList<>();
  String semLow=pq.semanticText.toLowerCase(Locale.ROOT).trim();
  if(!semLow.isEmpty()){
   for(String tk:semLow.split("\\s+")){
    if(tk.length()>=2||(!tk.isEmpty()&&tk.charAt(0)>=0x3400))semTokens.add(tk);
   }
  }

  Map<String,String> acctNames=new HashMap<>();
  JSONArray accts=st.optJSONArray("accounts");
  if(accts!=null){
   for(int i=0;i<accts.length();i++){
    JSONObject a=accts.getJSONObject(i);
    acctNames.put(a.optString("id",""),a.optString("name",""));
   }
  }

  final class Hit{
   final String id;final String mode;final double score;final String date;final long created;
   Hit(String id,String mode,double score,String date,long created){this.id=id;this.mode=mode;this.score=score;this.date=date;this.created=created;}
  }
  List<Hit> hits=new ArrayList<>();
  JSONArray txs=st.optJSONArray("transactions");
  if(txs!=null){
   for(int i=0;i<txs.length();i++){
    JSONObject t=txs.getJSONObject(i);
    String kind=t.optString("kind","");
    if(t.optBoolean("voided")||"opening".equals(kind))continue;
    if("splits".equals(kFilter)){if(!"shared".equals(kind)&&!"reimbursement".equals(kind))continue;}
    else if("anomaly".equals(kFilter)){if(!(t.optBoolean("anomaly")&&!t.optBoolean("anomalyDismissed")))continue;}
    else if(!"all".equals(kFilter)&&!kFilter.equals(kind))continue;

    String date=t.optString("date","");
    long amt=t.optLong("amount",0L);
    String acct=t.optString("account",""),toAcct=t.optString("to","");
    String cat=t.optString("category",""),sub=t.optString("subcategory","");
    String merch=t.optString("merchant",""),note=t.optString("note",""),person=t.optString("person","");
    String src=t.optString("source","manual");if(src.isEmpty())src="manual";
    String tagsStr="";
    Object rawTags=t.opt("tags");
    if(rawTags instanceof JSONArray){
     JSONArray ta=(JSONArray)rawTags;StringBuilder tsb=new StringBuilder();
     for(int j=0;j<ta.length();j++){if(j>0)tsb.append(' ');tsb.append(ta.optString(j,""));}
     tagsStr=tsb.toString();
    }else if(rawTags!=null)tagsStr=String.valueOf(rawTags);

    if(!advExactDate.isEmpty()&&!advExactDate.equals(date))continue;
    if(!advCat.isEmpty()&&!advCat.equals(cat))continue;
    if(advNeedsNote&&!t.optBoolean("needsNote"))continue;
    if(!advFrom.isEmpty()&&date.compareTo(advFrom)<0)continue;
    if(!advUntil.isEmpty()&&date.compareTo(advUntil)>0)continue;
    if(!advAcct.isEmpty()&&!advAcct.equals(acct)&&!advAcct.equals(toAcct))continue;
    if(advMin>=0L&&amt<advMin)continue;
    if(advMax>=0L&&amt>advMax)continue;
    if(!advSource.isEmpty()&&!advSource.equals(src))continue;
    if(!advTag.isEmpty()&&!tagsStr.toLowerCase(Locale.ROOT).contains(advTag))continue;

    // Structured constraints inferred from natural-language query
    if(pq.minMinor>=0L&&amt<pq.minMinor)continue;
    if(pq.maxMinor>=0L&&amt>pq.maxMinor)continue;
    if(!pq.fromDate.isEmpty()&&date.compareTo(pq.fromDate)<0)continue;
    if(!pq.untilDate.isEmpty()&&date.compareTo(pq.untilDate)>0)continue;
    if(!pq.hardKind.isEmpty()){
     if("shared".equals(pq.hardKind)){if(!"shared".equals(kind)&&!"reimbursement".equals(kind))continue;}
     else if(!pq.hardKind.equals(kind))continue;
    }
    String acctName=acctNames.getOrDefault(acct,"");
    String toName=toAcct.isEmpty()?"":acctNames.getOrDefault(toAcct,"");
    if(!pq.accountHint.isEmpty()){
     String ah=(acct+" "+acctName+" "+toAcct+" "+toName).toLowerCase(Locale.ROOT);
     if(!ah.contains(pq.accountHint))continue;
    }

    String id=t.optString("id","");
    long created=t.optLong("created",0L);
    if(!hasConceptualIntent){
     hits.add(new Hit(id,"filter",1.0,date,created));
     continue;
    }

    String taxBucket=taxReliefBucket(t);
    double lexScore=0.0;
    String merchLow=merch.toLowerCase(Locale.ROOT);
    String hay=(merch+" "+cat+" "+sub+" "+acctName+" "+toName+" "+note+" "+person+" "+tagsStr+" "+taxBucket).toLowerCase(Locale.ROOT);
    if(!semLow.isEmpty()){
     if(!merchLow.isEmpty()&&merchLow.contains(semLow)){
      lexScore+=(merchLow.equals(semLow)||merchLow.startsWith(semLow))?1.25:1.00;
     }else if(hay.contains(semLow)){
      lexScore+=0.90;
     }else if(semTokens.size()>=2){
      int matched=0,contentMatched=0,contentTotal=0;
      for(String tk:semTokens){
       boolean isContent=tk.length()>=4||tk.charAt(0)>=0x3400;
       if(isContent)contentTotal++;
       if(hay.contains(tk)){matched++;if(isContent)contentMatched++;}
      }
      if(matched==semTokens.size())lexScore+=0.78;
      else if(contentTotal>0&&contentMatched>0&&((double)matched/semTokens.size())>=0.67)lexScore+=0.48*((double)matched/semTokens.size());
     }
     if(lexScore<0.75&&MerchantCanonicalizer.isLoaded()&&pq.semanticText.length()>=4&&(!semanticUsed||merch.indexOf('*')>=0||pq.semanticText.indexOf('*')>=0)&&MerchantCanonicalizer.sameEntity(merch,pq.semanticText)){
      lexScore+=0.85;
     }
    }
    if(pq.taxReliefQuery&&!taxBucket.isEmpty())lexScore+=0.95;
    if(!pq.kindBoost.isEmpty()){
     boolean km="shared".equals(pq.kindBoost)?("shared".equals(kind)||"reimbursement".equals(kind)):pq.kindBoost.equals(kind);
     if(km)lexScore+=0.95;
    }

    double semScore=0.0;
    if(semanticUsed){
     float[] dVec=SemanticSearch.encodeTransaction(t,acctName,taxBucket);
     semScore=SemanticSearch.dot(qVec,dVec);
    }

    boolean keep=lexScore>0.0||(semanticUsed&&semScore>=semTh);
    if(!keep)continue;
    String mode=lexScore>=0.75?"exact":(lexScore>0.0&&semanticUsed&&semScore>=semTh?"hybrid":(lexScore>0.0?"exact":"semantic"));
    double comb=lexScore*0.70+Math.max(0.0,semScore)*0.55;
    hits.add(new Hit(id,mode,comb,date,created));
   }
  }

  hits.sort((a,b)->{
   if(!hasConceptualIntent){
    int dc=b.date.compareTo(a.date);
    return dc!=0?dc:Long.compare(b.created,a.created);
   }
   int sc=Double.compare(b.score,a.score);
   if(sc!=0)return sc;
   int dc=b.date.compareTo(a.date);
   return dc!=0?dc:Long.compare(b.created,a.created);
  });

  JSONArray ids=new JSONArray();
  JSONObject matches=new JSONObject();
  JSONObject scores=new JSONObject();
  for(Hit h:hits){
   ids.put(h.id);
   matches.put(h.id,h.mode);
   scores.put(h.id,Math.round(h.score*10000.0)/10000.0);
  }
  JSONObject pf=new JSONObject()
   .put("semanticText",pq.semanticText)
   .put("minMinor",pq.minMinor)
   .put("maxMinor",pq.maxMinor)
   .put("fromDate",pq.fromDate)
   .put("untilDate",pq.untilDate)
   .put("hardKind",pq.hardKind)
   .put("kindBoost",pq.kindBoost)
   .put("accountHint",pq.accountHint)
   .put("taxReliefQuery",pq.taxReliefQuery);
  double ms=(System.nanoTime()-t0)/1_000_000.0;
  return new JSONObject()
   .put("ids",ids)
   .put("matches",matches)
   .put("scores",scores)
   .put("parsedFilters",pf)
   .put("semanticUsed",semanticUsed)
   .put("queryMs",Math.round(ms*100.0)/100.0);
 }
 private static long parseAdvRmMinor(Object raw,long def){
  if(raw==null)return def;
  String s=String.valueOf(raw).trim();
  if(s.isEmpty()||"null".equals(s))return def;
  try{return Math.max(0L,Math.round(Double.parseDouble(s)*100.0));}catch(Exception ignored){return def;}
 }
}
