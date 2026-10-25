package app.moneylens;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable background scheduling, exact BNPL/PTPTN reminder checks, and encrypted auto-backup. */
public final class Automation {
 public static void schedule(Context c){
  try{c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(2107,new ComponentName(c,MoneyJob.class)).setPeriodic(15*60*1000L).setPersisted(true).build());}catch(Exception ignored){}
  KeepAliveService.ensureRunning(c);
  AlarmReceiver.scheduleNext(c);
 }
 public static boolean quiet(JSONObject settings){if(!settings.optBoolean("quietEnabled"))return false;int h=LocalTime.now().getHour(),start=settings.optInt("quietStart",22),end=settings.optInt("quietEnd",8);return start==end|| (start<end?h>=start&&h<end:h>=start||h<end);}
 public static boolean notify(Context c,String id,String title,String body,String screen){NotificationManager nm=c.getSystemService(NotificationManager.class);if(!nm.areNotificationsEnabled())return false;nm.createNotificationChannel(new NotificationChannel("planning","Planning & reminders",NotificationManager.IMPORTANCE_DEFAULT));Intent i=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent p=PendingIntent.getActivity(c,id.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);try{nm.notify(id.hashCode(),new Notification.Builder(c,"planning").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body).setContentIntent(p).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build());return true;}catch(SecurityException e){return false;}}
 static void alert(Context c,String id,String title,String body,String screen)throws Exception{if(!Vault.alertExists(c,id)&&notify(c,id,title,body,screen))Vault.markAlert(c,id);}

 public static void checkDueReminders(Context c)throws Exception{
  JSONObject s=Vault.read(c),prefs=s.getJSONObject("settings");
  if(!prefs.optBoolean("reminders",true)||quiet(prefs))return;
  LocalDate today=LocalDate.now();
  LocalTime nowTime=LocalTime.now();
  long nowMs=System.currentTimeMillis();
  JSONArray bills=s.getJSONArray("bills");
  for(int i=0;i<bills.length();i++){
   JSONObject b=bills.getJSONObject(i);
   if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;
   String dueStr=b.getString("date");
   if(dueStr.equals(b.optString("dismissedCycle","")))continue;
   long snoozeUntil=b.optLong("snoozeUntil",0);
   if(snoozeUntil>nowMs)continue;
   LocalDate due=LocalDate.parse(dueStr);
   int daysBefore=b.optInt("remindDaysBefore",3);
   LocalDate warnStart=due.minusDays(daysBefore);
   if(today.isBefore(warnStart))continue;
   String rt=b.optString("remindTime","09:00");
   String[] parts=rt.split(":");
   int hh=parts.length==2?Integer.parseInt(parts[0]):9;
   int mm=parts.length==2?Integer.parseInt(parts[1]):0;
   if(snoozeUntil==0&&nowTime.isBefore(LocalTime.of(hh,mm)))continue;
   String alertKey=snoozeUntil>0?("rem-snooze:"+b.getString("id")+":"+snoozeUntil):("rem:"+b.getString("id")+":"+dueStr+":"+today+":"+rt);
   if(!Vault.alertExists(c,alertKey)){
    if(fireReminderNotification(c,b,false)){
     Vault.markAlert(c,alertKey);
     if(snoozeUntil>0){
      Vault.command(c,"snoozeBill",new JSONObject().put("id",b.getString("id")).put("durationMs",0L));
     }
    }
   }
  }
 }

 public static boolean fireReminderNotification(Context c,JSONObject b,boolean isTest){
  try{
   NotificationManager nm=c.getSystemService(NotificationManager.class);
   if(nm==null||!nm.areNotificationsEnabled())return false;
   NotificationChannel ch=new NotificationChannel("reminders_high","BNPL, PTPTN & Bill Due Alerts",NotificationManager.IMPORTANCE_HIGH);
   ch.setDescription("High-priority payment due alerts for BNPL, PTPTN, credit cards, and bills.");
   nm.createNotificationChannel(ch);
   String id=b.getString("id"),name=b.getString("name"),dueStr=b.getString("date");
   LocalDate due=LocalDate.parse(dueStr),today=LocalDate.now();
   long daysDiff=ChronoUnit.DAYS.between(today,due);
   String amtStr="RM "+BigDecimal.valueOf(b.getLong("amount"),2).toPlainString();
   String rType=b.optString("reminderType","bill");
   String prefix="bnpl".equals(rType)?"BNPL Due Reminder":"ptptn".equals(rType)?"PTPTN Repayment Reminder":"credit".equals(rType)?"Credit Card Due Reminder":"Payment Due Reminder";
   String status=daysDiff<0?("OVERDUE by "+Math.abs(daysDiff)+" day(s)"):daysDiff==0?"Due TODAY":("Due in "+daysDiff+" day(s) ("+dueStr+")");
   String title=(isTest?"[Test] ":"")+prefix+" · "+name;
   String body=amtStr+" · "+status+". Pay before due date to avoid late fees.";
   int notifId=Math.abs(("rem_notif:"+id).hashCode());

   Intent open=new Intent(c,MainActivity.class).putExtra("screen","plan").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
   PendingIntent pOpen=PendingIntent.getActivity(c,notifId,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

   Intent snooze=new Intent(c,AlarmReceiver.class).setAction(AlarmReceiver.ACTION_SNOOZE_BILL).putExtra("billId",id).putExtra("notifId",notifId);
   PendingIntent pSnooze=PendingIntent.getBroadcast(c,notifId+1,snooze,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

   Intent dismiss=new Intent(c,AlarmReceiver.class).setAction(AlarmReceiver.ACTION_DISMISS_BILL).putExtra("billId",id).putExtra("notifId",notifId);
   PendingIntent pDismiss=PendingIntent.getBroadcast(c,notifId+2,dismiss,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

   Notification n=new Notification.Builder(c,"reminders_high")
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle(title)
    .setContentText(body)
    .setStyle(new Notification.BigTextStyle().bigText(body))
    .setContentIntent(pOpen)
    .setVisibility(Notification.VISIBILITY_PUBLIC)
    .setAutoCancel(true)
    .addAction(new Notification.Action.Builder(null,"Record Payment",pOpen).build())
    .addAction(new Notification.Action.Builder(null,"Snooze 1h",pSnooze).build())
    .addAction(new Notification.Action.Builder(null,"Dismiss cycle",pDismiss).build())
    .build();
   nm.notify(notifId,n);
   return true;
  }catch(Exception e){return false;}
 }

 public static void run(Context c)throws Exception{
  checkDueReminders(c);
  JSONObject s=Vault.read(c),prefs=s.getJSONObject("settings");LocalDate now=LocalDate.now();if(!quiet(prefs)){
   JSONArray drafts=s.getJSONArray("drafts");int count=0;for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).optString("state").equals("review"))count++;
   if(prefs.optBoolean("digest")){if(count>0&&LocalTime.now().getHour()>=prefs.optInt("digestHour",19))alert(c,"digest:"+now,"Your MoneyLens check-in",count+" suggestions are waiting for your review.","inbox");}
   else for(int i=0;i<drafts.length();i++){JSONObject d=drafts.getJSONObject(i);if(d.optString("state").equals("review")&&!d.optBoolean("prompted"))CaptureService.showPrompt(c,d);}
   if(prefs.optBoolean("reminders",true)){JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;String cancel=b.optString("cancelDate");if(!cancel.isEmpty()&&!LocalDate.parse(cancel).isAfter(now.plusDays(2)))alert(c,"cancel:"+b.getString("id")+":"+cancel,"Subscription reminder","A cancellation deadline is approaching. Check Plan.","plan");}}
   if(prefs.optBoolean("budgetAlerts",true)){JSONObject r=Finance.report(s,now);JSONArray b=r.getJSONArray("budgets");int threshold=prefs.optInt("threshold",80);for(int i=0;i<b.length();i++){JSONObject row=b.getJSONObject(i);if(row.getLong("available")>0&&row.getLong("spent")*100>=row.getLong("available")*threshold)alert(c,"budget:"+row.getString("id")+":"+row.getString("start")+":"+threshold,"Budget check-in","A category has reached your alert threshold. Open Plan for details.","plan");}}
  }
  autoBackup(c,false);MoneyWidget.refresh(c);
  AlarmReceiver.scheduleNext(c);
 }
 public static void configure(Context c,Uri uri,char[] password)throws Exception{byte[] salt=Crypto.random(16),key=Crypto.derive(password,salt).getEncoded();JSONObject cfg=new JSONObject().put("uri",uri.toString()).put("salt",android.util.Base64.encodeToString(salt,2)).put("key",android.util.Base64.encodeToString(key,2));Vault.writeSecret(c,"auto-backup",cfg.toString().getBytes(StandardCharsets.UTF_8));Arrays.fill(key,(byte)0);c.getSharedPreferences("health",0).edit().putBoolean("autoBackup",true).apply();autoBackup(c,true);}
 public static void autoBackup(Context c,boolean force)throws Exception{
  android.content.SharedPreferences health=c.getSharedPreferences("health",0);if(!health.getBoolean("autoBackup",false))return;if(!force&&System.currentTimeMillis()-health.getLong("backupSuccess",0)<24*3600000L)return;
  Uri dest=null;try{JSONObject cfg=new JSONObject(new String(Vault.readSecret(c,"auto-backup"),StandardCharsets.UTF_8));Uri tree=Uri.parse(cfg.getString("uri")),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));byte[] bytes=Crypto.backupWithKey(Vault.backupPlain(c),android.util.Base64.decode(cfg.getString("salt"),2),android.util.Base64.decode(cfg.getString("key"),2));dest=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/octet-stream","MoneyLens-auto-"+System.currentTimeMillis()+".moneylens");if(dest==null)throw new IOException();try(OutputStream out=c.getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException();out.write(bytes);out.flush();}health.edit().putLong("backupSuccess",System.currentTimeMillis()).putString("backupError","").apply();
  }catch(Exception e){if(dest!=null)try{DocumentsContract.deleteDocument(c.getContentResolver(),dest);}catch(Exception ignored){}health.edit().putString("backupError","Backup folder is unavailable or full. Choose the folder again or make a manual backup.").apply();if(force)throw e;}
 }
}
