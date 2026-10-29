package app.moneylens;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import org.json.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class MoneyWidget extends AppWidgetProvider {
 @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){
  for(int id:ids){
   RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget);
   try{
    JSONObject s=Vault.read(c),settings=s.getJSONObject("settings");
    if(!settings.optBoolean("appLock")){
     JSONObject r=Finance.report(s,LocalDate.now());
     if(settings.optBoolean("widgetAmounts",true)){
      String avail="RM "+BigDecimal.valueOf(r.getLong("availableToSpend"),2).toPlainString();
      String spent="RM "+BigDecimal.valueOf(r.getLong("spent"),2).toPlainString();
      v.setTextViewText(R.id.widget_status,"Avail "+avail+" · Spent "+spent);
     }
     JSONArray bills=s.optJSONArray("bills");
     String nextDue="";String nextName="";
     if(bills!=null){
      for(int i=0;i<bills.length();i++){
       JSONObject b=bills.getJSONObject(i);
       if(b.optBoolean("paid")||b.optBoolean("cancelled"))continue;
       String d=b.optString("date","");
       if(!d.isEmpty()&&(nextDue.isEmpty()||d.compareTo(nextDue)<0)){
        nextDue=d;nextName=b.optString("name","Bill");
       }
      }
     }
     if(!nextDue.isEmpty()){
      String shortName=nextName.length()>14?nextName.substring(0,14)+"…":nextName;
      v.setTextViewText(R.id.widget_due,"Next: "+shortName+" ("+nextDue.substring(5)+")");
     }
    }
   }catch(Exception ignored){}
   bind(c,v,R.id.widget_add,"quick");
   bind(c,v,R.id.widget_inbox,"inbox");
   bind(c,v,R.id.widget_scan,"scan");
   bind(c,v,R.id.widget_plan,"plan");
   m.updateAppWidget(id,v);
  }
 }
 static void bind(Context c,RemoteViews v,int id,String screen){Intent i=new Intent(c,MainActivity.class).putExtra("screen",screen).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);v.setOnClickPendingIntent(id,PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));}
 public static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);new MoneyWidget().onUpdate(c,m,m.getAppWidgetIds(new ComponentName(c,MoneyWidget.class)));}
}
