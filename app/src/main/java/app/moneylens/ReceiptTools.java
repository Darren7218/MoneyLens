package app.moneylens;
import android.content.Context;
import android.net.Uri;
import android.graphics.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

public final class ReceiptTools {
 public static JSONObject scan(Context c,Uri uri,String language)throws Exception{
  if(uri==null||!"content".equals(uri.getScheme()))throw new IllegalArgumentException("Choose an image from the document picker.");
  Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,source)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();if(w<=0||h<=0||((long)w*h)>150_000_000)throw new IllegalArgumentException("Image dimensions too large.");double scale=Math.min(1,2048.0/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
  try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);String text="";TextRecognizer engine="chinese".equals(language)?TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()):TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);try{text=Tasks.await(engine.process(InputImage.fromBitmap(bitmap,0)),45,TimeUnit.SECONDS).getText();}catch(Exception ignored){}finally{engine.close();}
   JSONObject d=ReceiptParser.parse(text);d.put("receiptId",Vault.saveReceipt(c,out.toByteArray())).put("autoCapture",true);if(text.isEmpty())d.put("reason","Image saved. Text could not be read; enter the amount and merchant manually.");return Vault.command(c,"draft",d);
  }finally{bitmap.recycle();}
 }
}
