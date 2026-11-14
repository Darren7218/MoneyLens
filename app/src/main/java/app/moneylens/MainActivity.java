package app.moneylens;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
  private static final String BASE="https://appassets.androidplatform.net/";
  public static volatile boolean visibleUnlocked=false;
  private static volatile MainActivity activeInstance=null;
  private static int testCursor=0;
  private String stagedCsv;private JSONArray stagedRows;private String scanLanguage="latin";private android.net.Uri cameraUri;private char[] autoPassword;
  private boolean pageReady=false;private WebView web;private LinearLayout lockOverlay;private CancellationSignal bioCancel;private long lastPausedAt=0L;private boolean awaitingExternalResult=false;
  private final ExecutorService io=Executors.newSingleThreadExecutor();private volatile boolean unlocked=false;private boolean authenticating=false;private byte[] pendingExport;private String exportToast="Encrypted backup saved.";private char[] restorePassword;private String startScreen="home";private String pendingEditNoteId="";
  public static void notifyCaptured(){MainActivity a=activeInstance;if(a!=null&&a.unlocked)a.js("window.nativeRefresh && window.nativeRefresh()");}
  @Override public void onCreate(Bundle b){super.onCreate(b);activeInstance=this;startScreen=screen(getIntent());pendingEditNoteId=String.valueOf(getIntent().getStringExtra("editNoteId")==null?"":getIntent().getStringExtra("editNoteId"));
    getSharedPreferences("health",0).edit().putBoolean("pendingBootOpen",false).apply();
    try{getSystemService(NotificationManager.class).cancel(2108);}catch(Exception ignored){}
    try{android.service.notification.NotificationListenerService.requestRebind(new ComponentName(this,CaptureService.class));}catch(Exception ignored){}
    Automation.schedule(this);shortcuts();
    Vault.configureClassifierLazy(this);
    web=new WebView(this);web.setBackgroundColor(0xfff7f8f2);WebSettings w=web.getSettings();w.setJavaScriptEnabled(true);w.setDomStorageEnabled(false);w.setAllowFileAccess(false);w.setAllowContentAccess(false);w.setAllowFileAccessFromFileURLs(false);w.setAllowUniversalAccessFromFileURLs(false);w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);w.setSupportMultipleWindows(false);
    web.addJavascriptInterface(new Bridge(),"MoneyLensNative");
    web.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){return true;}
      @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
        String path=r.getUrl().getPath();if(!"https".equals(r.getUrl().getScheme())||!"appassets.androidplatform.net".equals(r.getUrl().getHost()))return blocked();
        if(path!=null&&path.startsWith("/receipt/")&&unlocked){try{return new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(Vault.receipt(MainActivity.this,path.substring(9))));}catch(Exception e){return blocked();}}
        if(!Arrays.asList("/index.html","/style.css","/i18n.js","/format.js","/views/home.js","/views/plan.js","/views/transactions.js","/views/settings.js","/actions.js","/app.js").contains(path))return blocked();
        try{return new WebResourceResponse(path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":"text/html","UTF-8",getAssets().open(path.substring(1)));}catch(IOException e){return blocked();}
      }
      @Override public void onPageFinished(WebView v,String url){pageReady=true;if(unlocked){refreshScreen();handleShare();}}
    });
    FrameLayout root=new FrameLayout(this);
    root.setBackgroundColor(0xfff7f8f2);
    lockOverlay=buildLockOverlay();
    root.addView(web,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(lockOverlay,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    setContentView(root);web.setVisibility(View.INVISIBLE);lockOverlay.setVisibility(View.GONE);web.loadUrl(BASE+"index.html");
  }
  private LinearLayout buildLockOverlay(){
    float dp=getResources().getDisplayMetrics().density;
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER);
    box.setBackgroundColor(0xfff7f8f2);
    int pad=(int)(32*dp);
    box.setPadding(pad,pad,pad,pad);
    TextView badge=new TextView(this);
    badge.setText("M");
    badge.setTextColor(Color.WHITE);
    badge.setTextSize(26f);
    badge.setTypeface(Typeface.DEFAULT_BOLD);
    badge.setGravity(Gravity.CENTER);
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(0xff146c43);
    bg.setCornerRadius(20*dp);
    badge.setBackground(bg);
    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams((int)(64*dp),(int)(64*dp));
    bp.bottomMargin=(int)(20*dp);
    box.addView(badge,bp);
    TextView title=new TextView(this);
    title.setText("MoneyLens Vault Locked");
    title.setTextSize(22f);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(0xff182218);
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    TextView sub=new TextView(this);
    sub.setText("Verify your phone fingerprint, face, or screen lock to view your financial records.");
    sub.setTextSize(14f);
    sub.setTextColor(0xff526052);
    sub.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    sp.topMargin=(int)(10*dp);
    sp.bottomMargin=(int)(28*dp);
    box.addView(sub,sp);
    Button btn=new Button(this);
    btn.setText("Unlock with Fingerprint / Biometrics");
    btn.setAllCaps(false);
    btn.setTextColor(Color.WHITE);
    btn.setTextSize(15f);
    GradientDrawable btnBg=new GradientDrawable();
    btnBg.setColor(0xff146c43);
    btnBg.setCornerRadius(14*dp);
    btn.setBackground(btnBg);
    btn.setPadding((int)(24*dp),(int)(14*dp),(int)(24*dp),(int)(14*dp));
    btn.setOnClickListener(v->authenticate());
    box.addView(btn,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    return box;
  }
  private void updateSecureFlag(boolean secure){
    try{
      if(secure)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }catch(Exception ignored){}
  }
  private void launchExternal(Intent i,int req){
    awaitingExternalResult=true;
    startActivityForResult(i,req);
  }
  private boolean isBiometricAvailable(){
    try{
      if(Build.VERSION.SDK_INT>=29){
        BiometricManager bm=getSystemService(BiometricManager.class);
        if(bm!=null){
          int res=Build.VERSION.SDK_INT>=30
            ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG|BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            : bm.canAuthenticate();
          return res==BiometricManager.BIOMETRIC_SUCCESS;
        }
      }
      KeyguardManager k=getSystemService(KeyguardManager.class);
      return k!=null&&k.isDeviceSecure();
    }catch(Exception e){return false;}
  }
  private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
  private String screen(Intent i){String s=i.getStringExtra("screen");return Arrays.asList("inbox","plan","quick","scan","favorites","transactions").contains(s)?s:"home";}
  @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);startScreen=screen(i);pendingEditNoteId=String.valueOf(i.getStringExtra("editNoteId")==null?"":i.getStringExtra("editNoteId"));if(unlocked){refreshScreen();handleShare();}}
  @Override protected void onResume(){super.onResume();activeInstance=this;if(authenticating)return;final boolean wasExternal=awaitingExternalResult;awaitingExternalResult=false;io.execute(()->{try{boolean lock,blur;int timeoutSec;if(Vault.hasMeta(this)){lock=Vault.fastBool(this,"appLock",false);blur=Vault.fastBool(this,"privacyBlur",false);timeoutSec=Vault.fastInt(this,"lockTimeoutSec",0);}else{JSONObject settings=Vault.read(this).getJSONObject("settings");lock=settings.optBoolean("appLock");blur=settings.optBoolean("privacyBlur");timeoutSec=settings.optInt("lockTimeoutSec",0);}long elapsedMs=lastPausedAt>0?(System.currentTimeMillis()-lastPausedAt):Long.MAX_VALUE;boolean withinGrace=unlocked&&(wasExternal||(timeoutSec>0&&elapsedMs<timeoutSec*1000L));runOnUiThread(()->{updateSecureFlag(lock||blur);if(lock&&!withinGrace){unlocked=false;visibleUnlocked=false;if(lockOverlay!=null)lockOverlay.setVisibility(View.VISIBLE);if(web!=null)web.setVisibility(View.INVISIBLE);authenticate();}else unlock();});}catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Vault unavailable").setMessage("Your encrypted data could not be opened. It has not been replaced or deleted. Restore a backup to recover, or close the app.").setPositiveButton("Restore backup",(d,x)->passwordDialog(true)).setNegativeButton("Close",(d,x)->finish()).show());}});}
  @Override protected void onPause(){super.onPause();if(awaitingExternalResult||authenticating)return;lastPausedAt=System.currentTimeMillis();visibleUnlocked=false;io.execute(()->{try{Health.flush(this);boolean lock,zeroTimeout;if(Vault.hasMeta(this)){lock=Vault.fastBool(this,"appLock",false);zeroTimeout=Vault.fastInt(this,"lockTimeoutSec",0)==0;}else{JSONObject settings=Vault.read(this).getJSONObject("settings");lock=settings.optBoolean("appLock");zeroTimeout=settings.optInt("lockTimeoutSec",0)==0;}if(lock&&zeroTimeout){unlocked=false;Vault.trimCache();runOnUiThread(()->{if(web!=null)web.setVisibility(View.INVISIBLE);if(lockOverlay!=null)lockOverlay.setVisibility(View.VISIBLE);});}}catch(Exception ignored){}});}
  @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_UI_HIDDEN){Health.flush(this);Vault.trimCache();}}
  @Override protected void onDestroy(){Health.flush(this);if(bioCancel!=null){try{bioCancel.cancel();}catch(Exception ignored){}bioCancel=null;}if(activeInstance==this)activeInstance=null;super.onDestroy();}
  private void refreshScreen(){if(!pageReady)return;String noteId=pendingEditNoteId;pendingEditNoteId="";js("window.nativeRefresh && window.nativeRefresh("+JSONObject.quote(startScreen)+","+JSONObject.quote(noteId)+")");startScreen="home";}
  private void unlock(){unlocked=true;visibleUnlocked=true;lastPausedAt=System.currentTimeMillis();if(lockOverlay!=null)lockOverlay.setVisibility(View.GONE);if(web!=null)web.setVisibility(View.VISIBLE);refreshScreen();handleShare();}
  private void fallbackCredentialIntent(){
    KeyguardManager k=getSystemService(KeyguardManager.class);
    Intent intent=k!=null?k.createConfirmDeviceCredentialIntent("Unlock MoneyLens","Confirm your fingerprint or device screen lock to view your finances."):null;
    if(intent==null){toast("Set a phone fingerprint or screen lock in Android Settings first.");unlock();return;}
    authenticating=true;
    startActivityForResult(intent,42);
  }
  private void authenticate(){
    KeyguardManager k=getSystemService(KeyguardManager.class);
    if(k==null||!k.isDeviceSecure()){toast("Set a phone fingerprint or screen lock in Android Settings first.");unlock();return;}
    if(lockOverlay!=null)lockOverlay.setVisibility(View.VISIBLE);
    if(web!=null)web.setVisibility(View.INVISIBLE);
    if(authenticating)return;
    try{
      BiometricPrompt.Builder builder=new BiometricPrompt.Builder(this)
        .setTitle("Unlock MoneyLens")
        .setSubtitle("Fingerprint & Biometric Security")
        .setDescription("Touch your fingerprint sensor or confirm your screen lock to view your finances.");
      if(Build.VERSION.SDK_INT>=30){
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG|BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL);
      }else if(Build.VERSION.SDK_INT==29){
        builder.setDeviceCredentialAllowed(true);
      }else{
        builder.setNegativeButton("Use PIN / Pattern",getMainExecutor(),(dlg,w)->{authenticating=false;fallbackCredentialIntent();});
      }
      BiometricPrompt prompt=builder.build();
      bioCancel=new CancellationSignal();
      authenticating=true;
      prompt.authenticate(bioCancel,getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
          authenticating=false;bioCancel=null;unlock();
        }
        @Override public void onAuthenticationError(int errorCode,CharSequence errString){
          authenticating=false;bioCancel=null;
          if(Build.VERSION.SDK_INT<29&&(errorCode==BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT||errorCode==BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS)){
            fallbackCredentialIntent();
          }
        }
        @Override public void onAuthenticationFailed(){}
      });
    }catch(Exception e){
      authenticating=false;
      fallbackCredentialIntent();
    }
  }
  @Override public void onBackPressed(){if(unlocked)js("window.back && window.back()");else super.onBackPressed();}
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(unlocked)js("window.nativeRefresh && window.nativeRefresh()");}
  private void js(String s){runOnUiThread(()->{if(web!=null)web.evaluateJavascript(s,null);});}
  private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
  private JSONObject status()throws Exception{
    String listeners=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");ComponentName ours=new ComponentName(this,CaptureService.class);boolean access=false;if(listeners!=null)for(String item:listeners.split(":"))if(ours.equals(ComponentName.unflattenFromString(item)))access=true;
    PowerManager pm=getSystemService(PowerManager.class);
    boolean batteryOk=pm!=null&&pm.isIgnoringBatteryOptimizations(getPackageName());
    return new JSONObject().put("notificationAccess",access).put("listenerConnected",CaptureService.connected).put("schedulerRunning",KeepAliveService.running).put("batteryUnrestricted",batteryOk).put("biometricAvailable",isBiometricAvailable()).put("promptPermission",getSystemService(NotificationManager.class).areNotificationsEnabled()).put("overlayPermission",Settings.canDrawOverlays(this)).put("autoOpenOnBoot",getSharedPreferences("health",0).getBoolean("autoOpenOnBoot",true)).put("alwaysOnScheduler",getSharedPreferences("health",0).getBoolean("alwaysOnScheduler",true)).put("ecoCapture",!Vault.hasMeta(this)||Vault.fastBool(this,"ecoCapture",true)).put("error",getSharedPreferences("health",0).getString("error","")).put("version","0.10.0").put("autoBackup",getSharedPreferences("health",0).getBoolean("autoBackup",false)).put("backupSuccess",getSharedPreferences("health",0).getLong("backupSuccess",0)).put("backupError",getSharedPreferences("health",0).getString("backupError","")).put("recovery",new File(getNoBackupFilesDir(),"before-restore.vault").exists());
  }
  private JSONObject sendTestNotification(String presetKey)throws Exception{
    String[][] templates={
      {"toll","my.com.tngdigital.ewallet","Touch 'n Go eWallet","PayDirect Toll Deduction","You have paid RM2.50 at DUKE TOLL PLAZA.","You have paid RM2.50 at DUKE TOLL PLAZA. Wallet balance: RM84.20."},
      {"food","com.maybank2u.life","MAE by Maybank2u","MAE QRPay","RM 15.50 at RESTORAN PELITA","RM 15.50 at RESTORAN PELITA. Available balance: RM 1,240.00."},
      {"hawker","com.shopee.pay.my","ShopeePay MY","ShopeePay Payment Successful","You have paid RM 8.50 at Nasi Lemak Wanjo Kampung Baru.","You have paid RM 8.50 at Nasi Lemak Wanjo Kampung Baru. ShopeePay balance: RM 64.50."},
      {"bnpl","com.shopee.my","Shopee (SPayLater / ShopeePay)","SPayLater Payment","Payment of RM 49.90 for SPayLater installment completed.","Payment of RM 49.90 for SPayLater installment completed. Available credit limit: RM 1,500.00."},
      {"atome","com.atome.my","Atome BNPL","Atome Instalment Confirmed","Your Atome payment of RM 16.90 at Zus Coffee has succeeded.","Your Atome payment of RM 16.90 at Zus Coffee has succeeded. 1/3 instalment settled."},
      {"payflex","my.com.myboost","Boost / Boost PayFlex","Boost PayFlex QR Payment","You have paid RM 12.00 to Warung Pak Mat using PayFlex.","You have paid RM 12.00 to Warung Pak Mat using PayFlex."},
      {"groceries","my.com.gxbank.mobile","GXBank","GXBank DuitNow QR","You paid RM 34.80 at 99 Speedmart via DuitNow QR.","You paid RM 34.80 at 99 Speedmart via DuitNow QR. Available balance: RM 820.10."},
      {"petrol","my.com.setel.mobile","Setel","Setel Pump & Pay Completed","RM 50.00 paid at PETRONAS Sri Hartamas via Setel.","RM 50.00 paid at PETRONAS Sri Hartamas via Setel. Mesra points earned."},
      {"bills","com.maybank2u.life","MAE by Maybank2u","JomPAY Bill Payment","RM 142.30 paid to Tenaga Nasional (myTNB) Ref: 882104.","RM 142.30 paid to Tenaga Nasional (myTNB) Ref: 882104."},
      {"health","my.com.tngdigital.ewallet","Touch 'n Go eWallet","TNG eWallet Payment","You paid RM 45.00 at Klinik Mediviron Bangsar.","You paid RM 45.00 at Klinik Mediviron Bangsar via DuitNow QR."},
      {"insurance","com.cimb.octo","CIMB OCTO","Direct Debit Successful","RM 220.00 debited for Prudential PruBSN Medical Card.","RM 220.00 debited for Prudential PruBSN Medical Card monthly policy."},
      {"rent","com.maybank2u.life","MAE by Maybank2u","DuitNow Transfer","You transferred RM 1,250.00 for Monthly Rent October.","You transferred RM 1,250.00 for Monthly Rent October via DuitNow."},
      {"family","com.maybank2u.life","MAE by Maybank2u","DuitNow Instant Transfer","RM 300.00 transferred for Parents Allowance.","RM 300.00 transferred for Parents Allowance. Ref: MAK & AYAH."},
      {"shopping","com.shopee.my","Shopee MY","Order Payment Confirmed","RM 68.00 paid at UNIQLO Malaysia via ShopeePay.","RM 68.00 paid at UNIQLO Malaysia via ShopeePay."},
      {"education","my.com.tngdigital.ewallet","Touch 'n Go eWallet","eWallet Payment","You have paid RM 39.90 at POPULAR Bookstore.","You have paid RM 39.90 at POPULAR Bookstore."},
      {"entertainment","my.com.tngdigital.ewallet","Touch 'n Go eWallet","GSC Cinema Tickets","You paid RM 42.00 at GSC Mid Valley.","You paid RM 42.00 at GSC Mid Valley via TNG eWallet."},
      {"ptptn","com.maybank2u.life","Maybank2u","JomPAY Payment","You have paid RM 150.00 to PTPTN Direct Debit.","You have paid RM 150.00 to PTPTN Direct Debit. Ref: 9928172."}
    };
    int idx=(testCursor++)%templates.length;
    if(presetKey!=null&&!presetKey.isEmpty()){
      for(int i=0;i<templates.length;i++)if(templates[i][0].equalsIgnoreCase(presetKey)){idx=i;break;}
    }
    String[] tpl=templates[idx];
    long now=System.currentTimeMillis();
    NotificationManager nm=getSystemService(NotificationManager.class);
    if(nm!=null&&nm.areNotificationsEnabled()){
      nm.createNotificationChannel(new NotificationChannel("test_payment","Simulated bank & wallet alerts",NotificationManager.IMPORTANCE_HIGH));
      Bundle b=new Bundle();
      b.putBoolean("moneylensTestPayment",true);
      b.putString("testPackage",tpl[1]);
      b.putString("testLabel",tpl[2]);
      Notification n=new Notification.Builder(this,"test_payment")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tpl[3])
        .setContentText(tpl[4])
        .setStyle(new Notification.BigTextStyle().bigText(tpl[5]))
        .addExtras(b)
        .setAutoCancel(true)
        .build();
      try{nm.notify(3100+idx,n);}catch(SecurityException ignored){}
    }
    if(CaptureService.connected){
      try{Thread.sleep(450);}catch(InterruptedException ignored){}
    }
    JSONObject state=Vault.read(this);
    if(now-state.optLong("lastCapture",0)>2000){
      boolean canNotify=nm!=null&&nm.areNotificationsEnabled();
      JSONObject res=Vault.captureNotification(this,tpl[1],tpl[2],"test-"+now,tpl[3]+" "+tpl[5],now,visibleUnlocked,true,canNotify);
      if(res!=null&&"captured".equals(res.optString("status"))){
        JSONObject sPrefs=res.getJSONObject("settings");
        if(res.optBoolean("shouldPrompt"))try{CaptureService.showPrompt(this,res.getJSONObject("draft"),sPrefs,true);}catch(Exception ignored){}
        JSONArray alerts=res.optJSONArray("categoryAlerts");
        if(alerts!=null&&alerts.length()>0)Automation.dispatchCategoryAlerts(this,alerts,"ms".equals(sPrefs.optString("lang","en")));
      }
      state=Vault.read(this);
    }
    return state;
  }
  private JSONObject sendTestCategoryAlert(String category)throws Exception{
    JSONObject s=Vault.read(this),prefs=s.getJSONObject("settings");
    boolean ms="ms".equals(prefs.optString("lang","en"));
    java.time.LocalDate now=java.time.LocalDate.now();
    JSONArray live=Finance.evaluateCategoryAlerts(s,now,category);
    if(live.length()>0){
      Automation.dispatchCategoryAlerts(this,live,ms);
      return s;
    }
    String cat=(category==null||category.trim().isEmpty())?"Food & drinks":category.trim();
    String catDisp=Finance.categoryLabel(cat,ms);
    String emoji=Extras.categoryEmoji(cat);
    JSONObject sum=Finance.categoryBudgetSummary(s,now,cat,"");
    String title;String body;
    if(sum!=null&&sum.optBoolean("hasBudget")){
      int pct=Math.max(82,sum.optInt("spentPct",82));
      long avail=sum.getLong("available");
      long sampleSpent=Math.round(avail*(pct/100.0));
      long rem=avail-sampleSpent;
      title=emoji+" "+(rem<0?(ms?"Melebihi bajet · ":"Budget exceeded · "):(ms?"Amaran bajet · ":"Budget alert · "))+catDisp+" ("+pct+"%)";
      body=ms?("RM "+BigDecimal.valueOf(sampleSpent,2).toPlainString()+" daripada bajet RM "+BigDecimal.valueOf(avail,2).toPlainString()+" kitaran ini.")
             :("RM "+BigDecimal.valueOf(sampleSpent,2).toPlainString()+" of RM "+BigDecimal.valueOf(avail,2).toPlainString()+" budget this cycle.");
    }else{
      title=emoji+" "+(ms?"Amaran bajet · ":"Budget alert · ")+catDisp+" (85%)";
      body=ms?"RM 425.00 daripada had bajet RM 500.00 telah digunakan (baki RM 75.00 minggu ini).":"RM 425.00 of RM 500.00 limit used (RM 75.00 left this cycle).";
    }
    Automation.notifyCategoryAlert(this,"sample-cat:"+cat,title,body,ms);
    return s;
  }
  public final class Bridge {
    @JavascriptInterface public void request(String rid,String method,String payload){
      if(rid==null||!rid.matches("[0-9]{1,12}"))return;
      io.execute(()->{JSONObject reply=new JSONObject();try{
        if(!unlocked)throw new IllegalStateException("Unlock MoneyLens to continue.");if(payload==null||payload.length()>1000000)throw new IllegalArgumentException("Request too large.");JSONObject args=new JSONObject(payload);Object result;
        if(method.equals("snapshot"))result=new JSONObject().put("state",Vault.read(MainActivity.this)).put("status",status());
        else if(method.equals("report"))result=Finance.report(Vault.read(MainActivity.this),java.time.LocalDate.parse(args.optString("date",java.time.LocalDate.now().toString())));
        else if(method.equals("cycleRecap"))result=Finance.cycleRecap(Vault.read(MainActivity.this),java.time.LocalDate.parse(args.optString("date",java.time.LocalDate.now().toString())));
        else if(method.equals("exportPdf")){java.time.LocalDate dt=java.time.LocalDate.parse(args.optString("date",java.time.LocalDate.now().toString()));pendingExport=PdfReport.build(Vault.read(MainActivity.this),dt);exportToast="PDF report saved.";runOnUiThread(()->launchExternal(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MoneyLens-Recap-"+dt+".pdf"),50));result=true;}
        else if(method.equals("receiptPick")||method.equals("receiptCamera")){scanLanguage=args.optString("language","latin");runOnUiThread(()->pickReceipt(method.equals("receiptCamera")));result=true;}
        else if(method.equals("csvPick")){runOnUiThread(()->launchExternal(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),61));result=true;}
        else if(method.equals("csvPreview")){if(stagedCsv==null)throw new IllegalStateException("Choose a CSV file first.");stagedRows=CsvImport.preview(stagedCsv,args,Vault.read(MainActivity.this));result=stagedRows;}
        else if(method.equals("csvCommit")){if(stagedRows==null)throw new IllegalStateException("Preview the CSV first.");JSONArray selected=args.getJSONArray("indices"),rows=new JSONArray();for(int i=0;i<selected.length();i++){JSONObject row=stagedRows.getJSONObject(selected.getInt(i));if(row.has("error")||row.optBoolean("duplicate"))throw new IllegalArgumentException("Remove invalid or previously imported rows.");rows.put(row);}result=Vault.command(MainActivity.this,"importCommit",new JSONObject().put("batch",Ledger.id()).put("rows",rows));stagedCsv=null;stagedRows=null;}
        else if(method.equals("paste")){String text=args.getString("text");if(text.length()>4000)throw new IllegalArgumentException("Paste at most 4,000 characters.");JSONObject draft=PaymentParser.parse(text,"Shared text");if(draft==null)draft=ReceiptParser.parse(text);draft.put("autoCapture",true);result=Vault.command(MainActivity.this,"draft",draft);MoneyWidget.refresh(MainActivity.this);}
        else if(method.equals("testNotification")){result=sendTestNotification(args.optString("preset",""));MoneyWidget.refresh(MainActivity.this);}
        else if(method.equals("testCategoryAlert")){result=sendTestCategoryAlert(args.optString("category",""));}
        else if(method.equals("testReminder")){
          JSONObject s=Vault.read(MainActivity.this),bill=null;
          String bid=args.optString("id","");
          if(!bid.isEmpty()){try{bill=Ledger.find(s.getJSONArray("bills"),bid);}catch(Exception ignored){}}
          if(bill==null){
            bill=new JSONObject().put("id","sample-bnpl").put("name","SPayLater / PTPTN Monthly Due").put("amount",15000).put("date",java.time.LocalDate.now().plusDays(2).toString()).put("reminderType","bnpl");
          }
          Automation.fireReminderNotification(MainActivity.this,bill,true);
          result=s;
        }
        else if(method.equals("batteryOptimization")){runOnUiThread(()->{try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){}}});result=true;}
        else if(method.equals("recover"))result=Vault.recover(MainActivity.this);
        else if(method.equals("autoBackup")){runOnUiThread(()->autoBackupDialog());result=true;}
        else if(method.equals("stopAutoBackup")){getSharedPreferences("health",0).edit().putBoolean("autoBackup",false).apply();result=true;}
        else if(method.equals("backupNow")){Automation.autoBackup(MainActivity.this,true);result=true;}
        else if(method.equals("diagnosticExport")){exportToast="Diagnostics exported.";pendingExport=args.getString("text").getBytes(StandardCharsets.UTF_8);runOnUiThread(()->launchExternal(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MoneyLens-Diagnostics.txt"),50));result=true;}
        else if(method.equals("diagnostics"))result=new JSONObject().put("version","0.10.0").put("ecoCapture",!Vault.hasMeta(MainActivity.this)||Vault.fastBool(MainActivity.this,"ecoCapture",true)).put("mlClassifierLoaded",MlClassifier.isLoaded()).put("notificationAccess",status().getBoolean("notificationAccess")).put("listenerConnected",CaptureService.connected).put("schedulerRunning",KeepAliveService.running).put("lastCapture",Vault.read(MainActivity.this).optLong("lastCapture")).put("backupSuccess",status().getLong("backupSuccess")).put("workError",getSharedPreferences("health",0).getString("workError","")).put("pendingReviews",countPending()).put("networkPermission",false).put("counters",Health.snapshot(MainActivity.this));
        else if(method.equals("apps")){
          JSONArray apps=new JSONArray();List<ResolveInfo> list=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);Set<String> seen=new HashSet<>();
          List<JSONObject> rows=new ArrayList<>();
          for(ResolveInfo app:list){
            String pkg=app.activityInfo.packageName;if(pkg.equals(getPackageName())||!seen.add(pkg))continue;
            String[] known=PaymentParser.knownAppInfo(pkg);
            String label=app.loadLabel(getPackageManager()).toString();
            if((label==null||label.isEmpty()||label.equals(pkg))&&known!=null)label=known[1];
            rows.add(new JSONObject().put("package",pkg).put("name",label).put("group",known!=null?known[2]:"other").put("installed",true));
          }
          for(String[] k:PaymentParser.KNOWN_APPS){
            if(seen.add(k[0]))rows.add(new JSONObject().put("package",k[0]).put("name",k[1]).put("group",k[2]).put("installed",false));
          }
          rows.sort((a,b)->{
            int ga=a.optString("group","other").equals("other")?1:0,gb=b.optString("group","other").equals("other")?1:0;
            if(ga!=gb)return Integer.compare(ga,gb);
            boolean ia=a.optBoolean("installed",true),ib=b.optBoolean("installed",true);
            if(ia!=ib)return ia?-1:1;
            return a.optString("name").compareToIgnoreCase(b.optString("name"));
          });
          for(JSONObject r:rows)apps.put(r);
          result=apps;
        }else if(method.equals("notificationAccess")){runOnUiThread(()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));result=true;}
        else if(method.equals("overlayPermission")){runOnUiThread(()->{try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}});result=true;}
        else if(method.equals("promptPermission")){runOnUiThread(()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},5);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});result=true;}
        else if(method.equals("csvExport")){runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("Export unencrypted CSV?").setMessage("CSV contains readable financial records. Anyone with the file can open it. Use an encrypted backup for recovery.").setNegativeButton("Cancel",null).setPositiveButton("Export",(d,x)->exportCsv()).show());result=true;}
        else if(method.equals("backup")||method.equals("restore")){runOnUiThread(()->passwordDialog(method.equals("restore")));result=true;}
        else if(method.equals("lockNow")){
          runOnUiThread(()->{
            KeyguardManager k=getSystemService(KeyguardManager.class);
            if(k==null||!k.isDeviceSecure()){toast("Set a phone fingerprint or screen lock in Android Settings first.");return;}
            unlocked=false;visibleUnlocked=false;lastPausedAt=0L;
            updateSecureFlag(true);
            if(lockOverlay!=null)lockOverlay.setVisibility(View.VISIBLE);
            if(web!=null)web.setVisibility(View.INVISIBLE);
            authenticate();
          });
          result=true;
        }
        else {
          if(method.equals("settings")&&args.optBoolean("appLock")&&!getSystemService(KeyguardManager.class).isDeviceSecure())throw new IllegalArgumentException("Set a phone fingerprint or screen lock first.");
          if(method.equals("settings")&&args.has("autoOpenOnBoot"))getSharedPreferences("health",0).edit().putBoolean("autoOpenOnBoot",args.getBoolean("autoOpenOnBoot")).apply();
          if(method.equals("settings")&&args.has("alwaysOnScheduler")){
            boolean on=args.getBoolean("alwaysOnScheduler");
            getSharedPreferences("health",0).edit().putBoolean("alwaysOnScheduler",on).apply();
            if(on)KeepAliveService.ensureRunning(MainActivity.this);else KeepAliveService.stopScheduler(MainActivity.this);
          }
          result=Vault.command(MainActivity.this,method,args);MoneyWidget.refresh(MainActivity.this);
          if(method.equals("settings")){
            JSONObject st=((JSONObject)result).getJSONObject("settings");
            boolean sec=st.optBoolean("appLock")||st.optBoolean("privacyBlur");
            runOnUiThread(()->updateSecureFlag(sec));
            if(args.has("ecoCapture")&&st.optBoolean("alwaysOnScheduler",true))KeepAliveService.ensureRunning(MainActivity.this);
          }
          if(method.equals("bill")||method.equals("deletePlan")||method.equals("cancelBill"))AlarmReceiver.scheduleNext(MainActivity.this);
          if(method.equals("ignore")||method.equals("transaction")){String did=args.optString("draftId",args.optString("id",""));if(!did.isEmpty())getSystemService(NotificationManager.class).cancel(CaptureService.promptId(did));}
        }reply.put("ok",true).put("result",result);
      }catch(Exception e){try{String msg=e instanceof IllegalArgumentException||e instanceof IllegalStateException?e.getMessage():"Could not complete this action. Your existing saved data is unchanged.";reply.put("ok",false).put("error",msg);}catch(Exception ignored){}}
      js("window.nativeResponse && window.nativeResponse("+JSONObject.quote(rid)+","+reply.toString()+")");});
    }
  }
  private void passwordDialog(boolean restore){
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*getResources().getDisplayMetrics().density);box.setPadding(pad,8,pad,0);
    TextView info=new TextView(this);info.setText(restore?"Restore replaces the current ledger after validation. A device-local recovery copy is retained. Notification sources and app lock must be set up again.":"Choose at least 10 characters. Keep this password somewhere safe; there is no password recovery. The backup excludes unconfirmed notifications.");box.addView(info);
    EditText password=new EditText(this);password.setHint("Backup password");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(password);
    EditText confirm=new EditText(this);confirm.setHint("Repeat password");confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);if(!restore)box.addView(confirm);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(restore?"Restore encrypted backup":"Create encrypted backup").setView(box).setNegativeButton("Cancel",null).setPositiveButton(restore?"Choose backup":"Save backup",null).create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String p=password.getText().toString();if(p.length()<10){password.setError("At least 10 characters");return;}if(!restore&&!p.equals(confirm.getText().toString())){confirm.setError("Passwords do not match");return;}char[] chars=p.toCharArray();dialog.dismiss();
      if(restore){restorePassword=chars;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);launchExternal(i,51);}
      else io.execute(()->{try{pendingExport=Vault.exportBackup(this,chars);exportToast="Encrypted backup saved.";runOnUiThread(()->launchExternal(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MoneyLens-"+java.time.LocalDate.now()+".moneylens"),50));}catch(Exception e){toast("Backup could not be created.");}finally{Arrays.fill(chars,'\0');}});
    }));dialog.show();
  }
  private byte[] readLimited(InputStream in)throws Exception{if(in==null)throw new IOException();try(InputStream src=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=src.read(b))!=-1){if(out.size()+n>65_000_000)throw new IOException("Backup too large");out.write(b,0,n);}return out.toByteArray();}}
  @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);
    if(req==42){authenticating=false;if(res==RESULT_OK)unlock();else if(lockOverlay!=null)lockOverlay.setVisibility(View.VISIBLE);return;}
    if(req==60||req==63){if(res==RESULT_OK){android.net.Uri uri=req==63?cameraUri:data==null?null:data.getData();if(uri!=null)scanReceipt(uri);}else cleanupCamera();return;}
    if(req==61&&res==RESULT_OK&&data!=null){io.execute(()->{try{byte[] bytes=readLimited(getContentResolver().openInputStream(data.getData()));if(bytes.length>2_000_000)throw new IllegalArgumentException("CSV limit is 2 MB.");stagedCsv=new String(bytes,StandardCharsets.UTF_8);JSONObject preview=CsvImport.inspect(stagedCsv);js("window.csvLoaded && window.csvLoaded("+preview+")");}catch(Exception e){toast(e instanceof IllegalArgumentException?e.getMessage():"CSV could not be opened.");}});return;}
    if(req==62){char[] password=autoPassword;autoPassword=null;if(res==RESULT_OK&&data!=null&&password!=null){android.net.Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));io.execute(()->{try{Automation.configure(this,uri,password);toast("Daily encrypted backup enabled. First backup saved.");js("window.nativeRefresh()");}catch(Exception e){toast("Could not save in this folder. Choose a writable destination.");}finally{Arrays.fill(password,'\0');}});}catch(Exception e){Arrays.fill(password,'\0');toast("Folder permission could not be retained.");}}else if(password!=null)Arrays.fill(password,'\0');return;}
    if(req==50){byte[] bytes=pendingExport;String doneMsg=exportToast;pendingExport=null;if(res==RESULT_OK&&data!=null&&bytes!=null)io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException();out.write(bytes);out.flush();toast(doneMsg);}catch(Exception e){toast("File was not saved. Try another destination.");}});}
    if(req==51){char[] pass=restorePassword;restorePassword=null;if(res==RESULT_OK&&data!=null&&pass!=null)io.execute(()->{try{Vault.restoreBackup(this,readLimited(getContentResolver().openInputStream(data.getData())),pass);toast("Backup restored. Re-enable capture sources in Settings.");runOnUiThread(()->unlock());}catch(Exception e){toast("Restore failed: wrong password or invalid backup. Current ledger was not replaced.");}finally{Arrays.fill(pass,'\0');}});else if(pass!=null)Arrays.fill(pass,'\0');}
  }
  private int countPending()throws Exception{JSONArray rows=Vault.read(this).getJSONArray("drafts");int n=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("state").equals("review"))n++;return n;}
  private void shortcuts(){try{android.content.pm.ShortcutManager m=getSystemService(android.content.pm.ShortcutManager.class);List<android.content.pm.ShortcutInfo> list=new ArrayList<>();for(String screen:new String[]{"quick","scan","inbox","favorites"})list.add(new android.content.pm.ShortcutInfo.Builder(this,screen).setShortLabel(screen.equals("quick")?"Add expense":screen.equals("scan")?"Scan receipt":screen.equals("favorites")?"Quick favorites":"Review payments").setIcon(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_launcher)).setIntent(new Intent(this,MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("screen",screen)).build());m.setDynamicShortcuts(list);}catch(Exception ignored){}}
  private void pickReceipt(boolean camera){try{if(camera){File folder=new File(getCacheDir(),"camera");folder.mkdirs();File file=new File(folder,"receipt.jpg");cameraUri=androidx.core.content.FileProvider.getUriForFile(this,"app.moneylens.files",file);Intent i=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,cameraUri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Receipt",cameraUri));launchExternal(i,63);}else launchExternal(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),60);}catch(Exception e){toast("Camera unavailable. Choose an image from Photos instead.");}}
  private void cleanupCamera(){new File(getCacheDir(),"camera/receipt.jpg").delete();cameraUri=null;}
  private void scanReceipt(android.net.Uri uri){toast("Reading receipt on your device…");io.execute(()->{try{ReceiptTools.scan(this,uri,scanLanguage);js("window.nativeRefresh('inbox')");toast("Receipt processed.");}catch(Exception e){toast("Could not read this image. Try a smaller, clear receipt photograph.");}finally{cleanupCamera();}});}
  private void handleShare(){Intent intent=getIntent();if(!Intent.ACTION_SEND.equals(intent.getAction()))return;setIntent(new Intent(this,MainActivity.class));String type=intent.getType();if(type!=null&&type.startsWith("image/")){android.net.Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)scanReceipt(uri);}else if("text/plain".equals(type)){String text=intent.getStringExtra(Intent.EXTRA_TEXT);if(text==null||text.length()>4000){toast("Share text up to 4,000 characters.");return;}io.execute(()->{try{JSONObject d=PaymentParser.parse(text,"Shared text");if(d==null)d=ReceiptParser.parse(text);d.put("autoCapture",true);Vault.command(this,"draft",d);MoneyWidget.refresh(this);js("window.nativeRefresh('inbox')");}catch(Exception e){toast("Shared text could not be saved.");}});}}
  private void autoBackupDialog(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,20,40,10);TextView info=new TextView(this);info.setText("Choose a backup password and a writable folder. Android runs daily backups approximately. The derived key is kept encrypted on this device; keep your password for restoring elsewhere. Saved files remain until you remove them.");box.addView(info);EditText p=new EditText(this),confirm=new EditText(this);p.setHint("Password · at least 10 characters");confirm.setHint("Repeat password");p.setInputType(129);confirm.setInputType(129);box.addView(p);box.addView(confirm);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Daily encrypted backups").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Choose folder",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String pass=p.getText().toString();if(pass.length()<10){p.setError("At least 10 characters");return;}if(!pass.equals(confirm.getText().toString())){confirm.setError("Passwords do not match");return;}autoPassword=pass.toCharArray();dialog.dismiss();launchExternal(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),62);}));dialog.show();}
  private String csv(String s){if(s.matches("(?s)^[\\s]*[=+@-].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
  private void exportCsv(){io.execute(()->{try{
    JSONObject s=Vault.read(this);StringBuilder b=new StringBuilder("Date,Type,Amount,Currency,Account,Destination,Category,Subcategory,Merchant,Note\r\n");JSONArray tx=s.getJSONArray("transactions");
    for(int i=0;i<tx.length();i++){JSONObject t=tx.getJSONObject(i);if(t.optBoolean("voided")||t.getString("kind").equals("opening"))continue;String account=Ledger.find(s.getJSONArray("accounts"),t.getString("account")).getString("name"),dest=t.optString("to","");if(!dest.isEmpty())dest=Ledger.find(s.getJSONArray("accounts"),dest).getString("name");String[] row={t.getString("date"),t.getString("kind"),java.math.BigDecimal.valueOf(t.getLong("amount"),2).toPlainString(),"MYR",account,dest,t.optString("category"),t.optString("subcategory"),t.optString("merchant"),t.optString("note")};for(int j=0;j<row.length;j++){if(j>0)b.append(',');b.append(csv(row[j]));}b.append("\r\n");}
    pendingExport=b.toString().getBytes(StandardCharsets.UTF_8);exportToast="CSV exported.";runOnUiThread(()->launchExternal(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MoneyLens-Transactions.csv"),50));
  }catch(Exception e){toast("CSV could not be generated.");}});}
}
