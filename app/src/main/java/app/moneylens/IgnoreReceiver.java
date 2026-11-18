package app.moneylens;
import android.content.*;
import android.os.Bundle;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import org.json.JSONArray;
import org.json.JSONObject;
public final class IgnoreReceiver extends BroadcastReceiver {
  private static final String QUICK_CH="moneylens_quick_entry";
  private void notifyQuickFeedback(Context c,String title,String body,String screen,String txId,boolean ms){
    try{
      NotificationManager nm=c.getSystemService(NotificationManager.class);
      if(nm==null)return;
      nm.createNotificationChannel(new NotificationChannel(QUICK_CH,"Quick entry confirmations",NotificationManager.IMPORTANCE_DEFAULT));
      Intent open=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent openPi=PendingIntent.getActivity(c,2114,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Notification.Builder b=new Notification.Builder(c,QUICK_CH)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(openPi);
      if(txId!=null&&!txId.isEmpty()){
        Intent voidIntent=new Intent(c,IgnoreReceiver.class).putExtra("voidTxId",txId);
        PendingIntent undoPi=PendingIntent.getBroadcast(c,2115,voidIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null,ms?"Buat Asal":"Undo",undoPi).build());
      }
      nm.notify(KeepAliveService.QUICK_FEEDBACK_ID,b.build());
    }catch(Exception ignored){}
  }
  private void postShadeQuickText(Context c,String rawText,boolean ms){
    if(Vault.hasMeta(c)&&Vault.fastBool(c,"appLock",false)){
      KeepAliveService.refreshNotification(c);
      c.startActivity(new Intent(c,MainActivity.class).putExtra("screen","quick").setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));
      return;
    }
    if(rawText==null||rawText.trim().isEmpty()){
      KeepAliveService.refreshNotification(c);
      return;
    }
    try{
      JSONObject st=Vault.command(c,"quickPost",new JSONObject().put("text",rawText.trim()).put("key","shade:"+System.currentTimeMillis()));
      MoneyWidget.refresh(c,st);
      MainActivity.notifyCaptured();
      KeepAliveService.refreshNotification(c);
      JSONArray tx=st.optJSONArray("transactions");
      JSONObject last=(tx!=null&&tx.length()>0)?tx.getJSONObject(tx.length()-1):null;
      if(last!=null){
        String catDisp=Finance.categoryLabel(last.optString("category","Miscellaneous"),ms);
        String amtStr=Extras.rm(last.optLong("amount",0L));
        notifyQuickFeedback(c,"✓ RM "+amtStr+" · "+last.optString("merchant","Quick expense"),catDisp+(last.has("person")?" · "+last.optString("person"):""),"transactions",last.getString("id"),ms);
      }
    }catch(IllegalArgumentException bad){
      KeepAliveService.refreshNotification(c);
      notifyQuickFeedback(c,ms?"Rekod Pantas perlukan amaun":"Quick Log needs an amount",bad.getMessage(),"quick","",ms);
    }catch(Exception ignored){
      KeepAliveService.refreshNotification(c);
    }
  }
  public void onReceive(Context c,Intent i){
    PendingResult pending=goAsync();
    new Thread(()->{
      try{
        boolean ms=Vault.hasMeta(c)&&"ms".equals(Vault.fastStr(c,"lang","en"));
        if(KeepAliveService.ACTION_QUICK_ENTRY.equals(i.getAction())){
          Bundle remote=RemoteInput.getResultsFromIntent(i);
          CharSequence cs=remote!=null?remote.getCharSequence(KeepAliveService.KEY_QUICK_ENTRY):null;
          postShadeQuickText(c,cs!=null?cs.toString():"",ms);
          return;
        }
        if(KeepAliveService.ACTION_REPEAT_QUICK.equals(i.getAction())){
          String phrase=Vault.hasMeta(c)?Vault.fastStr(c,"topQuickPhrase",""):"";
          postShadeQuickText(c,phrase,ms);
          return;
        }
        String id=i.getStringExtra("id"),txId=i.getStringExtra("voidTxId"),noteTxId=i.getStringExtra("noteTxId");
        Bundle remote=RemoteInput.getResultsFromIntent(i);
        if(noteTxId!=null&&!noteTxId.isEmpty()&&remote!=null){
          CharSequence cs=remote.getCharSequence(CaptureService.KEY_REMOTE_NOTE);
          String note=cs!=null?cs.toString().trim():"";
          if(!note.isEmpty()){
            Vault.command(c,"quickNote",new JSONObject().put("id",noteTxId).put("note",note));
            MoneyWidget.refresh(c);
            MainActivity.notifyCaptured();
          }
        }else if(txId!=null&&!txId.isEmpty()){
          Vault.command(c,"void",new JSONObject().put("id",txId));
          MoneyWidget.refresh(c);
          MainActivity.notifyCaptured();
          NotificationManager nm=c.getSystemService(NotificationManager.class);
          if(nm!=null)nm.cancel(KeepAliveService.QUICK_FEEDBACK_ID);
        }else if(id!=null&&!id.isEmpty()){
          Vault.command(c,"ignore",new JSONObject().put("id",id));
          MainActivity.notifyCaptured();
        }
        if(id!=null)c.getSystemService(NotificationManager.class).cancel(CaptureService.promptId(id));
      }catch(Exception ignored){}finally{pending.finish();}
    }).start();
  }
}
