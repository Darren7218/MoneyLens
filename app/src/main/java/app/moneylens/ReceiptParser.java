package app.moneylens;
import org.json.*;
import java.util.regex.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
/** Extracts suggestions; never commits OCR text directly to the ledger. */
public final class ReceiptParser {
 public static JSONObject parse(String text)throws Exception{
  if(text==null)text="";String[] lines=text.split("\\r?\\n");String merchant="Receipt";for(String line:lines)if(line.trim().length()>2&&!line.trim().matches("[0-9\\W]+")){merchant=line.trim().substring(0,Math.min(120,line.trim().length()));break;}
  long best=0;int score=-1;boolean ambiguous=false;Pattern amount=Pattern.compile("(?i)(?:RM|MYR)?\\s*([0-9]{1,3}(?:,[0-9]{3})*\\.[0-9]{2}|[0-9]{1,9}\\.[0-9]{2})(?![0-9])");
  for(String line:lines){String low=line.toLowerCase(Locale.ROOT);int s=0;if(low.contains("grand total")||low.contains("amount paid")||low.contains("total payable")||low.contains("总计")||low.contains("合计"))s=4;else if(low.contains("total")||low.contains("jumlah"))s=3;if(low.contains("subtotal")||low.contains("sub total")||low.contains("change")||low.contains("cash tendered"))s=-1;
   Matcher m=amount.matcher(line);while(m.find()){long n;try{n=Ledger.minor(m.group(1).replace(",",""));}catch(IllegalArgumentException ignored){continue;}if(s>score&&s>=0){best=n;score=s;ambiguous=false;}else if(s==score&&n!=best)ambiguous=true;}}
  String date=LocalDate.now().toString();Matcher dm=Pattern.compile("\\b(20[0-9]{2})[-/]([0-9]{1,2})[-/]([0-9]{1,2})\\b").matcher(text);try{if(dm.find())date=LocalDate.of(Integer.parseInt(dm.group(1)),Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(3))).toString();else{dm=Pattern.compile("\\b([0-9]{1,2})[-/]([0-9]{1,2})[-/](20[0-9]{2})\\b").matcher(text);if(dm.find())date=LocalDate.of(Integer.parseInt(dm.group(3)),Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(1))).toString();}}catch(Exception ignored){}
  return new JSONObject().put("id",Ledger.id()).put("merchant",merchant).put("amount",ambiguous||score<3?0:best).put("kind","expense").put("category","Other").put("date",date).put("source","Receipt scan").put("state","review").put("revision",1).put("reason",ambiguous||score<3?"OCR found no unambiguous total. Enter the receipt total and check all details.":"Suggested from receipt text. Verify the total, date, merchant and account.");
 }
}
