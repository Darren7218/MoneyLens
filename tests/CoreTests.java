package app.moneylens;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

public final class CoreTests {
  static int passed=0;
  interface Check{void run()throws Exception;}
  static void test(String name,Check c)throws Exception{c.run();passed++;System.out.println("PASS "+name);}
  static void eq(long a,long b){if(a!=b)throw new AssertionError(a+" != "+b);}
  static void yes(boolean b){if(!b)throw new AssertionError();}
  static void rejects(Check c)throws Exception{try{c.run();}catch(Exception e){return;}throw new AssertionError("Expected rejection");}
  static JSONObject tx(String kind,String value)throws Exception{return new JSONObject().put("key",Ledger.id()).put("kind",kind).put("amount",value).put("account","cash").put("category","Food & drinks").put("merchant","Cafe").put("date",LocalDate.now().toString());}
  static long balance(JSONObject s,String id)throws Exception{long total=0;JSONArray a=s.getJSONArray("transactions");for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if(t.optBoolean("voided"))continue;JSONArray p=t.getJSONArray("postings");for(int j=0;j<p.length();j++)if(p.getJSONObject(j).getString("account").equals(id))total+=p.getJSONObject(j).getLong("amount");}return total;}
  static long spending(JSONObject s)throws Exception{long sum=0;JSONArray a=s.getJSONArray("transactions");for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if(t.optBoolean("voided"))continue;JSONArray p=t.getJSONArray("postings");for(int j=0;j<p.length();j++)if(p.getJSONObject(j).getString("account").startsWith("expense:"))sum+=p.getJSONObject(j).getLong("amount");}return sum;}
  public static void main(String[] args)throws Exception{
    test("Exact minor units",()->{eq(Ledger.minor("18.90"),1890);eq(Ledger.minor("0.01"),1);});
    test("Reject invalid money",()->{for(String v:new String[]{"-1","1.001","1e3","NaN","0","1,000"," 5"})rejects(()->Ledger.minor(v));});
    test("Expense balances",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","20"));eq(balance(s,"cash"),-2000);eq(spending(s),2000);});
    test("Transfer has no spending",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Wallet").put("type","wallet"));String to=s.getJSONArray("accounts").getJSONObject(1).getString("id");Ledger.apply(s,"transaction",tx("transfer","100").put("to",to));eq(balance(s,"cash"),-10000);eq(balance(s,to),10000);eq(spending(s),0);});
    test("Reject self transfer",()->{JSONObject s=Ledger.initial();rejects(()->Ledger.apply(s,"transaction",tx("transfer","10").put("to","cash")));});
    test("Refund reduces expense",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","50"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("refund","10").put("originalId",id));eq(spending(s),4000);eq(balance(s,"cash"),-4000);});
    test("Credit repayment is not spending",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Card").put("type","card"));String card=s.getJSONArray("accounts").getJSONObject(1).getString("id");Ledger.apply(s,"transaction",tx("expense","50").put("account",card));Ledger.apply(s,"transaction",tx("transfer","50").put("to",card));eq(balance(s,card),0);eq(spending(s),5000);});
    test("Card opening balance is debt",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Card").put("type","card").put("opening","500"));eq(balance(s,s.getJSONArray("accounts").getJSONObject(1).getString("id")),-50000);eq(spending(s),0);});
    test("Repeated save is idempotent",()->{JSONObject s=Ledger.initial(),t=tx("expense","10");Ledger.apply(s,"transaction",t);Ledger.apply(s,"transaction",t);eq(s.getJSONArray("transactions").length(),1);});
    test("Draft confirmation cannot double post",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"sample",new JSONObject());String id=s.getJSONArray("drafts").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("expense","18.90").put("draftId",id));Ledger.apply(s,"transaction",tx("expense","18.90").put("draftId",id));eq(s.getJSONArray("transactions").length(),1);});
    test("Ignored draft cannot be confirmed",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"sample",new JSONObject());String id=s.getJSONArray("drafts").getJSONObject(0).getString("id");Ledger.apply(s,"ignore",new JSONObject().put("id",id));rejects(()->Ledger.apply(s,"transaction",tx("expense","18.90").put("draftId",id)));});
    test("Edit preserves old version without double total",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","10"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("expense","12").put("editId",id));eq(spending(s),1200);yes(s.getJSONArray("transactions").getJSONObject(0).getBoolean("voided"));});
    test("Void is idempotent; explicit undo",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","10"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"void",new JSONObject().put("id",id));eq(spending(s),0);Ledger.apply(s,"void",new JSONObject().put("id",id));eq(spending(s),0);Ledger.apply(s,"void",new JSONObject().put("id",id).put("restore",true));eq(spending(s),1000);});
    test("Monthly bill clamps then returns to anchor day",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"bill",new JSONObject().put("id","b").put("name","Rent").put("amount","100").put("account","cash").put("date","2026-01-31").put("monthly",true));Ledger.apply(s,"transaction",tx("expense","100").put("billId","b").put("billDate","2026-01-31"));yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-02-28"));Ledger.apply(s,"transaction",tx("expense","100").put("billId","b").put("billDate","2026-02-28"));yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-03-31"));});
    test("Reject invalid or tampered backup ledger",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","10"));s.getJSONArray("transactions").getJSONObject(0).getJSONArray("postings").getJSONObject(1).put("amount",999);rejects(()->Ledger.validate(s));});
    test("Randomized ledger totals",()->{Random r=new Random(7);JSONObject s=Ledger.initial();long expected=0;for(int i=0;i<150;i++){long n=1+r.nextInt(100000);String kind=r.nextBoolean()?"expense":"refund";Ledger.apply(s,"transaction",tx(kind,new java.math.BigDecimal(n).movePointLeft(2).toPlainString()));expected+=kind.equals("expense")?n:-n;}eq(spending(s),expected);eq(balance(s,"cash"),-expected);});
    test("Parse payment",()->{JSONObject d=PaymentParser.parse("Payment of RM18.90 to ABC Cafe successful","Wallet");eq(d.getLong("amount"),1890);yes(d.getString("merchant").equals("ABC Cafe"));});
    test("Multiple amounts with balance resolves primary amount",()->eq(PaymentParser.parse("Paid RM18.90. Balance RM300.00","Bank").getLong("amount"),1890));
    test("Reject OTP, promotions, failed and pending",()->{for(String t:new String[]{"OTP for payment RM10 is 123456","Payment RM10 failed","Payment RM10 pending","RM10 voucher for your next payment","Secure2u authorise payment RM10"})yes(PaymentParser.parse(t,"Bank")==null);});
    test("Ignore plain balance",()->yes(PaymentParser.parse("Your balance is RM300.00","Bank")==null));
    test("Recognize transfer",()->yes(PaymentParser.parse("Transfer RM100 to wallet successful","Bank").getString("kind").equals("transfer")));
    test("Backup round trip and tamper rejection",()->{byte[] plain=Ledger.initial().toString().getBytes("UTF-8");char[] pw="Strong test password 123".toCharArray();byte[] file=Crypto.backup(plain,pw);yes(Arrays.equals(plain,Crypto.restore(file,pw)));rejects(()->Crypto.restore(file,"wrong password".toCharArray()));file[file.length-1]^=1;rejects(()->Crypto.restore(file,pw));});
    System.out.println("RESULT: "+passed+" tests passed");
  }
}
