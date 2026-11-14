package app.moneylens;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
/** Counts only, never financial text or values. Batches frequent drops in memory to avoid flash/SharedPreferences churn. */
public final class Health {
 private static final String[] KEYS=new String[]{"received","filtered","droppedNoise","parsed","duplicateOrUnsupported","errors"};
 private static final Map<String,Long> CACHE=new HashMap<>();
 private static boolean loaded=false;
 private static int dirtyNoise=0;
 private static void ensure(Context c){
  if(loaded)return;
  SharedPreferences p=c.getApplicationContext().getSharedPreferences("health-counts",0);
  for(String k:KEYS)CACHE.put(k,p.getLong(k,0));
  loaded=true;
 }
 public static synchronized void bump(Context c,String key){
  if(c==null||key==null)return;
  ensure(c);
  long next=(CACHE.containsKey(key)?CACHE.get(key):0L)+1L;
  CACHE.put(key,next);
  dirtyNoise++;
  boolean urgent="parsed".equals(key)||"errors".equals(key);
  if(urgent||dirtyNoise>=25){
   flushLocked(c);
  }
 }
 public static synchronized void flush(Context c){
  if(c==null||!loaded||dirtyNoise==0)return;
  flushLocked(c);
 }
 private static void flushLocked(Context c){
  SharedPreferences.Editor ed=c.getApplicationContext().getSharedPreferences("health-counts",0).edit();
  for(Map.Entry<String,Long> e:CACHE.entrySet())ed.putLong(e.getKey(),e.getValue());
  ed.apply();
  dirtyNoise=0;
 }
 public static synchronized JSONObject snapshot(Context c)throws Exception{
  ensure(c);
  if(dirtyNoise>0)flushLocked(c);
  JSONObject out=new JSONObject();
  for(String key:KEYS)out.put(key,CACHE.containsKey(key)?CACHE.get(key):0L);
  return out;
 }
}
