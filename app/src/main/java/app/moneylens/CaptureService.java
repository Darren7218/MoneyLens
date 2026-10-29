package app.moneylens;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.notification.*;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

public final class CaptureService extends NotificationListenerService {
  public static volatile boolean connected=false;
  public static final String KEY_REMOTE_NOTE="moneylens_remote_note";
  private final ExecutorService queue=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(100));
  private BroadcastReceiver unlockReceiver;
  @Override public void onListenerConnected(){
    connected=true;
    registerUnlockReceiver();
    scanActive();
  }
  private void registerUnlockReceiver(){
    if(unlockReceiver!=null)return;
    unlockReceiver=new BroadcastReceiver(){
      @Override public void onReceive(Context c,Intent i){
        SharedPreferences prefs=getSharedPreferences("health",0);
        if(prefs.getBoolean("pendingBootOpen",false)&&prefs.getBoolean("autoOpenOnBoot",true)){
          BootReceiver.launchOrNotify(CaptureService.this,prefs);
        }
        scanActive();
      }
    };
    try{registerReceiver(unlockReceiver,new IntentFilter(Intent.ACTION_USER_PRESENT));}catch(Exception ignored){}
  }
  private void scanActive(){
    try{StatusBarNotification[] active=getActiveNotifications();if(active!=null)for(StatusBarNotification n:active)onNotificationPosted(n);}catch(SecurityException ignored){}
  }
  @Override public void onListenerDisconnected(){connected=false;}
  @Override public void onDestroy(){
    connected=false;
    if(unlockReceiver!=null){try{unregisterReceiver(unlockReceiver);}catch(Exception ignored){}unlockReceiver=null;}
    queue.shutdown();super.onDestroy();
  }
  @Override public void onNotificationPosted(StatusBarNotification sbn){
    if(sbn==null||(sbn.getNotification().flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
    Bundle extras=sbn.getNotification().extras;
    boolean isTest=extras!=null&&extras.getBoolean("moneylensTestPayment",false);
    if(sbn.getPackageName().equals(getPackageName())&&!isTest)return;
    Health.bump(this,"received");try{queue.execute(()->{try{
      JSONObject state=Vault.read(this),settings=state.getJSONObject("settings");
      if(!settings.optBoolean("captureWhileLocked",true)&&!MainActivity.visibleUnlocked)return;
      String pkg=isTest?extras.getString("testPackage","my.com.tngdigital.ewallet"):sbn.getPackageName();
      String label=isTest?extras.getString("testLabel","TNG eWallet"):pkg;
      boolean selected=isTest;JSONArray sources=state.getJSONArray("sources");
      for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);if(source.getString("package").equals(pkg)){selected=true;label=source.optString("name",label);break;}}
      boolean allowAll=settings.optBoolean("captureAllSources",true);
      boolean isChatApp=!isTest&&pkg.matches("(?i).*(whatsapp|telegram|discord|messaging|mms|google\\.android\\.gm|facebook\\.orca|instagram|tencent\\.mm|line\\.android|viber|signal).*");
      if(!selected&&(!allowAll||isChatApp)){Health.bump(this,"filtered");return;}
      if(!selected){
        try{PackageManager pm=getPackageManager();CharSequence appLabel=pm.getApplicationLabel(pm.getApplicationInfo(pkg,0));if(appLabel!=null&&appLabel.length()>0)label=appLabel.toString();}catch(Exception ignored){}
      }
      String text=extractText(sbn.getNotification());
      if(text.isEmpty()||text.length()>4000)return;
      JSONObject d=Vault.capture(this,pkg,label,sbn.getKey(),text,sbn.getPostTime());
      if(d!=null){Health.bump(this,"parsed");MainActivity.notifyCaptured();showPrompt(this,d);}else Health.bump(this,"duplicateOrUnsupported");
    }catch(Exception e){Health.bump(this,"errors");getSharedPreferences("health",0).edit().putString("error","Capture could not be saved. Open MoneyLens to check your storage and Inbox.").apply();}});}catch(RejectedExecutionException e){getSharedPreferences("health",0).edit().putString("error","Capture queue is full. Check your ledger for missing payments.").apply();}
  }
  static String extractText(Notification n){
    if(n==null)return "";
    List<String> parts=new ArrayList<>();
    Bundle b=n.extras;
    if(b!=null){
      for(String k:new String[]{Notification.EXTRA_TITLE,Notification.EXTRA_TITLE_BIG,Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_INFO_TEXT}){
        CharSequence cs=b.getCharSequence(k);
        if(cs!=null){String s=cs.toString().trim();if(!s.isEmpty()&&!parts.contains(s))parts.add(s);}
      }
      CharSequence[] lines=b.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
      if(lines!=null)for(CharSequence line:lines)if(line!=null){String s=line.toString().trim();if(!s.isEmpty()&&!parts.contains(s))parts.add(s);}
    }
    if(n.tickerText!=null){String s=n.tickerText.toString().trim();if(!s.isEmpty()&&!parts.contains(s))parts.add(s);}
    return String.join(" ",parts).trim();
  }
  static int promptId(String id){return id.hashCode()&0x7fffffff;}
  public static void showPrompt(Context c,JSONObject d)throws Exception{
    JSONObject settings=Vault.read(c).getJSONObject("settings");if(Automation.quiet(settings)||settings.optBoolean("digest"))return;
    NotificationManager nm=c.getSystemService(NotificationManager.class);if(!nm.areNotificationsEnabled())return;nm.createNotificationChannel(new NotificationChannel("payments","Payment suggestions",NotificationManager.IMPORTANCE_DEFAULT));
    String id=d.getString("id");int code=promptId(id);
    if(d.optBoolean("autoRecorded")){
      String txId=d.optString("transactionId",""),amt=BigDecimal.valueOf(d.optLong("amount"),2).toPlainString(),merchant=d.optString("merchant","Payment"),cat=d.optString("category","Miscellaneous"),sub=d.optString("subcategory",""),matchedBill=d.optString("matchedBillName","");
      String catLabel=sub.isEmpty()?cat:(cat+" › "+sub);
      if(!matchedBill.isEmpty())catLabel+=" · Linked to "+matchedBill;
      Intent editIntent=new Intent(c,MainActivity.class).putExtra("screen","inbox").putExtra("editNoteId",txId).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent editPi=PendingIntent.getActivity(c,code,editIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Intent noteIntent=new Intent(c,IgnoreReceiver.class).setAction("note."+id).putExtra("id",id).putExtra("noteTxId",txId);
      int mutFlag=Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0;
      PendingIntent notePi=PendingIntent.getBroadcast(c,code+1,noteIntent,PendingIntent.FLAG_UPDATE_CURRENT|mutFlag);
      RemoteInput remoteInput=new RemoteInput.Builder(KEY_REMOTE_NOTE).setLabel("Type purpose / note (e.g. Toll to office)").build();
      Notification.Action inlineNoteAction=new Notification.Action.Builder(null,"Add Note",notePi).addRemoteInput(remoteInput).build();
      Intent undoIntent=new Intent(c,IgnoreReceiver.class).setAction("undo."+id).putExtra("id",id).putExtra("voidTxId",txId);
      PendingIntent undoPi=PendingIntent.getBroadcast(c,code,undoIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Notification n=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle("Recorded RM "+amt+" · "+merchant).setContentText(catLabel+(d.optBoolean("needsNote")?" · Tap to edit or Add Note":" · Auto-saved")).setContentIntent(editPi).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(inlineNoteAction).addAction(new Notification.Action.Builder(null,"Edit Category",editPi).build()).addAction(new Notification.Action.Builder(null,"Remove",undoPi).build()).build();
      try{nm.notify(code,n);Vault.markPrompt(c,id);}catch(SecurityException ignored){}
      return;
    }
    Intent open=new Intent(c,MainActivity.class).putExtra("screen","inbox");open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent review=PendingIntent.getActivity(c,code,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Intent ignore=new Intent(c,IgnoreReceiver.class).setAction("ignore."+id).putExtra("id",id);PendingIntent no=PendingIntent.getBroadcast(c,code,ignore,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification n=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle("Record a payment?").setContentText("A new suggestion is ready. Review the details in MoneyLens.").setContentIntent(review).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"Review",review).build()).addAction(new Notification.Action.Builder(null,"Ignore",no).build()).build();
    try{nm.notify(code,n);Vault.markPrompt(c,id);}catch(SecurityException ignored){}
  }
}
