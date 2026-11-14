package app.moneylens;
import org.json.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.security.MessageDigest;
import static app.moneylens.CoreTests.*;
public final class ExtendedTests {
 static JSONObject bill(String date,String frequency)throws Exception{return new JSONObject().put("id","bill").put("name","Phone").put("amount","20").put("account","cash").put("date",date).put("frequency",frequency);}
 static JSONObject imported()throws Exception{return new JSONObject().put("amount",1).put("date",0).put("merchant",2).put("account","cash");}
 public static void main(String[] args)throws Exception{
  test("Schema 1 migration preserves transactions",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","1.25"));s.put("schema",1);for(String k:new String[]{"rules","favorites","receipts","imports"})s.remove(k);Ledger.migrate(s);Ledger.validate(s);eq(s.getInt("schema"),2);eq(spending(s),125);});
  test("Split expense and split refund match exact sen",()->{JSONObject s=Ledger.initial();JSONArray splits=new JSONArray().put(new JSONObject().put("category","Food & drinks").put("amount","1.01")).put(new JSONObject().put("category","Other").put("amount","2.02"));Ledger.apply(s,"transaction",tx("expense","3.03").put("splits",splits));eq(Finance.spend(s,"Other",LocalDate.now(),LocalDate.now().plusDays(1)),202);Ledger.apply(s,"transaction",tx("refund","3.03").put("splits",splits));eq(spending(s),0);});
  test("Unbalanced split rejected",()->rejects(()->Ledger.apply(Ledger.initial(),"transaction",tx("expense","10").put("splits",new JSONArray().put(new JSONObject().put("category","Other").put("amount","9.99"))))));
  test("Shared payment only spends own share; repayment is not income",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("shared","100").put("ownAmount","40").put("person","Alex"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");eq(spending(s),4000);eq(Finance.report(s,LocalDate.now()).getLong("owed"),6000);Ledger.apply(s,"transaction",tx("reimbursement","25").put("reimbursementFor",id));eq(balance(s,"cash"),-7500);eq(spending(s),4000);eq(balance(s,"receivable:"+id),3500);rejects(()->Ledger.apply(s,"transaction",tx("reimbursement","35.01").put("reimbursementFor",id)));});
  test("Cannot remove shared purchase while repayment exists",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("shared","100").put("ownAmount","0").put("person","Alex"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("reimbursement","10").put("reimbursementFor",id));rejects(()->Ledger.apply(s,"void",new JSONObject().put("id",id)));});
  test("Installments distribute remainder without double expense",()->{JSONObject s=Ledger.initial();JSONObject a=new JSONObject().put("key","install").put("name","Laptop").put("account","cash").put("amount","100").put("fee","0.00").put("months",3).put("date","2026-01-31");Ledger.apply(s,"installment",a);eq(spending(s),10000);JSONArray bills=s.getJSONArray("bills");eq(bills.getJSONObject(0).getLong("amount"),3334);eq(bills.getJSONObject(2).getLong("amount"),3333);yes(bills.getJSONObject(1).getString("date").equals("2026-02-28"));yes(bills.getJSONObject(2).getString("date").equals("2026-03-31"));JSONObject b=bills.getJSONObject(0);Ledger.apply(s,"transaction",tx("transfer","33.34").put("to",b.getString("to")).put("billId",b.getString("id")).put("billDate",b.getString("date")));eq(spending(s),10000);Ledger.apply(s,"installment",a);eq(s.getJSONArray("installments").length(),1);});
  test("Salary cycle handles day 31 and leap years",()->{yes(Finance.start(LocalDate.of(2024,2,28),31).equals(LocalDate.of(2024,1,31)));yes(Finance.start(LocalDate.of(2024,2,29),31).equals(LocalDate.of(2024,2,29)));yes(Finance.next(LocalDate.of(2024,2,29),31).equals(LocalDate.of(2024,3,31)));});
  test("Budget rollover preserves previous limits",()->{JSONObject s=Ledger.initial();s.getJSONArray("budgets").put(new JSONObject().put("id","b").put("category","Food & drinks").put("limit",20000).put("rollover",true).put("createdDate","2026-01-01").put("history",new JSONArray().put(new JSONObject().put("start","2026-01-01").put("limit",10000).put("rollover",true)).put(new JSONObject().put("start","2026-02-01").put("limit",20000).put("rollover",true))));Ledger.apply(s,"transaction",tx("expense","25").put("date","2026-01-10"));JSONObject b=Finance.budget(s,s.getJSONArray("budgets").getJSONObject(0),LocalDate.of(2026,2,10));eq(b.getLong("carry"),7500);eq(b.getLong("available"),27500);});
  test("Monthly and yearly calendar schedules",()->{yes(Finance.advance(LocalDate.of(2024,2,29),"yearly",29).equals(LocalDate.of(2025,2,28)));yes(Finance.advance(LocalDate.of(2026,1,28),"weekly",28).equals(LocalDate.of(2026,2,4)));});
  test("Match existing bill without posting again",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"bill",bill("2026-01-31","monthly"));Ledger.apply(s,"transaction",tx("expense","21"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"matchBill",new JSONObject().put("id","bill").put("transactionId",id));eq(s.getJSONArray("transactions").length(),1);yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-02-28"));rejects(()->Ledger.apply(s,"matchBill",new JSONObject().put("id","bill").put("transactionId",id)));});
  test("Edit bill payment without advancing schedule twice",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"bill",bill("2026-01-31","monthly"));Ledger.apply(s,"transaction",tx("expense","20").put("billId","bill").put("billDate","2026-01-31"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("expense","21").put("editId",id).put("billId","bill").put("billDate","2026-01-31"));eq(spending(s),2100);yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-02-28"));});
  test("Reconciliation uses equity, not income",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"reconcile",new JSONObject().put("account","cash").put("date",LocalDate.now().toString()).put("actual","150.01").put("adjust",true));eq(balance(s,"cash"),15001);eq(spending(s),0);yes(s.getJSONArray("reconciliations").getJSONObject(0).getBoolean("adjusted"));});
  test("Rule priority and source constraint",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"rule",new JSONObject().put("match","cafe").put("category","Other").put("priority",1));Ledger.apply(s,"rule",new JSONObject().put("match","cafe").put("category","Food & drinks").put("priority",20).put("source","bank"));JSONObject d=new JSONObject().put("merchant","ABC Cafe").put("package","bank");Finance.suggest(s,d);yes(d.getString("category").equals("Food & drinks"));});
  test("Stale draft revision rejected",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"draft",new JSONObject().put("id","draft").put("merchant","Cafe").put("amount",100));rejects(()->Ledger.apply(s,"transaction",tx("expense","1").put("draftId","draft").put("draftRevision",2)));});
  test("CSV quoted delimiters, newline and escaped quote",()->{List<List<String>> rows=CsvImport.parse("date,amount,merchant\r\n2026-10-01,12.50,\"Cafe, \"\"A\"\"\nBranch\"\r\n");yes(rows.get(1).get(2).equals("Cafe, \"A\"\nBranch"));});
  test("CSV preview exact amount and invalid row",()->{JSONArray rows=CsvImport.preview("Date,Amount,Merchant\n2026-10-01,12.50,Cafe\n2026-02-30,2,Cafe",imported(),Ledger.initial());yes(rows.getJSONObject(0).getString("amount").equals("12.50"));yes(rows.getJSONObject(1).has("error"));});
  test("CSV strong dedup and import undo preserve corrected record",()->{JSONObject s=Ledger.initial();String file="Date,Amount,Merchant\n2026-10-01,12.50,Cafe\n2026-10-02,9.20,Shop";JSONArray rows=CsvImport.preview(file,imported(),s);Ledger.apply(s,"importCommit",new JSONObject().put("batch","batch").put("rows",rows));JSONArray preview=CsvImport.preview(file,imported(),s);yes(preview.getJSONObject(0).getBoolean("duplicate"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"transaction",tx("expense","13").put("editId",id));Ledger.apply(s,"undoImport",new JSONObject().put("id","batch"));eq(spending(s),1300);});
  test("Equal amount from different CSV is a suggestion, not a hard duplicate",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","12.50").put("date","2026-10-01"));JSONObject row=CsvImport.preview("Date,Amount,Merchant\n2026-10-01,12.50,Cafe",imported(),s).getJSONObject(0);yes(row.getBoolean("possibleDuplicate"));yes(!row.optBoolean("duplicate"));});
  test("OCR total beats subtotal and change; zero ignored",()->{JSONObject d=ReceiptParser.parse("ABC Cafe\n08/10/2026\nSubtotal RM18.00\nGrand Total RM19.08\nChange RM0.00");eq(d.getLong("amount"),1908);yes(d.getString("date").equals("2026-10-08"));});
  test("OCR ambiguous totals require review",()->eq(ReceiptParser.parse("Cafe\nTotal 10.00\nTotal 12.00").getLong("amount"),0));
  test("OCR Chinese total and empty image",()->{eq(ReceiptParser.parse("餐厅\n合计 RM20.50").getLong("amount"),2050);eq(ReceiptParser.parse("").getLong("amount"),0);});
  test("Encrypted archive includes receipt, rules and schema migration",()->{JSONObject s=Ledger.initial();byte[] receipt=new byte[]{1,2,3,4};String id=BackupArchive.hex(MessageDigest.getInstance("SHA-256").digest(receipt));s.getJSONArray("receipts").put(new JSONObject().put("id",id));Ledger.apply(s,"transaction",tx("expense","1").put("receiptId",id));Map<String,byte[]> files=new LinkedHashMap<>();files.put(id,receipt);byte[] archive=BackupArchive.pack(s,files);byte[] encrypted=Crypto.backup(archive,"test password 123".toCharArray());BackupArchive.Contents c=BackupArchive.unpack(Crypto.restore(encrypted,"test password 123".toCharArray()));eq(c.state.getJSONArray("transactions").length(),1);yes(Arrays.equals(receipt,c.receipts.get(id)));});
  test("Archive rejects traversal and missing referenced receipt",()->{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("../state.json"));zip.write("{}".getBytes());zip.closeEntry();}rejects(()->BackupArchive.unpack(out.toByteArray()));JSONObject s=Ledger.initial();s.getJSONArray("receipts").put(new JSONObject().put("id","missing"));rejects(()->BackupArchive.unpack(BackupArchive.pack(s,new HashMap<>())));});
  test("Scheduled backup key format restores with original password",()->{char[] p="my scheduled password".toCharArray();byte[] salt=Crypto.random(16),plain="archive content".getBytes();byte[] blob=Crypto.backupWithKey(plain,salt,Crypto.derive(p,salt).getEncoded());yes(Arrays.equals(plain,Crypto.restore(blob,p)));});
  test("Available estimate reserves debt once",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Bank").put("opening","1000").put("type","bank"));String bank=s.getJSONArray("accounts").getJSONObject(1).getString("id");Ledger.apply(s,"account",new JSONObject().put("name","Card").put("opening","100").put("type","card"));String card=s.getJSONArray("accounts").getJSONObject(2).getString("id");Ledger.apply(s,"bill",bill(LocalDate.now().toString(),"once").put("account",bank).put("kind","transfer").put("to",card).put("amount","100"));eq(Finance.report(s,LocalDate.now()).getLong("availableToSpend"),90000);});
  test("Subscription suggestion requires three matching regular payments",()->{JSONObject s=Ledger.initial();for(String date:new String[]{"2026-01-01","2026-02-01","2026-03-01"})Ledger.apply(s,"transaction",tx("expense","9.99").put("date",date).put("merchant","Stream"));JSONArray sub=Finance.subscriptions(s);eq(sub.length(),1);yes(sub.getJSONObject(0).getString("date").equals("2026-04-01"));});
  test("Void and undo latest bill payment reopen and close occurrence",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"bill",bill("2026-01-31","monthly"));Ledger.apply(s,"transaction",tx("expense","20").put("billId","bill").put("billDate","2026-01-31"));String id=s.getJSONArray("transactions").getJSONObject(0).getString("id");Ledger.apply(s,"void",new JSONObject().put("id",id));yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-01-31"));Ledger.apply(s,"void",new JSONObject().put("id",id).put("restore",true));yes(s.getJSONArray("bills").getJSONObject(0).getString("date").equals("2026-02-28"));});
  test("Rule changes can be undone",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"rule",new JSONObject().put("match","Cafe").put("category","Other"));eq(s.getJSONArray("rules").length(),1);Ledger.apply(s,"undoRule",new JSONObject());eq(s.getJSONArray("rules").length(),0);});
  test("Foreign fee has a separate expense posting and decimal rate",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","45").put("originalCurrency","USD").put("originalAmount","10").put("foreignFee","1"));eq(spending(s),4500);eq(Finance.spend(s,"Fees & charges",LocalDate.now(),LocalDate.now().plusDays(1)),100);yes(s.getJSONArray("transactions").getJSONObject(0).getString("exchangeRate").equals("4.40000000"));});
  test("TNG format and toll notifications parse merchant, amount, category and subcategory",()->{JSONObject d1=PaymentParser.parse("You have paid RM12.50 at RESTORAN ABC (PJ).","my.com.tngdigital.ewallet");eq(d1.getLong("amount"),1250);yes(d1.getString("merchant").equals("RESTORAN ABC (PJ)"));yes(d1.getString("category").equals("Food & drinks"));yes(d1.getString("subcategory").equals("Restaurant & Cafe"));JSONObject d2=PaymentParser.parse("PayDirect: RM2.50 deducted at LDP TOLL PLAZA","my.com.tngdigital.ewallet");eq(d2.getLong("amount"),250);yes(d2.getString("merchant").equals("LDP TOLL PLAZA"));yes(d2.getString("category").equals("Transport"));yes(d2.getString("subcategory").equals("Toll (TNG / RFID / PayDirect)"));});
  test("Security footers, verb-less notifications, BNPL and PTPTN parse and classify accurately",()->{JSONObject tng=PaymentParser.parse("You have paid RM2.50 at DUKE TOLL. Never share your OTP/TAC with anyone.","my.com.tngdigital.ewallet");yes(tng!=null);eq(tng.getLong("amount"),250);yes(tng.getString("merchant").equals("DUKE TOLL"));yes(tng.getString("category").equals("Transport"));yes(tng.getString("subcategory").equals("Toll (TNG / RFID / PayDirect)"));JSONObject mae=PaymentParser.parse("RM 15.50 at RESTORAN PELITA","com.maybank2u.life");yes(mae!=null);eq(mae.getLong("amount"),1550);yes(mae.getString("merchant").equals("RESTORAN PELITA"));JSONObject c=PaymentParser.parse("You have authorised a payment of MYR 32.00 to GRABPAY. Available balance: RM 300.00","com.cimb.octomy");eq(c.getLong("amount"),3200);eq(c.getLong("primaryAmount"),3200);yes(c.getString("merchant").equals("GRABPAY"));JSONObject sp=PaymentParser.parse("SPayLater: Payment of RM45.00 at SHOPEE MY. Remaining credit limit: RM1,200.00. Cashback earned: RM0.50","com.shopee.my");yes(sp!=null);eq(sp.getLong("amount"),4500);yes(sp.getString("category").equals("Loans, BNPL & Credit"));yes(sp.getString("subcategory").equals("BNPL (SPayLater / GrabPayLater / Atome)"));JSONObject pt=PaymentParser.parse("RM 150.00 - PTPTN BAYARAN BALIK PINJAMAN. Do not share your TAC.","com.cimb.octomy");yes(pt!=null);eq(pt.getLong("amount"),15000);yes(pt.getString("category").equals("Loans, BNPL & Credit"));yes(pt.getString("subcategory").equals("PTPTN Study Loan"));});
  test("Auto-record unseen merchant into Miscellaneous and repeat toll with history",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","TNG eWallet").put("type","wallet").put("opening","100"));String tng=s.getJSONArray("accounts").getJSONObject(1).getString("id");JSONObject r0=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","k0","You have paid RM9.90 at KEDAI SERBANEKA XYZ",50000L);yes(r0!=null&&r0.optBoolean("autoRecorded"));JSONObject t0=Ledger.find(s.getJSONArray("transactions"),r0.getString("transactionId"));yes(t0.getString("category").equals("Miscellaneous"));yes(t0.getString("subcategory").equals("Auto-Captured (Uncategorized)"));JSONObject r1=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","k1","You have paid RM2.50 at DUKE TOLL",100000L);yes(r1!=null&&r1.optBoolean("autoRecorded"));JSONObject t1=Ledger.find(s.getJSONArray("transactions"),r1.getString("transactionId"));yes(t1.getString("category").equals("Transport"));yes(t1.getString("subcategory").equals("Toll (TNG / RFID / PayDirect)"));yes(t1.getString("account").equals(tng));yes(t1.optBoolean("needsNote"));Ledger.apply(s,"quickNote",new JSONObject().put("id",t1.getString("id")).put("note","Morning commute toll").put("category","Transport").put("subcategory","Toll (TNG / RFID / PayDirect)").put("applyHistory",true));yes(t1.getString("category").equals("Transport"));eq(Finance.spend(s,"Transport",LocalDate.now(),LocalDate.now().plusDays(1)),250);JSONObject r2=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","k1","You have paid RM2.50 at DUKE TOLL",170000L);yes(r2!=null&&r2.optBoolean("autoRecorded"));JSONObject t2=Ledger.find(s.getJSONArray("transactions"),r2.getString("transactionId"));yes(t2.getString("category").equals("Transport"));yes(t2.getString("note").equals("Morning commute toll"));yes(!t2.optBoolean("needsNote"));JSONObject r3=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","k1","You have paid RM3.80 at DUKE TOLL",240000L);yes(r3!=null&&r3.optBoolean("autoRecorded"));JSONObject t3=Ledger.find(s.getJSONArray("transactions"),r3.getString("transactionId"));yes(t3.getString("category").equals("Transport"));yes(t3.optBoolean("needsNote"));});
  test("Retroactive merchant history update updates all previous transactions and postings",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",tx("expense","8.50").put("category","Miscellaneous").put("merchant","FAMILYMART MY"));Ledger.apply(s,"transaction",tx("expense","14.20").put("category","Miscellaneous").put("merchant","FAMILYMART MY"));String secondId=s.getJSONArray("transactions").getJSONObject(1).getString("id");Ledger.apply(s,"quickNote",new JSONObject().put("id",secondId).put("note","Snacks").put("category","Food & drinks").put("subcategory","Groceries & Convenience Store").put("applyHistory",true));eq(Finance.spend(s,"Food & drinks",LocalDate.now(),LocalDate.now().plusDays(1)),2270);eq(Finance.spend(s,"Miscellaneous",LocalDate.now(),LocalDate.now().plusDays(1)),0);eq(s.getJSONArray("rules").length(),1);Ledger.validate(s);});
  test("Categories and subcategories can be added and removed with 1 tap",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"category",new JSONObject().put("name","Pets & Vet").put("icon","heart").put("color","peach"));eq(s.getJSONArray("customCategories").length(),1);Ledger.apply(s,"addSubcategory",new JSONObject().put("category","Pets & Vet").put("subcategory","Cat Food"));yes(s.getJSONObject("settings").getJSONObject("customSubcategories").getJSONArray("Pets & Vet").getString(0).equals("Cat Food"));Ledger.apply(s,"removeSubcategory",new JSONObject().put("category","Pets & Vet").put("subcategory","Cat Food"));eq(s.getJSONObject("settings").getJSONObject("customSubcategories").getJSONArray("Pets & Vet").length(),0);yes(s.getJSONObject("settings").getJSONObject("hiddenSubcategories").getJSONArray("Pets & Vet").getString(0).equals("Cat Food"));Ledger.apply(s,"removeCategory",new JSONObject().put("name","Pets & Vet"));eq(s.getJSONArray("customCategories").length(),0);yes(s.getJSONObject("settings").getJSONArray("hiddenCategories").getString(0).equals("Pets & Vet"));});
  test("Malaysia LHDN progressive tax estimator calculates reliefs, rebates and PCB balance",()->{JSONObject s=Ledger.initial();int yr=LocalDate.now().getYear();Ledger.apply(s,"transaction",tx("expense","800.00").put("date",yr+"-03-15").put("category","Health & medical").put("subcategory","Full Medical Checkup (LHDN)").put("merchant","KPJ Hospital"));Ledger.apply(s,"transaction",tx("expense","200.00").put("date",yr+"-04-10").put("category","Family & gifts").put("subcategory","Zakat & Fitrah").put("merchant","PPZ Zakat"));Ledger.apply(s,"taxProfile",new JSONObject().put("monthlyBaseSalary","6000").put("annualBonus","6000").put("epfRate","11").put("monthlyPcb","200").put("lifestyle","2000"));JSONObject est=Finance.calculateTax(s,null,yr);eq(est.getLong("annualGross"),78000_00L);eq(est.getJSONObject("ledgerDetected").getLong("medical"),800_00L);eq(est.getJSONObject("ledgerDetected").getLong("zakat"),200_00L);yes(est.getLong("chargeableIncome")>0&&est.getLong("netTaxPayable")>0);eq(est.getLong("zakatRebate"),200_00L);eq(est.getLong("annualPcbPaid"),2400_00L);});
  test("BNPL and PTPTN reminders support custom everyXDays interval, timeslots and advance warning",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"bill",new JSONObject().put("id","spay").put("name","SPayLater").put("amount","120.50").put("account","cash").put("date","2026-10-01").put("category","Loans, BNPL & Credit").put("subcategory","BNPL (SPayLater / GrabPayLater / Atome)").put("frequency","everyXDays").put("intervalDays",15).put("remindDaysBefore",3).put("remindTime","09:30").put("reminderType","bnpl"));JSONObject b=s.getJSONArray("bills").getJSONObject(0);eq(b.getInt("remindDaysBefore"),3);yes(b.getString("remindTime").equals("09:30"));yes(b.getString("reminderType").equals("bnpl"));Ledger.apply(s,"snoozeBill",new JSONObject().put("id","spay").put("durationMs",3600_000L));yes(b.getLong("snoozeUntil")>System.currentTimeMillis());Ledger.apply(s,"dismissBillCycle",new JSONObject().put("id","spay"));yes(b.getString("dismissedCycle").equals("2026-10-01"));Ledger.apply(s,"transaction",tx("expense","120.50").put("billId","spay").put("billDate","2026-10-01"));yes(b.getString("date").equals("2026-10-16"));});
  test("DuitNow QR, FPX, JomPAY and GXBank notifications parse merchant and category",()->{JSONObject dn=PaymentParser.parse("DuitNow QR payment of RM 14.50 to GERAI NASI LEMAK ANTARABANGSA was successful.","com.maybank2u.life");yes(dn!=null);eq(dn.getLong("amount"),1450);yes(dn.getString("kind").equals("expense"));yes(dn.getString("merchant").equals("GERAI NASI LEMAK ANTARABANGSA"));yes(dn.getString("category").equals("Food & drinks"));JSONObject jom=PaymentParser.parse("JomPAY payment of RM 185.20 to TENAGA NASIONAL (Biller Code: 5454) is successful.","com.cimb.octomy");yes(jom!=null);eq(jom.getLong("amount"),18520);yes(jom.getString("merchant").equals("TENAGA NASIONAL"));yes(jom.getString("category").equals("Bills & utilities"));yes(jom.getString("subcategory").equals("Electricity (TNB)"));JSONObject fpx=PaymentParser.parse("FPX payment of MYR 89.00. Seller: POPULAR BOOK CO. Never share your OTP.","com.rhbgroup.rhbmobilebanking");yes(fpx!=null);eq(fpx.getLong("amount"),8900);yes(fpx.getString("merchant").equals("POPULAR BOOK CO"));yes(fpx.getString("category").equals("Education"));});
  test("Auto-captured payment auto-matches scheduled PTPTN or BNPL bill and advances due date",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Maybank").put("type","bank").put("opening","2000"));String mbbId=s.getJSONArray("accounts").getJSONObject(1).getString("id");String dueToday=LocalDate.now().toString();Ledger.apply(s,"bill",new JSONObject().put("id","ptptn-bill").put("name","PTPTN Study Loan Repayment").put("amount","150.00").put("account",mbbId).put("date",dueToday).put("category","Loans, BNPL & Credit").put("subcategory","PTPTN Study Loan").put("frequency","monthly").put("reminderType","ptptn"));JSONObject cap=Extras.captureState(s,"com.maybank2u.life","Maybank MAE","k-ptptn","You have paid RM 150.00 to PTPTN BAYARAN BALIK PINJAMAN.",50000L);yes(cap!=null&&cap.optBoolean("autoRecorded"));yes(cap.optString("matchedBillId").equals("ptptn-bill"));JSONObject txRow=Ledger.find(s.getJSONArray("transactions"),cap.getString("transactionId"));yes(txRow.getString("account").equals(mbbId));yes(txRow.getString("billId").equals("ptptn-bill"));JSONObject updatedBill=Ledger.find(s.getJSONArray("bills"),"ptptn-bill");yes(!updatedBill.getString("date").equals(dueToday));});
  test("1-tap payBillAuto and LHDN monthly paycheck + claimable audit list",()->{JSONObject s=Ledger.initial();String due=LocalDate.now().toString();Ledger.apply(s,"bill",new JSONObject().put("id","b-spay").put("name","SPayLater").put("amount","75.00").put("account","cash").put("date",due).put("category","Loans, BNPL & Credit").put("subcategory","BNPL (SPayLater / GrabPayLater / Atome)").put("frequency","everyXDays").put("intervalDays",30));Ledger.apply(s,"payBillAuto",new JSONObject().put("id","b-spay"));JSONObject b=Ledger.find(s.getJSONArray("bills"),"b-spay");yes(b.getString("date").equals(LocalDate.now().plusDays(30).toString()));int yr=LocalDate.now().getYear();Ledger.apply(s,"transaction",tx("expense","199.00").put("date",yr+"-05-10").put("category","Health & medical").put("subcategory","Gym, Sports & Fitness").put("merchant","DECATHLON MY"));Ledger.apply(s,"taxProfile",new JSONObject().put("monthlyBaseSalary","5000").put("epfRate","11").put("monthlyPcb","100"));JSONObject est=Finance.calculateTax(s,null,yr);eq(est.getJSONObject("monthlyPaycheck").getLong("grossMonthly"),5000_00L);eq(est.getJSONObject("monthlyPaycheck").getLong("epfMonthly"),550_00L);yes(est.getJSONObject("monthlyPaycheck").getLong("netTakeHomeMonthly")>4000_00L);yes(est.getJSONArray("claimableItems").length()>=1);});
  test("Cross-source twin alerts are merged once; Not a duplicate records it",()->{JSONObject s=Ledger.initial();JSONObject a=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","k-a","You have paid RM23.40 at MYNEWS KLCC",1_000_000L);yes(a!=null&&a.optBoolean("autoRecorded"));JSONObject b=Extras.captureState(s,"com.maybank2u.life","Maybank MAE","k-b","Card transaction RM23.40 at TNG DIGITAL approved",1_090_000L);yes(b!=null);yes(b.optString("duplicateOf").equals(a.getString("transactionId")));yes(!b.optBoolean("autoRecorded"));eq(spending(s),2340);JSONObject c=Extras.captureState(s,"com.cimb.octomy","CIMB","k-c","You have paid RM23.40 at MYNEWS KLCC",1_500_000L);yes(c!=null&&c.optBoolean("autoRecorded"));eq(spending(s),4680);Ledger.apply(s,"unmergeDuplicate",new JSONObject().put("id",b.getString("id")));eq(spending(s),7020);Ledger.validate(s);});
  test("Bill auto-match requires matching amount unless variable; unlinkBill restores due date",()->{JSONObject s=Ledger.initial();String due=LocalDate.now().toString();Ledger.apply(s,"bill",new JSONObject().put("id","tnb").put("name","TNB").put("amount","120.00").put("account","cash").put("date",due).put("category","Bills & utilities").put("subcategory","Electricity (TNB)").put("frequency","monthly"));JSONObject miss=Extras.captureState(s,"com.maybank2u.life","Maybank","k1","JomPAY payment of RM 15.00 to TENAGA NASIONAL is successful.",10_000L);yes(!miss.has("matchedBillId"));JSONObject hit=Extras.captureState(s,"com.maybank2u.life","Maybank","k2","JomPAY payment of RM 120.00 to TENAGA NASIONAL is successful.",900_000L);yes(hit.optString("matchedBillId").equals("tnb"));JSONObject bill=Ledger.find(s.getJSONArray("bills"),"tnb");yes(!bill.getString("date").equals(due));Ledger.apply(s,"unlinkBill",new JSONObject().put("id",hit.getString("transactionId")));yes(bill.getString("date").equals(due));yes(!Ledger.find(s.getJSONArray("transactions"),hit.getString("transactionId")).has("billId"));eq(spending(s),13500);});
  test("TNG reload from bank is a transfer, not spending, and both alerts fold into one record",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Maybank").put("type","bank").put("opening","500"));Ledger.apply(s,"account",new JSONObject().put("name","TNG eWallet").put("type","wallet").put("opening","10"));String mbb=s.getJSONArray("accounts").getJSONObject(1).getString("id"),tng=s.getJSONArray("accounts").getJSONObject(2).getString("id");JSONObject w=Extras.captureState(s,"my.com.tngdigital.ewallet","TNG eWallet","r1","Reload of RM50.00 is successful.",2_000_000L);yes(w!=null&&!w.optBoolean("autoRecorded"));yes(w.getString("state").equals("review"));JSONObject b=Extras.captureState(s,"com.maybank2u.life","Maybank MAE","r2","You have transferred RM50.00 to TNG eWallet.",2_060_000L);yes(b!=null&&b.optBoolean("autoRecorded"));JSONObject t=Ledger.find(s.getJSONArray("transactions"),b.getString("transactionId"));yes(t.getString("kind").equals("transfer"));yes(t.getString("account").equals(mbb));yes(t.getString("to").equals(tng));eq(spending(s),0);eq(Finance.balance(s,tng,"9999-12-31"),6000);yes(Ledger.find(s.getJSONArray("drafts"),w.getString("id")).getString("state").equals("linked"));});
  test("Debt planner compares Avalanche vs Snowball for BNPL, flat PTPTN Ujrah and card, and syncs reminder",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"debt",new JSONObject().put("id","bnpl").put("name","SPayLater").put("type","bnpl").put("balance","600.00").put("minPayment","100.00").put("apr","0").put("rateType","reducing").put("dueDay",10));Ledger.apply(s,"debt",new JSONObject().put("id","card").put("name","Credit Card").put("type","credit").put("balance","2000.00").put("minPayment","100.00").put("apr","18").put("rateType","reducing").put("dueDay",15));Ledger.apply(s,"debt",new JSONObject().put("id","ptptn").put("name","PTPTN Ujrah").put("type","ptptn").put("balance","1200.00").put("principal","1200.00").put("minPayment","100.00").put("apr","1.00").put("rateType","flat").put("dueDay",25));Ledger.apply(s,"debtExtra",new JSONObject().put("extraMonthly","300.00"));JSONObject dp=Finance.debtPlan(s,LocalDate.of(2026,1,1));eq(dp.getLong("totalBalance"),3800_00L);eq(dp.getLong("monthlyBudget"),600_00L);JSONObject av=dp.getJSONObject("avalanche"),sb=dp.getJSONObject("snowball");yes(av.getLong("totalInterest")<sb.getLong("totalInterest"));yes(dp.getLong("interestSavedByAvalanche")>0);yes(av.getJSONArray("order").getJSONObject(0).getString("id").equals("card"));yes(sb.getJSONArray("order").getJSONObject(0).getString("id").equals("bnpl"));Ledger.apply(s,"syncDebtReminder",new JSONObject().put("id","ptptn"));JSONObject pt=Ledger.find(s.getJSONArray("debts"),"ptptn");yes(pt.has("billId"));JSONObject bill=Ledger.find(s.getJSONArray("bills"),pt.getString("billId"));yes(bill.getString("reminderType").equals("ptptn"));Ledger.apply(s,"deletePlan",new JSONObject().put("table","debts").put("id","bnpl"));eq(s.getJSONArray("debts").length(),2);});
  test("Recurring income detection sets payday + cycleDay and 30-day forecast warns on shortfall before payday",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Maybank").put("type","bank").put("opening","400"));String mbb=s.getJSONArray("accounts").getJSONObject(1).getString("id");for(String dt:new String[]{"2026-01-25","2026-02-25","2026-03-25"})Ledger.apply(s,"transaction",new JSONObject().put("key",Ledger.id()).put("kind","income").put("amount","5000.00").put("account",mbb).put("category","Salary").put("merchant","ACME SDN BHD").put("date",dt));Ledger.apply(s,"transaction",new JSONObject().put("key",Ledger.id()).put("kind","expense").put("amount","14800.00").put("account",mbb).put("category","Housing & property").put("merchant","Prior spend").put("date","2026-03-30"));JSONArray inc=Finance.recurringIncome(s,LocalDate.of(2026,4,1));eq(inc.length(),1);JSONObject cand=inc.getJSONObject(0);eq(cand.getInt("dayOfMonth"),25);Ledger.apply(s,"setPayday",new JSONObject().put("name",cand.getString("name")).put("amountMinor",cand.getLong("amount")).put("account",cand.getString("account")).put("frequency",cand.getString("frequency")).put("dayOfMonth",cand.getInt("dayOfMonth")).put("nextDate","2026-04-25"));eq(s.getJSONObject("settings").getInt("cycleDay"),25);Ledger.apply(s,"bill",new JSONObject().put("name","Rent").put("amount","1200.00").put("account",mbb).put("date","2026-04-10").put("frequency","monthly"));JSONObject fc=Finance.forecast(s,LocalDate.of(2026,4,1),30);eq(fc.getLong("startingBalance"),200_00L);yes(fc.getBoolean("hasNegativeWarning"));yes(fc.getString("firstNegativeDate").equals("2026-04-10"));eq(fc.getLong("lowestBalance"),-1000_00L);eq(fc.getLong("endingBalance"),4000_00L);eq(fc.getLong("totalInflows"),5000_00L);eq(fc.getLong("totalOutflows"),1200_00L);eq(fc.getJSONArray("daily").length(),31);});
  test("Subcategory budgets isolate spend, track elapsed pace and generate weekly nudge",()->{JSONObject s=Ledger.initial();LocalDate on=LocalDate.of(2026,5,5);Ledger.apply(s,"budget",new JSONObject().put("id","b-all").put("category","Food & drinks").put("amount","500.00"));Ledger.apply(s,"budget",new JSONObject().put("id","b-sub").put("category","Food & drinks").put("subcategory","Hawker & Mamak").put("amount","100.00"));Ledger.apply(s,"transaction",tx("expense","60.00").put("category","Food & drinks").put("subcategory","Hawker & Mamak").put("date","2026-05-03"));Ledger.apply(s,"transaction",tx("expense","40.00").put("category","Food & drinks").put("subcategory","Restaurant & Cafe").put("date","2026-05-04"));JSONObject rSub=Finance.budget(s,Ledger.find(s.getJSONArray("budgets"),"b-sub"),on);eq(rSub.getLong("spent"),60_00L);eq(rSub.getLong("remaining"),40_00L);eq(rSub.getInt("spentPct"),60);yes(rSub.getString("paceStatus").equals("fast"));JSONObject rAll=Finance.budget(s,Ledger.find(s.getJSONArray("budgets"),"b-all"),on);eq(rAll.getLong("spent"),100_00L);JSONObject rep=Finance.report(s,on);eq(rep.getLong("dailyAllowance"),400_00L/java.time.temporal.ChronoUnit.DAYS.between(on,LocalDate.of(2026,6,1)));JSONObject nudge=Finance.weeklyBudgetNudge(s,on);yes(nudge!=null&&nudge.getString("subcategory").equals("Hawker & Mamak"));yes(nudge.getString("body").contains("ahead of pace"));});
  test("Cycle recap summarizes savings rate, top categories, top merchants and LHDN claimables",()->{JSONObject s=Ledger.initial();Ledger.apply(s,"transaction",new JSONObject().put("key",Ledger.id()).put("kind","income").put("amount","5000.00").put("account","cash").put("category","Salary").put("merchant","Employer").put("date","2026-06-05"));Ledger.apply(s,"transaction",tx("expense","1200.00").put("category","Food & drinks").put("merchant","Village Grocer").put("date","2026-06-08"));Ledger.apply(s,"transaction",tx("expense","300.00").put("category","Health & medical").put("subcategory","Pharmacy & Medicine").put("merchant","Watsons").put("date","2026-06-12"));JSONObject rc=Finance.cycleRecap(s,LocalDate.of(2026,6,15));eq(rc.getLong("totalIncome"),5000_00L);eq(rc.getLong("totalSpent"),1500_00L);eq(rc.getLong("netSaved"),3500_00L);eq(rc.getInt("savingsRatePct"),70);yes(rc.getJSONArray("topCategories").getJSONObject(0).getString("category").equals("Food & drinks"));yes(rc.getJSONArray("topMerchants").getJSONObject(0).getString("merchant").equals("Village Grocer"));yes(rc.getInt("claimableCount")>=1);});
  test("Bahasa Melayu setting localizes weekly budget nudge and rejects unsupported language",()->{JSONObject s=Ledger.initial();yes(s.getJSONObject("settings").getString("lang").equals("en"));rejects(()->Ledger.apply(s,"settings",new JSONObject().put("lang","fr")));Ledger.apply(s,"settings",new JSONObject().put("lang","ms"));yes(s.getJSONObject("settings").getString("lang").equals("ms"));LocalDate on=LocalDate.of(2026,5,5);Ledger.apply(s,"budget",new JSONObject().put("id","b-ms").put("category","Food & drinks").put("amount","100.00"));Ledger.apply(s,"transaction",tx("expense","60.00").put("category","Food & drinks").put("date","2026-05-03"));JSONObject nudge=Finance.weeklyBudgetNudge(s,on);yes(nudge!=null&&nudge.getString("title").equals("Semakan kadar bajet mingguan")&&nudge.getString("body").contains("lebih cepat daripada kadar"));});
  test("OCR header noise filtering, split-line amount, 5-sen rounding and item classification",()->{
   JSONObject r1=ReceiptParser.parse("SIMPLIFIED TAX INVOICE\nLOT 12 JALAN MERU\n41050 KLANG SELANGOR\nTEL: 03-3362 6863\n99 SPEEDMART SDN BHD (519537-X)\n15 Oct 2026\nSubtotal RM 12.48\nTOTAL RM 12.48\nRounding Adj +0.02\nTOTAL AFTER ROUNDING RM 12.50\nCash RM 50.00\nChange RM 37.50");
   yes(r1.getString("merchant").equals("99 SPEEDMART"));
   eq(r1.getLong("amount"),1250);
   yes(r1.getString("date").equals("2026-10-15"));
   yes(r1.getString("category").equals("Groceries"));
   yes(r1.getString("subcategory").equals("Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"));
   JSONObject r2=ReceiptParser.parse("TAX INVOICE\nSYARIKAT PERNIAGAAN CAHAYA SDN BHD\nDate: 20/10/2026\nPANADOL ACTIFAST 20S  15.90\nVITAMIN C 30.00\nTOTAL PAYABLE (RM)\n45.90");
   yes(r2.getString("merchant").equals("SYARIKAT PERNIAGAAN CAHAYA"));
   eq(r2.getLong("amount"),4590);
   yes(r2.getString("date").equals("2026-10-20"));
   yes(r2.getString("category").equals("Health & medical"));
   yes(r2.getString("subcategory").equals("Pharmacy & Medicine"));
   JSONObject r3=ReceiptParser.parse("Kopi Kiosk\nTotal RM12.48\nRounding Adj 0.02\nTotal RM12.50");
   eq(r3.getLong("amount"),1250);
  });
  test("PaymentParser gateway stripping, SSM normalization and Malaysian taxonomy",()->{
   JSONObject tnb=PaymentParser.parse("Paid RM88.00 to FPX*TENAGA NASIONAL BERHAD (419026-U) via DuitNow","Bank");
   yes(tnb.getString("merchant").equals("TENAGA NASIONAL"));
   yes(tnb.getString("category").equals("Bills & utilities")&&tnb.getString("subcategory").equals("Electricity (TNB)"));
   JSONObject kopi=PaymentParser.parse("Payment of RM15.90 at IPAY88 * ORIENTAL KOPI SDN BHD successful","Wallet");
   yes(kopi.getString("merchant").equals("ORIENTAL KOPI"));
   yes(kopi.getString("category").equals("Food & drinks")&&kopi.getString("subcategory").equals("Hawker & Mamak"));
   JSONObject diy=PaymentParser.parse("You have paid RM32.50 at MR. DIY (M) SDN BHD","TNG");
   yes(diy.getString("merchant").equals("MR. DIY (M)"));
   yes(diy.getString("category").equals("Shopping")&&diy.getString("subcategory").equals("Home & Living"));
   JSONObject mac=PaymentParser.parse("Paid RM2999.00 at MACHINES SDN BHD","Bank");
   yes(mac.getString("category").equals("Shopping")&&mac.getString("subcategory").equals("Electronics & Gadgets"));
   JSONObject lab=PaymentParser.parse("Paid RM180.00 at PATHLAB HEALTH","Bank");
   yes(lab.getString("category").equals("Health & medical")&&lab.getString("subcategory").equals("Full Medical Checkup (LHDN)"));
   JSONObject ev=PaymentParser.parse("Paid RM24.00 at JOMCHARGE EV","Wallet");
   yes(ev.getString("category").equals("Transport")&&ev.getString("subcategory").equals("Petrol & EV Charging"));
  });
  test("CsvImport auto-classifies uncategorized rows via rules, ledger history and PaymentParser",()->{
   JSONObject s=Ledger.initial();
   Ledger.apply(s,"transaction",tx("expense","42.00").put("merchant","Special Bistro").put("category","Entertainment & travel").put("subcategory","Movies, Concerts & Events"));
   Ledger.apply(s,"rule",new JSONObject().put("match","grabfood").put("category","Family & gifts").put("subcategory","Childcare & Family Support").put("priority",25));
   JSONObject map=imported().put("note",3);
   String csv="Date,Amount,Merchant,Note\n2026-10-01,42.00,Special Bistro,\n2026-10-02,19.50,GrabFood Malaysia,\n2026-10-03,14.80,FPX*99 SPEEDMART,\n2026-10-04,129.00,Unknown Corner Stall,Unifi bill payment";
   JSONArray rows=CsvImport.preview(csv,map,s);
   yes(rows.getJSONObject(0).getString("category").equals("Entertainment & travel"));
   yes(rows.getJSONObject(1).getString("category").equals("Family & gifts")&&rows.getJSONObject(1).getString("subcategory").equals("Childcare & Family Support"));
   yes(rows.getJSONObject(2).getString("category").equals("Groceries")&&rows.getJSONObject(2).getString("subcategory").equals("Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)"));
   yes(rows.getJSONObject(3).getString("category").equals("Bills & utilities")&&rows.getJSONObject(3).getString("subcategory").equals("Home Internet & WiFi (Unifi / Time)"));
  });
  test("LHDN relief detection covers Machines, BookXcess, Pathlab, Al-Ikhsan and Gentari EV charging",()->{
   JSONObject s=Ledger.initial();int yr=LocalDate.now().getYear();
   Ledger.apply(s,"transaction",tx("expense","3000.00").put("date",yr+"-02-01").put("category","Shopping").put("subcategory","Electronics & Gadgets").put("merchant","Machines"));
   Ledger.apply(s,"transaction",tx("expense","120.00").put("date",yr+"-02-05").put("category","Education").put("subcategory","Books & Stationery").put("merchant","BookXcess"));
   Ledger.apply(s,"transaction",tx("expense","250.00").put("date",yr+"-02-10").put("category","Health & medical").put("subcategory","Full Medical Checkup (LHDN)").put("merchant","Pathlab"));
   Ledger.apply(s,"transaction",tx("expense","180.00").put("date",yr+"-02-15").put("category","Health & medical").put("subcategory","Gym, Sports & Fitness").put("merchant","Al-Ikhsan"));
   Ledger.apply(s,"transaction",tx("expense","45.00").put("date",yr+"-02-20").put("category","Transport").put("subcategory","Petrol & EV Charging").put("merchant","Gentari"));
   JSONObject det=Finance.calculateTax(s,null,yr).getJSONObject("ledgerDetected");
   eq(det.getLong("lifestyle"),3120_00L);
   eq(det.getLong("medical"),250_00L);
   eq(det.getLong("sports"),180_00L);
   eq(det.getLong("sspnPrs"),45_00L);
  });
  test("Receipt Net Amount extraction, header cleanup and purchase confirmation expense guard",()->{
   JSONObject r1=ReceiptParser.parse("Yi Fang Tea Co\nDate: 02/02/2026\nSubtotal: RM 10.50\nService Tax: RM 0.105\nNet Amount: RM 10.61");
   eq(r1.getLong("amount"),1061);
   yes(r1.getString("merchant").equals("Yi Fang Tea Co"));
   yes(r1.getString("date").equals("2026-02-02"));
   JSONObject r2=ReceiptParser.parse("Nota: Telekom Malaysia\nDate: 11/03/2026\nAmount Due: RM 53.10");
   yes(r2.getString("merchant").equals("Telekom Malaysia"));
   eq(r2.getLong("amount"),5310);
   JSONObject p1=PaymentParser.parse("Payment received from Boost for your purchase at Jaya Grocer\nReference: TXN-20260707\nPaid Amount: RM 15.00","Boost");
   yes(p1!=null);
   yes(p1.getString("kind").equals("expense"));
   yes(p1.getString("merchant").equals("Jaya Grocer"));
   eq(p1.getLong("amount"),1500);
   yes(p1.getString("category").equals("Groceries"));
   JSONObject inc=PaymentParser.parse("DuitNow transfer of RM 250.00 received from Ahmad","Maybank");
   yes(inc!=null&&inc.getString("kind").equals("income")&&inc.getLong("amount")==25000);
  });
  test("MlClassifier sparse TF-IDF fallback classifies unseen SMEs safely and respects confidence threshold",()->{
   String mockModel="{\"version\":1,\"classes\":[\"Transport||Vehicle Maintenance & Service\",\"Education||Books & Stationery\"],\"intercepts\":[0.0,0.0],\"features\":{\"w:tukar\":[2.0,0,[[0,2.5]]],\"w:minyak\":[2.0,0,[[0,2.5]]],\"w:servis\":[2.0,0,[[0,2.5]]],\"c: servis \":[2.0,1,[[0,2.0]]],\"w:buku\":[2.0,0,[[1,2.5]]],\"w:latihan\":[2.0,0,[[1,2.5]]]}}";
   yes(MlClassifier.loadFromStream(new ByteArrayInputStream(mockModel.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
   try{
    MlClassifier.Prediction p=MlClassifier.predict("Syarikat Kejuruteraan Jaya","Tukar minyak enjin dan servis penuh kereta RM180.00");
    yes(p!=null&&p.category.equals("Transport")&&p.subcategory.equals("Vehicle Maintenance & Service")&&p.probability>0.7);
    String[] cls=PaymentParser.classify("Syarikat Kejuruteraan Jaya","Receipt scan","Tukar minyak enjin dan servis penuh kereta RM180.00");
    yes(cls[0].equals("Transport")&&cls[1].equals("Vehicle Maintenance & Service"));
    String[] unk=PaymentParser.classify("KEDAI SERBANEKA XYZ","TNG","paid rm9.90 at kedai serbaneka xyz");
    yes(unk[0].equals("Miscellaneous"));
   }finally{
    MlClassifier.clear();
   }
  });
  test("Malaysian integer RM receipts, Tunai-Baki fallback, BM multi-line notifications and address filtering",()->{
   JSONObject intRm=ReceiptParser.parse("KEDAI RIAH\nLot 8, Jalan Cheras\n22 Jun 2026\nAngpao Raya: RM 100\nCukai Perkhidmatan: RM 10\nJumlah Perlu Dibayar: RM 110\nBayaran Berjaya");
   eq(intRm.getLong("amount"),11000);
   JSONObject bayaran=ReceiptParser.parse("Kedai Pembiayaan Tunai\n20 NOVEMBER 2026\nBayaran RM 1200.00\nTunai: RM 1250.00\nBaki Kembali: RM 50.00");
   eq(bayaran.getLong("amount"),120000);
   JSONObject cashDiff=ReceiptParser.parse("Bengkel AutoZone Sdn Bhd\nNo. 10, Jalan Setia\n10/01/2026\nAngsuran Kenderaan\nTunai: RM 2,300.00\nBaki: RM 0.00");
   eq(cashDiff.getLong("amount"),230000);
   JSONObject sari=ReceiptParser.parse("Sari Garden, Cheras\n4/10/2026\nProduk Kecantikan: RM 40.50\nTunai: RM 50.00\nBaki: RM 9.50");
   eq(sari.getLong("amount"),4050);
   JSONObject addrGuard=ReceiptParser.parse("Electricity Power Sdn Bhd\nNo. 12, Jalan LRT\n47500 Petaling Jaya\n19/08/2026\nElectricity Bill RM 450.00\nGrand Total RM 450.00");
   yes(addrGuard.getString("category").equals("Bills & utilities")&&addrGuard.getString("subcategory").equals("Electricity (TNB)"));
   JSONObject bmMulti=PaymentParser.parse("Bayaran Berjaya: RM 26.50\nPejabat Pasar Sentral\nKod Transaksi: 123","Bank");
   yes(bmMulti!=null&&bmMulti.getString("merchant").equals("Pejabat Pasar Sentral"));
   JSONObject bmLabel=PaymentParser.parse("Bayaran Berjaya\nTarikh: 04 Okt 2026\nBayaran kepada: Salon Anisa, Cheras\nCukai Perkhidmatan: RM 10.00","Bank");
   yes(bmLabel!=null&&bmLabel.getString("merchant").equals("Salon Anisa, Cheras")&&bmLabel.getLong("amount")==1000);
   JSONObject lotSkip=PaymentParser.parse("You have paid RM125.50 to Lot 12, Jalan Pinang, George Town for your purchase at Sinar Cafe.","Bank");
   yes(lotSkip!=null&&lotSkip.getString("merchant").equals("Sinar Cafe"));
   JSONObject set=new JSONObject().put("digest",false);
   JSONObject dr=new JSONObject().put("id","d1");
   yes(Extras.shouldPromptDraft(false,set,dr,true));
   yes(!Extras.shouldPromptDraft(true,set,dr,true));
   yes(!Extras.shouldPromptDraft(false,set,dr,false));
    yes(!Extras.shouldPromptDraft(false,set,new JSONObject().put("id","d2").put("duplicateOf","tx1"),true));
  });
  test("Hierarchical ML, BM receipt dates, Malay alerts, live debt paydown, goal deltas and MY CSV presets",()->{
   JSONObject bmOct=ReceiptParser.parse("KEDAI RUNCIT BAHAGIA\nNo 18, Jalan Bukit\n8 Oktober 2026\nTotal: RM 24.50");
   yes(bmOct.getString("date").equals("2026-10-08"));yes(bmOct.getString("merchant").equals("KEDAI RUNCIT BAHAGIA"));eq(bmOct.getLong("amount"),2450);
   JSONObject bmOgos=ReceiptParser.parse("CAFE KITA\n23 Ogos 2026\nTotal RM 12.00");
   yes(bmOgos.getString("date").equals("2026-08-23"));
   JSONObject spDate=ReceiptParser.parse("Kedai Emas Murni\n24 02 2026\nJumlah: RM 300.00");
   yes(spDate.getString("date").equals("2026-02-24"));
   JSONObject mdDate=ReceiptParser.parse("KLINIK PRIMA\nMay 01, 2026\nAmount Due: RM 65.00");
   yes(mdDate.getString("date").equals("2026-05-01"));
   JSONObject bmNotif=PaymentParser.parse("Notifikasi Pembayaran\nBayaran kepada: Kedai Buku Ilmu\nJumlah: RM 45.00","Bank");
   yes(bmNotif!=null&&bmNotif.getString("merchant").equals("Kedai Buku Ilmu")&&bmNotif.getLong("amount")==4500&&bmNotif.getString("category").equals("Education"));
   JSONObject bmRef=PaymentParser.parse("Nota Pembayaran: #67889\nAnda telah membayar RM 35.00 kepada Kafe Santai pada 05 Oct 2026","Bank");
   yes(bmRef!=null&&bmRef.getString("merchant").equals("Kafe Santai")&&bmRef.getLong("amount")==3500);
   java.nio.file.Path modelPath=java.nio.file.Paths.get("app/src/main/assets/moneylens_classifier.json.gz");
   if(java.nio.file.Files.exists(modelPath)){
    try{
     MlClassifier.loadFromPath(modelPath);
     MlClassifier.Prediction pred=MlClassifier.predict("Klinik Pergigian Harapan","scaling");
     yes(pred.categoryProbability>=pred.probability&&pred.categoryProbability<=1.0001);
    }finally{MlClassifier.clear();}
   }

   JSONObject s=Ledger.initial();
   String acctId=s.getJSONArray("accounts").getJSONObject(0).getString("id");
   Ledger.apply(s,"transaction",new JSONObject().put("key","op1").put("kind","income").put("amount","5000.00").put("account",acctId).put("category","Salary").put("date",LocalDate.now().toString()));
   Extras.apply(s,"debt",new JSONObject().put("id","d_auto").put("name","Auto Pay Loan").put("type","loan").put("balance","1000.00").put("minPayment","200.00").put("apr","12").put("rateType","reducing").put("dueDay",15).put("account",acctId).put("createReminder",true));
   String billId=Ledger.find(s.getJSONArray("debts"),"d_auto").getString("billId");
   Extras.apply(s,"payBillAuto",new JSONObject().put("id",billId));
   eq(Ledger.find(s.getJSONArray("debts"),"d_auto").getLong("balance"),81000L);
   JSONObject paidTx=null;JSONArray txs=s.getJSONArray("transactions");
   for(int i=0;i<txs.length();i++){JSONObject t=txs.getJSONObject(i);if(billId.equals(t.optString("billId"))&&!t.optBoolean("voided"))paidTx=t;}
   yes(paidTx!=null&&paidTx.getLong("debtPrincipalApplied")==19000L);
   String txId=paidTx.getString("id");
   Ledger.apply(s,"void",new JSONObject().put("id",txId));
   eq(Ledger.find(s.getJSONArray("debts"),"d_auto").getLong("balance"),100000L);
   Ledger.apply(s,"void",new JSONObject().put("id",txId).put("restore",true));
   eq(Ledger.find(s.getJSONArray("debts"),"d_auto").getLong("balance"),81000L);
   Extras.apply(s,"unlinkBill",new JSONObject().put("id",txId));
   eq(Ledger.find(s.getJSONArray("debts"),"d_auto").getLong("balance"),100000L);
   Extras.apply(s,"matchBill",new JSONObject().put("id",billId).put("transactionId",txId));
   eq(Ledger.find(s.getJSONArray("debts"),"d_auto").getLong("balance"),81000L);

   Ledger.apply(s,"goal",new JSONObject().put("id","g_pc").put("name","PC Build").put("target","3000.00").put("saved","200.00").put("targetDate",LocalDate.now().plusMonths(3).toString()));
   Extras.apply(s,"adjustGoal",new JSONObject().put("id","g_pc").put("delta",5000L));
   eq(Ledger.find(s.getJSONArray("goals"),"g_pc").getLong("saved"),25000L);
   Extras.apply(s,"adjustGoal",new JSONObject().put("id","g_pc").put("delta",-999999L));
   eq(Ledger.find(s.getJSONArray("goals"),"g_pc").getLong("saved"),0L);

   String mbCsv="Transaction Date,Description,Debit,Credit,Balance\n14/11/2026,JAYA GROCER,85.40,,914.60\n15/11/2026,ACME PAYROLL,,4500.00,5414.60\n";
   JSONObject insp=CsvImport.inspect(mbCsv);
   JSONObject am=insp.getJSONObject("autoMap");
   yes(am.getString("preset").equals("Maybank2u / MAE"));
   eq(am.getInt("debit"),2);eq(am.getInt("credit"),3);yes(am.getString("dateFormat").equals("dmy"));
   JSONArray prev=CsvImport.preview(mbCsv,new JSONObject(am.toString()).put("account",acctId),s);
   yes(prev.getJSONObject(0).getString("kind").equals("expense"));yes(prev.getJSONObject(0).getString("amount").equals("85.40"));yes(prev.getJSONObject(0).getString("date").equals("2026-11-14"));
   yes(prev.getJSONObject(1).getString("kind").equals("income"));yes(prev.getJSONObject(1).getString("amount").equals("4500.00"));

   String cimbCsv="Date,Transaction Details,Money Out,Money In\n14 Nov 2026,TNG RELOAD,50.00,\n";
   JSONObject inspCimb=CsvImport.inspect(cimbCsv);
   yes(inspCimb.getJSONObject("autoMap").getString("preset").equals("CIMB Octo"));
   yes(inspCimb.getJSONObject("autoMap").getString("dateFormat").equals("dmmmy"));
   JSONArray prevCimb=CsvImport.preview(cimbCsv,new JSONObject(inspCimb.getJSONObject("autoMap").toString()).put("account",acctId),s);
   yes(prevCimb.getJSONObject(0).getString("date").equals("2026-11-14"));yes(prevCimb.getJSONObject(0).getString("kind").equals("expense"));
  });
  test("phase6 biometric lockTimeoutSec and parser precision v2.2",()->{
   JSONObject s=Ledger.initial();
   yes(s.getJSONObject("settings").getInt("lockTimeoutSec")==0);
   for(int valid:new int[]{0,60,300,900}){
    Ledger.apply(s,"settings",new JSONObject().put("appLock",true).put("lockTimeoutSec",valid));
    yes(s.getJSONObject("settings").getBoolean("appLock"));
    yes(s.getJSONObject("settings").getInt("lockTimeoutSec")==valid);
   }
   boolean rejected=false;
   try{
    Ledger.apply(s,"settings",new JSONObject().put("lockTimeoutSec",45));
   }catch(IllegalArgumentException e){rejected=true;}
   yes(rejected);

   String rotiNotif="Pembayaran telah berjaya!\n\n-\nRM 15.00\n-\nKopi O\n-\nRM 3.00\n-\nCukai Perkhidmatan (6%)\n-\nRM 1.08\nTotal: RM 19.08\n\nPeniaga:\nKedai Roti Canai";
   JSONObject pRoti=PaymentParser.parse(rotiNotif,"com.touchngo.ewallet");
   yes(pRoti!=null&&pRoti.getLong("amount")==1908&&pRoti.getString("merchant").equals("Kedai Roti Canai")&&pRoti.getString("category").equals("Food & drinks"));

   String prsNotif="Bayaran berjaya kepada Klinik Simpanan Persaraan PRS, Seremban 70300 sebanyak RM 250.00";
   JSONObject pPrs=PaymentParser.parse(prsNotif,"my.com.maybank2u.m2umobile");
   yes(pPrs!=null&&pPrs.getString("merchant").equals("Klinik Simpanan Persaraan PRS")&&pPrs.getString("category").equals("Investments & savings")&&pPrs.getString("subcategory").equals("PRS (Private Retirement Scheme)"));

   JSONObject pSpeed=PaymentParser.parse("You paid RM 14.90 to 99 Spe3dmart","com.touchngo.ewallet");
   yes(pSpeed!=null&&pSpeed.getString("category").equals("Groceries"));

   String postTaxReceipt="Rcv:R78901 12 Oct 2026\nNASI KANDAR PELITA\nJumlah Perlu Dibayar RM 22.00\nCukai Perkhidmatan (6%) RM 1.32\nTunai RM 30.00\nBaki RM 6.68";
   JSONObject rPost=ReceiptParser.parse(postTaxReceipt);
   yes(rPost.getString("merchant").equals("NASI KANDAR PELITA")&&rPost.getLong("amount")==2332);
  });
  test("phase7 priceHikes, LHDN reliefOptimizer, foreign FX auto-tag and parser v2.3",()->{
   JSONObject s=Ledger.initial();
   String acct=s.getJSONArray("accounts").getJSONObject(0).getString("id");
   Ledger.apply(s,"bill",new JSONObject().put("name","Unifi Home Fibre").put("amount","129.00").put("account",acct).put("category","Bills & utilities").put("date","2026-11-20").put("frequency","monthly"));
   String billId=s.getJSONArray("bills").getJSONObject(0).getString("id");
   Ledger.apply(s,"transaction",new JSONObject().put("key","u1").put("kind","expense").put("amount","129.00").put("account",acct).put("category","Bills & utilities").put("merchant","Unifi Home Fibre").put("date","2026-09-15"));
   Ledger.apply(s,"transaction",new JSONObject().put("key","u2").put("kind","expense").put("amount","129.00").put("account",acct).put("category","Bills & utilities").put("merchant","Unifi Home Fibre").put("date","2026-10-15"));
   Ledger.apply(s,"transaction",new JSONObject().put("key","u3").put("kind","expense").put("amount","149.00").put("account",acct).put("category","Bills & utilities").put("merchant","Unifi Home Fibre").put("date","2026-11-14"));
   JSONArray hikes=Finance.priceHikes(s);
   yes(hikes.length()==1);
   JSONObject h=hikes.getJSONObject(0);
   yes(h.getString("merchant").equals("Unifi Home Fibre")&&h.getLong("previousAvg")==12900&&h.getLong("latestAmount")==14900&&h.getLong("deltaAmount")==2000&&h.getString("billId").equals(billId));
   Extras.apply(s,"updateBillAmount",new JSONObject().put("id",billId).put("amountMinor",14900L));
   yes(s.getJSONArray("bills").getJSONObject(0).getLong("amount")==14900L);

   Extras.apply(s,"taxProfile",new JSONObject().put("monthlyBaseSalary","8000").put("epfRate","11").put("lifestyle","500"));
   JSONObject tax=Finance.calculateTax(s,null,2026);
   yes(tax.getLong("marginalRate")>0&&tax.getLong("maxAdditionalTaxSavings")>0);
   JSONArray opt=tax.getJSONArray("reliefOptimizer");
   yes(opt.length()>=5);
   JSONObject lifeOpt=opt.getJSONObject(0);
   yes(lifeOpt.getString("key").equals("lifestyle")&&lifeOpt.getLong("unused")==2000_00L&&lifeOpt.getLong("potentialTaxSaved")>0);

   JSONObject fxNotif=PaymentParser.parse("You spent SGD 25.00 (RM 82.50) at Changi Airport Jewel","my.com.maybank2u.m2umobile");
   yes(fxNotif!=null&&fxNotif.getLong("amount")==8250&&fxNotif.getString("originalCurrency").equals("SGD")&&fxNotif.getString("originalAmount").equals("25.00")&&fxNotif.getString("exchangeRate").startsWith("3.3"));

   String maju="Bayaran Berjaya\nKedai Elektronik Maju\n25 May 2026\n\nBarang: Television LED 43-inch\nJumlah: RM 1499.00\nCukai Perkhidmatan: RM 119.92\n\nJumlah Dibayar: RM 1618.92\nPembayaran Tunai Berjaya";
   JSONObject pMaju=PaymentParser.parse(maju,"com.touchngo.ewallet");
   yes(pMaju!=null&&pMaju.getString("merchant").equals("Kedai Elektronik Maju")&&pMaju.getLong("amount")==161892);

   JSONObject rNoisy=ReceiptParser.parse("HDRLH6 JB TYPX HOTTLS\nTVEEL APYBL (RM)\n200.50\nSUBTOTAL: RM 195.00\n15 AUG 2026");
   yes(rNoisy.getLong("amount")==20050&&rNoisy.getString("date").equals("2026-08-15"));
  });
  test("phase8 eco notification prefilter, cached rules, ml lazy trim and ecoCapture setting",()->{
   JSONObject s=Ledger.initial();
   yes(s.getJSONObject("settings").getBoolean("ecoCapture"));
   Ledger.apply(s,"settings",new JSONObject().put("ecoCapture",false));
   yes(!s.getJSONObject("settings").getBoolean("ecoCapture"));

   yes(PaymentParser.isLikelyPayment("You paid RM 14.90 to 99 Speedmart"));
   yes(PaymentParser.isLikelyPayment("You spent SGD 25.00 (RM 82.50) at Changi Airport"));
   yes(PaymentParser.isLikelyPayment("Bayaran berjaya 15.00 ke Restoran Pelita"));
   yes(!PaymentParser.isLikelyPayment(null));
   yes(!PaymentParser.isLikelyPayment("abc"));
   yes(!PaymentParser.isLikelyPayment("Downloading 45% (12 MB / 30 MB)"));
   yes(!PaymentParser.isLikelyPayment("Your Maybank2u TAC is 482910"));
   yes(!PaymentParser.isLikelyPayment("Login OTP: 918273"));
   yes(!PaymentParser.isLikelyPayment("3 new messages from Ali"));
   yes(!PaymentParser.isLikelyPayment("Battery 15% remaining"));

   MlClassifier.Prediction p1=MlClassifier.predict("Klinik Kesihatan","Rawatan");
   yes(p1!=null&&MlClassifier.isLoaded());
   MlClassifier.trim();
   yes(!MlClassifier.isLoaded());
   MlClassifier.Prediction p2=MlClassifier.predict("Klinik Kesihatan","Rawatan");
   yes(p2!=null&&MlClassifier.isLoaded()&&p1.category.equals(p2.category)&&Math.abs(p1.probability-p2.probability)<1e-9);
  });
  System.out.println("RESULT: "+passed+" extended tests passed");
 }
}
