package app.moneylens;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Approximate durable work; Android controls timing. No network requirement. */
public final class Automation {
 public static void schedule(Context c){c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(2107,new ComponentName(c,MoneyJob.class)).setPeriodic(15*60*1000L).setPersisted(true).build());}
 public static boolean quiet(JSONObject settings){if(!settings.optBoolean("quietEnabled"))return false;int h=LocalTime.now().getHour(),start=settings.optInt("quietStart",22),end=settings.optInt("quietEnd",8);return start==end|| (start<end?h>=start&&h<end:h>=start||h<end);}
 public static boolean notify(Context c,String id,String title,String body,String screen){NotificationManager nm=c.getSystemService(NotificationManager.class);if(!nm.areNotificationsEnabled())return false;nm.createNotificationChannel(new NotificationChannel("planning","Planning & reminders",NotificationManager.IMPORTANCE_DEFAULT));Intent i=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent p=PendingIntent.getActivity(c,id.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);try{nm.notify(id.hashCode(),new Notification.Builder(c,"planning").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body).setContentIntent(p).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build());return true;}catch(SecurityException e){return false;}}
 static void alert(Context c,String id,String title,String body,String screen)throws Exception{if(!Vault.alertExists(c,id)&&notify(c,id,title,body,screen))Vault.markAlert(c,id);}
 public static void run(Context c)throws Exception{
  JSONObject s=Vault.read(c),prefs=s.getJSONObject("settings");LocalDate now=LocalDate.now();if(!quiet(prefs)){
   JSONArray drafts=s.getJSONArray("drafts");int count=0;for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).optString("state").equals("review"))count++;
   if(prefs.optBoolean("digest")){if(count>0&&LocalTime.now().getHour()>=prefs.optInt("digestHour",19))alert(c,"digest:"+now,"Your MoneyLens check-in",count+" suggestions are waiting for your review.","inbox");}
   else for(int i=0;i<drafts.length();i++){JSONObject d=drafts.getJSONObject(i);if(d.optString("state").equals("review")&&!d.optBoolean("prompted"))CaptureService.showPrompt(c,d);}
   if(prefs.optBoolean("reminders",true)){JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;if(!LocalDate.parse(b.getString("date")).isAfter(now.plusDays(1)))alert(c,"bill:"+b.getString("id")+":"+b.getString("date"),"A planned payment is due","Open Plan to check the bill and confirm payment.","plan");String cancel=b.optString("cancelDate");if(!cancel.isEmpty()&&!LocalDate.parse(cancel).isAfter(now.plusDays(2)))alert(c,"cancel:"+b.getString("id")+":"+cancel,"Subscription reminder","A cancellation deadline is approaching. Check Plan.","plan");}}
   if(prefs.optBoolean("budgetAlerts",true)){JSONObject r=Finance.report(s,now);JSONArray b=r.getJSONArray("budgets");int threshold=prefs.optInt("threshold",80);for(int i=0;i<b.length();i++){JSONObject row=b.getJSONObject(i);if(row.getLong("available")>0&&row.getLong("spent")*100>=row.getLong("available")*threshold)alert(c,"budget:"+row.getString("id")+":"+row.getString("start")+":"+threshold,"Budget check-in","A category has reached your alert threshold. Open Plan for details.","plan");}}
  }
  autoBackup(c,false);MoneyWidget.refresh(c);
 }
 public static void configure(Context c,Uri uri,char[] password)throws Exception{byte[] salt=Crypto.random(16),key=Crypto.derive(password,salt).getEncoded();JSONObject cfg=new JSONObject().put("uri",uri.toString()).put("salt",android.util.Base64.encodeToString(salt,2)).put("key",android.util.Base64.encodeToString(key,2));Vault.writeSecret(c,"auto-backup",cfg.toString().getBytes(StandardCharsets.UTF_8));Arrays.fill(key,(byte)0);c.getSharedPreferences("health",0).edit().putBoolean("autoBackup",true).apply();autoBackup(c,true);}
 public static void autoBackup(Context c,boolean force)throws Exception{
  android.content.SharedPreferences health=c.getSharedPreferences("health",0);if(!health.getBoolean("autoBackup",false))return;if(!force&&System.currentTimeMillis()-health.getLong("backupSuccess",0)<24*3600000L)return;
  Uri dest=null;try{JSONObject cfg=new JSONObject(new String(Vault.readSecret(c,"auto-backup"),StandardCharsets.UTF_8));Uri tree=Uri.parse(cfg.getString("uri")),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));byte[] bytes=Crypto.backupWithKey(Vault.backupPlain(c),android.util.Base64.decode(cfg.getString("salt"),2),android.util.Base64.decode(cfg.getString("key"),2));dest=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/octet-stream","MoneyLens-auto-"+System.currentTimeMillis()+".moneylens");if(dest==null)throw new IOException();try(OutputStream out=c.getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException();out.write(bytes);out.flush();}health.edit().putLong("backupSuccess",System.currentTimeMillis()).putString("backupError","").apply();
  }catch(Exception e){if(dest!=null)try{DocumentsContract.deleteDocument(c.getContentResolver(),dest);}catch(Exception ignored){}health.edit().putString("backupError","Backup folder is unavailable or full. Choose the folder again or make a manual backup.").apply();if(force)throw e;}
 }
}
