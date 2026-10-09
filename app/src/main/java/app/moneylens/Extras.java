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
   case "category":{String n=name(a,"name",60);JSONArray rows=s.getJSONArray("customCategories");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("name").equalsIgnoreCase(n))return true;rows.put(new JSONObject().put("id",Ledger.id()).put("name",n).put("icon",a.optString("icon","wallet")).put("color",a.optString("color","sage")));return true;}
   case "deleteExtra":{String table=a.getString("table");if(!Arrays.asList("rules","favorites","customCategories").contains(table))throw new IllegalArgumentException("Invalid item.");if(table.equals("rules"))rememberRules(s);JSONArray rows=s.getJSONArray(table);for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("id").equals(a.getString("id")))rows.remove(i);return true;}
   case "draft":{JSONObject d=new JSONObject(a.toString()).put("id",a.optString("id",Ledger.id())).put("state","review").put("revision",1).put("created",System.currentTimeMillis());d.remove("text");Finance.suggest(s,d);s.getJSONArray("drafts").put(d);return true;}
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
   default:return false;
  }
 }
 public static void rememberRules(JSONObject s)throws Exception{JSONArray h=s.getJSONArray("ruleHistory");h.put(new JSONArray(s.getJSONArray("rules").toString()));while(h.length()>30)h.remove(0);}
 public static void rule(JSONObject s,JSONObject a)throws Exception{
  rememberRules(s);
  String match=name(a,"match",120),account=a.optString("account","");if(!account.isEmpty())Ledger.find(s.getJSONArray("accounts"),account);
  JSONObject row=new JSONObject().put("id",a.optString("id",Ledger.id())).put("match",match).put("category",name(a,"category",60)).put("account",account).put("source",a.optString("source","")).put("priority",Math.max(0,Math.min(100,a.optInt("priority",10)))).put("enabled",a.optBoolean("enabled",true));Ledger.upsert(s,"rules",row);
 }
 public static void budget(JSONObject s,JSONObject a)throws Exception{
  LocalDate now=LocalDate.now();String bid=a.optString("id",Ledger.id());JSONObject old=null;try{old=Ledger.find(s.getJSONArray("budgets"),bid);}catch(IllegalArgumentException ignored){}
  JSONObject b=new JSONObject().put("id",bid).put("category",name(a,"category",60)).put("limit",Ledger.minor(a.getString("amount"))).put("rollover",a.optBoolean("rollover")).put("createdDate",old==null?now.toString():old.optString("createdDate",now.toString()));
  JSONArray history=old==null?new JSONArray():old.optJSONArray("history");if(history==null)history=new JSONArray().put(new JSONObject().put("start",old.optString("createdDate",now.toString())).put("limit",old.getLong("limit")).put("rollover",old.optBoolean("rollover")));
  String cycle=Finance.start(now,s.getJSONObject("settings").optInt("cycleDay",1)).toString();for(int i=history.length()-1;i>=0;i--)if(history.getJSONObject(i).getString("start").equals(cycle))history.remove(i);history.put(new JSONObject().put("start",cycle).put("limit",b.getLong("limit")).put("rollover",b.optBoolean("rollover")));b.put("history",history);Ledger.upsert(s,"budgets",b);
 }
 public static void bill(JSONObject s,JSONObject a)throws Exception{
  LocalDate date=LocalDate.parse(a.getString("date"));String frequency=a.optString("frequency",a.optBoolean("monthly",true)?"monthly":"once");if(!Arrays.asList("once","weekly","monthly","yearly").contains(frequency))throw new IllegalArgumentException("Invalid schedule.");
  JSONObject b=new JSONObject().put("id",a.optString("id",Ledger.id())).put("name",name(a,"name",80)).put("amount",Ledger.minor(a.getString("amount"))).put("date",date.toString()).put("category",a.optString("category","Bills & utilities")).put("account",a.getString("account")).put("frequency",frequency).put("monthly",frequency.equals("monthly")).put("anchorDay",date.getDayOfMonth()).put("variable",a.optBoolean("variable")).put("subscription",a.optBoolean("subscription")).put("cancelDate",a.optString("cancelDate","")).put("kind",a.optString("kind","expense")).put("to",a.optString("to",""));
  if(!b.getString("cancelDate").isEmpty())LocalDate.parse(b.getString("cancelDate"));Ledger.upsert(s,"bills",b);
 }
 static void installment(JSONObject s,JSONObject a)throws Exception{
  String key=a.getString("key");JSONArray existing=s.getJSONArray("installments");for(int i=0;i<existing.length();i++)if(key.equals(existing.getJSONObject(i).optString("key")))return;
  String label=name(a,"name",80),pay=a.getString("account"),pid=Ledger.id();Ledger.find(s.getJSONArray("accounts"),pay);long price=Ledger.minor(a.getString("amount")),fee=new BigDecimal(a.optString("fee","0")).signum()==0?0:Ledger.minor(a.getString("fee"));int months=a.getInt("months");if(months<2||months>60)throw new IllegalArgumentException("Choose 2–60 payments.");long total=Math.addExact(price,fee);if(total<months)throw new IllegalArgumentException("Each installment must be at least one sen.");if(total>Ledger.MAX)throw new IllegalArgumentException("Installment total too large.");String liability=Ledger.id();s.getJSONArray("accounts").put(new JSONObject().put("id",liability).put("name",("Installments · "+label).substring(0,Math.min(60,15+label.length()))).put("type","card").put("color","purple"));
  LocalDate first=LocalDate.parse(a.getString("date"));JSONObject purchase=new JSONObject().put("key","purchase:"+key).put("kind","expense").put("amount",BigDecimal.valueOf(total,2).toPlainString()).put("account",liability).put("category",a.optString("category","Shopping")).put("merchant",label).put("date",LocalDate.now().toString()).put("installmentId",pid);Ledger.post(s,purchase);
  JSONArray bills=s.getJSONArray("bills");long base=total/months,remainder=total%months;for(int i=0;i<months;i++){LocalDate due=Finance.anchored(YearMonth.from(first).plusMonths(i),first.getDayOfMonth());bills.put(new JSONObject().put("id",Ledger.id()).put("name",label+" · "+(i+1)+"/"+months).put("amount",base+(i<remainder?1:0)).put("date",due.toString()).put("account",pay).put("to",liability).put("kind","transfer").put("category","Installments").put("frequency","once").put("monthly",false).put("installmentId",pid));}
  existing.put(new JSONObject().put("id",pid).put("key",key).put("name",label).put("amount",price).put("fee",fee).put("total",total).put("months",months).put("liability",liability).put("firstDate",first.toString()));
 }
}
