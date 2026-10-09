package app.moneylens;
import org.json.*;
import java.util.regex.*;
import java.time.LocalDate;

/** Conservative generic parser. Unsupported formats remain manual-entry cases. */
public final class PaymentParser {
  private static final Pattern AMOUNT=Pattern.compile("(?i)(?:RM|MYR)\\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![0-9]|\\.[0-9])");
  public static JSONObject parse(String text,String source)throws Exception{
    if(text==null||text.length()>4000)return null;String low=text.toLowerCase(java.util.Locale.ROOT);
    if(low.matches("(?s).*\\b(otp|tac|verification|secure2u|authorise|authorize|declined|failed|unsuccessful|promotion|cashback offer|discount|voucher|verification code)\\b.*"))return null;
    if(!low.matches("(?s).*\\b(paid|payment|purchase|spent|debited|credited|received|refund|transfer|withdrawal|top.?up|bayaran|dibayar|diterima)\\b.*"))return null;
    if(low.matches("(?s).*\\b(pending|processing|requested|scheduled|pre.?authori[sz]ation)\\b.*"))return null;
    Matcher m=AMOUNT.matcher(text);long amount=0;int count=0;JSONArray candidates=new JSONArray();while(m.find()){long parsed;try{parsed=Ledger.minor(m.group(1).replace(",",""));}catch(IllegalArgumentException ignored){continue;}count++;candidates.put(parsed);if(count==1)amount=parsed;}if(count==0)return null;
    String kind=low.contains("refund")?"refund":low.matches("(?s).*\\b(credited|received|diterima)\\b.*")?"income":"expense";
    String reason=count==1?"Check the account, amount and type before saving.":"Several amounts were found. Enter the payment amount; it may differ from the balance.";
    if(low.matches("(?s).*\\b(transfer|top.?up|withdrawal)\\b.*")){kind="transfer";reason="This may be a transfer between your own accounts. Choose both accounts.";}
    String merchant="Detected payment";Matcher merchantMatch=Pattern.compile("(?i)\\b(?:at|to|from)\\s+([\\p{L}0-9 &'._-]{2,60})").matcher(text);
    if(merchantMatch.find())merchant=merchantMatch.group(1).replaceAll("(?i)\\s+(successful|successfully|on|for)\\b.*","").trim();
    return new JSONObject().put("id",Ledger.id()).put("amount",count==1?amount:0).put("kind",kind).put("merchant",merchant).put("category","Other").put("date",LocalDate.now().toString()).put("source",source).put("state","review").put("reason",reason).put("amountCandidates",candidates).put("adapter","generic-2").put("revision",1).put("created",System.currentTimeMillis());
  }
}
