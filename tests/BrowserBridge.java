package app.moneylens;
import org.json.*;import java.io.*;import java.nio.file.*;import java.time.*;
/** Test-only JSON line protocol connects the actual domain to browser integration checks. */
public class BrowserBridge {
 public static void main(String[] argv)throws Exception{
  JSONObject state=new JSONObject(new String(Files.readAllBytes(Paths.get(argv[0])),"UTF-8"));BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;while((line=reader.readLine())!=null){JSONObject out=new JSONObject();try{JSONObject q=new JSONObject(line),a=q.getJSONObject("args");String m=q.getString("method");Object result;if(m.equals("snapshot"))result=new JSONObject().put("state",state).put("status",new JSONObject().put("version","0.2.0"));else if(m.equals("report"))result=Finance.report(state,LocalDate.now());else if(m.equals("paste")){JSONObject d=PaymentParser.parse(a.getString("text"),"Shared text");if(d==null)d=ReceiptParser.parse(a.getString("text"));JSONObject copy=new JSONObject(state.toString());result=state=Ledger.apply(copy,"draft",d);}else{JSONObject copy=new JSONObject(state.toString());result=state=Ledger.apply(copy,m,a);}out.put("ok",true).put("result",result);}catch(Exception e){out.put("ok",false).put("error",String.valueOf(e.getMessage()));}System.out.println(out);System.out.flush();}
 }
}
