package app.moneylens;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

public final class BootReceiver extends BroadcastReceiver {
  private static volatile boolean openedThisBoot=false;
  @Override public void onReceive(Context c,Intent i){
    String action=i==null?"":String.valueOf(i.getAction());
    try{NotificationListenerService.requestRebind(new ComponentName(c,CaptureService.class));}catch(Exception ignored){}
    UserManager um=c.getSystemService(UserManager.class);
    if(um!=null&&!um.isUserUnlocked())return;
    try{KeepAliveService.ensureRunning(c);}catch(Exception ignored){}
    try{Automation.schedule(c);}catch(Exception ignored){}
    SharedPreferences prefs=c.getSharedPreferences("health",0);
    boolean isBoot=action.contains("BOOT")||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    if(isBoot)prefs.edit().putBoolean("pendingBootOpen",true).apply();
    if((isBoot||Intent.ACTION_USER_PRESENT.equals(action))&&prefs.getBoolean("autoOpenOnBoot",true)){
      if(!isBoot&&openedThisBoot&&!prefs.getBoolean("pendingBootOpen",false))return;
      launchOrNotify(c,prefs);
    }
  }
  public static void launchOrNotify(Context c,SharedPreferences prefs){
    KeyguardManager km=c.getSystemService(KeyguardManager.class);
    boolean locked=km!=null&&km.isKeyguardLocked();
    Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if(!locked){
      openedThisBoot=true;prefs.edit().putBoolean("pendingBootOpen",false).apply();
      try{c.startActivity(open);if(Settings.canDrawOverlays(c))return;}catch(Exception ignored){}
    }
    try{
      NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm==null||!nm.areNotificationsEnabled())return;
      nm.createNotificationChannel(new NotificationChannel("boot","Startup & auto-capture",NotificationManager.IMPORTANCE_HIGH));
      PendingIntent pi=PendingIntent.getActivity(c,2108,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Notification n=new Notification.Builder(c,"boot").setSmallIcon(R.drawable.ic_notification).setContentTitle("MoneyLens is active").setContentText("Auto-capture & BNPL/PTPTN task scheduler are running. Tap to open.").setContentIntent(pi).setFullScreenIntent(pi,true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PUBLIC).build();
      nm.notify(2108,n);
    }catch(Exception ignored){}
  }
}
