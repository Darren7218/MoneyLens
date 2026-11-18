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
  try{
   long period=4*60*60*1000L;
   long flex=30*60*1000L;
   c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(2107,new ComponentName(c,MoneyJob.class)).setPeriodic(period,flex).setPersisted(true).build());
  }catch(Exception ignored){}
  KeepAliveService.ensureRunning(c);
  AlarmReceiver.scheduleNext(c);
 }
 public static boolean quiet(JSONObject settings){if(!settings.optBoolean("quietEnabled"))return false;int h=LocalTime.now().getHour(),start=settings.optInt("quietStart",22),end=settings.optInt("quietEnd",8);return start==end|| (start<end?h>=start&&h<end:h>=start||h<end);}
 public static boolean notify(Context c,String id,String title,String body,String screen){NotificationManager nm=c.getSystemService(NotificationManager.class);if(!nm.areNotificationsEnabled())return false;nm.createNotificationChannel(new NotificationChannel("planning","Planning & reminders",NotificationManager.IMPORTANCE_DEFAULT));Intent i=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent p=PendingIntent.getActivity(c,id.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);try{nm.notify(id.hashCode(),new Notification.Builder(c,"planning").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(p).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build());return true;}catch(SecurityException e){return false;}}
 public static boolean notifyCategoryAlert(Context c,String id,String title,String body,boolean ms){
  NotificationManager nm=c.getSystemService(NotificationManager.class);
  if(nm==null||!nm.areNotificationsEnabled())return false;
  nm.createNotificationChannel(new NotificationChannel("category_alerts",ms?"Amaran bajet kategori":"Category budget alerts",NotificationManager.IMPORTANCE_DEFAULT));
  Intent i=new Intent(c,MainActivity.class).putExtra("screen","plan").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent p=PendingIntent.getActivity(c,id.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  try{
   Notification pub=new Notification.Builder(c,"category_alerts").setSmallIcon(R.drawable.ic_notification).setContentTitle(ms?"Amaran bajet kategori":"Category budget alert").setContentText(ms?"Buka Rancang di MoneyLens untuk butiran.":"Open Plan in MoneyLens for details.").build();
   nm.notify(id.hashCode(),new Notification.Builder(c,"category_alerts").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setGroup("moneylens_category_alerts").setCategory(Notification.CATEGORY_STATUS).setPublicVersion(pub).setContentIntent(p).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build());
   return true;
  }catch(SecurityException e){return false;}
 }
 public static void dispatchCategoryAlerts(Context c,JSONArray alerts,boolean ms){
  if(alerts==null||alerts.length()==0)return;
  for(int i=0;i<alerts.length();i++){
   try{
    JSONObject row=alerts.getJSONObject(i);
    notifyCategoryAlert(c,row.getString("key"),row.getString("title"),row.getString("body"),ms);
   }catch(Exception ignored){}
  }
 }
 static void alert(Context c,String id,String title,String body,String screen)throws Exception{if(!Vault.alertExists(c,id)&&notify(c,id,title,body,screen))Vault.markAlert(c,id);}

 public static boolean checkBackupNudgeNotification(Context c,JSONObject s,long nowMs)throws Exception{
  JSONObject prefs=s.getJSONObject("settings");
  if(!prefs.optBoolean("backupNudgeNotify",false)||quiet(prefs))return false;
  SharedPreferences hp=c.getApplicationContext().getSharedPreferences("health",0);
  JSONObject meta=new JSONObject().put("backupSuccess",hp.getLong("backupSuccess",0L)).put("lastSnapshotAt",hp.getLong("lastSnapshotAt",0L));
  JSONObject bn=Finance.backupNudge(s,meta,nowMs);
  if(!bn.optBoolean("shouldShow"))return false;
  String alertKey="backup-nudge:"+(nowMs/(14L*86400000L));
  if(Vault.hasAlertInState(s,alertKey))return false;
  boolean ms="ms".equals(prefs.optString("lang","en"));
  String title=ms?("Sandarkan "+bn.optInt("activeTxCount")+" transaksi anda"):("Back up your "+bn.optInt("activeTxCount")+" transactions");
  String body=ms?"Semua data kekal di peranti anda. Eksport sandaran disulitkan atau syot kilat tempatan di MoneyLens.":"Everything stays offline. Save a password-encrypted archive or local snapshot from Home.";
  if(notify(c,alertKey,title,body,"home")){Vault.markAlert(c,alertKey);return true;}
  return false;
 }
 public static void checkDueReminders(Context c)throws Exception{
  if(Vault.hasMeta(c)){
   boolean wantBackup=Vault.fastBool(c,"backupNudgeNotify",false);
   if(!wantBackup&&(!Vault.fastBool(c,"reminders",true)||Vault.fastInt(c,"activeBillsCount",0)==0))return;
   if(Vault.fastBool(c,"quietEnabled",false)){
    int h=LocalTime.now().getHour(),start=Vault.fastInt(c,"quietStart",22),end=Vault.fastInt(c,"quietEnd",8);
    if(start==end||(start<end?h>=start&&h<end:h>=start||h<end))return;
   }
  }
  checkDueReminders(c,Vault.read(c));
 }
 public static void checkDueReminders(Context c,JSONObject s)throws Exception{
  try{checkBackupNudgeNotification(c,s,System.currentTimeMillis());}catch(Exception ignored){}
  JSONObject prefs=s.getJSONObject("settings");
  if(!prefs.optBoolean("reminders",true)||quiet(prefs))return;
  boolean ms="ms".equals(prefs.optString("lang","en"));
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
    if(fireReminderNotification(c,b,false,ms)){
     Vault.markAlert(c,alertKey);
     if(snoozeUntil>0){
      Vault.command(c,"snoozeBill",new JSONObject().put("id",b.getString("id")).put("durationMs",0L));
     }
    }
   }
  }
 }

 public static boolean fireReminderNotification(Context c,JSONObject b,boolean isTest){
  boolean ms=false;
  try{
   ms=Vault.hasMeta(c)?"ms".equals(c.getApplicationContext().getSharedPreferences("vault-meta",0).getString("lang","en")):"ms".equals(Vault.read(c).getJSONObject("settings").optString("lang","en"));
  }catch(Exception ignored){}
  return fireReminderNotification(c,b,isTest,ms);
 }
 public static boolean fireReminderNotification(Context c,JSONObject b,boolean isTest,boolean ms){
  try{
   NotificationManager nm=c.getSystemService(NotificationManager.class);
   if(nm==null||!nm.areNotificationsEnabled())return false;
   NotificationChannel ch=new NotificationChannel("reminders_high",ms?"Makluman BNPL, PTPTN & Bil":"BNPL, PTPTN & Bill Due Alerts",NotificationManager.IMPORTANCE_HIGH);
   ch.setDescription(ms?"Peringatan keutamaan tinggi untuk bayaran BNPL, PTPTN, kad kredit dan bil.":"High-priority payment due alerts for BNPL, PTPTN, credit cards, and bills.");
   nm.createNotificationChannel(ch);
   String id=b.getString("id"),name=b.getString("name"),dueStr=b.getString("date");
   LocalDate due=LocalDate.parse(dueStr),today=LocalDate.now();
   long daysDiff=ChronoUnit.DAYS.between(today,due);
   String amtStr="RM "+BigDecimal.valueOf(b.getLong("amount"),2).toPlainString();
   String rType=b.optString("reminderType","bill");
   String prefix=ms
    ?("bnpl".equals(rType)?"Peringatan Bayaran BNPL":"ptptn".equals(rType)?"Peringatan Bayaran PTPTN":"credit".equals(rType)?"Peringatan Kad Kredit":"Peringatan Bil")
    :("bnpl".equals(rType)?"BNPL Due Reminder":"ptptn".equals(rType)?"PTPTN Repayment Reminder":"credit".equals(rType)?"Credit Card Due Reminder":"Payment Due Reminder");
   String status=ms
    ?(daysDiff<0?("TERTUNGGAK "+Math.abs(daysDiff)+" hari"):daysDiff==0?"Perlu dibayar HARI INI":("Tamat tempoh dalam "+daysDiff+" hari ("+dueStr+")"))
    :(daysDiff<0?("OVERDUE by "+Math.abs(daysDiff)+" day(s)"):daysDiff==0?"Due TODAY":("Due in "+daysDiff+" day(s) ("+dueStr+")"));
   String title=(isTest?(ms?"[Ujian] ":"[Test] "):"")+prefix+" · "+name;
   String body=amtStr+" · "+status+(ms?". Ketik 'Tanda Dibayar' untuk rekod serta-merta atau bayar melalui bank/TNG.":". Tap 'Mark Paid' to record in 1 tap or pay via your bank/TNG.");
   int notifId=Math.abs(("rem_notif:"+id).hashCode());

   Intent open=new Intent(c,MainActivity.class).putExtra("screen","plan").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
   PendingIntent pOpen=PendingIntent.getActivity(c,notifId,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

   Intent payNow=new Intent(c,AlarmReceiver.class).setAction(AlarmReceiver.ACTION_PAY_BILL).putExtra("billId",id).putExtra("notifId",notifId);
   PendingIntent pPayNow=PendingIntent.getBroadcast(c,notifId+3,payNow,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

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
    .addAction(new Notification.Action.Builder(null,ms?"Tanda Dibayar":"Mark Paid",pPayNow).build())
    .addAction(new Notification.Action.Builder(null,ms?"Tunda 1j":"Snooze 1h",pSnooze).build())
    .addAction(new Notification.Action.Builder(null,ms?"Abaikan kitaran":"Dismiss cycle",pDismiss).build())
    .build();
   nm.notify(notifId,n);
   return true;
  }catch(Exception e){return false;}
 }

 public static void run(Context c)throws Exception{
  JSONObject s=Vault.read(c),prefs=s.getJSONObject("settings");
  checkDueReminders(c,s);
  LocalDate now=LocalDate.now();boolean ms="ms".equals(prefs.optString("lang","en"));if(!quiet(prefs)){
   JSONArray drafts=s.getJSONArray("drafts");int count=0;for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).optString("state").equals("review"))count++;
   if(prefs.optBoolean("digest")){if(count>0&&LocalTime.now().getHour()>=prefs.optInt("digestHour",19))alert(c,"digest:"+now,ms?"Semakan MoneyLens anda":"Your MoneyLens check-in",ms?(count+" cadangan menunggu semakan anda."):(count+" suggestions are waiting for your review."),"inbox");}
   else for(int i=0;i<drafts.length();i++){JSONObject d=drafts.getJSONObject(i);if(d.optString("state").equals("review")&&!d.optBoolean("prompted"))CaptureService.showPrompt(c,d);}
   if(prefs.optBoolean("reminders",true)){JSONArray bills=s.getJSONArray("bills");for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;String cancel=b.optString("cancelDate");if(!cancel.isEmpty()&&!LocalDate.parse(cancel).isAfter(now.plusDays(2)))alert(c,"cancel:"+b.getString("id")+":"+cancel,ms?"Peringatan langganan":"Subscription reminder",ms?"Tarikh akhir pembatalan semakin hampir. Semak Rancang.":"A cancellation deadline is approaching. Check Plan.","plan");}}
   if(prefs.optBoolean("budgetAlerts",true)){JSONArray catAlerts=Finance.evaluateCategoryAlerts(s,now,"");for(int i=0;i<catAlerts.length();i++){JSONObject row=catAlerts.getJSONObject(i);String ak=row.getString("key");if(!Vault.alertExists(c,ak)&&notifyCategoryAlert(c,ak,row.getString("title"),row.getString("body"),ms))Vault.markAlert(c,ak);}}
   if(prefs.optBoolean("weeklyBudgetNudge",false)){JSONObject nudge=Finance.weeklyBudgetNudge(s,now);if(nudge!=null)alert(c,nudge.getString("key"),nudge.getString("title"),nudge.getString("body"),"plan");}
  }
  try{Vault.createLocalSnapshot(c,"auto");}catch(Exception ignored){}
  autoBackup(c,false);MoneyWidget.refresh(c,s);
  AlarmReceiver.scheduleNext(c,s);
 }
 public static void configure(Context c,Uri uri,char[] password)throws Exception{byte[] salt=Crypto.random(16),key=Crypto.derive(password,salt).getEncoded();JSONObject cfg=new JSONObject().put("uri",uri.toString()).put("salt",android.util.Base64.encodeToString(salt,2)).put("key",android.util.Base64.encodeToString(key,2));Vault.writeSecret(c,"auto-backup",cfg.toString().getBytes(StandardCharsets.UTF_8));Arrays.fill(key,(byte)0);c.getSharedPreferences("health",0).edit().putBoolean("autoBackup",true).apply();autoBackup(c,true);}
 private static void pruneOldAutoBackups(Context c,Uri tree,int keep){
  try{
   Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
   String[] proj={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME};
   java.util.List<String[]> found=new java.util.ArrayList<>();
   try(android.database.Cursor cur=c.getContentResolver().query(children,proj,null,null,null)){
    while(cur!=null&&cur.moveToNext()){
     String id=cur.getString(0),name=cur.getString(1);
     if(name!=null&&name.matches("MoneyLens-auto-[0-9]+\\.moneylens"))found.add(new String[]{name,id});
    }
   }
   found.sort((a,b)->b[0].compareTo(a[0]));
   for(int i=keep;i<found.size();i++){
    Uri u=DocumentsContract.buildDocumentUriUsingTree(tree,found.get(i)[1]);
    try{DocumentsContract.deleteDocument(c.getContentResolver(),u);}catch(Exception ignored){}
   }
  }catch(Exception ignored){}
 }
 public static void autoBackup(Context c,boolean force)throws Exception{
  try{Vault.createLocalSnapshot(c,force?"manual":"auto");}catch(Exception ignored){}
  android.content.SharedPreferences health=c.getSharedPreferences("health",0);if(!health.getBoolean("autoBackup",false))return;if(!force&&System.currentTimeMillis()-health.getLong("backupSuccess",0)<24*3600000L)return;
  Uri dest=null;try{JSONObject cfg=new JSONObject(new String(Vault.readSecret(c,"auto-backup"),StandardCharsets.UTF_8));Uri tree=Uri.parse(cfg.getString("uri")),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));byte[] bytes=Crypto.backupWithKey(Vault.backupPlain(c),android.util.Base64.decode(cfg.getString("salt"),2),android.util.Base64.decode(cfg.getString("key"),2));dest=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/octet-stream","MoneyLens-auto-"+System.currentTimeMillis()+".moneylens");if(dest==null)throw new IOException();try(OutputStream out=c.getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException();out.write(bytes);out.flush();}health.edit().putLong("backupSuccess",System.currentTimeMillis()).putString("backupError","").apply();pruneOldAutoBackups(c,tree,5);}catch(Exception e){if(dest!=null)try{DocumentsContract.deleteDocument(c.getContentResolver(),dest);}catch(Exception ignored){}health.edit().putString("backupError","Daily backup could not be written. Choose the backup folder again in Settings.").apply();}
 }
 public static void disableBackup(Context c){new File(c.getNoBackupFilesDir(),"auto-backup").delete();c.getSharedPreferences("health",0).edit().putBoolean("autoBackup",false).putString("backupError","").apply();}
}
