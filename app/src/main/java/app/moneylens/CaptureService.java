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
  private static final long SCAN_COOLDOWN_MS=5*60_000L;
  private static final long IDLE_TRIM_MS=120_000L;
  private final ThreadPoolExecutor queue;
  private final Handler idleHandler=new Handler(Looper.getMainLooper());
  private final Runnable idleTrim=Vault::trimCache;
  private final Map<String,Long> recentNotifs=Collections.synchronizedMap(new LinkedHashMap<String,Long>(128,0.75f,true){
    @Override protected boolean removeEldestEntry(Map.Entry<String,Long> eldest){return size()>128;}
  });
  private BroadcastReceiver unlockReceiver;
  private volatile long lastScanAtMs=0L;

  public CaptureService(){
    ThreadPoolExecutor tpe=new ThreadPoolExecutor(1,1,30L,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100));
    tpe.allowCoreThreadTimeOut(true);
    this.queue=tpe;
  }

  @Override public void onListenerConnected(){
    connected=true;
    Vault.configureClassifierLazy(this);
    registerUnlockReceiver();
    scanActive(true);
  }
  private void registerUnlockReceiver(){
    if(unlockReceiver!=null)return;
    unlockReceiver=new BroadcastReceiver(){
      @Override public void onReceive(Context c,Intent i){
        SharedPreferences prefs=getSharedPreferences("health",0);
        if(prefs.getBoolean("pendingBootOpen",false)&&prefs.getBoolean("autoOpenOnBoot",true)){
          BootReceiver.launchOrNotify(CaptureService.this,prefs);
        }
        boolean eco=!Vault.hasMeta(CaptureService.this)||Vault.fastBool(CaptureService.this,"ecoCapture",true);
        boolean captureLocked=!Vault.hasMeta(CaptureService.this)||Vault.fastBool(CaptureService.this,"captureWhileLocked",true);
        long now=System.currentTimeMillis();
        // When Eco Mode + captureWhileLocked are active, live listener already received notifications while locked; avoid re-scanning 20+ status icons on every unlock.
        if(!captureLocked||!eco||(now-lastScanAtMs>=SCAN_COOLDOWN_MS)){
          scanActive(false);
        }
      }
    };
    try{registerReceiver(unlockReceiver,new IntentFilter(Intent.ACTION_USER_PRESENT));}catch(Exception ignored){}
  }
  private void scanActive(boolean force){
    long now=System.currentTimeMillis();
    if(!force&&now-lastScanAtMs<45_000L)return;
    lastScanAtMs=now;
    try{StatusBarNotification[] active=getActiveNotifications();if(active!=null)for(StatusBarNotification n:active)onNotificationPosted(n);}catch(SecurityException ignored){}
  }
  @Override public void onListenerDisconnected(){connected=false;}
  @Override public void onDestroy(){
    connected=false;
    idleHandler.removeCallbacks(idleTrim);
    Health.flush(this);
    if(unlockReceiver!=null){try{unregisterReceiver(unlockReceiver);}catch(Exception ignored){}unlockReceiver=null;}
    queue.shutdown();
    Vault.trimCache();
    super.onDestroy();
  }
  private static boolean isNoiseNotification(Notification n){
    if(n==null)return true;
    int skipFlags=Notification.FLAG_GROUP_SUMMARY|Notification.FLAG_ONGOING_EVENT|Notification.FLAG_FOREGROUND_SERVICE|Notification.FLAG_LOCAL_ONLY;
    if((n.flags&skipFlags)!=0)return true;
    String cat=n.category;
    if(Notification.CATEGORY_PROGRESS.equals(cat)||Notification.CATEGORY_TRANSPORT.equals(cat)||Notification.CATEGORY_NAVIGATION.equals(cat)||Notification.CATEGORY_CALL.equals(cat)||Notification.CATEGORY_ALARM.equals(cat)||Notification.CATEGORY_STOPWATCH.equals(cat)||Notification.CATEGORY_SYSTEM.equals(cat)||Notification.CATEGORY_SERVICE.equals(cat))return true;
    Bundle ex=n.extras;
    return ex!=null&&ex.getInt(Notification.EXTRA_PROGRESS_MAX,0)>0;
  }
  @Override public void onNotificationPosted(StatusBarNotification sbn){
    if(sbn==null)return;
    Notification n=sbn.getNotification();
    if(n==null||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
    Bundle extras=n.extras;
    boolean isTest=extras!=null&&extras.getBoolean("moneylensTestPayment",false);
    String rawPkg=sbn.getPackageName();
    if(getPackageName().equals(rawPkg)&&!isTest)return;
    if(!isTest&&isNoiseNotification(n)){Health.bump(this,"droppedNoise");return;}
    final String pkg=isTest?extras.getString("testPackage","my.com.tngdigital.ewallet"):rawPkg;
    final String label=isTest?extras.getString("testLabel","TNG eWallet"):pkg;
    final String text=extractText(n);
    if(text.isEmpty()||text.length()>4000)return;
    if(!isTest){
      String dedupKey=sbn.getKey()+"|"+sbn.getPostTime()+"|"+text.hashCode();
      synchronized(recentNotifs){
        if(recentNotifs.containsKey(dedupKey))return;
        recentNotifs.put(dedupKey,System.currentTimeMillis());
      }
      boolean eco=!Vault.hasMeta(this)||Vault.fastBool(this,"ecoCapture",true);
      if(eco){
        String fastDrop=Vault.fastPreFilterCapture(this,pkg,MainActivity.visibleUnlocked,false);
        if("locked".equals(fastDrop))return;
        if("filtered".equals(fastDrop)){Health.bump(this,"filtered");return;}
        if(!PaymentParser.isLikelyPayment(text)){Health.bump(this,"droppedNoise");return;}
      }
    }
    Health.bump(this,"received");
    try{queue.execute(()->{try{
      Vault.configureClassifierLazy(this);
      NotificationManager nm=getSystemService(NotificationManager.class);
      boolean canNotify=nm!=null&&nm.areNotificationsEnabled();
      JSONObject res=Vault.captureNotification(this,pkg,label,sbn.getKey(),text,sbn.getPostTime(),MainActivity.visibleUnlocked,isTest,canNotify);
      if(res==null){Health.bump(this,"duplicateOrUnsupported");return;}
      String status=res.optString("status");
      if("locked".equals(status))return;
      if("filtered".equals(status)){Health.bump(this,"filtered");return;}
      Health.bump(this,"parsed");MainActivity.notifyCaptured();
      JSONObject sPrefs=res.getJSONObject("settings");
      if(res.optBoolean("shouldPrompt"))showPrompt(this,res.getJSONObject("draft"),sPrefs,true);
      JSONArray catAlerts=res.optJSONArray("categoryAlerts");
      if(catAlerts!=null&&catAlerts.length()>0)Automation.dispatchCategoryAlerts(this,catAlerts,"ms".equals(sPrefs.optString("lang","en")));
      if(!MainActivity.visibleUnlocked){
        idleHandler.removeCallbacks(idleTrim);
        idleHandler.postDelayed(idleTrim,IDLE_TRIM_MS);
      }
    }catch(Exception e){Health.bump(this,"errors");getSharedPreferences("health",0).edit().putString("error","Capture could not be saved. Open MoneyLens to check your storage and Inbox.").apply();}});}catch(RejectedExecutionException e){getSharedPreferences("health",0).edit().putString("error","Capture queue is full. Check your ledger for missing payments.").apply();}
  }
  static String extractText(Notification n){
    if(n==null)return "";
    LinkedHashSet<String> parts=new LinkedHashSet<>();
    Bundle b=n.extras;
    if(b!=null){
      for(String k:new String[]{Notification.EXTRA_TITLE,Notification.EXTRA_TITLE_BIG,Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_INFO_TEXT}){
        CharSequence cs=b.getCharSequence(k);
        if(cs!=null){String s=cs.toString().trim();if(!s.isEmpty())parts.add(s);}
      }
      CharSequence[] lines=b.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
      if(lines!=null)for(CharSequence line:lines)if(line!=null){String s=line.toString().trim();if(!s.isEmpty())parts.add(s);}
    }
    if(n.tickerText!=null){String s=n.tickerText.toString().trim();if(!s.isEmpty())parts.add(s);}
    return String.join("\n",parts).trim();
  }
  static int promptId(String id){return id.hashCode()&0x7fffffff;}
  public static void showPrompt(Context c,JSONObject d)throws Exception{
    showPrompt(c,d,Vault.read(c).getJSONObject("settings"),false);
  }
  static void showPrompt(Context c,JSONObject d,JSONObject settings,boolean promptAlreadyMarked)throws Exception{
    NotificationManager nm=c.getSystemService(NotificationManager.class);
    if(!Extras.shouldPromptDraft(Automation.quiet(settings),settings,d,nm!=null&&nm.areNotificationsEnabled()))return;
    boolean ms="ms".equals(settings.optString("lang","en"));
    boolean blur=settings.optBoolean("privacyBlur",false);
    nm.createNotificationChannel(new NotificationChannel("payments",ms?"Cadangan bayaran":"Payment suggestions",NotificationManager.IMPORTANCE_DEFAULT));
    String id=d.getString("id");int code=promptId(id);
    if(d.optBoolean("autoRecorded")){
      String txId=d.optString("transactionId",""),cat=d.optString("category","Miscellaneous");
      Intent editIntent=new Intent(c,MainActivity.class).putExtra("screen","inbox").putExtra("editNoteId",txId).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent editPi=PendingIntent.getActivity(c,code,editIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Intent noteIntent=new Intent(c,IgnoreReceiver.class).setAction("note."+id).putExtra("id",id).putExtra("noteTxId",txId);
      int mutFlag=Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0;
      PendingIntent notePi=PendingIntent.getBroadcast(c,code+1,noteIntent,PendingIntent.FLAG_UPDATE_CURRENT|mutFlag);
      RemoteInput remoteInput=new RemoteInput.Builder(KEY_REMOTE_NOTE).setLabel(ms?"Taip tujuan / nota (cth. Tol pejabat)":"Type purpose / note (e.g. Toll to office)").build();
      Notification.Action inlineNoteAction=new Notification.Action.Builder(null,ms?"Tambah Nota":"Add Note",notePi).addRemoteInput(remoteInput).build();
      Intent undoIntent=new Intent(c,IgnoreReceiver.class).setAction("undo."+id).putExtra("id",id).putExtra("voidTxId",txId);
      PendingIntent undoPi=PendingIntent.getBroadcast(c,code,undoIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      String recTitle=Extras.formatRecordedTitle(d,settings,ms,blur);
      String recBody=Extras.formatRecordedBody(d,settings,ms,blur);
      Notification pubVersion=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle(Extras.categoryEmoji(cat)+" "+(ms?"Bayaran MoneyLens direkod":"MoneyLens payment recorded")).setContentText(ms?"Buka MoneyLens untuk lihat butiran.":"Open MoneyLens to view details.").build();
      Notification n=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle(recTitle).setContentText(recBody).setStyle(new Notification.BigTextStyle().bigText(recBody)).setGroup("moneylens_payments").setCategory(Notification.CATEGORY_STATUS).setPublicVersion(pubVersion).setContentIntent(editPi).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(inlineNoteAction).addAction(new Notification.Action.Builder(null,ms?"Ubah Kategori":"Edit Category",editPi).build()).addAction(new Notification.Action.Builder(null,ms?"Buang":"Remove",undoPi).build()).build();
      try{nm.notify(code,n);}catch(SecurityException ignored){}
      return;
    }
    Intent open=new Intent(c,MainActivity.class).putExtra("screen","inbox");open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent review=PendingIntent.getActivity(c,code,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Intent ignore=new Intent(c,IgnoreReceiver.class).setAction("ignore."+id).putExtra("id",id);PendingIntent no=PendingIntent.getBroadcast(c,code,ignore,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    String revTitle=Extras.formatReviewTitle(d,settings,ms,blur);
    String revBody=Extras.formatReviewBody(d,settings,ms,blur);
    Notification n=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle(revTitle).setContentText(revBody).setStyle(new Notification.BigTextStyle().bigText(revBody)).setGroup("moneylens_payments").setCategory(Notification.CATEGORY_STATUS).setContentIntent(review).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,ms?"Semak":"Review",review).build()).addAction(new Notification.Action.Builder(null,ms?"Abaikan":"Ignore",no).build()).build();
    try{nm.notify(code,n);if(!promptAlreadyMarked)Vault.markPrompt(c,id);}catch(SecurityException ignored){}
  }
}
