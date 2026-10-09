package app.moneylens;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Versioned, bounded portable archive. All bytes are authenticated by Crypto before parsing. */
public final class BackupArchive {
 public static final int MAX=64_000_000;
 public static final class Contents {public JSONObject state;public Map<String,byte[]> receipts=new LinkedHashMap<>();}
 public static byte[] pack(JSONObject state,Map<String,byte[]> receipts)throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){
   put(zip,"manifest.json","{\"format\":2,\"currency\":\"MYR\"}".getBytes(StandardCharsets.UTF_8));put(zip,"state.json",state.toString().getBytes(StandardCharsets.UTF_8));int size=out.size();for(Map.Entry<String,byte[]> e:receipts.entrySet()){size+=e.getValue().length;if(size>MAX)throw new IllegalArgumentException("Backup exceeds 64 MB. Export fewer receipts.");put(zip,"receipts/"+e.getKey()+".jpg",e.getValue());}}
  if(out.size()>MAX)throw new IllegalArgumentException("Backup too large.");return out.toByteArray();
 }
 static void put(ZipOutputStream zip,String name,byte[] bytes)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
 public static Contents unpack(byte[] bytes)throws Exception{
  Contents c=new Contents();if(bytes.length>MAX)throw new IllegalArgumentException("Backup too large.");if(bytes.length>0&&bytes[0]=='{'){c.state=new JSONObject(new String(bytes,StandardCharsets.UTF_8));Ledger.migrate(c.state);Ledger.validate(c.state);return c;}
  Set<String> names=new HashSet<>();int total=0;boolean manifest=false;
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null){String name=e.getName();if(!names.add(name)||names.size()>10002||e.isDirectory()||!(name.equals("manifest.json")||name.equals("state.json")||name.matches("receipts/[a-f0-9]{64}\\.jpg")))throw new IllegalArgumentException("Invalid backup path.");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=zip.read(b))!=-1){total+=n;if(total>MAX||out.size()+n>(name.startsWith("receipts/")?4_000_000:50_000_000))throw new IllegalArgumentException("Expanded backup too large.");out.write(b,0,n);}byte[] content=out.toByteArray();if(name.equals("manifest.json")){manifest=new JSONObject(new String(content,StandardCharsets.UTF_8)).getInt("format")==2;}else if(name.equals("state.json"))c.state=new JSONObject(new String(content,StandardCharsets.UTF_8));else {String id=name.substring(9,73);if(!id.equals(hex(MessageDigest.getInstance("SHA-256").digest(content))))throw new IllegalArgumentException("Receipt integrity check failed.");c.receipts.put(id,content);}}}
  if(!manifest||c.state==null)throw new IllegalArgumentException("Incomplete backup.");Ledger.migrate(c.state);Ledger.validate(c.state);JSONArray receipts=c.state.getJSONArray("receipts");Set<String> ids=new HashSet<>();for(int i=0;i<receipts.length();i++){String id=receipts.getJSONObject(i).getString("id");if(!ids.add(id)||!c.receipts.containsKey(id))throw new IllegalArgumentException("Missing receipt.");}for(int i=0;i<c.state.getJSONArray("transactions").length();i++){String id=c.state.getJSONArray("transactions").getJSONObject(i).optString("receiptId");if(!id.isEmpty()&&!ids.contains(id))throw new IllegalArgumentException("Missing receipt reference.");}return c;
 }
 static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format("%02x",x));return out.toString();}
}
