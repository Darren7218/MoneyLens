package app.moneylens;
import android.content.*;
import android.app.NotificationManager;
import org.json.JSONObject;
public final class IgnoreReceiver extends BroadcastReceiver {
  public void onReceive(Context c,Intent i){
    PendingResult pending=goAsync();
    new Thread(()->{
      try{
        String id=i.getStringExtra("id"),txId=i.getStringExtra("voidTxId");
        if(txId!=null&&!txId.isEmpty()){
          Vault.command(c,"void",new JSONObject().put("id",txId));
          MoneyWidget.refresh(c);
        }else if(id!=null&&!id.isEmpty()){
          Vault.command(c,"ignore",new JSONObject().put("id",id));
        }
        if(id!=null)c.getSystemService(NotificationManager.class).cancel(CaptureService.promptId(id));
      }catch(Exception ignored){}finally{pending.finish();}
    }).start();
  }
}
