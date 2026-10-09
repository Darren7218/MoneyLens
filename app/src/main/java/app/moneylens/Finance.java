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
 public static LocalDate advance(LocalDate d,String frequency,int anchor){switch(frequency){case "weekly":return d.plusWeeks(1);case "yearly":return anchored(YearMonth.from(d).plusYears(1),anchor);default:return anchored(YearMonth.from(d).plusMonths(1),anchor);}}
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
  return new JSONObject().put("start",from.toString()).put("end",until.toString()).put("spent",spend(s,"",from,on.plusDays(1))).put("previousComparable",spend(s,"",prev,prevUntil)).put("budgets",budgets).put("dailyAllowance",budgetRemaining/Math.max(1,ChronoUnit.DAYS.between(on,until))).put("liquid",liquid).put("cardReserve",debt).put("billsReserve",commitments).put("goalReserve",reserved).put("availableToSpend",Math.max(0,liquid-debt-commitments-reserved)).put("owed",owed).put("receivables",receivables).put("months",months).put("subscriptions",subscriptions(s));
 }
 public static JSONArray subscriptions(JSONObject s)throws Exception{
  Map<String,List<JSONObject>> groups=new LinkedHashMap<>();JSONArray tx=s.getJSONArray("transactions"),out=new JSONArray();for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.getString("kind").equals("expense")||t.optString("merchant").trim().isEmpty())continue;String k=t.getString("merchant").toLowerCase(Locale.ROOT)+"|"+t.getString("account");groups.computeIfAbsent(k,x->new ArrayList<>()).add(t);}
  for(List<JSONObject> rows:groups.values()){rows.sort(Comparator.comparing(x->x.optString("date")));if(rows.size()<3)continue;JSONObject a=rows.get(rows.size()-3),b=rows.get(rows.size()-2),c=rows.get(rows.size()-1);long gap1=ChronoUnit.DAYS.between(LocalDate.parse(a.getString("date")),LocalDate.parse(b.getString("date"))),gap2=ChronoUnit.DAYS.between(LocalDate.parse(b.getString("date")),LocalDate.parse(c.getString("date")));boolean weekly=gap1>=6&&gap1<=8&&gap2>=6&&gap2<=8,monthly=gap1>=26&&gap1<=33&&gap2>=26&&gap2<=33;if((weekly||monthly)&&a.getLong("amount")==c.getLong("amount")&&b.getLong("amount")==c.getLong("amount")){boolean exists=false;JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++)if(bills.getJSONObject(i).optString("name").equalsIgnoreCase(c.getString("merchant")))exists=true;if(!exists)out.put(new JSONObject().put("name",c.getString("merchant")).put("amount",c.getLong("amount")).put("account",c.getString("account")).put("category",c.getString("category")).put("frequency",weekly?"weekly":"monthly").put("date",(weekly?LocalDate.parse(c.getString("date")).plusWeeks(1):LocalDate.parse(c.getString("date")).plusMonths(1)).toString()));}}
  return out;
 }
 public static JSONObject suggest(JSONObject s,JSONObject draft)throws Exception{
  JSONArray rules=s.getJSONArray("rules");List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<rules.length();i++)sorted.add(rules.getJSONObject(i));sorted.sort((a,b)->Integer.compare(b.optInt("priority"),a.optInt("priority")));
  for(JSONObject rule:sorted){if(!rule.optBoolean("enabled",true))continue;String merchant=draft.optString("merchant").toLowerCase(Locale.ROOT),needle=rule.optString("match").toLowerCase(Locale.ROOT),source=rule.optString("source");if(!needle.isEmpty()&&merchant.contains(needle)&&(source.isEmpty()||source.equals(draft.optString("package")))){draft.put("category",rule.getString("category"));if(!rule.optString("account").isEmpty())draft.put("account",rule.getString("account"));draft.put("ruleId",rule.getString("id"));break;}}return draft;
 }
}
