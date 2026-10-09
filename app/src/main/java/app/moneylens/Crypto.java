package app.moneylens;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.Arrays;
import java.nio.ByteBuffer;

public final class Crypto {
  static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
  public static byte[] seal(byte[] plain,SecretKey key,byte[] aad)throws Exception{
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(aad);byte[] iv=c.getIV(),out=c.doFinal(plain);return ByteBuffer.allocate(1+iv.length+out.length).put((byte)iv.length).put(iv).put(out).array();
  }
  public static byte[] open(byte[] data,SecretKey key,byte[] aad)throws Exception{
    if(data.length<30||data[0]!=12)throw new IllegalArgumentException("Invalid encrypted file.");
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(data,1,13)));c.updateAAD(aad);return c.doFinal(data,13,data.length-13);
  }
  static SecretKey derive(char[] password,byte[] salt)throws Exception{
    PBEKeySpec spec=new PBEKeySpec(password,salt,310000,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}
  }
  public static byte[] backup(byte[] plain,char[] password)throws Exception{
    if(password.length<10)throw new IllegalArgumentException("Use a backup password of at least 10 characters.");byte[] salt=random(16),header=ByteBuffer.allocate(20).put(new byte[]{77,76,66,1}).put(salt).array();byte[] enc=seal(plain,derive(password,salt),header);return ByteBuffer.allocate(header.length+enc.length).put(header).put(enc).array();
  }
  public static byte[] backupWithKey(byte[] plain,byte[] salt,byte[] key)throws Exception{
    byte[] header=ByteBuffer.allocate(20).put(new byte[]{77,76,66,1}).put(salt).array();byte[] enc=seal(plain,new SecretKeySpec(key,"AES"),header);return ByteBuffer.allocate(header.length+enc.length).put(header).put(enc).array();
  }
  public static byte[] restore(byte[] file,char[] password)throws Exception{
    if(file.length<50||file.length>65_000_000||file[0]!=77||file[1]!=76||file[2]!=66||file[3]!=1)throw new IllegalArgumentException("Not a supported MoneyLens backup.");byte[] head=Arrays.copyOf(file,20);return open(Arrays.copyOfRange(file,20,file.length),derive(password,Arrays.copyOfRange(file,4,20)),head);
  }
}
