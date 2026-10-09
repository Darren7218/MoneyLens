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
 public static JSONObject inspect(String text)throws Exception{List<List<String>> rows=parse(text);return new JSONObject().put("headers",new JSONArray(rows.get(0))).put("sample",new JSONArray(rows.subList(1,Math.min(6,rows.size())))).put("count",rows.size()-1);}
 public static JSONArray preview(String text,JSONObject map,JSONObject state)throws Exception{
  List<List<String>> rows=parse(text);JSONArray out=new JSONArray();String digest=hash(text),account=map.getString("account");Ledger.find(state.getJSONArray("accounts"),account);
  for(int i=1;i<rows.size();i++){List<String> row=rows.get(i);JSONObject result=new JSONObject().put("row",i).put("importRow",digest+":"+i);try{
   String raw=get(row,map,"amount").replace("RM","").replace("MYR","").replace(",","").trim();if(raw.startsWith("(")&&raw.endsWith(")"))raw="-"+raw.substring(1,raw.length()-1);BigDecimal n=new BigDecimal(raw);long value=n.abs().movePointRight(2).longValueExact();if(value<=0||value>Ledger.MAX)throw new IllegalArgumentException("Invalid amount");
   String d=get(row,map,"date"),format=map.optString("dateFormat","iso");LocalDate date=format.equals("dmy")?LocalDate.parse(d,DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)):format.equals("mdy")?LocalDate.parse(d,DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT)):LocalDate.parse(d);
   String kind=get(row,map,"kind").toLowerCase(Locale.ROOT);if(kind.isEmpty())kind=map.optBoolean("signed")?(n.signum()<0?"expense":"income"):map.optString("defaultKind","expense");if(!Arrays.asList("expense","income","refund").contains(kind))throw new IllegalArgumentException("Use expense, income or refund; review transfers manually");
   String merchant=get(row,map,"merchant"),category=get(row,map,"category");if(category.isEmpty())category=kind.equals("income")?"Other income":"Other";if(merchant.length()>120||category.length()>60)throw new IllegalArgumentException("Description too long");
   result.put("kind",kind).put("amount",BigDecimal.valueOf(value,2).toPlainString()).put("merchant",merchant).put("category",category).put("date",date.toString()).put("account",account).put("note",get(row,map,"note")).put("key","import:"+digest+":"+i);
   JSONArray tx=state.getJSONArray("transactions");for(int j=0;j<tx.length();j++){JSONObject t=tx.getJSONObject(j);if(result.getString("importRow").equals(t.optString("importRow")))result.put("duplicate",true);if(!t.optBoolean("voided")&&t.getLong("amount")==value&&t.getString("date").equals(date.toString())&&t.getString("account").equals(account)&&t.optString("merchant").equalsIgnoreCase(merchant))result.put("possibleDuplicate",true);}
  }catch(Exception e){result.put("error","Row "+i+": check the date, amount and column mapping.");}out.put(result);}
  return out;
 }
 static String hash(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format("%02x",v));return b.toString();}
}
