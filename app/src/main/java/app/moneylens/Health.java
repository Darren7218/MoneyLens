package app.moneylens;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
/** Counts only, never financial text or values. */
public final class Health {
 public static synchronized void bump(Context c,String key){SharedPreferences p=c.getSharedPreferences("health-counts",0);p.edit().putLong(key,p.getLong(key,0)+1).apply();}
 public static synchronized JSONObject snapshot(Context c)throws Exception{SharedPreferences p=c.getSharedPreferences("health-counts",0);JSONObject out=new JSONObject();for(String key:new String[]{"received","filtered","parsed","duplicateOrUnsupported","errors"})out.put(key,p.getLong(key,0));return out;}
}
