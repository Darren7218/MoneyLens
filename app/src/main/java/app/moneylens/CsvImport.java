package app.moneylens;
import org.json.*;
import java.util.*;
import java.time.*;
import java.time.format.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class CsvImport {
 public static List<List<String>> parse(String input){
  if(input.length()>2_000_000)throw new IllegalArgumentException("Choose a CSV smaller than 2 MB.");if(input.startsWith("\uFEFF"))input=input.substring(1);List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
  for(int i=0;i<input.length();i++){char c=input.charAt(i);if(c=='"'){if(quoted&&i+1<input.length()&&input.charAt(i+1)=='"'){cell.append('"');i++;}else if(quoted||cell.length()==0)quoted=!quoted;else throw new IllegalArgumentException("Malformed CSV quote.");}else if(!quoted&&(c==','||c=='\n'||c=='\r')){row.add(cell.toString());cell.setLength(0);if(c!=','){if(c=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;if(row.stream().anyMatch(x->!x.trim().isEmpty()))rows.add(row);row=new ArrayList<>();}}else cell.append(c);if(cell.length()>2000||row.size()>50||rows.size()>1001)throw new IllegalArgumentException("CSV exceeds 1,000 rows, 50 columns or 2,000 characters per cell.");}
  if(quoted)throw new IllegalArgumentException("CSV has an unclosed quote.");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}if(rows.size()<2)throw new IllegalArgumentException("CSV needs a header and at least one transaction.");return rows;
 }
 static String get(List<String> row,JSONObject m,String field){int ix=m.optInt(field,-1);return ix>=0&&ix<row.size()?row.get(ix).trim():"";}
 public static JSONObject inspect(String text)throws Exception{
  List<List<String>> rows=parse(text);
  List<String> headers=rows.get(0);
  List<List<String>> sample=rows.subList(1,Math.min(6,rows.size()));
  return new JSONObject().put("headers",new JSONArray(headers)).put("sample",new JSONArray(sample)).put("count",rows.size()-1).put("autoMap",autoMap(headers,sample));
 }
 private static int matchCol(List<String> lowerHeaders,String... keys){
  for(String k:keys)for(int i=0;i<lowerHeaders.size();i++)if(lowerHeaders.get(i).equals(k))return i;
  for(String k:keys)for(int i=0;i<lowerHeaders.size();i++)if(lowerHeaders.get(i).contains(k)&&!lowerHeaders.get(i).contains("balance")&&!lowerHeaders.get(i).contains("baki"))return i;
  return -1;
 }
 private static JSONObject autoMap(List<String> headers,List<List<String>> sample)throws JSONException{
  List<String> lh=new ArrayList<>(headers.size());
  for(String h:headers)lh.add(h==null?"":h.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "));
  String joined=String.join("|",lh);
  int date=matchCol(lh,"date","tarikh","transaction date","trans. date","trans date","posting date","value date","datetime","txn date");
  int debit=matchCol(lh,"debit","debit (rm)","debit amount","withdrawal","money out","out","keluar","dr");
  int credit=matchCol(lh,"credit","credit (rm)","credit amount","deposit","money in","in","masuk","cr");
  int amount=matchCol(lh,"amount","amount (rm)","amount(myr)","transaction amount","jumlah","total","myr","rm","amt");
  if(debit>=0&&credit>=0&&(amount==debit||amount==credit||amount<0))amount=-1;
  int merchant=matchCol(lh,"merchant","description","transaction details","details","particulars","transaction description","payee","recipient","butiran","name");
  int category=matchCol(lh,"category","kategori");
  int kind=matchCol(lh,"kind","type","transaction type","dr/cr","debit/credit");
  int note=matchCol(lh,"note","remark","remarks","reference","ref","catatan","memo");
  String preset="";
  if(joined.contains("maybank")||joined.contains("mae")||(debit>=0&&credit>=0&&joined.contains("description")&&!joined.contains("posting date")))preset="Maybank2u / MAE";
  else if(joined.contains("cimb")||joined.contains("money out")||joined.contains("transaction details"))preset="CIMB Octo";
  else if(joined.contains("touch 'n go")||joined.contains("tng")||joined.contains("ewallet"))preset="TNG eWallet";
  else if(joined.contains("public bank")||joined.contains("rhb")||joined.contains("posting date")||joined.contains("withdrawal"))preset="Public Bank / RHB";
  else if(joined.contains("grabpay")||joined.contains("grab"))preset="GrabPay";
  else if(date>=0&&amount>=0&&merchant>=0&&category>=0&&kind>=0)preset="MoneyLens CSV";
  else if(debit>=0&&credit>=0)preset="Malaysian Bank Statement";
  String dateFormat="iso";
  boolean signed=false;
  for(List<String> r:sample){
   if(date>=0&&date<r.size()){
    String dv=r.get(date).trim().replaceFirst("[T ]\\d{2}:\\d{2}(:\\d{2})?.*$","");
    if(dv.matches("\\d{4}-\\d{2}-\\d{2}"))dateFormat="iso";
    else if(dv.matches("\\d{1,2}[\\s-][A-Za-z]{3,9}[\\s-]\\d{4}"))dateFormat="dmmmy";
    else if(dv.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}")){
     String[] p=dv.split("[/-]");
     int a=Integer.parseInt(p[0]),b=Integer.parseInt(p[1]);
     dateFormat=(a<=12&&b>12)?"mdy":"dmy";
    }
   }
   if(amount>=0&&amount<r.size()&&debit<0&&credit<0){
    String av=r.get(amount).trim().toUpperCase(Locale.ROOT);
    if(av.startsWith("-")||av.startsWith("(")||av.endsWith("DR")||av.endsWith("CR"))signed=true;
   }
  }
  return new JSONObject().put("preset",preset).put("date",date).put("amount",amount).put("debit",debit).put("credit",credit).put("merchant",merchant).put("category",category).put("kind",kind).put("note",note).put("dateFormat",dateFormat).put("signed",signed);
 }
 private static BigDecimal cleanCsvAmount(String raw){
  String c=raw.replace("RM","").replace("MYR","").replace(",","").trim();
  if(c.startsWith("(")&&c.endsWith(")"))c="-"+c.substring(1,c.length()-1);
  return new BigDecimal(c.trim());
 }
 private static LocalDate parseCsvDate(String d,String format){
  String clean=d.trim().replaceFirst("^(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}|\\d{1,2}[\\s-][A-Za-z]{3,9}[\\s-]\\d{4})(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)$","$1");
  if(format.equals("iso"))return LocalDate.parse(clean);
  if(format.equals("mdy")){
   String[] fmts={"M/d/uuuu","M-d-uuuu"};
   for(String f:fmts){try{return LocalDate.parse(clean,DateTimeFormatter.ofPattern(f).withResolverStyle(ResolverStyle.STRICT));}catch(Exception ignored){}}
   throw new IllegalArgumentException("Invalid MDY date");
  }
  if(format.equals("dmmmy")){
   for(String f:new String[]{"d MMM uuuu","d-MMM-uuuu","d MMMM uuuu"}){
    try{return LocalDate.parse(clean,new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(f).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT));}catch(Exception ignored){}
   }
   throw new IllegalArgumentException("Invalid D-MMM-YYYY date");
  }
  for(String f:new String[]{"d/M/uuuu","d-M-uuuu","d MMM uuuu","d-MMM-uuuu"}){
   try{return LocalDate.parse(clean,new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(f).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT));}catch(Exception ignored){}
  }
  throw new IllegalArgumentException("Invalid DMY date");
 }
 public static JSONArray preview(String text,JSONObject map,JSONObject state)throws Exception{
  List<List<String>> rows=parse(text);JSONArray out=new JSONArray();String digest=hash(text),account=map.getString("account");Ledger.find(state.getJSONArray("accounts"),account);
  for(int i=1;i<rows.size();i++){List<String> row=rows.get(i);JSONObject result=new JSONObject().put("row",i).put("importRow",digest+":"+i);try{
   String raw=get(row,map,"amount"),rawDr=get(row,map,"debit"),rawCr=get(row,map,"credit"),inferredKind="";
   BigDecimal n;
   if(!raw.isEmpty()){
    String u=raw.toUpperCase(Locale.ROOT).trim();
    if(u.endsWith("DR")){inferredKind="expense";raw=raw.substring(0,raw.length()-2).trim();}
    else if(u.endsWith("CR")){inferredKind="income";raw=raw.substring(0,raw.length()-2).trim();}
    n=cleanCsvAmount(raw);
   }else{
    BigDecimal dVal=rawDr.isEmpty()?BigDecimal.ZERO:cleanCsvAmount(rawDr).abs();
    BigDecimal cVal=rawCr.isEmpty()?BigDecimal.ZERO:cleanCsvAmount(rawCr).abs();
    if(dVal.signum()>0&&cVal.signum()>0)throw new IllegalArgumentException("Both debit and credit set");
    if(dVal.signum()>0){n=dVal;inferredKind="expense";}
    else if(cVal.signum()>0){n=cVal;inferredKind="income";}
    else throw new IllegalArgumentException("Invalid amount");
   }
   long value=n.abs().movePointRight(2).longValueExact();if(value<=0||value>Ledger.MAX)throw new IllegalArgumentException("Invalid amount");
   LocalDate date=parseCsvDate(get(row,map,"date"),map.optString("dateFormat","iso"));
   String kind=get(row,map,"kind").toLowerCase(Locale.ROOT).trim();
   if(kind.equals("dr")||kind.equals("debit"))kind="expense";
   else if(kind.equals("cr")||kind.equals("credit"))kind="income";
   if(kind.isEmpty())kind=!inferredKind.isEmpty()?inferredKind:(map.optBoolean("signed")?(n.signum()<0?"expense":"income"):map.optString("defaultKind","expense"));
   if(!Arrays.asList("expense","income","refund").contains(kind))throw new IllegalArgumentException("Use expense, income or refund; review transfers manually");
   String merchant=get(row,map,"merchant"),category=get(row,map,"category"),note=get(row,map,"note");
   if(category.isEmpty()){
    JSONObject probe=new JSONObject().put("merchant",merchant).put("amount",value).put("account",account).put("source","CSV import").put("note",note);
    Finance.suggest(state,probe);
    if(kind.equals("income")){
     category="Other income";
     if(!probe.optString("ruleId","").isEmpty()){
      category=probe.optString("category",category);
      if(!probe.optString("subcategory").isEmpty())result.put("subcategory",probe.getString("subcategory"));
     }
    }else{
     category=probe.optString("category","Miscellaneous");
     if(!probe.optString("subcategory").isEmpty())result.put("subcategory",probe.getString("subcategory"));
    }
   }
   if(merchant.length()>120||category.length()>60)throw new IllegalArgumentException("Description too long");
   result.put("kind",kind).put("amount",BigDecimal.valueOf(value,2).toPlainString()).put("merchant",merchant).put("category",category).put("date",date.toString()).put("account",account).put("note",note).put("key","import:"+digest+":"+i);
   JSONArray tx=state.getJSONArray("transactions");for(int j=0;j<tx.length();j++){JSONObject t=tx.getJSONObject(j);if(result.getString("importRow").equals(t.optString("importRow")))result.put("duplicate",true);if(!t.optBoolean("voided")&&t.getLong("amount")==value&&t.getString("date").equals(date.toString())&&t.getString("account").equals(account)&&t.optString("merchant").equalsIgnoreCase(merchant))result.put("possibleDuplicate",true);}
  }catch(Exception e){result.put("error","Row "+i+": check the date, amount and column mapping.");}out.put(result);}
  return out;
 }
 static String hash(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format("%02x",v));return b.toString();}
}
