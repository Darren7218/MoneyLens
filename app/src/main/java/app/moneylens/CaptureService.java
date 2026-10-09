package app.moneylens;
import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.*;
import org.json.*;
import java.util.concurrent.*;

public final class CaptureService extends NotificationListenerService {
  public static volatile boolean connected=false;
  private final ExecutorService queue=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(100));
  @Override public void onListenerConnected(){connected=true;try{StatusBarNotification[] active=getActiveNotifications();if(active!=null)for(StatusBarNotification n:active)onNotificationPosted(n);}catch(SecurityException ignored){}}
  @Override public void onListenerDisconnected(){connected=false;}
  @Override public void onDestroy(){connected=false;queue.shutdown();super.onDestroy();}
  @Override public void onNotificationPosted(StatusBarNotification sbn){
    if(sbn.getPackageName().equals(getPackageName())||(sbn.getNotification().flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
    Health.bump(this,"received");try{queue.execute(()->{try{
      JSONObject state=Vault.read(this);if(!state.getJSONObject("settings").optBoolean("captureWhileLocked",true)&&!MainActivity.visibleUnlocked)return;boolean selected=false;JSONArray sources=state.getJSONArray("sources");String label=sbn.getPackageName();
      for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);if(source.getString("package").equals(sbn.getPackageName())){selected=true;label=source.optString("name",label);break;}}if(!selected){Health.bump(this,"filtered");return;}
      Bundle b=sbn.getNotification().extras;CharSequence title=b.getCharSequence(Notification.EXTRA_TITLE,"");CharSequence body=b.getCharSequence(Notification.EXTRA_BIG_TEXT);if(body==null)body=b.getCharSequence(Notification.EXTRA_TEXT,"");
      String text=title+" "+body;if(text.length()>4000)return;
      JSONObject d=Vault.capture(this,sbn.getPackageName(),label,sbn.getKey(),text);if(d!=null){Health.bump(this,"parsed");showPrompt(this,d);}else Health.bump(this,"duplicateOrUnsupported");
    }catch(Exception e){Health.bump(this,"errors");getSharedPreferences("health",0).edit().putString("error","Capture could not be saved. Open MoneyLens to check your storage and Inbox.").apply();}});}catch(RejectedExecutionException e){getSharedPreferences("health",0).edit().putString("error","Capture queue is full. Check your ledger for missing payments.").apply();}
  }
  static int promptId(String id){return id.hashCode()&0x7fffffff;}
  public static void showPrompt(Context c,JSONObject d)throws Exception{
    JSONObject settings=Vault.read(c).getJSONObject("settings");if(Automation.quiet(settings)||settings.optBoolean("digest"))return;
    NotificationManager nm=c.getSystemService(NotificationManager.class);if(!nm.areNotificationsEnabled())return;nm.createNotificationChannel(new NotificationChannel("payments","Payment suggestions",NotificationManager.IMPORTANCE_DEFAULT));
    String id=d.getString("id");int code=promptId(id);
    Intent open=new Intent(c,MainActivity.class).putExtra("screen","inbox");open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent review=PendingIntent.getActivity(c,code,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Intent ignore=new Intent(c,IgnoreReceiver.class).setAction("ignore."+id).putExtra("id",id);PendingIntent no=PendingIntent.getBroadcast(c,code,ignore,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification n=new Notification.Builder(c,"payments").setSmallIcon(R.drawable.ic_notification).setContentTitle("Record a payment?").setContentText("A new suggestion is ready. Review the details in MoneyLens.").setContentIntent(review).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"Review",review).build()).addAction(new Notification.Action.Builder(null,"Ignore",no).build()).build();
    try{nm.notify(code,n);Vault.markPrompt(c,id);}catch(SecurityException ignored){}
  }
}
