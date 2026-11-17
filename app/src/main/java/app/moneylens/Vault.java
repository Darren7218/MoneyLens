package app.moneylens;
import android.content.Context;
import android.util.AtomicFile;
import android.security.keystore.*;
import javax.crypto.*;
import java.security.*;
import java.io.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Encrypted atomic snapshot repository for a single-device personal ledger. */
public final class Vault {
  private static final byte[] AAD="MoneyLens-vault-1".getBytes(StandardCharsets.UTF_8);
  private static AtomicFile file(Context c){return new AtomicFile(new File(c.getNoBackupFilesDir(),"moneylens.vault"));}
  private static SecretKey key()throws Exception{
    KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);
    if(!k.containsAlias("moneylens-v1")){KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder("moneylens-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
    return (SecretKey)k.getKey("moneylens-v1",null);
  }
  private static String cachedPlain=null;
  private static long cachedMtime=-1L;
  private static long cachedLen=-1L;
  public static synchronized void trimCache(){
    cachedPlain=null;cachedMtime=-1L;cachedLen=-1L;
    MlClassifier.trim();
  }
  private static void syncMeta(Context c,JSONObject s){
    try{
      JSONObject st=s.optJSONObject("settings");if(st==null)st=new JSONObject();
      JSONArray bills=s.optJSONArray("bills");int activeBills=0;
      if(bills!=null)for(int i=0;i<bills.length();i++){JSONObject b=bills.getJSONObject(i);if(!b.optBoolean("paid")&&!b.optBoolean("cancelled"))activeBills++;}
      JSONArray sources=s.optJSONArray("sources");java.util.Set<String> pkgs=new java.util.HashSet<>();
      if(sources!=null)for(int i=0;i<sources.length();i++){String p=sources.getJSONObject(i).optString("package","");if(!p.isEmpty())pkgs.add(p);}
      c.getApplicationContext().getSharedPreferences("vault-meta",0).edit()
        .putBoolean("ready",true)
        .putBoolean("captureWhileLocked",st.optBoolean("captureWhileLocked",true))
        .putBoolean("captureAllSources",st.optBoolean("captureAllSources",true))
        .putBoolean("alwaysOnScheduler",st.optBoolean("alwaysOnScheduler",true))
        .putBoolean("ecoCapture",st.optBoolean("ecoCapture",true))
        .putBoolean("autoOpenOnBoot",st.optBoolean("autoOpenOnBoot",true))
        .putBoolean("reminders",st.optBoolean("reminders",true))
        .putBoolean("quietEnabled",st.optBoolean("quietEnabled",false))
        .putInt("quietStart",st.optInt("quietStart",22))
        .putInt("quietEnd",st.optInt("quietEnd",8))
        .putBoolean("appLock",st.optBoolean("appLock",false))
        .putBoolean("privacyBlur",st.optBoolean("privacyBlur",false))
        .putBoolean("darkMode","dark".equals(st.optString("theme","light")))
        .putInt("lockTimeoutSec",st.optInt("lockTimeoutSec",0))
        .putString("lang",st.optString("lang","en"))
        .putInt("activeBillsCount",activeBills)
        .putStringSet("sources",pkgs)
        .apply();
    }catch(Exception ignored){}
  }
  public static boolean hasMeta(Context c){return c.getApplicationContext().getSharedPreferences("vault-meta",0).getBoolean("ready",false);}
  public static boolean fastBool(Context c,String key,boolean def){return c.getApplicationContext().getSharedPreferences("vault-meta",0).getBoolean(key,def);}
  public static int fastInt(Context c,String key,int def){return c.getApplicationContext().getSharedPreferences("vault-meta",0).getInt(key,def);}
  public static String fastStr(Context c,String key,String def){return c.getApplicationContext().getSharedPreferences("vault-meta",0).getString(key,def);}
  public static boolean fastIsChatPkg(String pkg){return pkg!=null&&pkg.matches("(?i).*(whatsapp|telegram|discord|messaging|mms|google\\.android\\.gm|facebook\\.orca|instagram|tencent\\.mm|line\\.android|viber|signal).*");}
  public static String fastPreFilterCapture(Context c,String pkg,boolean visibleUnlocked,boolean isTest){
    if(isTest)return null;
    android.content.SharedPreferences p=c.getApplicationContext().getSharedPreferences("vault-meta",0);
    if(!p.getBoolean("ready",false))return null;
    if(!p.getBoolean("captureWhileLocked",true)&&!visibleUnlocked)return "locked";
    java.util.Set<String> srcs=p.getStringSet("sources",null);
    boolean selected=srcs!=null&&pkg!=null&&srcs.contains(pkg);
    boolean allowAll=p.getBoolean("captureAllSources",true);
    if(!selected&&(!allowAll||fastIsChatPkg(pkg)))return "filtered";
    return null;
  }
  public static synchronized JSONObject read(Context c)throws Exception{
    AtomicFile f=file(c);File base=f.getBaseFile();
    if(!base.exists()&&!new File(base+".bak").exists()){JSONObject init=Ledger.initial();syncMeta(c,init);return init;}
    long mtime=base.lastModified(),len=base.length();
    if(cachedPlain!=null&&mtime==cachedMtime&&len==cachedLen){
      return new JSONObject(cachedPlain);
    }
    byte[] plain=Crypto.open(f.readFully(),key(),AAD);
    String str=new String(plain,StandardCharsets.UTF_8);
    JSONObject state=new JSONObject(str);
    Ledger.migrate(state);
    cachedPlain=state.toString();cachedMtime=base.lastModified();cachedLen=base.length();
    syncMeta(c,state);
    return state;
  }
  private static void write(Context c,JSONObject s)throws Exception{
    String outStr=s.toString();
    byte[] plain=outStr.getBytes(StandardCharsets.UTF_8);if(plain.length>50_000_000)throw new IllegalStateException("The vault has reached this 50 MB limit. Export a backup before continuing.");byte[] bytes=Crypto.seal(plain,key(),AAD);AtomicFile f=file(c);FileOutputStream out=null;
    try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    File base=f.getBaseFile();cachedPlain=outStr;cachedMtime=base.lastModified();cachedLen=base.length();
    syncMeta(c,s);
  }
  public static synchronized JSONObject command(Context c,String action,JSONObject args)throws Exception{
    JSONObject s=Ledger.apply(read(c),action,args);write(c,s);return s;
  }
  public static synchronized byte[] backupPlain(Context c)throws Exception{
    JSONObject s=read(c);s.put("drafts",new JSONArray()).put("seen",new JSONArray()).put("sources",new JSONArray());s.getJSONObject("settings").put("appLock",false);
    java.util.Map<String,byte[]> files=new java.util.LinkedHashMap<>();JSONArray meta=s.getJSONArray("receipts"),keep=new JSONArray();java.util.Set<String> used=new java.util.HashSet<>();JSONArray tx=s.getJSONArray("transactions");for(int i=0;i<tx.length();i++){String id=tx.getJSONObject(i).optString("receiptId");if(!id.isEmpty())used.add(id);}for(int i=0;i<meta.length();i++){JSONObject r=meta.getJSONObject(i);String id=r.getString("id");if(used.contains(id)){files.put(id,receipt(c,id));keep.put(r);}}s.put("receipts",keep);return BackupArchive.pack(s,files);
  }
  public static synchronized byte[] exportBackup(Context c,char[] password)throws Exception{return Crypto.backup(backupPlain(c),password);}
  public static final class InspectedBackup {
    public final BackupArchive.Contents contents;
    public final JSONObject preview;
    InspectedBackup(BackupArchive.Contents contents,JSONObject preview){this.contents=contents;this.preview=preview;}
  }
  private static final int MAX_LOCAL_SNAPSHOTS=5;
  private static String snapPath(String id){
    if(id==null||!id.matches("[0-9]{10,20}(?:_[0-9]+)?"))throw new IllegalArgumentException("Invalid snapshot id.");
    return "auto-snapshots/"+id+".snap";
  }
  public static synchronized JSONObject applyRestoredContents(Context c,BackupArchive.Contents contents)throws Exception{
    JSONObject s=contents.state;
    Ledger.migrate(s);Ledger.validate(s);
    for(java.util.Map.Entry<String,byte[]> entry:contents.receipts.entrySet())writeSecret(c,"receipts/"+entry.getKey(),entry.getValue());
    AtomicFile old=file(c);if(old.getBaseFile().exists())atomic(new AtomicFile(new File(c.getNoBackupFilesDir(),"before-restore.vault")),old.readFully());
    write(c,s);
    return s;
  }
  public static synchronized InspectedBackup inspectBackup(Context c,byte[] raw,char[] password)throws Exception{
    BackupArchive.Contents contents=BackupArchive.unpack(Crypto.restore(raw,password));
    JSONObject s=contents.state;s.put("drafts",new JSONArray()).put("seen",new JSONArray()).put("sources",new JSONArray());s.getJSONObject("settings").put("appLock",false);
    Ledger.migrate(s);Ledger.validate(s);
    JSONObject diff=Finance.diffStates(read(c),s);
    JSONObject preview=new JSONObject().put("diff",diff).put("receiptCount",contents.receipts.size());
    return new InspectedBackup(contents,preview);
  }
  public static synchronized void restoreBackup(Context c,byte[] raw,char[] password)throws Exception{
    applyRestoredContents(c,inspectBackup(c,raw,password).contents);
  }
  public static synchronized JSONArray listLocalSnapshots(Context c)throws Exception{
    File idxFile=new File(c.getNoBackupFilesDir(),"auto-snapshots/index.json");
    if(!idxFile.exists())return new JSONArray();
    JSONArray raw=new JSONArray(new String(readSecret(c,"auto-snapshots/index.json"),StandardCharsets.UTF_8));
    JSONArray valid=new JSONArray();
    for(int i=0;i<raw.length();i++){
      JSONObject row=raw.getJSONObject(i);
      if(new File(c.getNoBackupFilesDir(),snapPath(row.getString("id"))).exists())valid.put(row);
    }
    return valid;
  }
  public static synchronized JSONObject createLocalSnapshot(Context c,String reason)throws Exception{
    JSONObject s=read(c);
    long now=System.currentTimeMillis(),rev=s.optLong("revision",0L);
    JSONArray list=listLocalSnapshots(c);
    if("auto".equals(reason)&&list.length()>0){
      JSONObject top=list.getJSONObject(0);
      if(now-top.optLong("created",0L)<12L*3600000L&&top.optLong("revision",-1L)==rev)return top;
    }
    java.util.Map<String,byte[]> files=new java.util.LinkedHashMap<>();
    java.util.Set<String> used=new java.util.HashSet<>();
    JSONArray tx=s.getJSONArray("transactions"),meta=s.getJSONArray("receipts");
    for(int i=0;i<tx.length();i++){String rid=tx.getJSONObject(i).optString("receiptId");if(!rid.isEmpty())used.add(rid);}
    for(int i=0;i<meta.length();i++){String rid=meta.getJSONObject(i).getString("id");if(used.contains(rid))files.put(rid,receipt(c,rid));}
    byte[] packed=BackupArchive.pack(s,files);
    String snapId=now+"_"+list.length();
    writeSecret(c,snapPath(snapId),packed);
    JSONObject stats=Finance.diffStates(null,s).getJSONObject("candidate");
    JSONObject entry=new JSONObject()
      .put("id",snapId)
      .put("created",now)
      .put("reason",reason==null||reason.isEmpty()?"manual":reason)
      .put("revision",rev)
      .put("txCount",stats.getInt("txCount"))
      .put("accountCount",stats.getInt("accountCount"))
      .put("netBalance",stats.getLong("netBalance"))
      .put("receiptCount",files.size())
      .put("sizeBytes",packed.length);
    JSONArray updated=new JSONArray().put(entry);
    for(int i=0;i<list.length();i++){
      JSONObject old=list.getJSONObject(i);
      if(updated.length()<MAX_LOCAL_SNAPSHOTS)updated.put(old);
      else new File(c.getNoBackupFilesDir(),snapPath(old.getString("id"))).delete();
    }
    writeSecret(c,"auto-snapshots/index.json",updated.toString().getBytes(StandardCharsets.UTF_8));
    c.getApplicationContext().getSharedPreferences("health",0).edit().putLong("lastSnapshotAt",now).putInt("snapshotCount",updated.length()).apply();
    return entry;
  }
  public static synchronized JSONObject previewLocalSnapshot(Context c,String snapId)throws Exception{
    BackupArchive.Contents contents=BackupArchive.unpack(readSecret(c,snapPath(snapId)));
    Ledger.migrate(contents.state);Ledger.validate(contents.state);
    JSONArray list=listLocalSnapshots(c);
    long created=0L;String reason="manual";
    for(int i=0;i<list.length();i++){JSONObject r=list.getJSONObject(i);if(snapId.equals(r.getString("id"))){created=r.optLong("created");reason=r.optString("reason","manual");break;}}
    return new JSONObject().put("id",snapId).put("created",created).put("reason",reason).put("receiptCount",contents.receipts.size()).put("diff",Finance.diffStates(read(c),contents.state));
  }
  public static synchronized JSONObject restoreLocalSnapshot(Context c,String snapId)throws Exception{
    BackupArchive.Contents contents=BackupArchive.unpack(readSecret(c,snapPath(snapId)));
    return applyRestoredContents(c,contents);
  }
  public static synchronized JSONObject recover(Context c)throws Exception{AtomicFile f=new AtomicFile(new File(c.getNoBackupFilesDir(),"before-restore.vault"));JSONObject s=new JSONObject(new String(Crypto.open(f.readFully(),key(),AAD),StandardCharsets.UTF_8));Ledger.migrate(s);Ledger.validate(s);write(c,s);return s;}
  private static void atomic(AtomicFile f,byte[] bytes)throws Exception{FileOutputStream out=null;try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
  public static synchronized void writeSecret(Context c,String name,byte[] bytes)throws Exception{File f=new File(c.getNoBackupFilesDir(),name);f.getParentFile().mkdirs();atomic(new AtomicFile(f),Crypto.seal(bytes,key(),name.getBytes(StandardCharsets.UTF_8)));}
  public static synchronized byte[] readSecret(Context c,String name)throws Exception{return Crypto.open(new AtomicFile(new File(c.getNoBackupFilesDir(),name)).readFully(),key(),name.getBytes(StandardCharsets.UTF_8));}
  public static synchronized String saveReceipt(Context c,byte[] bytes)throws Exception{if(bytes.length>4_000_000)throw new IllegalArgumentException("Receipt too large.");String id=hex(MessageDigest.getInstance("SHA-256").digest(bytes));writeSecret(c,"receipts/"+id,bytes);JSONObject s=read(c);JSONArray receipts=s.getJSONArray("receipts");boolean exists=false;for(int i=0;i<receipts.length();i++)if(receipts.getJSONObject(i).getString("id").equals(id))exists=true;if(!exists){receipts.put(new JSONObject().put("id",id).put("mime","image/jpeg").put("size",bytes.length));write(c,s);}return id;}
  public static synchronized byte[] receipt(Context c,String id)throws Exception{if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid receipt.");return readSecret(c,"receipts/"+id);}
  public static synchronized void markPrompt(Context c,String id)throws Exception{JSONObject s=read(c);Ledger.find(s.getJSONArray("drafts"),id).put("prompted",true);write(c,s);}
  static boolean hasAlertInState(JSONObject s,String id)throws Exception{JSONArray a=s.getJSONArray("alerts");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return true;return false;}
  static void putAlertInState(JSONObject s,String id)throws Exception{JSONArray a=s.getJSONArray("alerts");a.put(new JSONObject().put("id",id));while(a.length()>2000)a.remove(0);}
  public static synchronized boolean alertExists(Context c,String id)throws Exception{return hasAlertInState(read(c),id);}
  public static synchronized void markAlert(Context c,String id)throws Exception{JSONObject s=read(c);putAlertInState(s,id);write(c,s);}
  public static synchronized JSONObject capture(Context c,String pkg,String label,String key,String text)throws Exception{
    return capture(c,pkg,label,key,text,0L);
  }
  public static synchronized JSONObject capture(Context c,String pkg,String label,String key,String text,long postTime)throws Exception{
    JSONObject s=read(c);
    JSONObject d=Extras.captureState(s,pkg,label,key,text,postTime,System.currentTimeMillis());
    if(d==null)return null;
    write(c,s);
    try{MoneyWidget.refresh(c,s);}catch(Exception ignored){}
    return d;
  }
  /** Single read-modify-write notification pipeline to minimize vault encryption and flash churn. */
  public static synchronized JSONObject captureNotification(Context c,String pkg,String fallbackLabel,String key,String text,long postTime,boolean visibleUnlocked,boolean isTest,boolean canNotify)throws Exception{
    JSONObject s=read(c),settings=s.getJSONObject("settings");
    if(!settings.optBoolean("captureWhileLocked",true)&&!visibleUnlocked)return new JSONObject().put("status","locked");
    String label=fallbackLabel;
    boolean selected=isTest;JSONArray sources=s.getJSONArray("sources");
    for(int i=0;i<sources.length();i++){JSONObject src=sources.getJSONObject(i);if(src.getString("package").equals(pkg)){selected=true;label=src.optString("name",label);break;}}
    boolean allowAll=settings.optBoolean("captureAllSources",true);
    boolean isChatApp=!isTest&&fastIsChatPkg(pkg);
    if(!selected&&(!allowAll||isChatApp))return new JSONObject().put("status","filtered");
    if(!selected){
      try{android.content.pm.PackageManager pm=c.getPackageManager();CharSequence appLabel=pm.getApplicationLabel(pm.getApplicationInfo(pkg,0));if(appLabel!=null&&appLabel.length()>0)label=appLabel.toString();}catch(Exception ignored){}
      if(label.equals(pkg)||label.trim().isEmpty()){String known=PaymentParser.knownAppLabel(pkg);if(!known.isEmpty())label=known;}
    }
    JSONObject d=Extras.captureState(s,pkg,label,key,text,postTime,System.currentTimeMillis());
    if(d==null)return null;
    boolean prompt=Extras.shouldPromptDraft(Automation.quiet(settings),settings,d,canNotify);
    if(prompt&&!d.optBoolean("autoRecorded"))d.put("prompted",true);
    JSONArray pendingAlerts=new JSONArray();
    if(d.optBoolean("autoRecorded")&&"expense".equals(d.optString("kind","expense"))&&canNotify&&settings.optBoolean("budgetAlerts",true)&&!Automation.quiet(settings)){
      JSONArray eval=Finance.evaluateCategoryAlerts(s,java.time.LocalDate.now(),d.optString("category",""));
      for(int i=0;i<eval.length();i++){
        JSONObject row=eval.getJSONObject(i);
        String ak=row.getString("key");
        if(!hasAlertInState(s,ak)){
          putAlertInState(s,ak);
          pendingAlerts.put(row);
        }
      }
    }
    write(c,s);
    try{MoneyWidget.refresh(c,s);}catch(Exception ignored){}
    return new JSONObject().put("status","captured").put("draft",d).put("settings",settings).put("shouldPrompt",prompt).put("categoryAlerts",pendingAlerts);
  }
  public static String hex(byte[] b){return Extras.hex(b);}
  /** Registers lazy on-demand loading for the ML classifier so app launch and idle filtering use zero ML RAM. */
  public static void configureClassifierLazy(Context c){
    if(c==null)return;
    final Context app=c.getApplicationContext();
    MlClassifier.setStreamSupplier(()->{
      for(String asset:new String[]{"moneylens_classifier.json.gz","moneylens_classifier.json"}){
        try{return app.getAssets().open(asset);}catch(Exception ignored){}
      }
      return null;
    });
  }
  public static void initClassifier(Context c){
    if(c==null)return;
    configureClassifierLazy(c);
    if(MlClassifier.isLoaded())return;
    for(String asset:new String[]{"moneylens_classifier.json.gz","moneylens_classifier.json"}){
      try(InputStream in=c.getAssets().open(asset)){if(MlClassifier.loadFromStream(in))return;}catch(Exception ignored){}
    }
  }
}
