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
    JSONObject s=read(c);boolean selected=false;JSONArray sources=s.getJSONArray("sources");for(int i=0;i<sources.length();i++)if(sources.getJSONObject(i).getString("package").equals(pkg))selected=true;if(!selected)return null;
    JSONObject d=PaymentParser.parse(text,label);if(d==null)return null;
    String digest=hex(MessageDigest.getInstance("SHA-256").digest((pkg+"|"+key+"|"+text).getBytes(StandardCharsets.UTF_8)));
    JSONArray seen=s.getJSONArray("seen");long now=System.currentTimeMillis();
    for(int i=seen.length()-1;i>=0;i--){JSONObject row=seen.getJSONObject(i);if(now-row.optLong("time")>30L*86400000)seen.remove(i);else if(row.optString("hash").equals(digest))return null;}
    JSONArray drafts=s.getJSONArray("drafts");int pending=0;for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).optString("state").equals("review"))pending++;
    if(pending>=500)throw new IllegalStateException("Review your Inbox before capturing more payments.");
    while(drafts.length()>=1000){int remove=-1;for(int i=0;i<drafts.length();i++)if(!drafts.getJSONObject(i).optString("state").equals("review")){remove=i;break;}if(remove<0)break;drafts.remove(remove);}
    for(int i=drafts.length()-1;i>=0;i--){JSONObject other=drafts.getJSONObject(i);if(d.optLong("amount")>0&&other.optLong("amount")==d.optLong("amount")&&now-other.optLong("created")<300000)d.put("reason",d.getString("reason")+" A similar recent amount exists; check for a duplicate.");}
    d.put("package",pkg);Finance.suggest(s,d);drafts.put(d);seen.put(new JSONObject().put("hash",digest).put("time",now));while(seen.length()>5000)seen.remove(0);s.put("lastCapture",now).put("revision",s.optLong("revision")+1);write(c,s);return d;
  }
  public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
}
