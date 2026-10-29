package app.moneylens;
import android.content.*;
import android.os.Bundle;
import android.app.NotificationManager;
import android.app.RemoteInput;
import org.json.JSONObject;
public final class IgnoreReceiver extends BroadcastReceiver {
  public void onReceive(Context c,Intent i){
    PendingResult pending=goAsync();
    new Thread(()->{
      try{
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
        }else if(id!=null&&!id.isEmpty()){
          Vault.command(c,"ignore",new JSONObject().put("id",id));
          MainActivity.notifyCaptured();
        }
        if(id!=null)c.getSystemService(NotificationManager.class).cancel(CaptureService.promptId(id));
      }catch(Exception ignored){}finally{pending.finish();}
    }).start();
  }
}
