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
  public static synchronized JSONObject read(Context c)throws Exception{
    AtomicFile f=file(c);if(!f.getBaseFile().exists()&&!new File(f.getBaseFile()+".bak").exists())return Ledger.initial();
    byte[] plain=Crypto.open(f.readFully(),key(),AAD);JSONObject state=new JSONObject(new String(plain,StandardCharsets.UTF_8));Ledger.migrate(state);return state;
  }
  private static void write(Context c,JSONObject s)throws Exception{
    byte[] plain=s.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>50_000_000)throw new IllegalStateException("The vault has reached this 50 MB limit. Export a backup before continuing.");byte[] bytes=Crypto.seal(plain,key(),AAD);AtomicFile f=file(c);FileOutputStream out=null;
    try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
  }
  public static synchronized JSONObject command(Context c,String action,JSONObject args)throws Exception{
    JSONObject s=Ledger.apply(read(c),action,args);write(c,s);return s;
  }
  public static synchronized byte[] backupPlain(Context c)throws Exception{
    JSONObject s=read(c);s.put("drafts",new JSONArray()).put("seen",new JSONArray()).put("sources",new JSONArray());s.getJSONObject("settings").put("appLock",false);
    java.util.Map<String,byte[]> files=new java.util.LinkedHashMap<>();JSONArray meta=s.getJSONArray("receipts"),keep=new JSONArray();java.util.Set<String> used=new java.util.HashSet<>();JSONArray tx=s.getJSONArray("transactions");for(int i=0;i<tx.length();i++){String id=tx.getJSONObject(i).optString("receiptId");if(!id.isEmpty())used.add(id);}for(int i=0;i<meta.length();i++){JSONObject r=meta.getJSONObject(i);String id=r.getString("id");if(used.contains(id)){files.put(id,receipt(c,id));keep.put(r);}}s.put("receipts",keep);return BackupArchive.pack(s,files);
  }
  public static synchronized byte[] exportBackup(Context c,char[] password)throws Exception{return Crypto.backup(backupPlain(c),password);}
  public static synchronized void restoreBackup(Context c,byte[] raw,char[] password)throws Exception{
    BackupArchive.Contents contents=BackupArchive.unpack(Crypto.restore(raw,password));JSONObject s=contents.state;s.put("drafts",new JSONArray()).put("seen",new JSONArray()).put("sources",new JSONArray());s.getJSONObject("settings").put("appLock",false);
    for(java.util.Map.Entry<String,byte[]> entry:contents.receipts.entrySet())writeSecret(c,"receipts/"+entry.getKey(),entry.getValue());
    AtomicFile old=file(c);if(old.getBaseFile().exists())atomic(new AtomicFile(new File(c.getNoBackupFilesDir(),"before-restore.vault")),old.readFully());write(c,s);
  }
  public static synchronized JSONObject recover(Context c)throws Exception{AtomicFile f=new AtomicFile(new File(c.getNoBackupFilesDir(),"before-restore.vault"));JSONObject s=new JSONObject(new String(Crypto.open(f.readFully(),key(),AAD),StandardCharsets.UTF_8));Ledger.migrate(s);Ledger.validate(s);write(c,s);return s;}
  private static void atomic(AtomicFile f,byte[] bytes)throws Exception{FileOutputStream out=null;try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
  public static synchronized void writeSecret(Context c,String name,byte[] bytes)throws Exception{File f=new File(c.getNoBackupFilesDir(),name);f.getParentFile().mkdirs();atomic(new AtomicFile(f),Crypto.seal(bytes,key(),name.getBytes(StandardCharsets.UTF_8)));}
  public static synchronized byte[] readSecret(Context c,String name)throws Exception{return Crypto.open(new AtomicFile(new File(c.getNoBackupFilesDir(),name)).readFully(),key(),name.getBytes(StandardCharsets.UTF_8));}
  public static synchronized String saveReceipt(Context c,byte[] bytes)throws Exception{if(bytes.length>4_000_000)throw new IllegalArgumentException("Receipt too large.");String id=hex(MessageDigest.getInstance("SHA-256").digest(bytes));writeSecret(c,"receipts/"+id,bytes);JSONObject s=read(c);JSONArray receipts=s.getJSONArray("receipts");boolean exists=false;for(int i=0;i<receipts.length();i++)if(receipts.getJSONObject(i).getString("id").equals(id))exists=true;if(!exists){receipts.put(new JSONObject().put("id",id).put("mime","image/jpeg").put("size",bytes.length));write(c,s);}return id;}
  public static synchronized byte[] receipt(Context c,String id)throws Exception{if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid receipt.");return readSecret(c,"receipts/"+id);}
  public static synchronized void markPrompt(Context c,String id)throws Exception{JSONObject s=read(c);Ledger.find(s.getJSONArray("drafts"),id).put("prompted",true);write(c,s);}
  public static synchronized boolean alertExists(Context c,String id)throws Exception{JSONArray a=read(c).getJSONArray("alerts");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return true;return false;}
  public static synchronized void markAlert(Context c,String id)throws Exception{JSONObject s=read(c);JSONArray a=s.getJSONArray("alerts");a.put(new JSONObject().put("id",id));while(a.length()>2000)a.remove(0);write(c,s);}
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
    boolean isChatApp=!isTest&&pkg!=null&&pkg.matches("(?i).*(whatsapp|telegram|discord|messaging|mms|google\\.android\\.gm|facebook\\.orca|instagram|tencent\\.mm|line\\.android|viber|signal).*");
    if(!selected&&(!allowAll||isChatApp))return new JSONObject().put("status","filtered");
    if(!selected){
      try{android.content.pm.PackageManager pm=c.getPackageManager();CharSequence appLabel=pm.getApplicationLabel(pm.getApplicationInfo(pkg,0));if(appLabel!=null&&appLabel.length()>0)label=appLabel.toString();}catch(Exception ignored){}
    }
    JSONObject d=Extras.captureState(s,pkg,label,key,text,postTime,System.currentTimeMillis());
    if(d==null)return null;
    boolean prompt=Extras.shouldPromptDraft(Automation.quiet(settings),settings,d,canNotify);
    if(prompt&&!d.optBoolean("autoRecorded"))d.put("prompted",true);
    write(c,s);
    try{MoneyWidget.refresh(c,s);}catch(Exception ignored){}
    return new JSONObject().put("status","captured").put("draft",d).put("settings",settings).put("shouldPrompt",prompt);
  }
  public static String hex(byte[] b){return Extras.hex(b);}
  public static void initClassifier(Context c){
    if(c==null||MlClassifier.isLoaded())return;
    for(String asset:new String[]{"moneylens_classifier.json.gz","moneylens_classifier.json"}){
      try(InputStream in=c.getAssets().open(asset)){if(MlClassifier.loadFromStream(in))return;}catch(Exception ignored){}
    }
  }
}
