package app.moneylens;
import android.content.*;
import android.app.NotificationManager;
import org.json.JSONObject;
public final class IgnoreReceiver extends BroadcastReceiver {
  public void onReceive(Context c,Intent i){PendingResult pending=goAsync();new Thread(()->{try{String id=i.getStringExtra("id");Vault.command(c,"ignore",new JSONObject().put("id",id));c.getSystemService(NotificationManager.class).cancel(CaptureService.promptId(id));}catch(Exception ignored){}finally{pending.finish();}}).start();}
}
