package app.moneylens;

import org.json.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure domain model. No Android dependency. All writes operate on a private snapshot. */
public final class Ledger {
  public static final long MAX = 100_000_000_000L;
  public static String id(){return UUID.randomUUID().toString();}
  public static long minor(String input) {
    if(input==null || !input.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Enter a positive amount with at most two decimal places.");
    long n=new BigDecimal(input).movePointRight(2).longValueExact();
    if(n<=0 || n>MAX) throw new IllegalArgumentException("Amount is outside the supported range.");
    return n;
  }
  public static JSONObject initial() throws JSONException {
    JSONObject s=new JSONObject();s.put("schema",2);s.put("revision",0);
    for(String name: new String[]{"accounts","transactions","drafts","budgets","bills","goals","sources","seen","rules","favorites","receipts","imports","reconciliations","installments","customCategories","alerts","ruleHistory"})s.put(name,new JSONArray());
    s.getJSONArray("accounts").put(new JSONObject().put("id","cash").put("name","Cash").put("type","cash").put("color","sage"));
    s.put("settings",new JSONObject().put("name","Darren").put("theme","light").put("cycleDay",1).put("appLock",false));
    return s;
  }
  public static JSONObject find(JSONArray a,String id) throws JSONException {
    for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("id").equals(id))return a.getJSONObject(i);
    throw new IllegalArgumentException("This record no longer exists.");
  }
  static String str(JSONObject a,String key,int max) {
    String v=a.optString(key,"").trim();if(v.length()>max)throw new IllegalArgumentException(key+" is too long.");return v;
  }
  static void date(String v){LocalDate.parse(v);}
  public static JSONObject apply(JSONObject s,String action,JSONObject a)throws Exception{
    migrate(s);
    if(Extras.apply(s,action,a)){validate(s);s.put("revision",s.optLong("revision")+1);return s;}
    switch(action){
      case "transaction": post(s,a);break;
      case "void": {JSONObject t=find(s.getJSONArray("transactions"),a.getString("id"));if(a.optBoolean("restore")&&t.has("replacedBy"))throw new IllegalArgumentException("An edited transaction cannot be restored over its replacement.");boolean restoring=a.optBoolean("restore"),wasVoided=t.optBoolean("voided");if(restoring!=wasVoided)break;if(t.has("billId")){JSONObject bill=null;try{bill=find(s.getJSONArray("bills"),t.getString("billId"));}catch(IllegalArgumentException ignored){}if(bill!=null){String due=t.getString("billDate"),f=bill.optString("frequency",bill.optBoolean("monthly")?"monthly":"once"),next=f.equals("once")?due:Finance.advance(LocalDate.parse(due),f,bill.optInt("anchorDay",LocalDate.parse(due).getDayOfMonth())).toString();if(restoring){if(!bill.getString("date").equals(due)||bill.optBoolean("paid"))throw new IllegalArgumentException("The bill plan has moved on. Restore manually as a new record.");if(f.equals("once"))bill.put("paid",true);else bill.put("date",next);}else if(bill.getString("date").equals(next)){bill.put("date",due).put("paid",false);}}}t.put("voided",!restoring);break;}
      case "account": {
        String name=str(a,"name",60),type=a.optString("type","bank");
        if(name.isEmpty()||!Arrays.asList("bank","cash","wallet","card").contains(type))throw new IllegalArgumentException("Choose an account name and type.");
        String aid=id();s.getJSONArray("accounts").put(new JSONObject().put("id",aid).put("name",name).put("type",type).put("color",a.optString("color","sage")).put("balanceVerified",!a.optString("opening").isEmpty()));
        String opening=a.optString("opening","");if(!opening.isEmpty()&&new BigDecimal(opening).signum()!=0){
          long n=minor(opening);if(type.equals("card"))n=-n;
          JSONObject t=new JSONObject().put("id",id()).put("kind","opening").put("account",aid).put("amount",Math.abs(n)).put("date",LocalDate.now().toString()).put("merchant","Opening balance").put("category","Opening balance").put("created",System.currentTimeMillis());
          t.put("postings",new JSONArray().put(entry(aid,n)).put(entry("equity",-n)));s.getJSONArray("transactions").put(t);
        }break;
      }
      case "budget": Extras.budget(s,a);break;
      case "bill": Extras.bill(s,a);break;
      case "goal": {
        long target=minor(a.getString("target"));String raw=a.optString("saved","0");long saved=raw.equals("0")||raw.isEmpty()?0:minor(raw);
        upsert(s,"goals",new JSONObject().put("id",a.optString("id",id())).put("name",str(a,"name",80)).put("target",target).put("saved",saved));break;
      }
      case "deletePlan": {
        String table=a.getString("table");if(!Arrays.asList("budgets","bills","goals").contains(table))throw new IllegalArgumentException("Invalid plan.");JSONArray rows=s.getJSONArray(table);for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("id").equals(a.getString("id")))rows.remove(i);break;
      }
      case "ignore": {JSONObject draft=find(s.getJSONArray("drafts"),a.getString("id"));if(draft.optString("state").equals("review"))draft.put("state","ignored");break;}
      case "settings":{
        JSONObject settings=s.getJSONObject("settings");if(a.has("name"))settings.put("name",str(a,"name",50));
        if(a.has("theme"))settings.put("theme",a.getString("theme").equals("dark")?"dark":"light");
        if(a.has("cycleDay")){int d=a.getInt("cycleDay");if(d<1||d>31)throw new IllegalArgumentException("Cycle day must be 1–31.");settings.put("cycleDay",d);}
        if(a.has("appLock"))settings.put("appLock",a.getBoolean("appLock"));
        for(String k:new String[]{"quietEnabled","digest","reminders","budgetAlerts","widgetAmounts","captureWhileLocked"})if(a.has(k))settings.put(k,a.getBoolean(k));
        for(String k:new String[]{"quietStart","quietEnd","digestHour"})if(a.has(k)){int v=a.getInt(k);if(v<0||v>23)throw new IllegalArgumentException("Choose an hour from 0 to 23.");settings.put(k,v);}
        if(a.has("threshold")){int v=a.getInt("threshold");if(v<1||v>100)throw new IllegalArgumentException("Threshold must be 1–100%.");settings.put("threshold",v);}break;
      }
      case "source": {
        String pkg=a.getString("package");if(!pkg.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("Invalid app.");JSONArray rows=s.getJSONArray("sources");
        for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("package").equals(pkg))rows.remove(i);
        if(a.optBoolean("enabled"))rows.put(new JSONObject().put("package",pkg).put("name",str(a,"name",80)));break;
      }
      case "sample": {
        JSONObject d=new JSONObject().put("id",id()).put("merchant","ABC Cafe · sample").put("amount",1890).put("kind","expense").put("category","Food & drinks").put("source","Demonstration").put("date",LocalDate.now().toString()).put("state","review").put("sample",true).put("reason","Synthetic example. Saving creates a sample-labelled transaction.");s.getJSONArray("drafts").put(d);break;
      }
      default:throw new IllegalArgumentException("Unknown operation.");
    }
    validate(s);s.put("revision",s.optLong("revision")+1);return s;
  }
  static JSONObject entry(String account,long amount)throws JSONException{return new JSONObject().put("account",account).put("amount",amount).put("currency","MYR");}
  static void upsert(JSONObject s,String table,JSONObject row)throws JSONException{
    JSONArray rows=s.getJSONArray(table);for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(row.getString("id"))){rows.put(i,row);return;}rows.put(row);
  }
  static void post(JSONObject s,JSONObject a)throws Exception{
    String key=a.getString("key");if(key.length()>100)throw new IllegalArgumentException("Invalid save key.");
    JSONArray rows=s.getJSONArray("transactions");for(int i=0;i<rows.length();i++)if(key.equals(rows.getJSONObject(i).optString("key")))return;
    String draftId=a.optString("draftId","");JSONObject draft=null;
    if(!draftId.isEmpty()){
      draft=find(s.getJSONArray("drafts"),draftId);
      if(draft.optString("state").equals("confirmed"))return;
      if(a.has("draftRevision")&&a.getInt("draftRevision")!=draft.optInt("revision",1))throw new IllegalArgumentException("This suggestion changed. Open it again.");
      if(!draft.optString("state").equals("review"))throw new IllegalArgumentException("This suggestion has already been dismissed.");
    }
    String account=a.getString("account"),kind=a.getString("kind"),category=str(a,"category",60),dt=a.getString("date");
    find(s.getJSONArray("accounts"),account);date(dt);long n=minor(a.getString("amount"));JSONArray p=new JSONArray();
    String tid=id();p=postings(s,a,tid,n);
    JSONObject t=new JSONObject().put("id",tid).put("key",key).put("kind",kind).put("amount",n).put("account",account).put("to",a.optString("to","")).put("category",category).put("merchant",str(a,"merchant",120)).put("date",dt).put("note",str(a,"note",500)).put("postings",p).put("created",System.currentTimeMillis());
    for(String k:new String[]{"splits","ownAmount","person","receiptId","originalCurrency","originalAmount","exchangeRate","foreignFee","source","sourcePackage","tags","reimbursementFor","importBatch","importRow","installmentId"})if(a.has(k))t.put(k,a.get(k));
    if(a.has("originalCurrency")){String currency=a.getString("originalCurrency").toUpperCase(java.util.Locale.ROOT);java.util.Currency.getInstance(currency);if(new java.math.BigDecimal(a.getString("originalAmount")).signum()<=0)throw new IllegalArgumentException("Original amount must be positive.");t.put("originalCurrency",currency);BigDecimal fee=new BigDecimal(a.optString("foreignFee","0"));if(fee.signum()<0||fee.movePointRight(2).longValueExact()>=n)throw new IllegalArgumentException("Included fees must be less than the MYR total.");t.put("exchangeRate",BigDecimal.valueOf(n,2).subtract(fee).divide(new BigDecimal(a.getString("originalAmount")),8,java.math.RoundingMode.HALF_UP).toPlainString());}
    if(a.has("tags")&&a.getJSONArray("tags").length()>30)throw new IllegalArgumentException("At most 30 tags.");
    if(a.has("foreignFee")&&new BigDecimal(a.getString("foreignFee")).signum()<0)throw new IllegalArgumentException("Fee cannot be negative.");
    if(a.has("receiptId"))find(s.getJSONArray("receipts"),a.getString("receiptId"));
    String edit=a.optString("editId","");if(!edit.isEmpty()){JSONObject old=find(rows,edit);if(old.optBoolean("voided"))throw new IllegalArgumentException("This transaction was already removed.");old.put("voided",true).put("replacedBy",tid);t.put("replaces",edit);for(String k:new String[]{"billId","billDate","installmentId"})if(old.has(k))t.put(k,old.get(k));}
    String original=a.optString("originalId","");if(!original.isEmpty()){JSONObject orig=find(rows,original);if(!kind.equals("refund")||!orig.getString("kind").equals("expense")||orig.optBoolean("voided"))throw new IllegalArgumentException("Choose a recorded expense for this refund.");t.put("originalId",original);}
    rows.put(t);if(draft!=null){t.put("source",draft.optString("source")).put("sourcePackage",draft.optString("package"));draft.put("state","confirmed").put("transactionId",tid);if(draft.has("receiptId"))t.put("receiptId",draft.getString("receiptId"));}
    s.getJSONObject("settings").put("lastAccount",account).put("lastCategory",category);
    if(a.optBoolean("rememberRule")&&!t.optString("merchant").isEmpty())Extras.rule(s,new JSONObject().put("match",t.getString("merchant")).put("category",category).put("account",account).put("priority",10).put("enabled",true));
    String billId=a.optString("billId","");if(!billId.isEmpty()&&edit.isEmpty()){
      JSONObject bill=find(s.getJSONArray("bills"),billId);if(bill.optBoolean("paid")||bill.optBoolean("cancelled"))throw new IllegalArgumentException("This planned payment is already paid or cancelled.");String due=bill.getString("date");
      if(!due.equals(a.optString("billDate")))throw new IllegalArgumentException("This bill occurrence has changed. Open it again.");
      t.put("billId",billId).put("billDate",due);
      String frequency=bill.optString("frequency",bill.optBoolean("monthly")?"monthly":"once");
      if(!frequency.equals("once")){bill.put("date",Finance.advance(LocalDate.parse(due),frequency,bill.optInt("anchorDay",LocalDate.parse(due).getDayOfMonth())).toString());}else bill.put("paid",true);
    }
  }
  public static void migrate(JSONObject s)throws Exception{
    int version=s.getInt("schema");if(version<1||version>2)throw new IllegalArgumentException("Unsupported backup version.");
    for(String k:new String[]{"rules","favorites","receipts","imports","reconciliations","installments","customCategories","alerts","ruleHistory"})if(!s.has(k))s.put(k,new JSONArray());s.put("schema",2);
  }
  static JSONArray postings(JSONObject s,JSONObject a,String tid,long n)throws Exception{
    String kind=a.getString("kind"),account=a.getString("account"),category=a.optString("category","Other");JSONArray p=new JSONArray();
    if(kind.equals("transfer")){String to=a.getString("to");find(s.getJSONArray("accounts"),to);if(to.equals(account))throw new IllegalArgumentException("Choose two different accounts.");return p.put(entry(account,-n)).put(entry(to,n));}
    if(kind.equals("income"))return p.put(entry(account,n)).put(entry("income:"+(category.isEmpty()?"Other income":category),-n));
    if(kind.equals("reimbursement")){String original=a.getString("reimbursementFor");JSONObject t=find(s.getJSONArray("transactions"),original);if(!t.getString("kind").equals("shared")||t.optBoolean("voided"))throw new IllegalArgumentException("Choose an active shared expense.");long outstanding=Finance.balance(s,"receivable:"+original,"9999-12-31");if(!a.has("editId")&&n>outstanding)throw new IllegalArgumentException("Repayment exceeds the amount owed.");return p.put(entry(account,n)).put(entry("receivable:"+original,-n));}
    if(!Arrays.asList("expense","refund","shared").contains(kind))throw new IllegalArgumentException("Invalid transaction type.");long sign=kind.equals("refund")?-1:1;p.put(entry(account,-n*sign));
    if(kind.equals("shared")){String person=str(a,"person",80);if(person.isEmpty())throw new IllegalArgumentException("Enter who owes you.");long own=a.optString("ownAmount","0").equals("0")?0:minor(a.getString("ownAmount"));if(own>=n)throw new IllegalArgumentException("Your share must be less than the full payment.");if(own>0)p.put(entry("expense:"+category,own));p.put(entry("receivable:"+tid,n-own));return p;}
    JSONArray splits=a.optJSONArray("splits");if(splits==null||splits.length()==0){if(category.isEmpty())throw new IllegalArgumentException("Choose a category.");long fee=a.has("originalCurrency")?new BigDecimal(a.optString("foreignFee","0")).movePointRight(2).longValueExact():0;if(fee<0||fee>=n)throw new IllegalArgumentException("Fee must be less than the payment.");p.put(entry("expense:"+category,(n-fee)*sign));if(fee>0)p.put(entry("expense:Fees & charges",fee*sign));}else{if(splits.length()>30)throw new IllegalArgumentException("At most 30 splits.");long total=0;for(int i=0;i<splits.length();i++){JSONObject part=splits.getJSONObject(i);String cat=str(part,"category",60);if(cat.isEmpty())throw new IllegalArgumentException("Each split needs a category.");long v=minor(part.getString("amount"));total=Math.addExact(total,v);p.put(entry("expense:"+cat,v*sign));}if(total!=n)throw new IllegalArgumentException("Split amounts must add up to the full payment.");}return p;
  }
  public static void validate(JSONObject s)throws Exception{
    migrate(s);Set<String> ac=new HashSet<>(),ids=new HashSet<>();JSONArray accounts=s.getJSONArray("accounts");
    if(accounts.length()<1||accounts.length()>100)throw new IllegalArgumentException("Invalid account count.");
    for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);if(!ac.add(a.getString("id"))||a.getString("name").length()>60)throw new IllegalArgumentException("Invalid account.");}
    JSONArray tx=s.getJSONArray("transactions");if(tx.length()>100000)throw new IllegalArgumentException("Maximum 100,000 transactions.");
    Map<String,Long> receivables=new HashMap<>();
    for(int i=0;i<tx.length();i++){
      JSONObject t=tx.getJSONObject(i);if(!ids.add(t.getString("id")))throw new IllegalArgumentException("Duplicate transaction.");date(t.getString("date"));if(!ac.contains(t.getString("account")))throw new IllegalArgumentException("Missing account.");
      long n=t.getLong("amount");if(n<=0||n>MAX)throw new IllegalArgumentException("Invalid amount.");JSONArray p=t.getJSONArray("postings");if(p.length()<2||p.length()>31)throw new IllegalArgumentException("Invalid posting count.");long sum=0;
      String kind=t.getString("kind"),first=p.getJSONObject(0).getString("account");long firstAmount=p.getJSONObject(0).getLong("amount");if(!first.equals(t.getString("account"))||Math.abs(firstAmount)!=n)throw new IllegalArgumentException("Account mismatch.");
      for(int j=0;j<p.length();j++){JSONObject e=p.getJSONObject(j);long v=e.getLong("amount");String destination=e.getString("account");if(v==0||v>MAX||v< -MAX||!e.getString("currency").equals("MYR"))throw new IllegalArgumentException("Invalid posting.");sum=Math.addExact(sum,v);if(!t.optBoolean("voided")&&destination.startsWith("receivable:"))receivables.put(destination,Math.addExact(receivables.getOrDefault(destination,0L),v));
       if(j>0){switch(kind){
        case "expense":case "refund":if(!destination.startsWith("expense:")||Long.signum(v)!=(kind.equals("expense")?1:-1))throw new IllegalArgumentException("Invalid expense allocation.");break;
        case "shared":if(!(destination.startsWith("expense:")||destination.equals("receivable:"+t.getString("id")))||v<=0)throw new IllegalArgumentException("Invalid shared allocation.");break;
        case "income":if(!destination.startsWith("income:")||v>=0)throw new IllegalArgumentException("Invalid income.");break;
        case "transfer":if(!ac.contains(destination)||!destination.equals(t.getString("to"))||destination.equals(first)||v<=0||p.length()!=2)throw new IllegalArgumentException("Invalid transfer.");break;
        case "reimbursement":if(!destination.equals("receivable:"+t.getString("reimbursementFor"))||v>=0||p.length()!=2)throw new IllegalArgumentException("Invalid repayment.");break;
        case "opening":case "adjustment":if(!destination.equals("equity")||p.length()!=2)throw new IllegalArgumentException("Invalid adjustment.");break;
        default:throw new IllegalArgumentException("Invalid transaction type.");
       }}
      }if(sum!=0)throw new IllegalArgumentException("Unbalanced transaction.");
      if(Arrays.asList("expense","shared","transfer").contains(kind)&&firstAmount!=-n)throw new IllegalArgumentException("Invalid payment direction.");if(Arrays.asList("income","refund","reimbursement").contains(kind)&&firstAmount!=n)throw new IllegalArgumentException("Invalid receipt direction.");
    }
    for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided"))continue;if(t.getString("kind").equals("reimbursement")){JSONObject original=find(tx,t.getString("reimbursementFor"));if(original.optBoolean("voided")||!original.getString("kind").equals("shared"))throw new IllegalArgumentException("Missing shared expense.");}if(t.has("originalId")){JSONObject original=find(tx,t.getString("originalId"));if(!t.getString("kind").equals("refund")||!original.getString("kind").equals("expense"))throw new IllegalArgumentException("Invalid refund link.");}}
    for(long remaining:receivables.values())if(remaining<0)throw new IllegalArgumentException("A repayment would exceed its shared expense. Remove the repayment first.");
    for(String k:new String[]{"drafts","budgets","bills","goals","sources","seen","rules","favorites","receipts","imports","reconciliations","installments","customCategories","alerts","ruleHistory"})if(s.getJSONArray(k).length()>100000)throw new IllegalArgumentException("Too many records.");
    Set<String> cats=new HashSet<>();JSONArray budgets=s.getJSONArray("budgets");for(int i=0;i<budgets.length();i++){JSONObject b=budgets.getJSONObject(i);if(b.getLong("limit")<=0||b.getLong("limit")>MAX||!cats.add(b.getString("category")))throw new IllegalArgumentException("Invalid or duplicate category budget.");}
    JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);date(b.getString("date"));if(!ac.contains(b.getString("account"))||b.getLong("amount")<=0)throw new IllegalArgumentException("Invalid bill.");}
    int day=s.getJSONObject("settings").optInt("cycleDay",1);if(day<1||day>31)throw new IllegalArgumentException("Invalid cycle day.");
  }
}
