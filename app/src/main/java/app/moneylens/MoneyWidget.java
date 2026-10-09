package app.moneylens;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import org.json.*;
public final class MoneyWidget extends AppWidgetProvider {
 @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){for(int id:ids){RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget);try{JSONObject s=Vault.read(c);if(s.getJSONObject("settings").optBoolean("widgetAmounts")&&!s.getJSONObject("settings").optBoolean("appLock"))v.setTextViewText(R.id.widget_status,"Available estimate · RM "+java.math.BigDecimal.valueOf(Finance.report(s,java.time.LocalDate.now()).getLong("availableToSpend"),2).toPlainString());}catch(Exception ignored){}bind(c,v,R.id.widget_add,"quick");bind(c,v,R.id.widget_inbox,"inbox");bind(c,v,R.id.widget_scan,"scan");m.updateAppWidget(id,v);}}
 static void bind(Context c,RemoteViews v,int id,String screen){Intent i=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);v.setOnClickPendingIntent(id,PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));}
 public static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);new MoneyWidget().onUpdate(c,m,m.getAppWidgetIds(new ComponentName(c,MoneyWidget.class)));}
}
