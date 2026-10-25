package app.moneylens;
import org.json.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** Additional workflows share the same validated ledger transaction. */
public final class Extras {
 static String name(JSONObject a,String field,int max){String n=Ledger.str(a,field,max);if(n.isEmpty())throw new IllegalArgumentException("Enter "+field+".");return n;}
 public static boolean apply(JSONObject s,String action,JSONObject a)throws Exception{
  switch(action){
   case "rule":rule(s,a);return true;
   case "undoRule":{JSONArray history=s.getJSONArray("ruleHistory");if(history.length()==0)throw new IllegalArgumentException("No rule change to undo.");s.put("rules",history.getJSONArray(history.length()-1));history.remove(history.length()-1);return true;}
   case "favorite":{JSONObject row=new JSONObject(a.toString()).put("id",a.optString("id",Ledger.id()));name(row,"name",80);Ledger.minor(row.getString("amount"));Ledger.find(s.getJSONArray("accounts"),row.getString("account"));Ledger.upsert(s,"favorites",row);return true;}
   case "category":{
    String n=name(a,"name",60);JSONObject settings=s.getJSONObject("settings");
    JSONArray hidden=settings.optJSONArray("hiddenCategories");
    if(hidden!=null){for(int i=hidden.length()-1;i>=0;i--)if(hidden.optString(i).equalsIgnoreCase(n))hidden.remove(i);}
    JSONArray rows=s.getJSONArray("customCategories");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("name").equalsIgnoreCase(n))return true;
    rows.put(new JSONObject().put("id",Ledger.id()).put("name",n).put("icon",a.optString("icon","wallet")).put("color",a.optString("color","sage")));
    return true;
   }
   case "removeCategory":{
    String n=name(a,"name",60);JSONArray rows=s.getJSONArray("customCategories");
    for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).optString("name").equalsIgnoreCase(n)||rows.getJSONObject(i).optString("id").equals(a.optString("id")))rows.remove(i);
    JSONObject settings=s.getJSONObject("settings");JSONArray hidden=settings.optJSONArray("hiddenCategories");if(hidden==null){hidden=new JSONArray();settings.put("hiddenCategories",hidden);}
    boolean exists=false;for(int i=0;i<hidden.length();i++)if(hidden.optString(i).equalsIgnoreCase(n))exists=true;
    if(!exists)hidden.put(n);
    return true;
   }
   case "addSubcategory":{
    String cat=name(a,"category",60),sub=name(a,"subcategory",80);
    JSONObject settings=s.getJSONObject("settings");
    JSONObject hiddenMap=settings.optJSONObject("hiddenSubcategories");
    if(hiddenMap!=null&&hiddenMap.has(cat)){JSONArray h=hiddenMap.getJSONArray(cat);for(int i=h.length()-1;i>=0;i--)if(h.optString(i).equalsIgnoreCase(sub))h.remove(i);}
    JSONObject customMap=settings.optJSONObject("customSubcategories");if(customMap==null){customMap=new JSONObject();settings.put("customSubcategories",customMap);}
    JSONArray list=customMap.optJSONArray(cat);if(list==null){list=new JSONArray();customMap.put(cat,list);}
    for(int i=0;i<list.length();i++)if(list.optString(i).equalsIgnoreCase(sub))return true;
    list.put(sub);
    return true;
   }
   case "removeSubcategory":{
    String cat=name(a,"category",60),sub=name(a,"subcategory",80);
    JSONObject settings=s.getJSONObject("settings");
    JSONObject customMap=settings.optJSONObject("customSubcategories");
    if(customMap!=null&&customMap.has(cat)){JSONArray list=customMap.getJSONArray(cat);for(int i=list.length()-1;i>=0;i--)if(list.optString(i).equalsIgnoreCase(sub))list.remove(i);}
    JSONObject hiddenMap=settings.optJSONObject("hiddenSubcategories");if(hiddenMap==null){hiddenMap=new JSONObject();settings.put("hiddenSubcategories",hiddenMap);}
    JSONArray h=hiddenMap.optJSONArray(cat);if(h==null){h=new JSONArray();hiddenMap.put(cat,h);}
    boolean exists=false;for(int i=0;i<h.length();i++)if(h.optString(i).equalsIgnoreCase(sub))exists=true;
    if(!exists)h.put(sub);
    return true;
   }
   case "taxProfile":{
    JSONObject prof=new JSONObject();
    for(String k:new String[]{"monthlyBaseSalary","annualBonus","otherIncome","epfRate","monthlyPcb","epfRelief","lifeInsurance","medicalInsurance","socso","lifestyle","sports","medical","education","sspnPrs","familyRelief","zakat"}){
     if(a.has(k)&&!a.optString(k,"").trim().isEmpty()){
      double v=Double.parseDouble(a.optString(k,"0").trim());
      if(v<0||v>100_000_000)throw new IllegalArgumentException("Enter a valid non-negative amount for "+k+".");
      prof.put(k,v);
     }
    }
    s.getJSONObject("settings").put("taxProfile",prof);
    return true;
   }
   case "snoozeBill":{
    JSONObject b=Ledger.find(s.getJSONArray("bills"),a.getString("id"));
    b.put("snoozeUntil",System.currentTimeMillis()+a.optLong("durationMs",3600_000L));
    return true;
   }
   case "dismissBillCycle":{
    JSONObject b=Ledger.find(s.getJSONArray("bills"),a.getString("id"));
    b.put("dismissedCycle",b.getString("date"));
    return true;
   }
   case "deleteExtra":{String table=a.getString("table");if(!Arrays.asList("rules","favorites","customCategories").contains(table))throw new IllegalArgumentException("Invalid item.");if(table.equals("rules"))rememberRules(s);JSONArray rows=s.getJSONArray(table);for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("id").equals(a.getString("id")))rows.remove(i);return true;}
   case "draft":{JSONObject d=new JSONObject(a.toString()).put("id",a.optString("id",Ledger.id())).put("state","review").put("revision",1).put("created",System.currentTimeMillis());boolean auto=d.optBoolean("autoCapture");d.remove("autoCapture");d.remove("text");Finance.suggest(s,d);s.getJSONArray("drafts").put(d);if(auto)autoPost(s,d);return true;}
   case "linkDraft":{JSONObject d=Ledger.find(s.getJSONArray("drafts"),a.getString("id"));JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("transactionId"));if(t.optBoolean("voided"))throw new IllegalArgumentException("Choose a current transaction.");if(!d.optString("state").equals("review"))return true;d.put("state","linked").put("transactionId",t.getString("id"));if(d.has("receiptId")&&!t.has("receiptId"))t.put("receiptId",d.getString("receiptId"));return true;}
   case "ignoreBatch":{JSONArray ids=a.getJSONArray("ids");for(int i=0;i<ids.length();i++){JSONObject d=Ledger.find(s.getJSONArray("drafts"),ids.getString(i));if(d.optString("state").equals("review"))d.put("state","ignored");}return true;}
   case "confirmBatch":{JSONArray rows=a.getJSONArray("rows");if(rows.length()>100)throw new IllegalArgumentException("Review at most 100 suggestions at once.");for(int i=0;i<rows.length();i++)Ledger.post(s,rows.getJSONObject(i));return true;}
   case "reconcile":{
    String account=a.getString("account"),dt=a.getString("date");Ledger.find(s.getJSONArray("accounts"),account);LocalDate.parse(dt);long actual=new BigDecimal(a.getString("actual")).movePointRight(2).longValueExact();if(Math.abs(actual)>Ledger.MAX)throw new IllegalArgumentException("Invalid balance.");Ledger.find(s.getJSONArray("accounts"),account).put("balanceVerified",true);long recorded=Finance.balance(s,account,dt),delta=actual-recorded;JSONObject r=new JSONObject().put("id",Ledger.id()).put("account",account).put("date",dt).put("actual",actual).put("recorded",recorded).put("difference",delta).put("adjusted",a.optBoolean("adjust"));
    if(a.optBoolean("adjust")&&delta!=0){String tid=Ledger.id();JSONObject t=new JSONObject().put("id",tid).put("kind","adjustment").put("amount",Math.abs(delta)).put("account",account).put("date",dt).put("merchant","Balance reconciliation").put("category","Adjustment").put("note",Ledger.str(a,"note",500)).put("created",System.currentTimeMillis()).put("postings",new JSONArray().put(Ledger.entry(account,delta)).put(Ledger.entry("equity",-delta)));s.getJSONArray("transactions").put(t);r.put("transactionId",tid);}s.getJSONArray("reconciliations").put(r);return true;
   }
   case "installment":installment(s,a);return true;
   case "matchBill":{JSONObject bill=Ledger.find(s.getJSONArray("bills"),a.getString("id")),t=Ledger.find(s.getJSONArray("transactions"),a.getString("transactionId"));if(bill.optBoolean("paid")||bill.optBoolean("cancelled")||t.optBoolean("voided")||t.has("billId"))throw new IllegalArgumentException("Choose an unpaid plan and an unlinked active transaction.");if(!t.getString("account").equals(bill.getString("account"))||!t.getString("kind").equals(bill.optString("kind","expense"))||("transfer".equals(t.getString("kind"))&&!t.optString("to").equals(bill.optString("to"))))throw new IllegalArgumentException("Payment account or type does not match this bill.");t.put("billId",bill.getString("id")).put("billDate",bill.getString("date"));String f=bill.optString("frequency",bill.optBoolean("monthly")?"monthly":"once");if(f.equals("once"))bill.put("paid",true);else bill.put("date",Finance.advance(LocalDate.parse(bill.getString("date")),f,bill.optInt("anchorDay",LocalDate.parse(bill.getString("date")).getDayOfMonth())).toString());return true;}
   case "cancelBill":{JSONObject b=Ledger.find(s.getJSONArray("bills"),a.getString("id"));b.put("cancelled",a.optBoolean("cancelled",true));return true;}
   case "importCommit":{
    String batch=a.getString("batch");JSONArray rows=a.getJSONArray("rows");if(rows.length()>1000)throw new IllegalArgumentException("Import at most 1,000 rows per batch.");int count=0;JSONArray ids=new JSONArray();
    for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String sourceKey=row.getString("importRow");boolean exists=false;JSONArray tx=s.getJSONArray("transactions");for(int j=0;j<tx.length();j++)if(sourceKey.equals(tx.getJSONObject(j).optString("importRow")))exists=true;if(exists)continue;int before=tx.length();row.put("key","import:"+sourceKey).put("importBatch",batch);Ledger.post(s,row);if(tx.length()>before){count++;ids.put(tx.getJSONObject(tx.length()-1).getString("id"));}}
    Ledger.upsert(s,"imports",new JSONObject().put("id",batch).put("count",count).put("name",a.optString("name","CSV import")).put("date",LocalDate.now().toString()).put("transactionIds",ids));return true;
   }
   case "undoImport":{JSONObject batch=Ledger.find(s.getJSONArray("imports"),a.getString("id"));JSONArray tx=s.getJSONArray("transactions");for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optString("importBatch").equals(batch.getString("id"))&&!t.has("replacedBy"))t.put("voided",true);}batch.put("undone",true);return true;}
   case "quickNote":{
    JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("id"));if(t.optBoolean("voided"))throw new IllegalArgumentException("This transaction was removed.");
    t.put("note",Ledger.str(a,"note",500)).put("needsNote",false);
    if(a.has("subcategory"))t.put("subcategory",Ledger.str(a,"subcategory",80));
    if(a.has("category")){
     String newCat=Ledger.str(a,"category",60);
     if(!newCat.isEmpty()&&Arrays.asList("expense","refund").contains(t.optString("kind"))&&!t.has("splits")){
      String oldCat=t.optString("category","");t.put("category",newCat);
      JSONArray p=t.getJSONArray("postings");for(int i=0;i<p.length();i++){JSONObject e=p.getJSONObject(i);if(e.getString("account").equals("expense:"+oldCat)){e.put("account","expense:"+newCat);break;}}
     }
    }
    if(a.optBoolean("applyHistory")&&!t.optString("merchant").isEmpty())updateMerchantHistory(s,t.getString("merchant"),t.optString("category"),t.optString("subcategory",""),t.optString("account"));
    return true;
   }
   case "updateMerchantHistory":{
    updateMerchantHistory(s,name(a,"merchant",120),name(a,"category",60),a.optString("subcategory",""),a.optString("account",""));
    return true;
   }
   default:return false;
  }
 }
 public static int updateMerchantHistory(JSONObject s,String merchant,String category,String account)throws Exception{
  return updateMerchantHistory(s,merchant,category,"",account);
 }
 public static int updateMerchantHistory(JSONObject s,String merchant,String category,String subcategory,String account)throws Exception{
  String m=merchant==null?"":merchant.trim(),cat=category==null?"":category.trim(),sub=subcategory==null?"":subcategory.trim();if(m.isEmpty()||cat.isEmpty())return 0;
  int updated=0;JSONArray tx=s.getJSONArray("transactions");
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||!t.optString("merchant","").trim().equalsIgnoreCase(m))continue;
   if(!Arrays.asList("expense","refund").contains(t.optString("kind"))||t.has("splits"))continue;
   String oldCat=t.optString("category","");
   if(!sub.isEmpty())t.put("subcategory",sub);
   if(!oldCat.equals(cat)){
    t.put("category",cat);
    JSONArray p=t.getJSONArray("postings");for(int j=0;j<p.length();j++){JSONObject e=p.getJSONObject(j);if(e.getString("account").equals("expense:"+oldCat)){e.put("account","expense:"+cat);break;}}
    updated++;
   }
  }
  JSONObject ruleArgs=new JSONObject().put("match",m).put("category",cat).put("priority",20).put("enabled",true);
  if(!sub.isEmpty())ruleArgs.put("subcategory",sub);
  if(account!=null&&!account.isEmpty())ruleArgs.put("account",account);
  JSONArray rules=s.getJSONArray("rules");
  for(int i=0;i<rules.length();i++){if(rules.getJSONObject(i).optString("match","").trim().equalsIgnoreCase(m)){ruleArgs.put("id",rules.getJSONObject(i).getString("id"));break;}}
  rule(s,ruleArgs);
  return updated;
 }
 public static void rememberRules(JSONObject s)throws Exception{JSONArray h=s.getJSONArray("ruleHistory");h.put(new JSONArray(s.getJSONArray("rules").toString()));while(h.length()>30)h.remove(0);}
 public static void rule(JSONObject s,JSONObject a)throws Exception{
  rememberRules(s);
  String match=name(a,"match",120),account=a.optString("account","");if(!account.isEmpty())Ledger.find(s.getJSONArray("accounts"),account);
  JSONObject row=new JSONObject().put("id",a.optString("id",Ledger.id())).put("match",match).put("category",name(a,"category",60)).put("account",account).put("source",a.optString("source","")).put("priority",Math.max(0,Math.min(100,a.optInt("priority",10)))).put("enabled",a.optBoolean("enabled",true));
  if(!a.optString("subcategory").isEmpty())row.put("subcategory",Ledger.str(a,"subcategory",80));
  Ledger.upsert(s,"rules",row);
 }
 public static void budget(JSONObject s,JSONObject a)throws Exception{
  LocalDate now=LocalDate.now();String bid=a.optString("id",Ledger.id());JSONObject old=null;try{old=Ledger.find(s.getJSONArray("budgets"),bid);}catch(IllegalArgumentException ignored){}
  JSONObject b=new JSONObject().put("id",bid).put("category",name(a,"category",60)).put("limit",Ledger.minor(a.getString("amount"))).put("rollover",a.optBoolean("rollover")).put("createdDate",old==null?now.toString():old.optString("createdDate",now.toString()));
  JSONArray history=old==null?new JSONArray():old.optJSONArray("history");if(history==null)history=new JSONArray().put(new JSONObject().put("start",old.optString("createdDate",now.toString())).put("limit",old.getLong("limit")).put("rollover",old.optBoolean("rollover")));
  String cycle=Finance.start(now,s.getJSONObject("settings").optInt("cycleDay",1)).toString();for(int i=history.length()-1;i>=0;i--)if(history.getJSONObject(i).getString("start").equals(cycle))history.remove(i);history.put(new JSONObject().put("start",cycle).put("limit",b.getLong("limit")).put("rollover",b.optBoolean("rollover")));b.put("history",history);Ledger.upsert(s,"budgets",b);
 }
 public static void bill(JSONObject s,JSONObject a)throws Exception{
  LocalDate date=LocalDate.parse(a.getString("date"));String frequency=a.optString("frequency",a.optBoolean("monthly",true)?"monthly":"once");if(!Arrays.asList("once","daily","weekly","everyXDays","monthly","yearly").contains(frequency))throw new IllegalArgumentException("Invalid schedule.");
  int intervalDays=Math.max(1,Math.min(365,a.optInt("intervalDays",30)));
  int anchor=frequency.equals("everyXDays")?intervalDays:date.getDayOfMonth();
  int remindDaysBefore=Math.max(0,Math.min(30,a.optInt("remindDaysBefore",3)));
  String remindTime=a.optString("remindTime","09:00").trim();
  if(!remindTime.matches("([01]?[0-9]|2[0-3]):[0-5][0-9]"))remindTime="09:00";
  if(remindTime.length()==4)remindTime="0"+remindTime;
  JSONObject b=new JSONObject().put("id",a.optString("id",Ledger.id())).put("name",name(a,"name",80)).put("amount",Ledger.minor(a.getString("amount"))).put("date",date.toString()).put("category",a.optString("category","Bills & utilities")).put("subcategory",a.optString("subcategory","")).put("account",a.getString("account")).put("frequency",frequency).put("monthly",frequency.equals("monthly")).put("anchorDay",anchor).put("intervalDays",intervalDays).put("remindDaysBefore",remindDaysBefore).put("remindTime",remindTime).put("reminderType",a.optString("reminderType","bill")).put("variable",a.optBoolean("variable")).put("subscription",a.optBoolean("subscription")).put("cancelDate",a.optString("cancelDate","")).put("kind",a.optString("kind","expense")).put("to",a.optString("to",""));
  if(!b.getString("cancelDate").isEmpty())LocalDate.parse(b.getString("cancelDate"));Ledger.upsert(s,"bills",b);
 }
 static void installment(JSONObject s,JSONObject a)throws Exception{
  String key=a.getString("key");JSONArray existing=s.getJSONArray("installments");for(int i=0;i<existing.length();i++)if(key.equals(existing.getJSONObject(i).optString("key")))return;
  String label=name(a,"name",80),pay=a.getString("account"),pid=Ledger.id();Ledger.find(s.getJSONArray("accounts"),pay);long price=Ledger.minor(a.getString("amount")),fee=new BigDecimal(a.optString("fee","0")).signum()==0?0:Ledger.minor(a.getString("fee"));int months=a.getInt("months");if(months<2||months>60)throw new IllegalArgumentException("Choose 2–60 payments.");long total=Math.addExact(price,fee);if(total<months)throw new IllegalArgumentException("Each installment must be at least one sen.");if(total>Ledger.MAX)throw new IllegalArgumentException("Installment total too large.");String liability=Ledger.id();s.getJSONArray("accounts").put(new JSONObject().put("id",liability).put("name",("Installments · "+label).substring(0,Math.min(60,15+label.length()))).put("type","card").put("color","purple"));
  LocalDate first=LocalDate.parse(a.getString("date"));JSONObject purchase=new JSONObject().put("key","purchase:"+key).put("kind","expense").put("amount",BigDecimal.valueOf(total,2).toPlainString()).put("account",liability).put("category",a.optString("category","Shopping")).put("merchant",label).put("date",LocalDate.now().toString()).put("installmentId",pid);Ledger.post(s,purchase);
  JSONArray bills=s.getJSONArray("bills");long base=total/months,remainder=total%months;for(int i=0;i<months;i++){LocalDate due=Finance.anchored(YearMonth.from(first).plusMonths(i),first.getDayOfMonth());bills.put(new JSONObject().put("id",Ledger.id()).put("name",label+" · "+(i+1)+"/"+months).put("amount",base+(i<remainder?1:0)).put("date",due.toString()).put("account",pay).put("to",liability).put("kind","transfer").put("category","Installments").put("frequency","once").put("monthly",false).put("installmentId",pid));}
  existing.put(new JSONObject().put("id",pid).put("key",key).put("name",label).put("amount",price).put("fee",fee).put("total",total).put("months",months).put("liability",liability).put("firstDate",first.toString()));
 }
 public static void autoPost(JSONObject s,JSONObject d)throws Exception{
  long amt=d.optLong("amount")>0?d.optLong("amount"):d.optLong("primaryAmount",0);
  if(!s.getJSONObject("settings").optBoolean("autoRecord",true)||amt<=0)return;
  String acct=resolveAccount(s,d.optString("account",""));
  String kind=d.optString("kind","expense");
  String to=d.optString("to","");
  if(kind.equals("transfer")&&(to.isEmpty()||to.equals(acct)))kind="expense";
  String cat=d.optString("category","Miscellaneous");if(cat.isEmpty()||cat.equals("Other"))cat="Miscellaneous";
  d.put("amount",amt).put("account",acct).put("category",cat).put("kind",kind);
  JSONObject txArgs=new JSONObject().put("key","auto:"+d.getString("id")).put("draftId",d.getString("id")).put("draftRevision",d.optInt("revision",1)).put("kind",kind).put("amount",BigDecimal.valueOf(amt,2).toPlainString()).put("account",acct).put("category",cat).put("merchant",d.optString("merchant","Detected payment")).put("date",d.optString("date",LocalDate.now().toString())).put("note",d.optString("note","")).put("autoRecorded",true).put("historyMatch",d.optString("historyMatch","none")).put("needsNote",d.optBoolean("needsNote",true));
  if(!d.optString("subcategory").isEmpty())txArgs.put("subcategory",d.getString("subcategory"));
  if(kind.equals("transfer"))txArgs.put("to",to);
  Ledger.post(s,txArgs);
  d.put("autoRecorded",true);
 }
 public static JSONObject captureState(JSONObject s,String pkg,String label,String key,String text,long now)throws Exception{
  return captureState(s,pkg,label,key,text,now,now);
 }
 public static JSONObject captureState(JSONObject s,String pkg,String label,String key,String text,long postTime,long now)throws Exception{
  boolean selected=false;JSONArray sources=s.getJSONArray("sources");
  for(int i=0;i<sources.length();i++){JSONObject src=sources.getJSONObject(i);if(src.getString("package").equals(pkg)){selected=true;label=src.optString("name",label);break;}}
  boolean allowAll=s.getJSONObject("settings").optBoolean("captureAllSources",true);
  boolean isChatApp=pkg!=null&&pkg.matches("(?i).*(whatsapp|telegram|discord|messaging|mms|google\\.android\\.gm|facebook\\.orca|instagram|tencent\\.mm|line\\.android|viber|signal).*");
  if(!selected&&(!allowAll||isChatApp))return null;
  JSONObject d=PaymentParser.parse(text,label);if(d==null)return null;
  String instanceDigest=hex(java.security.MessageDigest.getInstance("SHA-256").digest((pkg+"|"+key+"|"+postTime+"|"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  String contentDigest=hex(java.security.MessageDigest.getInstance("SHA-256").digest((pkg+"|"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  JSONArray seen=s.getJSONArray("seen");
  for(int i=seen.length()-1;i>=0;i--){
   JSONObject row=seen.getJSONObject(i);long age=now-row.optLong("time");
   if(age>7L*86400000){seen.remove(i);continue;}
   if(postTime>0&&row.optString("hash").equals(instanceDigest))return null;
   if(row.optString("contentHash",row.optString("hash")).equals(contentDigest)&&Math.abs(age)<60_000L)return null;
  }
  if(!selected&&pkg!=null&&pkg.matches("[A-Za-z0-9_.]+")){
   sources.put(new JSONObject().put("package",pkg).put("name",label==null||label.isEmpty()?pkg:label.substring(0,Math.min(80,label.length()))));
  }
  JSONArray drafts=s.getJSONArray("drafts");int pending=0;for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).optString("state").equals("review"))pending++;
  if(pending>=500)throw new IllegalStateException("Review your Inbox before capturing more payments.");
  while(drafts.length()>=1000){int remove=-1;for(int i=0;i<drafts.length();i++)if(!drafts.getJSONObject(i).optString("state").equals("review")){remove=i;break;}if(remove<0)break;drafts.remove(remove);}
  for(int i=drafts.length()-1;i>=0;i--){JSONObject other=drafts.getJSONObject(i);if(d.optLong("amount")>0&&other.optLong("amount")==d.optLong("amount")&&now-other.optLong("created")<300000)d.put("reason",d.getString("reason")+" A similar recent amount exists; check for a duplicate.");}
  d.put("package",pkg);Finance.suggest(s,d);drafts.put(d);
  autoPost(s,d);
  seen.put(new JSONObject().put("hash",instanceDigest).put("contentHash",contentDigest).put("time",now));while(seen.length()>5000)seen.remove(0);
  s.put("lastCapture",now).put("revision",s.optLong("revision")+1);Ledger.validate(s);return d;
 }
 private static String resolveAccount(JSONObject s,String preferred)throws JSONException{
  JSONArray accounts=s.getJSONArray("accounts");
  if(!preferred.isEmpty())for(int i=0;i<accounts.length();i++)if(preferred.equals(accounts.getJSONObject(i).optString("id")))return preferred;
  String last=s.getJSONObject("settings").optString("lastAccount","");
  if(!last.isEmpty())for(int i=0;i<accounts.length();i++)if(last.equals(accounts.getJSONObject(i).optString("id")))return last;
  return accounts.getJSONObject(0).getString("id");
 }
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
}
