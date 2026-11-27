package app.moneylens;
import org.json.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
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
   case "updateBillAmount":{
    JSONObject b=Ledger.find(s.getJSONArray("bills"),a.getString("id"));
    long newAmt=a.has("amountMinor")?a.getLong("amountMinor"):Ledger.minor(a.getString("amount"));
    if(newAmt<=0||newAmt>Ledger.MAX)throw new IllegalArgumentException("Invalid bill amount.");
    b.put("amount",newAmt);
    return true;
   }
   case "payBillAuto":{
    JSONObject bill=Ledger.find(s.getJSONArray("bills"),a.getString("id"));
    if(bill.optBoolean("paid")||bill.optBoolean("cancelled"))return true;
    String due=bill.getString("date");
    String acct=resolveAccount(s,bill.optString("account",""),"","");
    String kind=bill.optString("kind","expense");
    JSONObject txArgs=new JSONObject()
     .put("key","autopay:"+bill.getString("id")+":"+due)
     .put("kind",kind)
     .put("amount",BigDecimal.valueOf(bill.getLong("amount"),2).toPlainString())
     .put("account",acct)
     .put("category",bill.optString("category","Bills & utilities"))
     .put("merchant",bill.getString("name"))
     .put("date",LocalDate.now().toString())
     .put("billId",bill.getString("id"))
     .put("billDate",due);
    if(!bill.optString("subcategory").isEmpty())txArgs.put("subcategory",bill.getString("subcategory"));
    if(!bill.optString("to").isEmpty())txArgs.put("to",bill.getString("to"));
    Ledger.post(s,txArgs);
    return true;
   }
   case "unlinkBill":{
    JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("id"));
    if(!t.has("billId"))return true;
    JSONObject bill=null;try{bill=Ledger.find(s.getJSONArray("bills"),t.getString("billId"));}catch(IllegalArgumentException ignored){}
    if(bill!=null&&t.has("billDate")){
     String due=t.getString("billDate"),f=bill.optString("frequency",bill.optBoolean("monthly")?"monthly":"once");
     String next=f.equals("once")?due:Finance.advance(LocalDate.parse(due),f,bill.optInt("anchorDay",LocalDate.parse(due).getDayOfMonth())).toString();
     if(f.equals("once")&&bill.optBoolean("paid"))bill.put("paid",false);
     else if(bill.getString("date").equals(next))bill.put("date",due);
    }
    adjustDebtBalance(s,"",t,true);
    t.remove("billId");t.remove("billDate");
    return true;
   }
   case "unmergeDuplicate":{
    JSONObject d=Ledger.find(s.getJSONArray("drafts"),a.getString("id"));
    if(!d.has("duplicateOf"))return true;
    d.remove("duplicateOf");d.remove("transactionId");d.put("state","review").put("revision",d.optInt("revision",1)+1);
    autoPost(s,d);
    return true;
   }
   case "deleteExtra":{String table=a.getString("table");if(!Arrays.asList("rules","favorites","customCategories").contains(table))throw new IllegalArgumentException("Invalid item.");if(table.equals("rules"))rememberRules(s);JSONArray rows=s.getJSONArray(table);for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("id").equals(a.getString("id")))rows.remove(i);return true;}
   case "draft":{JSONObject d=new JSONObject(a.toString()).put("id",a.optString("id",Ledger.id())).put("state","review").put("revision",1).put("created",System.currentTimeMillis());boolean auto=d.optBoolean("autoCapture");d.remove("autoCapture");d.remove("text");Finance.suggest(s,d);s.getJSONArray("drafts").put(d);if(auto)autoPost(s,d);return true;}
   case "linkDraft":{JSONObject d=Ledger.find(s.getJSONArray("drafts"),a.getString("id"));JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("transactionId"));if(t.optBoolean("voided"))throw new IllegalArgumentException("Choose a current transaction.");if(!d.optString("state").equals("review"))return true;d.put("state","linked").put("transactionId",t.getString("id"));if(d.has("receiptId")&&!t.has("receiptId"))t.put("receiptId",d.getString("receiptId"));return true;}
   case "ignoreBatch":{JSONArray ids=a.getJSONArray("ids");for(int i=0;i<ids.length();i++){JSONObject d=Ledger.find(s.getJSONArray("drafts"),ids.getString(i));if(d.optString("state").equals("review"))d.put("state","ignored");}return true;}
   case "confirmBatch":{
    JSONArray rows=a.getJSONArray("rows");
    if(rows.length()>100)throw new IllegalArgumentException("Review at most 100 suggestions at once.");
    boolean partial=a.optBoolean("partial",false);
    JSONArray drafts=s.getJSONArray("drafts"),accounts=s.getJSONArray("accounts"),receipts=s.optJSONArray("receipts");
    int savedCnt=0;JSONArray failedRows=new JSONArray();
    for(int i=0;i<rows.length();i++){
     JSONObject row=new JSONObject(rows.getJSONObject(i).toString());
     String did=row.optString("draftId","").trim();
     JSONObject draft=null;
     if(!did.isEmpty()){
      try{draft=Ledger.find(drafts,did);}catch(IllegalArgumentException ignored){}
     }
     try{
      String rKind=row.optString("kind","").trim();
      if(rKind.isEmpty())rKind=draft!=null&&!draft.optString("kind","").isEmpty()?draft.optString("kind"):"expense";
      boolean rowReload=row.optBoolean("reload",draft!=null&&draft.optBoolean("reload"));
      String rAcct=row.optString("account","").trim();
      if(rAcct.isEmpty()&&draft!=null)rAcct=draft.optString("account","");
      if(rAcct.isEmpty()&&accounts.length()>0)rAcct=accounts.getJSONObject(0).getString("id");
      row.put("account",rAcct);
      if("transfer".equals(rKind)){
       String toId=row.optString("to","").trim();
       if(toId.isEmpty()&&draft!=null)toId=draft.optString("to","").trim();
       if(toId.isEmpty()||toId.equals(rAcct)){
        String hint=row.optString("transferHint","").trim();
        if(hint.isEmpty()&&draft!=null)hint=draft.optString("transferHint","").trim();
        if(!hint.isEmpty())toId=Finance.matchAccountByName(accounts,hint,"",rAcct);
       }
       if(toId.isEmpty()||toId.equals(rAcct)){
        if(rowReload){rKind="reload";toId=rAcct;}
        else throw new IllegalArgumentException("Transfer \""+row.optString("merchant","payment")+"\" needs both From and To accounts.");
       }
       row.put("to",toId);
      }else if("reload".equals(rKind)){
       String toId=row.optString("to","").trim();
       if(toId.isEmpty()&&draft!=null)toId=draft.optString("to","").trim();
       if(toId.isEmpty()&&!row.optString("transferHint","").trim().isEmpty())toId=Finance.matchAccountByName(accounts,row.optString("transferHint","").trim(),"",rAcct);
       row.put("to",toId.isEmpty()?rAcct:toId);
      }else if("reimbursement".equals(rKind)){
       String rf=row.optString("reimbursementFor","").trim();
       if(rf.isEmpty()&&draft!=null)rf=draft.optString("reimbursementFor","").trim();
       if(rf.isEmpty())throw new IllegalArgumentException("Repayment \""+row.optString("merchant","payment")+"\" needs an open shared expense.");
       row.put("reimbursementFor",rf);
      }
      row.put("kind",rKind);
      if(row.optString("date","").trim().isEmpty())row.put("date",draft!=null&&!draft.optString("date","").isEmpty()?draft.optString("date"):LocalDate.now().toString());
      if(row.optString("category","").trim().isEmpty())row.put("category","income".equals(rKind)?"Other income":"Miscellaneous");
      String merch=row.optString("merchant","");if(merch.length()>120)row.put("merchant",merch.substring(0,120));
      String catStr=row.optString("category","");if(catStr.length()>60)row.put("category",catStr.substring(0,60));
      String noteStr=row.optString("note","");if(noteStr.length()>500)row.put("note",noteStr.substring(0,500));
      if("".equals(row.optString("originalCurrency","").trim())||"".equals(row.optString("originalAmount","").trim())){
       row.remove("originalCurrency");row.remove("originalAmount");row.remove("foreignFee");row.remove("exchangeRate");
      }
      String recId=row.optString("receiptId","").trim();
      if(!recId.isEmpty()){
       boolean recOk=false;
       if(receipts!=null)for(int rj=0;rj<receipts.length();rj++)if(recId.equals(receipts.getJSONObject(rj).optString("id"))){recOk=true;break;}
       if(!recOk)row.remove("receiptId");
      }else row.remove("receiptId");
      Ledger.post(s,row);
      if(draft!=null)draft.remove("batchError");
      savedCnt++;
     }catch(IllegalArgumentException ex){
      if(!partial)throw ex;
      String msg=ex.getMessage()==null?"Could not record this row.":ex.getMessage();
      if(draft!=null)draft.put("batchError",msg);
      failedRows.put(new JSONObject().put("draftId",did).put("merchant",row.optString("merchant","")).put("error",msg));
     }
    }
    if(partial)s.put("lastBatchResult",new JSONObject().put("saved",savedCnt).put("failed",failedRows));
    else s.remove("lastBatchResult");
    return true;
   }
   case "reconcile":{
    String account=a.getString("account"),dt=a.getString("date");Ledger.find(s.getJSONArray("accounts"),account);LocalDate.parse(dt);long actual=new BigDecimal(a.getString("actual")).movePointRight(2).longValueExact();if(Math.abs(actual)>Ledger.MAX)throw new IllegalArgumentException("Invalid balance.");Ledger.find(s.getJSONArray("accounts"),account).put("balanceVerified",true);long recorded=Finance.balance(s,account,dt),delta=actual-recorded;JSONObject r=new JSONObject().put("id",Ledger.id()).put("account",account).put("date",dt).put("actual",actual).put("recorded",recorded).put("difference",delta).put("adjusted",a.optBoolean("adjust"));
    if(a.optBoolean("adjust")&&delta!=0){String tid=Ledger.id();JSONObject t=new JSONObject().put("id",tid).put("kind","adjustment").put("amount",Math.abs(delta)).put("account",account).put("date",dt).put("merchant","Balance reconciliation").put("category","Adjustment").put("note",Ledger.str(a,"note",500)).put("created",System.currentTimeMillis()).put("postings",new JSONArray().put(Ledger.entry(account,delta)).put(Ledger.entry("equity",-delta)));s.getJSONArray("transactions").put(t);r.put("transactionId",tid);}s.getJSONArray("reconciliations").put(r);return true;
   }
   case "installment":installment(s,a);return true;
   case "matchBill":{JSONObject bill=Ledger.find(s.getJSONArray("bills"),a.getString("id")),t=Ledger.find(s.getJSONArray("transactions"),a.getString("transactionId"));if(bill.optBoolean("paid")||bill.optBoolean("cancelled")||t.optBoolean("voided")||t.has("billId"))throw new IllegalArgumentException("Choose an unpaid plan and an unlinked active transaction.");if(!t.getString("account").equals(bill.getString("account"))||!t.getString("kind").equals(bill.optString("kind","expense"))||("transfer".equals(t.getString("kind"))&&!t.optString("to").equals(bill.optString("to"))))throw new IllegalArgumentException("Payment account or type does not match this bill.");t.put("billId",bill.getString("id")).put("billDate",bill.getString("date"));String f=bill.optString("frequency",bill.optBoolean("monthly")?"monthly":"once");if(f.equals("once"))bill.put("paid",true);else bill.put("date",Finance.advance(LocalDate.parse(bill.getString("date")),f,bill.optInt("anchorDay",LocalDate.parse(bill.getString("date")).getDayOfMonth())).toString());adjustDebtBalance(s,bill.getString("id"),t,false);return true;}
   case "cancelBill":{JSONObject b=Ledger.find(s.getJSONArray("bills"),a.getString("id"));b.put("cancelled",a.optBoolean("cancelled",true));return true;}
   case "importCommit":{
    String batch=a.getString("batch");JSONArray rows=a.getJSONArray("rows");if(rows.length()>1000)throw new IllegalArgumentException("Import at most 1,000 rows per batch.");int count=0;JSONArray ids=new JSONArray();
    JSONArray tx=s.getJSONArray("transactions");
    Set<String> seenImportRows=new HashSet<>(tx.length()*2+8);
    for(int j=0;j<tx.length();j++){String ir=tx.getJSONObject(j).optString("importRow","");if(!ir.isEmpty())seenImportRows.add(ir);}
    for(int i=0;i<rows.length();i++){
     JSONObject row=rows.getJSONObject(i);String sourceKey=row.getString("importRow");
     if(seenImportRows.contains(sourceKey))continue;
     if("reload".equals(row.optString("kind"))&&row.optString("to","").trim().isEmpty())row.put("to",row.getString("account"));
     int before=tx.length();row.put("key","import:"+sourceKey).put("importBatch",batch);Ledger.post(s,row);
     if(tx.length()>before){seenImportRows.add(sourceKey);count++;ids.put(tx.getJSONObject(tx.length()-1).getString("id"));}
    }
    Ledger.upsert(s,"imports",new JSONObject().put("id",batch).put("count",count).put("name",a.optString("name","CSV import")).put("date",LocalDate.now().toString()).put("transactionIds",ids));return true;
   }
   case "undoImport":{JSONObject batch=Ledger.find(s.getJSONArray("imports"),a.getString("id"));JSONArray tx=s.getJSONArray("transactions");for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optString("importBatch").equals(batch.getString("id"))&&!t.has("replacedBy"))t.put("voided",true);}batch.put("undone",true);return true;}
   case "quickNote":{
    JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("id"));if(t.optBoolean("voided"))throw new IllegalArgumentException("This transaction was removed.");
    String prevCat=t.optString("category",""),prevSub=t.optString("subcategory","");
    t.put("note",Ledger.str(a,"note",500)).put("needsNote",false);
    if(a.has("subcategory"))t.put("subcategory",Ledger.str(a,"subcategory",80));
    if(a.has("category")){
     String newCat=Ledger.str(a,"category",60);
     if(!newCat.isEmpty()&&Arrays.asList("expense","refund").contains(t.optString("kind"))&&!t.has("splits")){
      String oldCat=t.optString("category","");t.put("category",newCat);
      JSONArray p=t.getJSONArray("postings");for(int i=0;i<p.length();i++){JSONObject e=p.getJSONObject(i);if(e.getString("account").equals("expense:"+oldCat)){e.put("account","expense:"+newCat);break;}}
     }
    }
    if(!prevCat.equals(t.optString("category",""))||!prevSub.equals(t.optString("subcategory",""))){
     JSONObject bef=new JSONObject().put("kind",t.optString("kind","")).put("category",prevCat).put("subcategory",prevSub).put("merchant",t.optString("merchant","")).put("amount",t.optLong("amount",0L));
     JSONObject aft=new JSONObject().put("kind",t.optString("kind","")).put("category",t.optString("category","")).put("subcategory",t.optString("subcategory","")).put("merchant",t.optString("merchant","")).put("amount",t.optLong("amount",0L));
     Ledger.recordCorrection(s,"quickNote",t.optString("source",""),t.optString("sourcePackage",""),bef,aft);
    }
    if(a.optBoolean("dismissAnomaly"))t.put("anomaly",false).put("anomalyDismissed",true);
    if(a.optBoolean("applyHistory")&&!t.optString("merchant").isEmpty())updateMerchantHistory(s,t.getString("merchant"),t.optString("category"),t.optString("subcategory",""),t.optString("account"));
    return true;
   }
   case "clearCorrectionLog":{
    s.put("correctionLog",new JSONArray());
    return true;
   }
   case "dismissAnomaly":{
    JSONObject t=Ledger.find(s.getJSONArray("transactions"),a.getString("id"));
    if(t.optBoolean("voided"))return true;
    t.put("anomaly",false).put("anomalyDismissed",true).put("needsNote",false);
    return true;
   }
   case "updateMerchantHistory":{
    updateMerchantHistory(s,name(a,"merchant",120),name(a,"category",60),a.optString("subcategory",""),a.optString("account",""));
    return true;
   }
   case "debt":debt(s,a);return true;
   case "debtExtra":{
    String raw=a.optString("extraMonthly","0").trim();
    long extra=raw.isEmpty()||raw.equals("0")||raw.equals("0.00")?0L:Ledger.minor(raw);
    s.getJSONObject("settings").put("debtExtraMonthly",extra);
    return true;
   }
   case "syncDebtReminder":syncDebtReminder(s,Ledger.find(s.getJSONArray("debts"),a.getString("id")));return true;
   case "quickPost":postQuickEntry(s,a.optString("text",""),LocalDate.now(),a.optString("key",""));return true;
   case "applyBudgetSuggestions":{
    JSONArray items=a.optJSONArray("items");
    if(items==null||items.length()==0)items=Finance.suggestBudgets(s,LocalDate.now());
    JSONArray existing=s.getJSONArray("budgets");
    for(int i=0;i<items.length();i++){
     JSONObject it=items.getJSONObject(i);
     String cat=it.getString("category").trim();
     String amt=it.has("amount")?it.getString("amount"):BigDecimal.valueOf(it.getLong("suggestedLimit"),2).toPlainString();
     JSONObject bArgs=new JSONObject().put("category",cat).put("amount",amt).put("rollover",a.optBoolean("rollover",true));
     for(int j=0;j<existing.length();j++){
      JSONObject ex=existing.getJSONObject(j);
      if(ex.getString("category").equalsIgnoreCase(cat)&&ex.optString("subcategory","").trim().isEmpty()){bArgs.put("id",ex.getString("id"));break;}
     }
     budget(s,bArgs);
    }
    return true;
   }
   case "adjustGoal":{
    JSONObject g=Ledger.find(s.getJSONArray("goals"),a.getString("id"));
    long delta=a.has("delta")?a.getLong("delta"):(a.optBoolean("withdraw")?-1L:1L)*Ledger.minor(a.getString("amount"));
    g.put("saved",Math.max(0L,Math.min(Ledger.MAX,g.optLong("saved",0L)+delta)));
    return true;
   }
   case "setPayday":{
    String pName=name(a,"name",80),acct=a.optString("account","").trim();
    if(acct.isEmpty())acct=s.getJSONArray("accounts").getJSONObject(0).getString("id");
    else Ledger.find(s.getJSONArray("accounts"),acct);
    long amt=a.has("amountMinor")?a.getLong("amountMinor"):Ledger.minor(a.getString("amount"));
    if(amt<=0||amt>Ledger.MAX)throw new IllegalArgumentException("Payday amount must be positive.");
    int dom=Math.max(1,Math.min(31,a.optInt("dayOfMonth",1)));
    String freq="weekly".equals(a.optString("frequency"))?"weekly":"monthly";
    String nextDate=a.optString("nextDate","").trim();
    if(!nextDate.isEmpty())LocalDate.parse(nextDate);
    else{LocalDate now=LocalDate.now();LocalDate d=Finance.anchored(YearMonth.from(now),dom);if(d.isBefore(now))d=Finance.advance(d,freq,dom);nextDate=d.toString();}
    JSONObject sets=s.getJSONObject("settings");
    if(!"monthly".equals(sets.optString("cycleMode","")))sets.put("cycleDay",dom);
    sets.put("payday",new JSONObject().put("name",pName).put("amount",amt).put("account",acct).put("frequency",freq).put("dayOfMonth",dom).put("nextDate",nextDate));
    return true;
   }
   case "clearPayday":{
    JSONObject sets=s.getJSONObject("settings");
    sets.remove("payday");
    if(!"custom".equals(sets.optString("cycleMode","")))sets.put("cycleDay",1);
    return true;
   }
   case "settlePerson":{
    String person=name(a,"person",80),account=a.getString("account"),dt=a.optString("date",LocalDate.now().toString()).trim();
    Ledger.find(s.getJSONArray("accounts"),account);LocalDate.parse(dt);
    List<JSONObject> open=new ArrayList<>();
    long totalOpen=0L;String disp=person;
    JSONArray tx=s.getJSONArray("transactions");
    for(int i=0;i<tx.length();i++){
     JSONObject t=tx.getJSONObject(i);
     if(t.optBoolean("voided")||!"shared".equals(t.optString("kind")))continue;
     String p=t.optString("person","").trim();
     if(!p.equalsIgnoreCase(person))continue;
     long rem=Finance.balance(s,"receivable:"+t.getString("id"),"9999-12-31");
     if(rem>0){disp=p;open.add(t);totalOpen+=rem;}
    }
    if(open.isEmpty())throw new IllegalArgumentException("No open split bills found for "+person+".");
    open.sort((x,y)->{int c=x.optString("date").compareTo(y.optString("date"));return c!=0?c:Long.compare(x.optLong("created"),y.optLong("created"));});
    String rawAmt=a.optString("amount","").trim();
    long budget=rawAmt.isEmpty()?totalOpen:Ledger.minor(rawAmt);
    if(budget>totalOpen)throw new IllegalArgumentException("Settlement exceeds total RM "+rm(totalOpen)+" owed by "+disp+".");
    String baseKey=a.optString("key","settle:"+Ledger.id()),note=Ledger.str(a,"note",500);
    for(int i=0;i<open.size()&&budget>0;i++){
     JSONObject sh=open.get(i);
     long rem=Finance.balance(s,"receivable:"+sh.getString("id"),"9999-12-31");
     long pay=Math.min(budget,rem);
     if(pay<=0)continue;
     JSONObject postArgs=new JSONObject()
      .put("key",baseKey+":"+i)
      .put("kind","reimbursement")
      .put("reimbursementFor",sh.getString("id"))
      .put("account",account)
      .put("amount",BigDecimal.valueOf(pay,2).toPlainString())
      .put("category","Repayment")
      .put("merchant","Repayment · "+disp)
      .put("matchedPerson",disp)
      .put("date",dt)
      .put("note",note);
     Ledger.post(s,postArgs);
     budget-=pay;
    }
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
  String sub=Ledger.str(a,"subcategory",80);
  JSONObject b=new JSONObject().put("id",bid).put("category",name(a,"category",60)).put("limit",Ledger.minor(a.getString("amount"))).put("rollover",a.optBoolean("rollover")).put("createdDate",old==null?now.toString():old.optString("createdDate",now.toString()));
  if(!sub.isEmpty())b.put("subcategory",sub);
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
  String acct=resolveAccount(s,d.optString("account",""),d.optString("source",""),d.optString("package",""),d.optString("subcategory","").contains("BNPL")?(d.optString("source","")+" "+d.optString("merchant","")):"");
  String kind=d.optString("kind","expense");
  String to=d.optString("to","");
  if(kind.equals("transfer")&&(to.isEmpty()||to.equals(acct))){
   // A reload / own-account transfer is not spending. Work out both sides, or leave it in Inbox for review.
   JSONArray accounts=s.getJSONArray("accounts");
   String fromHint=d.optString("transferFromHint","");
   String srcType="";for(int i=0;i<accounts.length();i++)if(accounts.getJSONObject(i).optString("id").equals(acct))srcType=accounts.getJSONObject(i).optString("type");
   if(!fromHint.isEmpty()&&(d.optBoolean("reload")||"wallet".equals(srcType))){
    String fundedBy=Finance.matchAccountByName(accounts,fromHint,"",acct);
    if(!fundedBy.isEmpty()){to=acct;acct=fundedBy;}
    else{d.put("reason","Wallet reload from "+fromHint+" detected. Choose the account the money came from; it won't be counted as spending.");return;}
   }else{
    String hint=d.optString("transferHint","")+" "+d.optString("merchant","");
    String other=Finance.matchAccountByName(accounts,hint,"",acct);
    if(!other.isEmpty()){to=other;}
    else if(d.optBoolean("reload")&&accounts.length()<=1){kind="reload";to=acct;}
    else if(d.optBoolean("reload")&&"wallet".equals(srcType)){d.put("reason","Wallet reload detected. Choose the account the money came from; it won't be counted as spending.");return;}
    else{d.put("reason","Looks like a transfer between your own accounts. Choose both accounts; it won't be counted as spending.");return;}
   }
   d.put("to",to);
  }
  String cat=d.optString("category","Miscellaneous");if(cat.isEmpty()||cat.equals("Other"))cat="Miscellaneous";
  String sub=d.optString("subcategory","");
  String dt=d.optString("date",LocalDate.now().toString());
  String merchant=d.optString("merchant","Detected payment");
  d.put("amount",amt).put("account",acct).put("category",cat).put("kind",kind);
  JSONObject txArgs=new JSONObject().put("key","auto:"+d.getString("id")).put("draftId",d.getString("id")).put("draftRevision",d.optInt("revision",1)).put("kind",kind).put("amount",BigDecimal.valueOf(amt,2).toPlainString()).put("account",acct).put("category",cat).put("merchant",merchant).put("date",dt).put("note",d.optString("note","")).put("autoRecorded",true).put("historyMatch",d.optString("historyMatch","none")).put("needsNote",d.optBoolean("needsNote",true));
  if(!sub.isEmpty())txArgs.put("subcategory",sub);
  if(d.has("originalCurrency")&&d.has("originalAmount"))txArgs.put("originalCurrency",d.getString("originalCurrency")).put("originalAmount",d.getString("originalAmount"));
  if(kind.equals("transfer")||kind.equals("reload"))txArgs.put("to",to.isEmpty()?acct:to);
  JSONObject matchedBill=findMatchingBill(s,kind,amt,merchant,cat,sub,dt);
  if(matchedBill!=null){
   txArgs.put("billId",matchedBill.getString("id")).put("billDate",matchedBill.getString("date")).put("needsNote",false);
   d.put("matchedBillId",matchedBill.getString("id")).put("matchedBillName",matchedBill.getString("name")).put("needsNote",false);
  }
  if("refund".equals(kind)){
   JSONObject matchedOrig=findMatchingRefundExpense(s,acct,amt,merchant,dt);
   if(matchedOrig!=null){
    String oCat=matchedOrig.optString("category",cat),oSub=matchedOrig.optString("subcategory",sub);
    if(!oCat.isEmpty()){cat=oCat;txArgs.put("category",cat);d.put("category",cat);}
    if(!oSub.isEmpty()){sub=oSub;txArgs.put("subcategory",sub);d.put("subcategory",sub);}
    long remBefore=Math.max(amt,matchedOrig.getLong("amount")-refundedAmountFor(s,matchedOrig.getString("id")));
    String matchType=amt>=remBefore?"full":"partial";
    String origMerchant=matchedOrig.optString("merchant",cat);
    txArgs.put("originalId",matchedOrig.getString("id")).put("needsNote",false);
    d.put("matchedOriginalId",matchedOrig.getString("id")).put("matchedOriginalMerchant",origMerchant).put("refundMatchType",matchType).put("needsNote",false).put("reason","Matched ("+matchType+") to RM "+rm(matchedOrig.getLong("amount"))+" at "+origMerchant+" ("+matchedOrig.getString("date")+"). Category spend & tax relief adjusted.");
   }
  }else if("income".equals(kind)&&s.getJSONObject("settings").optBoolean("splitAutoMatch",true)){
   JSONObject splitMatch=findMatchingReceivable(s,amt,merchant+" "+d.optString("source",""),dt);
   if(splitMatch!=null){
    JSONObject shared=splitMatch.getJSONObject("sharedTx");
    String person=shared.optString("person","Friend").trim();if(person.isEmpty())person="Friend";
    String matchType=splitMatch.getString("matchType");
    String reimbMerchant="Repayment · "+person;
    txArgs.put("kind","reimbursement").put("reimbursementFor",shared.getString("id")).put("category","Repayment").put("merchant",reimbMerchant).put("autoReconciledSplit",true).put("matchedPerson",person).put("needsNote",false);
    txArgs.remove("subcategory");
    d.put("kind","reimbursement").put("category","Repayment").put("merchant",reimbMerchant).put("reimbursementFor",shared.getString("id")).put("matchedPerson",person).put("splitMatchType",matchType).put("autoReconciledSplit",true).put("needsNote",false).put("reason","Auto-matched ("+matchType+") incoming transfer to "+person+"'s shared bill ("+shared.optString("merchant","")+").");
   }
  }else if("expense".equals(kind)){
   JSONObject anom=Finance.detectSpendAnomaly(s,merchant,cat,amt,LocalDate.parse(dt),"");
   if(anom.optBoolean("isAnomaly")){
    txArgs.put("anomaly",true).put("anomalyMedian",anom.getLong("median")).put("anomalyRatio",anom.getString("ratioText")).put("needsNote",true);
    d.put("anomaly",true).put("anomalyMedian",anom.getLong("median")).put("anomalyRatio",anom.getString("ratioText")).put("needsNote",true);
   }
  }
  Ledger.post(s,txArgs);
  d.put("autoRecorded",true);
  if("expense".equals(kind)){
   try{
    JSONObject sum=Finance.categoryBudgetSummary(s,LocalDate.parse(dt),cat,sub);
    if(sum!=null){
     d.put("catCycleSpent",sum.getLong("cycleSpent"));
     if(sum.optBoolean("hasBudget")){
      d.put("catHasBudget",true)
       .put("catBudgetAvailable",sum.getLong("available"))
       .put("catBudgetSpent",sum.getLong("spent"))
       .put("catBudgetRemaining",sum.getLong("remaining"))
       .put("catBudgetPct",sum.getInt("spentPct"))
       .put("catBudgetPace",sum.optString("paceStatus","on_track"));
     }
    }
   }catch(Exception ignored){}
  }
 }
 /** Sums already-linked non-voided refund amounts against a given original expense id. */
 static long refundedAmountFor(JSONObject s,String originalId){
  long sum=0;
  JSONArray tx=s.optJSONArray("transactions");
  if(tx==null)return 0;
  for(int i=0;i<tx.length();i++){
   JSONObject t=tx.optJSONObject(i);
   if(t!=null&&!t.optBoolean("voided")&&"refund".equals(t.optString("kind"))&&originalId.equals(t.optString("originalId","")))sum+=t.optLong("amount",0L);
  }
  return sum;
 }
 /** Matches an incoming refund notification to an eligible historical expense within 60 days. */
 static JSONObject findMatchingRefundExpense(JSONObject s,String acct,long amt,String merchant,String dateStr){
  try{
   JSONArray tx=s.optJSONArray("transactions");
   if(tx==null||tx.length()==0||amt<=0)return null;
   LocalDate txDate=LocalDate.parse(dateStr);
   String mNorm=Finance.normalizeMerchant(merchant);
   if(mNorm.equals("detected payment"))mNorm="";
   JSONObject best=null;int bestScore=0;
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided")||!"expense".equals(t.optString("kind")))continue;
    LocalDate origDate=LocalDate.parse(t.getString("date"));
    long days=ChronoUnit.DAYS.between(origDate,txDate);
    if(days<-1||days>60)continue;
    long remaining=t.getLong("amount")-refundedAmountFor(s,t.getString("id"));
    if(remaining<amt)continue;
    int score=0;
    String oNorm=Finance.normalizeMerchant(t.optString("merchant",""));
    if(!mNorm.isEmpty()&&!oNorm.isEmpty()){
     if(mNorm.equals(oNorm))score+=65;
     else if(mNorm.length()>=4&&oNorm.length()>=4&&(mNorm.contains(oNorm)||oNorm.contains(mNorm)))score+=50;
     else if(MerchantCanonicalizer.sameEntity(merchant,t.optString("merchant","")))score+=60;
     else{for(String tok:mNorm.split(" "))if(tok.length()>=4&&oNorm.contains(tok)){score+=35;break;}}
    }
    if(!acct.isEmpty()&&acct.equals(t.optString("account","")))score+=25;
    if(amt==remaining||amt==t.getLong("amount"))score+=50;
    else if(amt<remaining)score+=15;
    score+=Math.max(0,15-(int)(Math.max(0L,days)/4));
    if(score>bestScore){bestScore=score;best=t;}
   }
   return bestScore>=70?best:null;
  }catch(Exception ignored){return null;}
 }
 private static final Set<String> SPLIT_STOPWORDS=new HashSet<>(Arrays.asList("duitnow","instant","transfer","received","credited","payment","pay","from","dari","daripada","bank","maybank","mae","cimb","rhb","hlb","public","ambank","gxbank","aeon","ryt","tng","ewallet","wallet","boost","shopeepay","grabpay","account","akaun","ref","reference","ibft","giro","qr","detected","shared","text"));
 /** Matches an incoming P2P transfer notification against open shared-expense receivables within 60 days. */
 static JSONObject findMatchingReceivable(JSONObject s,long amt,String senderText,String dateStr){
  try{
   JSONArray tx=s.optJSONArray("transactions");
   if(tx==null||tx.length()==0||amt<=0)return null;
   LocalDate txDate=LocalDate.parse(dateStr);
   String sNorm=Finance.normalizeMerchant(senderText);
   List<String> sTokens=new ArrayList<>();
   for(String tok:sNorm.split(" "))if(tok.length()>=2&&!SPLIT_STOPWORDS.contains(tok))sTokens.add(tok);
   JSONObject best=null;long bestRem=0;int bestScore=0;
   List<JSONObject> exactOddCents=new ArrayList<>();
   for(int i=0;i<tx.length();i++){
    JSONObject t=tx.getJSONObject(i);
    if(t.optBoolean("voided")||!"shared".equals(t.optString("kind")))continue;
    LocalDate shDate=LocalDate.parse(t.getString("date"));
    long days=ChronoUnit.DAYS.between(shDate,txDate);
    if(days<-3||days>60)continue;
    long rem=Finance.balance(s,"receivable:"+t.getString("id"),"9999-12-31");
    if(rem<amt||rem<=0)continue;
    if(rem==amt&&(amt%100L)!=0L)exactOddCents.add(t);
    String pNorm=Finance.normalizeMerchant(t.optString("person",""));
    if(pNorm.isEmpty())continue;
    int score=0;
    String pJoin=String.join(" ",pNorm.split(" "));
    String sJoin=String.join(" ",sTokens);
    if(!sJoin.isEmpty()&&pJoin.equals(sJoin))score+=70;
    else{
     for(String pt:pNorm.split(" ")){
      if(pt.length()<2)continue;
      if(sTokens.contains(pt)){score+=55;break;}
     }
    }
    if(score==0)continue;
    if(amt==rem)score+=40;
    else if(amt<rem)score+=15;
    if(days<=7)score+=10;
    else if(days<=30)score+=5;
    if(score>bestScore){bestScore=score;best=t;bestRem=rem;}
   }
   if(best!=null&&bestScore>=85){
    return new JSONObject().put("sharedTx",best).put("remaining",bestRem).put("matchType",amt>=bestRem?"full":"partial").put("score",bestScore);
   }
   if(sTokens.isEmpty()&&exactOddCents.size()==1){
    JSONObject uniq=exactOddCents.get(0);
    return new JSONObject().put("sharedTx",uniq).put("remaining",amt).put("matchType","full").put("score",85);
   }
   return null;
  }catch(Exception ignored){return null;}
 }
 static JSONObject findMatchingBill(JSONObject s,String kind,long amt,String merchant,String category,String subcategory,String dateStr){
  if(!"expense".equals(kind))return null;
  try{
   JSONArray bills=s.optJSONArray("bills");
   if(bills==null||bills.length()==0)return null;
   LocalDate txDate=LocalDate.parse(dateStr);
   String mLow=String.valueOf(merchant).toLowerCase(Locale.ROOT).trim();
   String subLow=String.valueOf(subcategory).toLowerCase(Locale.ROOT).trim();
   JSONObject best=null;int bestScore=0;
   for(int i=0;i<bills.length();i++){
    JSONObject b=bills.getJSONObject(i);
    if(b.optBoolean("paid")||b.optBoolean("cancelled")||!"expense".equals(b.optString("kind","expense")))continue;
    LocalDate due=LocalDate.parse(b.getString("date"));
    long daysDiff=Math.abs(ChronoUnit.DAYS.between(txDate,due));
    if(daysDiff>20)continue;
    String bName=b.optString("name","").toLowerCase(Locale.ROOT).trim();
    String bSub=b.optString("subcategory","").toLowerCase(Locale.ROOT).trim();
    String rType=b.optString("reminderType","bill");
    if(b.optLong("amount")!=amt&&!b.optBoolean("variable"))continue;
    int score=0;
    if(!bName.isEmpty()&&!mLow.isEmpty()&&!mLow.equals("detected payment")&&(mLow.contains(bName)||bName.contains(mLow))){
     score+=50;
    }
    for(String kw:new String[]{"ptptn","spaylater","shopee paylater","grabpaylater","atome","payflex","boost payflex","lazpaylater","tiktok paylater","tnb","tenaga nasional","unifi","time fibre","air selangor","syabas","indah water","maxis","celcom","digi","umobile","u mobile","astro","netflix"}){
     if((mLow.contains(kw)||subLow.contains(kw))&&(bName.contains(kw)||bSub.contains(kw))){score+=60;break;}
    }
    if(score==0&&!subLow.isEmpty()&&subLow.equals(bSub)&&("ptptn".equals(rType)||subLow.contains("ptptn")||subLow.contains("electricity")||subLow.contains("water")||subLow.contains("internet"))){
     score+=40;
    }
    if(score>0){
     if(b.optLong("amount")==amt)score+=30;
     score-=Math.min(15,(int)daysDiff);
     if(score>bestScore){bestScore=score;best=b;}
    }
   }
   return best;
  }catch(Exception e){return null;}
 }
 public static boolean shouldPromptDraft(boolean quiet,JSONObject settings,JSONObject d,boolean notificationsEnabled){
  return notificationsEnabled&&!quiet&&!settings.optBoolean("digest")&&d!=null&&!d.has("duplicateOf");
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
  if(!selected&&(label==null||label.isEmpty()||label.equals(pkg))){String known=PaymentParser.knownAppLabel(pkg);if(!known.isEmpty())label=known;}
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
  long eventTime=postTime>0L?postTime:now;
  for(int i=drafts.length()-1;i>=0;i--){JSONObject other=drafts.getJSONObject(i);if(d.optLong("amount")>0&&other.optLong("amount")==d.optLong("amount")&&Math.abs(eventTime-draftEventTime(other))<300000L)d.put("reason",d.getString("reason")+" A similar recent amount exists; check for a duplicate.");}
  d.put("package",pkg).put("capturedAt",now).put("postedAt",eventTime);Finance.suggest(s,d);drafts.put(d);
  JSONObject twin=findPostedDraftTwin(s,d,pkg,eventTime);
  if(twin!=null){
   String twinTx=twin.getString("transactionId");
   String seenFrom=(label==null||label.isEmpty())?pkg:label;
   d.put("state","linked").put("duplicateOf",twinTx).put("transactionId",twinTx).put("reason","Same amount reported by "+twin.optString("source","another app")+" around the same time, so it was not recorded twice. Tap \"Not a duplicate\" if these were separate payments.");
   appendAlsoSeen(s,twinTx,seenFrom);
  }else{
   JSONObject recentTx=findRecentMatchingTx(s,d,eventTime);
   if(recentTx!=null){
    String txId=recentTx.getString("id");
    String seenFrom=(label==null||label.isEmpty())?pkg:label;
    d.put("state","linked").put("duplicateOf",txId).put("transactionId",txId).put("reason","Matches a recent "+recentTx.optString("merchant","recorded")+" entry of the same amount, so it was not recorded twice. Tap \"Not a duplicate\" if these were separate payments.");
    appendAlsoSeen(s,txId,seenFrom);
   }else{
    JSONObject reviewTwin=findReviewDraftTwin(s,d,pkg,eventTime);
    if(reviewTwin!=null&&!d.optBoolean("reload")){
     if(isRailOrGeneric(Finance.normalizeMerchant(d.optString("merchant","")),reviewTwin.optString("source",""),reviewTwin.optString("package",""))
        &&!isRailOrGeneric(Finance.normalizeMerchant(reviewTwin.optString("merchant","")),label,pkg)){
      d.put("merchant",reviewTwin.optString("merchant"));
      if("expense".equals(d.optString("kind"))&&!reviewTwin.optString("category","").isEmpty()&&!"Miscellaneous".equals(reviewTwin.optString("category"))){
       d.put("category",reviewTwin.getString("category"));
       if(!reviewTwin.optString("subcategory","").isEmpty())d.put("subcategory",reviewTwin.getString("subcategory"));
      }
     }
    }
    autoPost(s,d);
    if(d.optBoolean("autoRecorded")){
     String postedId=d.getString("transactionId");
     if(reviewTwin!=null){
      linkReviewDraftToPosted(s,reviewTwin,d,postedId);
     }
     if("transfer".equals(d.optString("kind"))){
      // Also fold any other pending reload-side review draft from another app within 4 minutes.
      for(int i=0;i<drafts.length();i++){
       JSONObject o=drafts.getJSONObject(i);
       if(o==d||o==reviewTwin||!"review".equals(o.optString("state"))||!"transfer".equals(o.optString("kind"))||!amountsMatchForTwin(o,d))continue;
       if(pkg==null||pkg.equals(o.optString("package"))||Math.abs(eventTime-draftEventTime(o))>240_000L)continue;
       linkReviewDraftToPosted(s,o,d,postedId);
      }
     }
    }else if(reviewTwin!=null){
     String seenFrom=(label==null||label.isEmpty())?pkg:label;
     d.put("state","linked").put("duplicateOf",reviewTwin.getString("id")).put("reason","Same amount reported by "+reviewTwin.optString("source","another app")+" around the same time. Tap \"Not a duplicate\" if these were separate payments.");
     reviewTwin.put("alsoSeenFrom",mergeSeenLabel(reviewTwin.optString("alsoSeenFrom",""),seenFrom));
    }
   }
  }
  seen.put(new JSONObject().put("hash",instanceDigest).put("contentHash",contentDigest).put("time",now));while(seen.length()>5000)seen.remove(0);
  s.put("lastCapture",now).put("revision",s.optLong("revision")+1);Ledger.validate(s);return d;
 }
 private static final Set<String> TWIN_EXACT_RAILS=new HashSet<>(Arrays.asList("tng digital","touch n go","tng","shopeepay","spaylater","grabpay","grabpaylater","boost","myboost","boost payflex","atome","lazpaylater","tiktok paylater","bigpay","setel","gxbank","aeon bank","ryt bank","boost bank","maybank","mae","cimb","cimb octo","rhb","public bank","mypb","hong leong","hlb","ambank","bank islam","duitnow","fpx","jompay","ipay88","razerpay","senangpay","billplz","stripe","molpay","apple pay","google pay","samsung pay","ewallet","e wallet","wallet","card transaction","online banking","qr pay","qrpay","detected payment"));
 private static final Set<String> TWIN_PLACE_WORDS=new HashSet<>(Arrays.asList("at","to","di","ke","no","in","on","of","by","kl","klcc","sunway","pyramid","mid","valley","pavilion","subang","puchong","petaling","jaya","shah","alam","damansara","bangsar","cheras","klang","penang","johor","ipoh","melaka","malaysia","my","online","store","shop","mart","cafe","restoran","restaurant","kedai","gerai","parking","payment","transfer","purchase","outlet","branch","cawangan","bhd","sdn","berhad","enterprise","enterprises","service","services"));
 private static long draftEventTime(JSONObject d){
  if(d==null)return Long.MIN_VALUE/2;
  return d.has("postedAt")?d.optLong("postedAt",0L):d.optLong("capturedAt",d.optLong("created",Long.MIN_VALUE/2));
 }
 private static String mergeSeenLabel(String existing,String add){
  String a=add==null?"":add.trim(),e=existing==null?"":existing.trim();
  if(a.isEmpty())return e;
  if(e.isEmpty())return a;
  for(String part:e.split(","))if(part.trim().equalsIgnoreCase(a))return e;
  return e+", "+a;
 }
 private static void appendAlsoSeen(JSONObject s,String txId,String srcLabel){
  try{
   JSONObject t=Ledger.find(s.getJSONArray("transactions"),txId);
   t.put("alsoSeenFrom",mergeSeenLabel(t.optString("alsoSeenFrom",""),srcLabel));
  }catch(Exception ignored){}
 }
 private static void linkReviewDraftToPosted(JSONObject s,JSONObject reviewDraft,JSONObject postedDraft,String txId)throws JSONException{
  String by=postedDraft.optString("source","another app");
  reviewDraft.put("state","linked").put("duplicateOf",txId).put("transactionId",txId).put("reason","Matched to "+by+" transaction of the same amount.");
  appendAlsoSeen(s,txId,reviewDraft.optString("source",reviewDraft.optString("package","")));
 }
 private static boolean isRailOrGeneric(String mNorm,String otherSrc,String otherPkg){
  if(mNorm==null||mNorm.isEmpty()||TWIN_EXACT_RAILS.contains(mNorm))return true;
  for(String r:TWIN_EXACT_RAILS){
   if(r.length()>=4&&(mNorm.startsWith(r+" ")||mNorm.endsWith(" "+r)))return true;
  }
  String oMeta=(String.valueOf(otherSrc)+" "+String.valueOf(otherPkg)).toLowerCase(Locale.ROOT);
  String[][] pairHints={{"tng","tng"},{"touch n go","tng"},{"mae","maybank"},{"maybank","maybank"},{"cimb","cimb"},{"octo","cimb"},{"rhb","rhb"},{"hong leong","hlb"},{"hlb","hlb"},{"public bank","pbe"},{"publicbank","pbe"},{"mypb","pbe"},{"pbb","pbe"},{"ambank","ambank"},{"gxbank","gx"},{"aeon","aeon"},{"ryt","ryt"},{"shopeepay","shopee"},{"shopee","shopee"},{"grab","grab"},{"boost","boost"},{"atome","atome"},{"bigpay","bigpay"},{"setel","setel"}};
  for(String[] ph:pairHints){
   if(oMeta.contains(ph[1])&&(mNorm.equals(ph[0])||mNorm.startsWith(ph[0]+" ")||mNorm.endsWith(" "+ph[0])))return true;
  }
  return false;
 }
 private static List<String> brandTokens(String norm){
  List<String> out=new ArrayList<>();
  if(norm==null||norm.isEmpty())return out;
  for(String tok:norm.split(" ")){
   if(tok.length()>=2&&!TWIN_PLACE_WORDS.contains(tok)&&!tok.matches("[0-9]+"))out.add(tok);
  }
  return out;
 }
 private static List<String> conflictTokens(String mRaw){
  List<String> out=new ArrayList<>();
  String cleaned=mRaw==null?"":mRaw.replaceAll("(?i)^\\s*(?:pay|dnqr|fpx|shopeepay|shopeep|spaylater|spay|ipay88|razerms|tngd?|mydebit|cimbl|visa|mc|pos|qr|grab)\\*\\s*","");
  String n=Finance.normalizeMerchant(cleaned);
  if(n==null||n.isEmpty())return out;
  for(String tok:n.split(" ")){
   if(tok.length()<2||tok.matches("[0-9]+|[a-z]{1,2}[0-9]{1,3}|[0-9]{1,2}[a-z]{1,3}"))continue;
   if(tok.matches("(?:at|to|di|ke|no|in|on|of|by|kl|klcc|pj|my|malaysia|trx|klia|bktjll|slngr|penang|johor|ipoh|melaka|subang|puchong|petaling|shah|alam|damansara|bangsar|cheras|klang|sunway|pyramid|mid|valley|pavilion|sdn|bhd|berhad|enterprise|enterprises|trading|services|service|official|personal|care|express|online|store|shop|mart|supermarket|hypermarket|kedai|restoran|restaurant|klinik|farmasi|pasar|mini|station|stesen|clinic|pharmacy|plaza|outlet|branch|cawangan|company|co|corp|hub|ventures?|holdings|kopitiam|kopi|cafe|coffee|buku|book|bookstore|bookshop|bistro|kitchen|dapur|warung|gerai|bakery|dental|pergigian|specialist|pakar|perubatan|kereta|bengkel|medan|selera)"))continue;
   out.add(tok);
  }
  return out;
 }
 private static String consSkel(String s){
  if(s==null)return "";
  return s.replaceAll("[aeiou]","");
 }
 private static boolean tokenOverlapsSet(String tok,List<String> others,String otherJoinedSkel){
  String tSkel=consSkel(tok);
  if(tSkel.length()>=3&&otherJoinedSkel.contains(tSkel))return true;
  if(tSkel.length()==2&&others.size()==1&&(otherJoinedSkel.startsWith(tSkel)||otherJoinedSkel.endsWith(tSkel)))return true;
  for(String o:others){
   if(tok.equals(o))return true;
   if(tok.length()>=4&&o.length()>=4&&(tok.startsWith(o)||o.startsWith(tok)))return true;
   String oSkel=consSkel(o);
   if(tSkel.length()>=3&&oSkel.length()>=3&&(tSkel.equals(oSkel)||tSkel.contains(oSkel)||oSkel.contains(tSkel)))return true;
   if(tSkel.length()>=2&&tSkel.equals(oSkel)&&!tok.isEmpty()&&!o.isEmpty()&&tok.charAt(0)==o.charAt(0))return true;
  }
  return false;
 }
 private static int twinMerchantQuality(String mA,String srcA,String pkgA,String mB,String srcB,String pkgB){
  String nA=Finance.normalizeMerchant(mA),nB=Finance.normalizeMerchant(mB);
  boolean rA=isRailOrGeneric(nA,srcB,pkgB),rB=isRailOrGeneric(nB,srcA,pkgA);
  if(!rA&&!rB&&!nA.isEmpty()&&nA.equals(nB))return 2;
  List<String> tA=brandTokens(nA),tB=brandTokens(nB);
  if(!rA&&!rB){
   List<String> cA=conflictTokens(mA),cB=conflictTokens(mB);
   StringBuilder jA=new StringBuilder(),jB=new StringBuilder();
   for(String a:cA)jA.append(consSkel(a));
   for(String b:cB)jB.append(consSkel(b));
   String skA=jA.toString(),skB=jB.toString();
   boolean diffA=false,diffB=false;
   for(String a:cA)if(!tokenOverlapsSet(a,cB,skB)){diffA=true;break;}
   for(String b:cB)if(!tokenOverlapsSet(b,cA,skA)){diffB=true;break;}
   if(diffA&&diffB)return 0;
   if(!cA.isEmpty()&&!cB.isEmpty()&&!diffA&&!diffB&&!skA.isEmpty()&&!skB.isEmpty()&&(skA.equals(skB)||(skA.length()>=4&&skB.length()>=4&&(skA.contains(skB)||skB.contains(skA)))))return 2;
   if(MerchantCanonicalizer.sameEntity(mA,mB))return 2;
   if(MerchantCanonicalizer.hasDistinctEntityConflict(mA,mB))return 0;
   for(String a:tA){
    for(String b:tB){
     if(a.equals(b)||(a.length()>=5&&b.length()>=5&&(a.startsWith(b)||b.startsWith(a))))return 2;
    }
   }
  }
  if(rA||rB)return 1;
  return 0;
 }
 private static boolean amountsMatchForTwin(JSONObject a,JSONObject b){
  long aAmt=a.optLong("amount",-1L),bAmt=b.optLong("amount",-1L);
  if(aAmt<=0||bAmt<=0)return false;
  if(aAmt==bAmt)return true;
  String curA=a.optString("originalCurrency","").trim().toUpperCase(Locale.ROOT);
  String curB=b.optString("originalCurrency","").trim().toUpperCase(Locale.ROOT);
  if(!curA.isEmpty()&&curA.equals(curB)){
   try{
    BigDecimal oa=new BigDecimal(a.optString("originalAmount","").trim());
    BigDecimal ob=new BigDecimal(b.optString("originalAmount","").trim());
    if(oa.signum()>0&&oa.subtract(ob).abs().compareTo(new BigDecimal("0.01"))<=0){
     long maxDiff=Math.max(150L,Math.round(Math.max(aAmt,bAmt)*0.04));
     if(Math.abs(aAmt-bAmt)<=maxDiff)return true;
    }
   }catch(Exception ignored){}
  }
  return false;
 }
 private static boolean areKindsTwinCompatible(JSONObject a,JSONObject b){
  String kA=a.optString("kind","expense"),kB=b.optString("kind","expense");
  if(kA.equals(kB))return true;
  if(("reload".equals(kA)&&"transfer".equals(kB)&&b.optBoolean("reload"))||("transfer".equals(kA)&&a.optBoolean("reload")&&"reload".equals(kB)))return true;
  if(("shared".equals(kA)&&"expense".equals(kB))||("expense".equals(kA)&&"shared".equals(kB)))return true;
  boolean trMatch=(("expense".equals(kA)||"shared".equals(kA))&&"transfer".equals(kB))||("transfer".equals(kA)&&("expense".equals(kB)||"shared".equals(kB)));
  if(!trMatch)return false;
  if(a.optBoolean("reload")||b.optBoolean("reload")||"reload".equals(kA)||"reload".equals(kB))return false;
  JSONObject tr="transfer".equals(kA)?a:b,ex="transfer".equals(kA)?b:a;
  // Never merge a real bank-to-wallet internal reload transfer with a subsequent wallet purchase.
  String trTo=tr.optString("to",""),exAcct=ex.optString("account","");
  if(!trTo.isEmpty()&&trTo.equals(exAcct))return false;
  return true;
 }
 /** Finds an auto-recorded draft from a different app matching amount, time window, compatible kind and non-conflicting merchant. */
 static JSONObject findCrossSourceTwin(JSONObject s,JSONObject d,String pkg,long eventTime)throws JSONException{
  return findPostedDraftTwin(s,d,pkg,eventTime);
 }
 static JSONObject findPostedDraftTwin(JSONObject s,JSONObject d,String pkg,long eventTime)throws JSONException{
  long amt=d.optLong("amount");
  if(amt<=0||pkg==null||!s.getJSONObject("settings").optBoolean("autoRecord",true))return null;
  JSONArray drafts=s.getJSONArray("drafts");
  String dMerch=d.optString("merchant",""),dSrc=d.optString("source","");
  for(int i=drafts.length()-1;i>=0;i--){
   JSONObject o=drafts.getJSONObject(i);
   if(o==d||o.has("duplicateOf")||!amountsMatchForTwin(o,d))continue;
   if(pkg.equals(o.optString("package")))continue;
   String txId=o.optString("transactionId","");
   if(txId.isEmpty()||(!o.optBoolean("autoRecorded")&&!"confirmed".equals(o.optString("state"))))continue;
   if(!areKindsTwinCompatible(o,d))continue;
   int q=twinMerchantQuality(o.optString("merchant",""),o.optString("source",""),o.optString("package",""),dMerch,dSrc,pkg);
   if(q==0)continue;
   long maxWin=q>=2?300_000L:240_000L;
   if(Math.abs(eventTime-draftEventTime(o))>maxWin)continue;
   try{if(Ledger.find(s.getJSONArray("transactions"),txId).optBoolean("voided"))continue;}catch(IllegalArgumentException e){continue;}
   return o;
  }
  return null;
 }
 static JSONObject findReviewDraftTwin(JSONObject s,JSONObject d,String pkg,long eventTime)throws JSONException{
  long amt=d.optLong("amount");
  if(amt<=0||pkg==null)return null;
  JSONArray drafts=s.getJSONArray("drafts");
  String dMerch=d.optString("merchant",""),dSrc=d.optString("source","");
  for(int i=drafts.length()-1;i>=0;i--){
   JSONObject o=drafts.getJSONObject(i);
   if(o==d||!"review".equals(o.optString("state"))||o.has("duplicateOf")||!amountsMatchForTwin(o,d))continue;
   if(pkg.equals(o.optString("package")))continue;
   if(!areKindsTwinCompatible(o,d))continue;
   int q=twinMerchantQuality(o.optString("merchant",""),o.optString("source",""),o.optString("package",""),dMerch,dSrc,pkg);
   if(q==0)continue;
   long maxWin=q>=2?300_000L:240_000L;
   if(Math.abs(eventTime-draftEventTime(o))>maxWin)continue;
   return o;
  }
  return null;
 }
 static JSONObject findRecentMatchingTx(JSONObject s,JSONObject d,long eventTime)throws JSONException{
  long amt=d.optLong("amount");
  if(amt<=0)return null;
  JSONArray tx=s.optJSONArray("transactions");
  if(tx==null)return null;
  String dMerch=d.optString("merchant",""),dSrc=d.optString("source",""),dPkg=d.optString("package","");
  for(int i=tx.length()-1;i>=0;i--){
   JSONObject t=tx.getJSONObject(i);
   if(t.optBoolean("voided")||t.has("importBatch"))continue;
   String tk=t.optString("kind","");
   if(!Arrays.asList("expense","transfer","reload","income","refund","shared").contains(tk))continue;
   if(!amountsMatchForTwin(t,d))continue;
   String sp=t.optString("sourcePackage","");
   if(!dPkg.isEmpty()&&dPkg.equals(sp))continue;
   if(!areKindsTwinCompatible(t,d))continue;
   int q=twinMerchantQuality(t.optString("merchant",""),t.optString("source",""),sp,dMerch,dSrc,dPkg);
   if(q==0)continue;
   long created=t.optLong("created",0L);
   if(created<=0L)continue;
   long maxWin=q>=2?7_200_000L:900_000L;
   if(Math.abs(eventTime-created)>maxWin)continue;
   return t;
  }
  return null;
 }
 private static String resolveAccount(JSONObject s,String preferred,String sourceLabel,String pkg)throws JSONException{return resolveAccount(s,preferred,sourceLabel,pkg,"");}
 private static String resolveAccount(JSONObject s,String preferred,String sourceLabel,String pkg,String railHint)throws JSONException{
  JSONArray accounts=s.getJSONArray("accounts");
  if(!preferred.isEmpty())for(int i=0;i<accounts.length();i++)if(preferred.equals(accounts.getJSONObject(i).optString("id")))return preferred;
  if(railHint!=null&&!railHint.isEmpty()){
   String byRail=Finance.matchAccountByName(accounts,railHint,"");
   if(!byRail.isEmpty())return byRail;
  }
  String byName=Finance.matchAccountByName(accounts,sourceLabel,pkg);
  if(!byName.isEmpty())return byName;
  String last=s.getJSONObject("settings").optString("lastAccount","");
  if(!last.isEmpty())for(int i=0;i<accounts.length();i++)if(last.equals(accounts.getJSONObject(i).optString("id")))return last;
  return accounts.getJSONObject(0).getString("id");
 }
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
 /** Saves or updates a debt item for Avalanche/Snowball payoff planning. */
 static void debt(JSONObject s,JSONObject a)throws Exception{
  String id=a.optString("id","").trim();if(id.isEmpty())id=Ledger.id();
  String debtName=name(a,"name",80);
  String type=a.optString("type","loan").trim().toLowerCase(Locale.ROOT);
  if(!Arrays.asList("bnpl","ptptn","credit","loan").contains(type))type="loan";
  String balRaw=a.getString("balance").trim();
  long bal=balRaw.equals("0")||balRaw.equals("0.00")?0L:Ledger.minor(balRaw);
  long minPay=Ledger.minor(a.getString("minPayment").trim());
  String aprRaw=a.optString("apr","0").trim();
  int aprBps=aprRaw.isEmpty()?0:new BigDecimal(aprRaw).movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).intValueExact();
  if(aprBps<0||aprBps>10000)throw new IllegalArgumentException("Annual interest rate must be 0–100%.");
  String rateType="flat".equals(a.optString("rateType","reducing"))?"flat":"reducing";
  String prinRaw=a.optString("principal","").trim();
  long principal=prinRaw.isEmpty()||prinRaw.equals("0")||prinRaw.equals("0.00")?bal:Ledger.minor(prinRaw);
  int dueDay=Math.max(1,Math.min(31,a.optInt("dueDay",1)));
  String account=a.optString("account","").trim();
  if(!account.isEmpty())Ledger.find(s.getJSONArray("accounts"),account);
  else account=s.getJSONArray("accounts").getJSONObject(0).getString("id");
  String billId=a.optString("billId","").trim();
  if(billId.isEmpty()){try{billId=Ledger.find(s.getJSONArray("debts"),id).optString("billId","");}catch(IllegalArgumentException ignored){}}
  JSONObject row=new JSONObject().put("id",id).put("name",debtName).put("type",type).put("balance",bal).put("principal",principal).put("minPayment",minPay).put("aprBps",aprBps).put("rateType",rateType).put("dueDay",dueDay).put("account",account);
  if(!billId.isEmpty())row.put("billId",billId);
  Ledger.upsert(s,"debts",row);
  if(a.optBoolean("createReminder"))syncDebtReminder(s,row);
 }
 /** Creates or updates a recurring monthly bill reminder linked to a debt item. */
 static void syncDebtReminder(JSONObject s,JSONObject debtRow)throws Exception{
  String billId=debtRow.optString("billId","").trim();
  JSONObject existing=null;
  if(!billId.isEmpty()){try{existing=Ledger.find(s.getJSONArray("bills"),billId);}catch(IllegalArgumentException ignored){billId="";}}
  if(billId.isEmpty())billId=Ledger.id();
  int dueDay=Math.max(1,Math.min(31,debtRow.optInt("dueDay",1)));
  LocalDate now=LocalDate.now();
  LocalDate due=Finance.anchored(YearMonth.from(now),dueDay);
  if(due.isBefore(now))due=Finance.anchored(YearMonth.from(now).plusMonths(1),dueDay);
  String type=debtRow.optString("type","loan");
  String rType="ptptn".equals(type)?"ptptn":"credit".equals(type)?"credit":"bnpl".equals(type)?"bnpl":"bill";
  String sub="ptptn".equals(type)?"PTPTN Study Loan":"bnpl".equals(type)?"BNPL (SPayLater / GrabPayLater / Atome)":"credit".equals(type)?"Credit Card Bill":"Personal Loan";
  JSONObject args=new JSONObject().put("id",billId).put("name",debtRow.getString("name")).put("amount",BigDecimal.valueOf(debtRow.getLong("minPayment"),2).toPlainString()).put("date",existing!=null?existing.getString("date"):due.toString()).put("account",debtRow.optString("account",s.getJSONArray("accounts").getJSONObject(0).getString("id"))).put("category","Loans, BNPL & Credit").put("subcategory",sub).put("frequency","monthly").put("remindDaysBefore",3).put("remindTime","09:00").put("reminderType",rType);
  bill(s,args);
  debtRow.put("billId",billId);
 }
 /** Deducts estimated principal (payment minus monthly interest) from a debt linked to billId, or reverses that exact adjustment. */
 static void adjustDebtBalance(JSONObject s,String billId,JSONObject tx,boolean reverse)throws JSONException{
  JSONArray debts=s.optJSONArray("debts");
  if(debts==null||tx==null)return;
  if(reverse){
   String did=tx.optString("debtId","");
   long applied=tx.optLong("debtPrincipalApplied",0L);
   if(!did.isEmpty()&&applied>0L){
    for(int i=0;i<debts.length();i++){
     JSONObject d=debts.getJSONObject(i);
     if(did.equals(d.optString("id"))){
      d.put("balance",Math.min(Ledger.MAX,d.optLong("balance",0L)+applied));
      break;
     }
    }
   }
   tx.remove("debtId");tx.remove("debtPrincipalApplied");
   return;
  }
  if(billId==null||billId.isEmpty()||tx.has("debtPrincipalApplied"))return;
  long pay=tx.optLong("amount",0L);
  if(pay<=0L)return;
  for(int i=0;i<debts.length();i++){
   JSONObject d=debts.getJSONObject(i);
   if(!billId.equals(d.optString("billId")))continue;
   long bal=d.optLong("balance",0L);
   if(bal<=0L)return;
   int aprBps=d.optInt("aprBps",0);
   long base="flat".equals(d.optString("rateType","reducing"))?d.optLong("principal",bal):bal;
   long monthlyInterest=aprBps<=0?0L:Math.round(base*(double)aprBps/120000.0);
   long principalPaid=Math.min(bal,Math.max(0L,pay-monthlyInterest));
   if(principalPaid>0L){
    d.put("balance",bal-principalPaid);
    tx.put("debtId",d.getString("id")).put("debtPrincipalApplied",principalPaid);
   }
   return;
  }
 }
 public static String categoryEmoji(String category){
  if(category==null)return "💸";
  switch(category){
   case "Food & drinks":return "🍲";
   case "Groceries":return "🛒";
   case "Transport":return "🚗";
   case "Bills & utilities":return "⚡";
   case "Loans, BNPL & Credit":return "💳";
   case "Housing & property":return "🏠";
   case "Shopping":return "🛍️";
   case "Health & medical":return "🏥";
   case "Insurance & Takaful":return "🛡️";
   case "Education":return "📚";
   case "Family & gifts":return "🎁";
   case "Entertainment & travel":return "🎬";
   case "Investments & savings":return "📈";
   case "Taxes & government":return "🏛️";
   case "Fees & charges":return "🧾";
   case "Income & salary":return "💼";
   case "Transfers & top-up":return "🔄";
   default:return "💸";
  }
 }
 public static String rm(long minor){
  return BigDecimal.valueOf(Math.abs(minor),2).toPlainString();
 }
 public static String formatRecordedTitle(JSONObject d,JSONObject settings,boolean ms,boolean blur){
  String cat=d.optString("category","Miscellaneous");
  boolean catRich=settings==null||settings.optBoolean("categoryAlerts",true);
  String prefix=catRich?(categoryEmoji(cat)+" "):"";
  String merchant=d.optString("merchant",ms?"Bayaran":"Payment");
  String kind=d.optString("kind","");
  if("reimbursement".equals(kind)){
   String person=d.optString("matchedPerson",merchant);
   if(blur)return "🤝 "+(ms?"Bayaran kongsi diterima · ":"Split settled · ")+person;
   return "🤝 "+(ms?"Bayaran kongsi RM ":"Split settled RM ")+rm(d.optLong("amount"))+" · "+person;
  }
  boolean isRef="refund".equals(kind);
  if(blur)return prefix+(isRef?(ms?"Bayaran balik direkod · ":"Refund recorded · "):(ms?"Bayaran direkod · ":"Payment recorded · "))+merchant;
  return prefix+(isRef?(ms?"Bayaran balik RM ":"Refund RM "):(ms?"Direkod RM ":"Recorded RM "))+rm(d.optLong("amount"))+" · "+merchant;
 }
 public static String formatRecordedBody(JSONObject d,JSONObject settings,boolean ms,boolean blur){
  String cat=d.optString("category","Miscellaneous"),sub=d.optString("subcategory",""),matchedBill=d.optString("matchedBillName",""),matchedOrig=d.optString("matchedOriginalMerchant",""),refType=d.optString("refundMatchType",""),matchedPerson=d.optString("matchedPerson",""),splitType=d.optString("splitMatchType","");
  String catDisp="reimbursement".equals(d.optString("kind"))?(ms?"Bayaran Balik Kongsi":"Split Repayment"):Finance.categoryLabel(cat,ms);
  StringBuilder sb=new StringBuilder(sub.isEmpty()?catDisp:(catDisp+" › "+sub));
  String origCur=d.optString("originalCurrency",""),origAmt=d.optString("originalAmount","");
  if(!origCur.isEmpty()&&!origAmt.isEmpty()&&!blur)sb.append(" (").append(origCur).append(" ").append(origAmt).append(")");
  if(!matchedBill.isEmpty())sb.append(ms?" · Dipaut ke ":" · Linked to ").append(matchedBill);
  if(!matchedOrig.isEmpty())sb.append(ms?(" · Dipadankan dgn "+matchedOrig+("partial".equals(refType)?" (Sebahagian)":"")):(" · Matched to "+matchedOrig+("partial".equals(refType)?" (Partial)":"")));
  if(!matchedPerson.isEmpty())sb.append(ms?(" · Dilangsaikan automatik bagi "+matchedPerson+("partial".equals(splitType)?" (Sebahagian)":"")):(" · Auto-settled "+matchedPerson+"'s split"+("partial".equals(splitType)?" (Partial)":"")));
  if(d.optBoolean("anomaly")){
   String ratio=d.optString("anomalyRatio","");
   if(blur)sb.append(ms?" · ⚠ Amaun luar biasa":" · ⚠ Unusual amount");
   else sb.append(ms?(" · ⚠ "+ratio+" drpd biasa (med RM "+rm(d.optLong("anomalyMedian"))+")"):(" · ⚠ "+ratio+" usual (med RM "+rm(d.optLong("anomalyMedian"))+")"));
  }
  boolean catRich=settings==null||settings.optBoolean("categoryAlerts",true);
  if(!blur&&catRich){
   if(d.optBoolean("catHasBudget")){
    int pct=d.optInt("catBudgetPct",0);
    long rem=d.optLong("catBudgetRemaining",0L);
    if(rem<0)sb.append(ms?(" · "+pct+"% bajet (terlebih RM "+rm(-rem)+")"):(" · "+pct+"% budget (RM "+rm(-rem)+" over)"));
    else sb.append(ms?(" · "+pct+"% bajet (baki RM "+rm(rem)+")"):(" · "+pct+"% of budget (RM "+rm(rem)+" left)"));
   }else{
    long cycleSpent=d.optLong("catCycleSpent",0L);
    if(cycleSpent>d.optLong("amount",0L))sb.append(ms?(" · RM "+rm(cycleSpent)+" kitaran ini"):(" · RM "+rm(cycleSpent)+" this cycle"));
   }
  }
  sb.append(d.optBoolean("needsNote")?(ms?" · Ketik untuk ubah atau Tambah Nota":" · Tap to edit or Add Note"):(ms?" · Disimpan automatik":" · Auto-saved"));
  return sb.toString();
 }
 public static String formatReviewTitle(JSONObject d,JSONObject settings,boolean ms,boolean blur){
  String cat=d.optString("category","Miscellaneous");
  boolean catRich=settings==null||settings.optBoolean("categoryAlerts",true);
  String prefix=catRich?(categoryEmoji(cat)+" "):"";
  if(blur)return prefix+(ms?"Semak bayaran baharu?":"Review new payment?");
  long amt=d.optLong("amount",0L);
  String merchant=d.optString("merchant","");
  if(amt>0&&!merchant.isEmpty())return prefix+(ms?"Semak · RM ":"Review · RM ")+rm(amt)+" · "+merchant;
  return prefix+(ms?"Rekod bayaran?":"Record a payment?");
 }
 public static String formatReviewBody(JSONObject d,JSONObject settings,boolean ms,boolean blur){
  if(blur)return ms?"Cadangan baharu sedia. Semak butiran di MoneyLens.":"A new suggestion is ready. Review the details in MoneyLens.";
  String cat=d.optString("category","Miscellaneous"),sub=d.optString("subcategory","");
  String catDisp=Finance.categoryLabel(cat,ms);
  String head=sub.isEmpty()?catDisp:(catDisp+" › "+sub);
  String src=d.optString("source","");
  if(!src.isEmpty())return head+" · "+src+(ms?" · Ketik Semak":" · Tap Review");
  return head+(ms?" · Ketik untuk semak butiran.":" · Tap to review details.");
 }
 private static final String[][] QE_ACCOUNT_ALIASES={
  {"cash","Cash","cash"},
  {"tunai","Cash","cash"},
  {"现金","Cash","cash"},
  {"现钱","Cash","cash"},
  {"touch n go","Touch 'n Go eWallet","tng"},
  {"touch 'n go","Touch 'n Go eWallet","tng"},
  {"tngdigital","Touch 'n Go eWallet","tng"},
  {"tngo","Touch 'n Go eWallet","tng"},
  {"tng","Touch 'n Go eWallet","tng"},
  {"一触即通","Touch 'n Go eWallet","tng"},
  {"maybank2u","Maybank","MAE"},
  {"maybank","Maybank","MAE"},
  {"马来亚银行","Maybank","MAE"},
  {"m2u","MAE","Maybank"},
  {"mae","MAE","Maybank"},
  {"cimb octo","CIMB","cimb"},
  {"cimb","CIMB","cimb"},
  {"联昌","CIMB","cimb"},
  {"public bank","Public Bank","pbb"},
  {"mypb","Public Bank","pbb"},
  {"pbb","Public Bank","pbb"},
  {"大众银行","Public Bank","pbb"},
  {"rhb","RHB","rhb"},
  {"hong leong","HLB","hong leong"},
  {"hlb","HLB","hlb"},
  {"丰隆","HLB","hlb"},
  {"gxbank","GXBank","gx"},
  {"gx","GXBank","gx"},
  {"aeon bank","AEON Bank","aeon"},
  {"aeon","AEON","aeon"},
  {"ryt bank","Ryt Bank","ryt"},
  {"ryt","Ryt","ryt"},
  {"shopeepay","ShopeePay","spay"},
  {"spay","ShopeePay","spay"},
  {"grabpay","GrabPay","grabpay"},
  {"boost bank","Boost Bank","boost"},
  {"boost","Boost","boost"},
  {"bigpay","BigPay","bigpay"},
  {"setel","Setel","setel"},
  {"信用卡","Credit Card","card"}
 };
 private static String[] findQuickAccountMatch(JSONArray accounts,String text,String excludeId)throws Exception{
  if(accounts==null||text==null||text.trim().isEmpty())return null;
  String bestId="",bestTok="",bestHint="";int bestLen=0;
  for(int i=0;i<accounts.length();i++){
   JSONObject a=accounts.getJSONObject(i);String id=a.optString("id","");
   if(id.equals(excludeId))continue;
   String aname=a.optString("name","").trim();
   if(aname.isEmpty())continue;
   String pat=isAsciiOnlyToken(aname)?("(?i)(?<![A-Za-z0-9])"+java.util.regex.Pattern.quote(aname)+"(?![A-Za-z0-9])"):("(?i)"+java.util.regex.Pattern.quote(aname));
   java.util.regex.Matcher m=java.util.regex.Pattern.compile(pat).matcher(text);
   if(m.find()&&aname.length()>bestLen){
    bestId=id;bestTok=m.group(0);bestHint=aname;bestLen=aname.length();
   }
  }
  if(!bestId.isEmpty())return new String[]{bestId,bestTok,bestHint};
  for(String[] al:QE_ACCOUNT_ALIASES){
   String key=al[0];
   String pat=isAsciiOnlyToken(key)?("(?i)(?<![A-Za-z0-9])"+java.util.regex.Pattern.quote(key)+"(?![A-Za-z0-9])"):("(?i)"+java.util.regex.Pattern.quote(key));
   java.util.regex.Matcher m=java.util.regex.Pattern.compile(pat).matcher(text);
   if(!m.find())continue;
   String id=Finance.matchAccountByName(accounts,al[1],"",excludeId);
   if(id.isEmpty()&&!al[2].isEmpty())id=Finance.matchAccountByName(accounts,al[2],"",excludeId);
   if(id.isEmpty()&&"Cash".equalsIgnoreCase(al[1])){
    for(int i=0;i<accounts.length();i++){
     JSONObject a=accounts.getJSONObject(i);
     if("cash".equals(a.optString("type"))&&!a.optString("id","").equals(excludeId)){id=a.getString("id");break;}
    }
   }
   if(!id.isEmpty())return new String[]{id,m.group(0),al[1]};
  }
  return null;
 }
 private static boolean isAsciiOnlyToken(String s){
  for(int i=0;i<s.length();i++)if(s.charAt(i)>127)return false;
  return true;
 }
 private static int parseChineseOrIntCount(String raw){
  if(raw==null||raw.trim().isEmpty())return -1;
  String t=raw.trim();
  if(t.matches("[0-9]+"))return Integer.parseInt(t);
  switch(t){
   case "二":case "两":case "兩":return 2;
   case "三":return 3;
   case "四":return 4;
   case "五":return 5;
   case "六":return 6;
   case "七":return 7;
   case "八":return 8;
   case "九":return 9;
   case "十":return 10;
   default:return -1;
  }
 }
 /** Parses a natural-language EN/BM/Manglish/ZH quick entry phrase into structured transaction fields. */
 public static JSONObject parseQuickEntry(JSONObject s,String rawText,LocalDate today)throws Exception{
  String raw=rawText==null?"":rawText.trim();
  if(raw.isEmpty())return null;
  LocalDate refToday=today!=null?today:LocalDate.now();
  LocalDate parsedDate=refToday;
  String work=raw;

  // 1. Extract explicit or relative EN/BM/Manglish/ZH dates BEFORE amount extraction
  java.util.regex.Matcher isoM=java.util.regex.Pattern.compile("\\b(20[0-9]{2}-[01][0-9]-[0-3][0-9])\\b").matcher(work);
  if(isoM.find()){
   try{parsedDate=LocalDate.parse(isoM.group(1));}catch(Exception ignored){}
   work=(work.substring(0,isoM.start())+" "+work.substring(isoM.end())).trim();
  }else{
   java.util.regex.Matcher dmyM=java.util.regex.Pattern.compile("(?<![0-9.])([0-3]?[0-9])/([01]?[0-9])(?:/(20[0-9]{2}))?(?![0-9.])").matcher(work);
   if(dmyM.find()){
    try{
     int d=Integer.parseInt(dmyM.group(1)),m=Integer.parseInt(dmyM.group(2));
     int y=dmyM.group(3)!=null?Integer.parseInt(dmyM.group(3)):refToday.getYear();
     if(m>=1&&m<=12&&d>=1&&d<=31){
      parsedDate=LocalDate.of(y,m,Math.min(d,YearMonth.of(y,m).lengthOfMonth()));
      work=(work.substring(0,dmyM.start())+" "+work.substring(dmyM.end())).trim();
     }
    }catch(Exception ignored){}
   }else{
    java.util.regex.Matcher zhDt=java.util.regex.Pattern.compile("(1[0-2]|[1-9])月([123]?[0-9])[日号]").matcher(work);
    if(zhDt.find()){
     try{
      int m=Integer.parseInt(zhDt.group(1)),d=Integer.parseInt(zhDt.group(2));
      parsedDate=LocalDate.of(refToday.getYear(),m,Math.min(d,YearMonth.of(refToday.getYear(),m).lengthOfMonth()));
      work=(work.substring(0,zhDt.start())+" "+work.substring(zhDt.end())).trim();
     }catch(Exception ignored){}
    }else{
     java.util.regex.Matcher agoM=java.util.regex.Pattern.compile("(?i)(?:\\b([0-9]{1,2})\\s*(?:days?\\s+ago|hari\\s+(?:lepas|lalu))\\b|([0-9]{1,2})\\s*天前)").matcher(work);
     if(agoM.find()){
      int n=Integer.parseInt(agoM.group(1)!=null?agoM.group(1):agoM.group(2));
      if(n>=0&&n<=90)parsedDate=refToday.minusDays(n);
      work=(work.substring(0,agoM.start())+" "+work.substring(agoM.end())).trim();
     }else{
      java.util.regex.Matcher relM=java.util.regex.Pattern.compile("(?i)(?:\\b(?:yesterday|semalam|smlm|kelmarin|today|hari\\s+ini|harini|tadi)\\b|大前天|前天|前晚|昨天|昨晚|昨日|今天|今日|刚才|刚刚)").matcher(work);
      if(relM.find()){
       String tk=relM.group(0).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
       if("yesterday".equals(tk)||"semalam".equals(tk)||"smlm".equals(tk)||"昨天".equals(tk)||"昨晚".equals(tk)||"昨日".equals(tk))parsedDate=refToday.minusDays(1);
       else if("kelmarin".equals(tk)||"前天".equals(tk)||"前晚".equals(tk))parsedDate=refToday.minusDays(2);
       else if("大前天".equals(tk))parsedDate=refToday.minusDays(3);
       work=(work.substring(0,relM.start())+" "+work.substring(relM.end())).trim();
      }
     }
    }
   }
  }
  work=work.replaceAll("\\s+"," ").trim();

  // 2. Multilingual amount extraction (comma thousands, EN/BM/ZH currency units, sen, brand-number trap immunity)
  String amtToken="";int amtStart=-1,amtEnd=-1;boolean isSenOnly=false;
  java.util.regex.Matcher pfxM=java.util.regex.Pattern.compile("(?i)(?:(?<![A-Za-z0-9])(?:rm|myr)|马币)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})(?:\\.([0-9]{1,2}))?").matcher(work);
  java.util.regex.Matcher sfxM=java.util.regex.Pattern.compile("(?i)([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})(?:\\.([0-9]{1,2}))?\\s*(?:(?:rm|myr|ringgit|hinggit|bucks)(?![A-Za-z0-9])|块钱|块|元|令吉|马币|蚊)").matcher(work);
  if(pfxM.find()){
   amtToken=pfxM.group(1).replace(",","")+(pfxM.group(2)!=null?("."+pfxM.group(2)):"");
   amtStart=pfxM.start();amtEnd=pfxM.end();
  }else if(sfxM.find()){
   amtToken=sfxM.group(1).replace(",","")+(sfxM.group(2)!=null?("."+sfxM.group(2)):"");
   amtStart=sfxM.start();amtEnd=sfxM.end();
  }else{
   java.util.regex.Matcher senM=java.util.regex.Pattern.compile("(?i)(?<![0-9.])([0-9]{1,3})\\s*sen(?![A-Za-z0-9])").matcher(work);
   if(senM.find()){
    isSenOnly=true;amtToken=senM.group(1);amtStart=senM.start();amtEnd=senM.end();
   }else{
    java.util.regex.Matcher bareM=java.util.regex.Pattern.compile("(?<![0-9.:/-])([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,6}(?:\\.[0-9]{1,2})?)(?![0-9.:/-])").matcher(work);
    int bestScore=Integer.MIN_VALUE;
    while(bareM.find()){
     String tok=bareM.group(1);int st=bareM.start(),en=bareM.end();
     String before=work.substring(Math.max(0,st-24),st).toLowerCase(Locale.ROOT);
     String after=work.substring(en,Math.min(work.length(),en+24)).toLowerCase(Locale.ROOT);
     int score=0;
     if(tok.indexOf('.')>=0||tok.indexOf(',')>=0)score+=100;
     if(en<work.length()&&Character.isLetter(work.charAt(en)))score-=280;
     if(st>0&&Character.isLetter(work.charAt(st-1)))score-=260;
     if(before.matches(".*\\b(?:iphone|galaxy\\s*s?|pixel|ipad|macbook|ps|playstation|xbox|switch|xiaomi|redmi|oppo|vivo|realme|huawei|mate|pura|rtx|gtx|wifi|usb|series|gen|ver|v|model|lot|unit|no\\.?|room|table|order)\\s*$")||before.matches(".*#\\s*$"))score-=260;
     if(after.matches("^\\s*(?:speedmart|eleven|utama|fingers|plus)\\b.*")||after.matches("^[\\s-]*(?:speedmart|eleven|utama|fingers|plus)\\b.*"))score-=260;
     if(after.matches("^\\s*(?:x\\b|packs?|pcs?|pieces?|boxes?|bottles?|btl|cans?|cups?|plates?|bowls?|sets?|pairs?|items?|units?|tickets?|tix|pax|orang|org|ways?|am|pm|%|hrs?|hours?|mins?|minutes?|days?|weeks?|months?|pro|max|plus|ultra|mini|air|lite|fe|se|gen|generation|km|ml|kg|g|gb|tb|人|个|位|杯|碗|点|份).*"))score-=240;
     if(before.matches(".*\\b(?:split|kongsi|bahagi|divide)\\s*$")||before.matches(".*(?:平分|均摊)\\s*$"))score-=160;
     score+=st/5;
     if(score>bestScore){bestScore=score;amtToken=tok.replace(",","");amtStart=st;amtEnd=en;}
    }
   }
  }
  if(amtToken.isEmpty())return null;
  long amountMinor=isSenOnly?Long.parseLong(amtToken):Ledger.minor(amtToken);
  if(amountMinor<=0L)return null;
  String withoutAmt=(work.substring(0,amtStart)+" "+work.substring(amtEnd)).replaceAll("\\s+"," ").trim();
  String rawWithoutAmt=withoutAmt;

  // 3. Kind detection: shared -> refund -> transfer -> income -> expense
  String kind="expense",person="",ownAmount="",sourceHint="",payerHint="";
  int splitWays=2;
  boolean paidForFriendOnly=false;

  java.util.regex.Matcher pffEn=java.util.regex.Pattern.compile("(?i)\\b(?:paid|pay|spotted|cover(?:ed)?)\\s+(?:for|on\\s+behalf\\s+of)\\s+([\\p{L}0-9._-]{1,24}(?:\\s*&\\s*[\\p{L}0-9._-]{1,20})?)\\b").matcher(withoutAmt);
  java.util.regex.Matcher pffBm=java.util.regex.Pattern.compile("(?i)\\b(?:bayar\\s+dulu|dahulukan)\\s+untuk\\s+([\\p{L}0-9._-]{1,24}(?:\\s*&\\s*[\\p{L}0-9._-]{1,20})?)\\b").matcher(withoutAmt);
  java.util.regex.Matcher pffZh=java.util.regex.Pattern.compile("(?:帮|替)\\s*([A-Za-z0-9._-]+|[\\u4e00-\\u9fff]{1,5}?)\\s*(?:先?垫付|代付|先付)").matcher(withoutAmt);
  if(pffEn.find()){
   kind="shared";paidForFriendOnly=true;person=pffEn.group(1).trim();
   withoutAmt=(withoutAmt.substring(0,pffEn.start())+" "+withoutAmt.substring(pffEn.end())).trim();
  }else if(pffBm.find()){
   kind="shared";paidForFriendOnly=true;person=pffBm.group(1).trim();
   withoutAmt=(withoutAmt.substring(0,pffBm.start())+" "+withoutAmt.substring(pffBm.end())).trim();
  }else if(pffZh.find()){
   kind="shared";paidForFriendOnly=true;person=pffZh.group(1).trim();
   withoutAmt=(withoutAmt.substring(0,pffZh.start())+" "+withoutAmt.substring(pffZh.end())).trim();
  }else{
   java.util.regex.Matcher spEn=java.util.regex.Pattern.compile("(?i)\\b(?:split|kongsi|share|dutch|tong[\\s-]?tong|bahagi)(?:\\s+([0-9]{1,2})\\s*(?:ways?|orang|org))?(?:\\s+(?:with|dengan|dgn))?\\s+([\\p{L}0-9][\\p{L}0-9 .,'&_-]{0,44})").matcher(withoutAmt);
   java.util.regex.Matcher spZh=java.util.regex.Pattern.compile("(?:和|跟|与)\\s*([A-Za-z0-9._\\u4e00-\\u9fff&、,， ]{1,36}?)(?:([0-9一二两三四五六七八九十]+)\\s*人)?\\s*(?:平分|AA|aa制|均摊|分摊)").matcher(withoutAmt);
   java.util.regex.Matcher spZhBare=java.util.regex.Pattern.compile("(?:([0-9一二两三四五六七八九十]+)\\s*人)?\\s*(?:平分|AA|aa制|均摊)").matcher(withoutAmt);
   if(spEn.find()){
    kind="shared";
    int w=parseChineseOrIntCount(spEn.group(1));
    if(w>=2)splitWays=w;
    person=spEn.group(2)
     .replaceAll("(?i)\\s+(?:via|by|using|with|pada|melalui|on)\\s+(?:cash|tunai|tng|mae|maybank|cimb|rhb|hlb|gxbank|aeon|ryt|shopeepay|grabpay|boost|bigpay|bank|card).*$","")
     .replaceAll("(?i)\\s+(?:cash|tunai|tng|mae|maybank|cimb|rhb|hlb|gxbank|aeon|ryt|shopeepay|spay|grabpay|boost|bigpay|bank|card)$","")
     .replaceAll("[\\s,.]+$","").trim();
    withoutAmt=(withoutAmt.substring(0,spEn.start())+" "+withoutAmt.substring(spEn.end())).trim();
   }else if(spZh.find()){
    kind="shared";
    int w=parseChineseOrIntCount(spZh.group(2));
    if(w>=2)splitWays=w;
    person=spZh.group(1).replaceAll("(?:还有|以及|和|与|及|、|，|,)+"," & ").replaceAll("\\s+&\\s+"," & ").trim();
    withoutAmt=(withoutAmt.substring(0,spZh.start())+" "+withoutAmt.substring(spZh.end())).trim();
   }else if(spZhBare.find()){
    kind="shared";
    int w=parseChineseOrIntCount(spZhBare.group(1));
    if(w>=2)splitWays=w;
    person="Friend";
    withoutAmt=(withoutAmt.substring(0,spZhBare.start())+" "+withoutAmt.substring(spZhBare.end())).trim();
   }
  }
  if("shared".equals(kind)){
   if(paidForFriendOnly){
    ownAmount="0.00";
   }else{
    if(splitWays<=2&&!person.isEmpty()){
     int extraPeople=person.split("\\s*(?:&|\\band\\b|\\bdan\\b|,)\\s*").length;
     if(extraPeople>=2)splitWays=extraPeople+1;
    }
    ownAmount=BigDecimal.valueOf(amountMinor/Math.max(2,splitWays),2).toPlainString();
   }
  }

  String woLow=withoutAmt.toLowerCase(Locale.ROOT);
  boolean quickReloadCue=false;
  if("expense".equals(kind)){
   boolean isIncomingSignal=java.util.regex.Pattern.compile("(?i)(?:\\b(?:received|terima|dapat|daripada|gaji|salary|bonus|payday|income|dividend|dividen|commission|komisen|freelance)\\b|收到|工资|薪水|发薪|奖金|收入|入账|进账|报销)").matcher(withoutAmt).find();
   boolean hasTransferExpenseKw=java.util.regex.Pattern.compile("(?i)(?:\\b(?:prabayar|prepaid|postpaid|data|line|celcom|maxis|digi|umobile|hotlink|vitamin|vitamins|supplement|supplements|prs|asb|asnb|epf|kwsp|fee|fees|charge|charges|saving|savings|investment)\\b|流量|话费|维他命|保健|基金|费用|手续费|储蓄|投资)").matcher(withoutAmt).find();
   boolean isNonRefundable=java.util.regex.Pattern.compile("(?i)(?:\\b(?:non.?refundable|not\\s+refundable|no\\s+refund|tidak\\s+boleh\\s+dikembalikan)\\b|不可退款|不能退款|不退不换|恕不退款)").matcher(withoutAmt).find();
   if(!isNonRefundable&&java.util.regex.Pattern.compile("(?i)(?:\\b(?:refund|refunded|reversal|chargeback|bayaran\\s+balik|pulangan(?:\\s+wang)?)\\b|退款|退回|退钱|赔付)").matcher(withoutAmt).find()){
    kind="refund";
   }else if(!isIncomingSignal&&!hasTransferExpenseKw&&java.util.regex.Pattern.compile("(?i)(?:\\b(?:transfer|transferred|pindah|top[\\s-]?up|reload|reloaded|tambah\\s+nilai)\\b|转账|充值|加额|转到|转去|转入)").matcher(withoutAmt).find()){
    quickReloadCue=java.util.regex.Pattern.compile("(?i)(?:\\b(?:top[\\s-]?up|reload|reloaded|tambah\\s+nilai)\\b|充值|加额)").matcher(withoutAmt).find();
    kind="transfer";
   }else if(isIncomingSignal||java.util.regex.Pattern.compile("(?i)\\bfrom\\s+[\\p{L}]").matcher(withoutAmt).find()){
    kind="income";
   }
  }

  // 4. Account resolution (handles directional "topup X from Y" / "从Y充值到X" vs "transfer Y to X")
  JSONArray accounts=s!=null?s.optJSONArray("accounts"):null;
  String matchedAcct="",matchedAcctToken="",toAcct="",toAcctToken="";
  if(accounts!=null){
   if("transfer".equals(kind)){
    java.util.regex.Matcher fromM=java.util.regex.Pattern.compile("(?i)(?:\\b(?:from|dari|daripada)\\s+|从\\s*)([\\p{L}0-9 .'_-]{2,32})").matcher(withoutAmt);
    java.util.regex.Matcher toM=java.util.regex.Pattern.compile("(?i)(?:\\b(?:to|ke|into)\\s+|(?:到|去|进|给)\\s*)([\\p{L}0-9 .'_-]{2,32})").matcher(withoutAmt);
    if(fromM.find()){
     String[] srcHit=findQuickAccountMatch(accounts,fromM.group(1),"");
     if(srcHit!=null){matchedAcct=srcHit[0];matchedAcctToken=srcHit[1];sourceHint=srcHit[2];}
    }
    if(toM.find()){
     String[] dstHit=findQuickAccountMatch(accounts,toM.group(1),matchedAcct);
     if(dstHit!=null){toAcct=dstHit[0];toAcctToken=dstHit[1];}
    }
    if(matchedAcct.isEmpty()){
     String restForSrc=withoutAmt;
     if(!toAcctToken.isEmpty())restForSrc=restForSrc.replaceAll("(?i)"+java.util.regex.Pattern.quote(toAcctToken)," ");
     String[] srcHit=findQuickAccountMatch(accounts,restForSrc,toAcct);
     if(srcHit!=null){matchedAcct=srcHit[0];matchedAcctToken=srcHit[1];sourceHint=srcHit[2];}
    }
    if(toAcct.isEmpty()&&!matchedAcct.isEmpty()){
     String restForDst=withoutAmt;
     if(!matchedAcctToken.isEmpty())restForDst=restForDst.replaceAll("(?i)"+java.util.regex.Pattern.quote(matchedAcctToken)," ");
     String[] dstHit=findQuickAccountMatch(accounts,restForDst,matchedAcct);
     if(dstHit!=null){toAcct=dstHit[0];toAcctToken=dstHit[1];}
    }
   }
   if(matchedAcct.isEmpty()){
    String[] hit=findQuickAccountMatch(accounts,withoutAmt,"");
    if(hit==null&&!rawWithoutAmt.equals(withoutAmt))hit=findQuickAccountMatch(accounts,rawWithoutAmt,"");
    if(hit!=null){matchedAcct=hit[0];matchedAcctToken=hit[1];sourceHint=hit[2];}
   }
   if("transfer".equals(kind)&&quickReloadCue&&(accounts.length()==1||(toAcct.isEmpty()&&!withoutAmt.toLowerCase(Locale.ROOT).matches(".*\\b(?:from|dari|daripada|to|ke|into)\\b.*")&&!withoutAmt.contains("从")&&!withoutAmt.contains("到")))){
    kind="reload";
    toAcct=matchedAcct;
   }
  }else if("transfer".equals(kind)&&quickReloadCue){
   kind="reload";
  }
  if(matchedAcct.isEmpty()&&s!=null&&accounts!=null){
   JSONObject prefs=s.optJSONObject("settings");
   String lastAcc=prefs!=null?prefs.optString("lastAccount",""):"";
   if(!lastAcc.isEmpty()){
    for(int i=0;i<accounts.length();i++)if(lastAcc.equals(accounts.getJSONObject(i).optString("id"))){matchedAcct=lastAcc;break;}
   }
   if(matchedAcct.isEmpty()&&accounts.length()>0)matchedAcct=accounts.getJSONObject(0).optString("id","cash");
  }

  // 5. Clean multilingual merchant / description
  String merchantClean=withoutAmt;
  if(!matchedAcctToken.isEmpty()){
   String pat=isAsciiOnlyToken(matchedAcctToken)
     ?("(?i)(?:(?:\\b(?:via|with|using|by|at|on|melalui|ke|dari|daripada|into|masuk)\\s+|(?:用|从|到|进|刷)\\s*))?(?<![A-Za-z0-9])"+java.util.regex.Pattern.quote(matchedAcctToken)+"(?![A-Za-z0-9])")
     :("(?i)(?:用|从|到|进|刷)?\\s*"+java.util.regex.Pattern.quote(matchedAcctToken));
   merchantClean=merchantClean.replaceAll(pat," ");
  }
  if(!toAcctToken.isEmpty()){
   String pat=isAsciiOnlyToken(toAcctToken)
     ?("(?i)(?:(?:\\b(?:to|ke|into)\\s+|(?:到|去|进|给)\\s*))?(?<![A-Za-z0-9])"+java.util.regex.Pattern.quote(toAcctToken)+"(?![A-Za-z0-9])")
     :("(?i)(?:到|去|进|给)?\\s*"+java.util.regex.Pattern.quote(toAcctToken));
   merchantClean=merchantClean.replaceAll(pat," ");
  }
  if("income".equals(kind)){
   java.util.regex.Matcher frm=java.util.regex.Pattern.compile("(?i)\\b(?:from|daripada)\\s+([\\p{L}0-9 .'_-]{2,40})").matcher(withoutAmt);
   java.util.regex.Matcher zhFrm=java.util.regex.Pattern.compile("收到(?:来自|了)?\\s*([A-Za-z0-9._-]+|[\\u4e00-\\u9fff]{1,6}?)\\s*(?:的)?(?:转账|汇款|红包|转来|还)").matcher(withoutAmt);
   if(frm.find())payerHint=frm.group(1).replaceAll("(?i)\\b(?:via|duitnow|mae|tng|cash|tunai|bank).*","").trim();
   else if(zhFrm.find())payerHint=zhFrm.group(1).trim();
  }
  merchantClean=merchantClean
   .replaceAll("(?i)\\b(?:paid|pay|spent|bought|spend|bayar|beli|received|terima|dapat|masuk|duitnow|fpx|qr|via|using|with|melalui|from|daripada|at|di|for|untuk|to|kepada|on)\\b"," ")
   .replaceAll("(?:买了|花了|吃了|喝了|用了|付了|交了|给了|收到|到|进|在|去)"," ")
   .replaceAll("[.,;:!\\-\\s]+$","").replaceAll("^[.,;:!\\-\\s]+","").replaceAll("\\s+"," ").trim();
  if(!payerHint.isEmpty())merchantClean=payerHint;
  if("refund".equals(kind)){
   String noRef=merchantClean.replaceAll("(?i)(?:\\b(?:refund|refunded|reversal|bayaran\\s+balik|pulangan)\\b|退款|退回|退钱)"," ").replaceAll("\\s+"," ").trim();
   if(noRef.length()>=2)merchantClean=noRef;
  }
  if(merchantClean.isEmpty())merchantClean="shared".equals(kind)?"Shared expense":"income".equals(kind)?"Incoming transfer":"refund".equals(kind)?"Refund":"Quick expense";
  else if(Character.isLowerCase(merchantClean.charAt(0)))merchantClean=Character.toUpperCase(merchantClean.charAt(0))+merchantClean.substring(1);

  // 6. Classification cascade: Income/Transfer rules -> PaymentParser -> SemanticSearch -> MerchantCanonicalizer -> Finance.suggest
  String[] cls;
  if("income".equals(kind)){
   boolean isSalary=java.util.regex.Pattern.compile("(?i)(?:\\b(?:salary|gaji|payday|bonus)\\b|工资|薪水|发薪|奖金)").matcher(woLow+" "+raw.toLowerCase(Locale.ROOT)).find();
   cls=new String[]{isSalary?"Salary":"Other income",""};
  }else if("transfer".equals(kind)||"reload".equals(kind)){
   cls=new String[]{"Miscellaneous",""};
  }else{
   MerchantCanonicalizer.Result canon=null;
   if(merchantClean.length()>=3){
    canon=MerchantCanonicalizer.canonicalize(merchantClean,"");
    if(canon!=null&&canon.matchedCatalog&&canon.similarity>=0.72&&MerchantCanonicalizer.isNoisyTruncatedSurface(merchantClean)){
     merchantClean=canon.canonicalName;
    }
   }
   String[] ruleCls=PaymentParser.classifyRulesOnly(merchantClean,sourceHint,raw);
   if(!"Miscellaneous".equals(ruleCls[0])){
    cls=ruleCls;
   }else if(canon!=null&&canon.matchedCatalog&&canon.similarity>=0.60&&!"Miscellaneous".equals(canon.defaultCategory)){
    cls=new String[]{canon.defaultCategory,canon.defaultSubcategory};
   }else{
    SemanticSearch.CategoryPrediction pred=SemanticSearch.classifyMultilingual(merchantClean,raw);
    if(pred!=null&&!"Miscellaneous".equals(pred.category)){
     cls=new String[]{pred.category,pred.subcategory};
    }else{
     cls=PaymentParser.classify(merchantClean,sourceHint,raw);
    }
   }
  }

  JSONObject res=new JSONObject()
   .put("kind",kind)
   .put("amount",BigDecimal.valueOf(amountMinor,2).toPlainString())
   .put("amountMinor",amountMinor)
   .put("merchant",merchantClean)
   .put("category",cls[0])
   .put("subcategory",cls[1])
   .put("account",matchedAcct)
   .put("date",parsedDate.toString());
  if(!ownAmount.isEmpty())res.put("ownAmount",ownAmount);
  if(!person.isEmpty())res.put("person",person);
  if(s!=null&&"expense".equals(kind)){
   JSONObject draft=new JSONObject().put("merchant",merchantClean).put("amount",amountMinor).put("source",sourceHint).put("category",cls[0]).put("subcategory",cls[1]).put("account",matchedAcct);
   Finance.suggest(s,draft);
   String sugCat=draft.optString("category","");
   if(!sugCat.isEmpty()&&!"Miscellaneous".equals(sugCat)&&!"Other".equals(sugCat)){
    res.put("category",sugCat);
    if(!draft.optString("subcategory").isEmpty())res.put("subcategory",draft.optString("subcategory"));
   }else if("Miscellaneous".equals(res.optString("category",""))&&!sugCat.isEmpty()){
    res.put("category",sugCat);
   }
   if(!draft.optString("account").isEmpty())res.put("account",draft.optString("account"));
  }
  if("transfer".equals(kind)&&accounts!=null&&accounts.length()>1){
   if(toAcct.isEmpty()||toAcct.equals(matchedAcct)){
    for(int i=0;i<accounts.length();i++){String id=accounts.getJSONObject(i).getString("id");if(!id.equals(matchedAcct)){toAcct=id;break;}}
   }
   if(!toAcct.isEmpty())res.put("to",toAcct);
  }else if("reload".equals(kind)){
   res.put("to",toAcct.isEmpty()?matchedAcct:toAcct);
  }
  return res;
 }
 /** Parses a natural-language quick entry phrase and posts it directly as a double-entry transaction. */
 public static JSONObject postQuickEntry(JSONObject s,String rawText,LocalDate today,String saveKey)throws Exception{
  JSONObject parsed=parseQuickEntry(s,rawText,today);
  if(parsed==null)throw new IllegalArgumentException("Include an amount like \"Nasi Lemak 12.50 cash\".");
  String kind=parsed.optString("kind","expense");
  String acct=parsed.optString("account","");
  if(acct.isEmpty())acct=s.getJSONArray("accounts").getJSONObject(0).getString("id");
  JSONObject postArgs=new JSONObject()
   .put("kind",kind)
   .put("amount",parsed.getString("amount"))
   .put("account",acct)
   .put("merchant",parsed.optString("merchant","Quick expense"))
   .put("category",parsed.optString("category","Miscellaneous"))
   .put("date",parsed.optString("date",today.toString()))
   .put("source","Quick Log")
   .put("autoRecorded",false)
   .put("key",(saveKey!=null&&!saveKey.trim().isEmpty())?saveKey.trim():("quick:"+Ledger.id()));
  String sub=parsed.optString("subcategory","");
  if(!sub.isEmpty())postArgs.put("subcategory",sub);
  if("shared".equals(kind)){
   String person=parsed.optString("person","").trim();
   postArgs.put("person",person.isEmpty()?"Friend":person);
   postArgs.put("ownAmount",parsed.optString("ownAmount","0"));
  }else if("transfer".equals(kind)){
   String toAcct=parsed.optString("to","");
   if(toAcct.isEmpty()||toAcct.equals(acct)){
    JSONArray accts=s.getJSONArray("accounts");
    for(int i=0;i<accts.length();i++){String id=accts.getJSONObject(i).getString("id");if(!id.equals(acct)){toAcct=id;break;}}
   }
   if(toAcct.isEmpty()||toAcct.equals(acct))throw new IllegalArgumentException("Add at least two accounts to record a transfer.");
   postArgs.put("to",toAcct);
  }else if("reload".equals(kind)){
   String toAcct=parsed.optString("to","");
   postArgs.put("to",toAcct.isEmpty()?acct:toAcct);
  }
  Ledger.post(s,postArgs);
  JSONArray tx=s.getJSONArray("transactions");
  return tx.getJSONObject(tx.length()-1);
 }
}
