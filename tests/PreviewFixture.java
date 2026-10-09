package app.moneylens;
import org.json.*;
import java.time.LocalDate;
public class PreviewFixture {
  public static void main(String[] args)throws Exception{
    JSONObject s=Ledger.initial();Ledger.apply(s,"account",new JSONObject().put("name","Maybank").put("type","bank").put("opening","1250.35"));Ledger.apply(s,"account",new JSONObject().put("name","TNG eWallet").put("type","wallet").put("opening","180"));
    String bank=s.getJSONArray("accounts").getJSONObject(1).getString("id"),wallet=s.getJSONArray("accounts").getJSONObject(2).getString("id");
    String[][] rows={{"income","3500","Salary","Monthly salary",bank},{"expense","18.90","Food & drinks","Coffee & a little pause",wallet},{"expense","129.90","Shopping","Shopee",bank},{"expense","65","Transport","Petrol",bank},{"expense","86.40","Groceries","Lotus's",bank},{"expense","70","Bills & utilities","Phone plan",bank},{"expense","24.50","Food & drinks","Dinner with friends",wallet},{"expense","19.90","Entertainment","Movie night",wallet},{"expense","165","Food & drinks","Meals this week",bank}};
    for(int i=0;i<rows.length;i++){String[] r=rows[i];Ledger.apply(s,"transaction",new JSONObject().put("key",Ledger.id()).put("kind",r[0]).put("amount",r[1]).put("category",r[2]).put("merchant",r[3]).put("account",r[4]).put("date",LocalDate.now().withDayOfMonth(Math.max(1,LocalDate.now().getDayOfMonth()-i%6)).toString()));}
    for(String[] b:new String[][]{{"Food & drinks","650"},{"Shopping","400"},{"Transport","300"}})Ledger.apply(s,"budget",new JSONObject().put("category",b[0]).put("amount",b[1]));
    Ledger.apply(s,"goal",new JSONObject().put("name","My next desktop PC").put("target","5000").put("saved","1800"));
    Ledger.apply(s,"bill",new JSONObject().put("name","Home internet").put("amount","70").put("account",bank).put("category","Bills & utilities").put("date",LocalDate.now().plusDays(4).toString()).put("monthly",true));
    Ledger.apply(s,"sample",new JSONObject());System.out.print(s.toString(2));
  }
}
