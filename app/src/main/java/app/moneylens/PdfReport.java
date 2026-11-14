package app.moneylens;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Builds an offline multi-page A4 PDF recap covering cycle cash-flow, categories, budgets and LHDN claimables. */
public final class PdfReport {
 private static final int W=595,H=842,MARGIN=40,BOTTOM=792;
 private final PdfDocument doc=new PdfDocument();
 private final Paint titlePaint=paint(18,true,0xFF14211A),subPaint=paint(10,false,0xFF56685D),h2Paint=paint(12,true,0xFF14532D),bodyPaint=paint(10,false,0xFF1F2933),boldPaint=paint(10,true,0xFF1F2933),mutedPaint=paint(9,false,0xFF6B7280),linePaint=new Paint();
 private PdfDocument.Page page;
 private Canvas canvas;
 private int pageNum=0;
 private float y=MARGIN;

 private PdfReport(){linePaint.setColor(0xFFDCE5DE);linePaint.setStrokeWidth(1f);}
 private static Paint paint(float sz,boolean bold,int color){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(sz);p.setColor(color);p.setTypeface(Typeface.create(Typeface.SANS_SERIF,bold?Typeface.BOLD:Typeface.NORMAL));return p;}
 private static String rm(long sen){String neg=sen<0?"-":"";return neg+"RM "+BigDecimal.valueOf(Math.abs(sen),2).toPlainString();}
 private static String clip(String s,int max){if(s==null)return "";String t=s.replace('\n',' ').trim();return t.length()<=max?t:t.substring(0,max-1)+"…";}

 private void newPage(){
  if(page!=null){canvas.drawText("MoneyLens · Page "+pageNum,MARGIN,820,mutedPaint);doc.finishPage(page);}
  pageNum++;
  page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,pageNum).create());
  canvas=page.getCanvas();
  y=MARGIN;
  if(pageNum>1){canvas.drawText("MoneyLens Cycle & LHDN Summary (cont.)",MARGIN,y,mutedPaint);y+=18;}
 }
 private void ensure(float need){if(page==null||y+need>BOTTOM)newPage();}
 private void section(String title){ensure(36);y+=10;canvas.drawText(title,MARGIN,y,h2Paint);y+=6;canvas.drawLine(MARGIN,y,W-MARGIN,y,linePaint);y+=14;}
 private void row(String left,String right,Paint lp,Paint rp){
  ensure(18);
  canvas.drawText(clip(left,56),MARGIN,y,lp);
  float rw=rp.measureText(right);
  canvas.drawText(right,W-MARGIN-rw,y,rp);
  y+=16;
 }

 public static byte[] build(JSONObject state,LocalDate on)throws Exception{
  JSONObject recap=Finance.cycleRecap(state,on);
  JSONObject tax=Finance.calculateTax(state,null,on.getYear());
  PdfReport r=new PdfReport();
  try{
   r.newPage();
   r.canvas.drawText("MoneyLens Financial Recap",MARGIN,r.y,r.titlePaint);
   r.y+=18;
   r.canvas.drawText("Cycle: "+recap.getString("start")+" to "+recap.getString("end")+"  ·  Generated "+LocalDate.now()+"  ·  MYR (Offline)",MARGIN,r.y,r.subPaint);
   r.y+=14;

   r.section("1. Cycle Overview");
   long net=recap.getLong("netSaved"),delta=recap.getLong("spentDelta");
   r.row("Total Income",rm(recap.getLong("totalIncome")),r.bodyPaint,r.boldPaint);
   r.row("Total Spending",rm(recap.getLong("totalSpent")),r.bodyPaint,r.boldPaint);
   r.row("Net Saved",(net>=0?"+":"")+rm(net)+" ("+recap.getInt("savingsRatePct")+"%)",r.boldPaint,r.boldPaint);
   r.row("Previous Cycle Spending",rm(recap.getLong("previousSpent"))+" ("+(delta>=0?"+":"")+rm(delta)+" change)",r.bodyPaint,r.bodyPaint);
   r.row("Budgets On Track / Over",recap.getInt("underBudgetCount")+" on track  ·  "+recap.getInt("overBudgetCount")+" over",r.bodyPaint,r.bodyPaint);

   r.section("2. Spending by Category");
   JSONArray cats=recap.getJSONArray("topCategories");
   if(cats.length()==0)r.row("No expenses recorded in this cycle.","",r.mutedPaint,r.mutedPaint);
   for(int i=0;i<cats.length();i++){
    JSONObject c=cats.getJSONObject(i);
    r.row(c.getString("category")+" ("+c.getInt("sharePct")+"%)",rm(c.getLong("amount")),r.bodyPaint,r.boldPaint);
   }

   r.section("3. Top Merchants");
   JSONArray merch=recap.getJSONArray("topMerchants");
   if(merch.length()==0)r.row("No merchant activity recorded.","",r.mutedPaint,r.mutedPaint);
   for(int i=0;i<merch.length();i++){
    JSONObject m=merch.getJSONObject(i);
    r.row(m.getString("merchant")+" ("+m.getInt("count")+"x)",rm(m.getLong("amount")),r.bodyPaint,r.boldPaint);
   }

   JSONArray budgets=recap.getJSONArray("budgets");
   if(budgets.length()>0){
    r.section("4. Budget Performance");
    for(int i=0;i<budgets.length();i++){
     JSONObject b=budgets.getJSONObject(i);
     String lbl=b.getString("category")+(b.optString("subcategory","").isEmpty()?"":" › "+b.getString("subcategory"));
     r.row(lbl+" (avail "+rm(b.getLong("available"))+")",rm(b.getLong("spent"))+" used · left "+rm(b.getLong("remaining")),r.bodyPaint,r.bodyPaint);
    }
   }

   r.section("5. Malaysia LHDN Tax Claimables (YA "+tax.getInt("year")+")");
   r.row("Estimated Annual Net Tax Payable",rm(tax.getLong("netTaxPayable"))+" ("+tax.getString("effectiveRate")+"% eff.)",r.bodyPaint,r.boldPaint);
   JSONArray claim=tax.getJSONArray("claimableItems");
   if(claim.length()==0)r.row("No LHDN-claimable ledger expenses recorded yet for YA "+tax.getInt("year")+".","",r.mutedPaint,r.mutedPaint);
   for(int i=0;i<claim.length();i++){
    JSONObject it=claim.getJSONObject(i);
    String receiptMark=it.optString("receiptId","").isEmpty()?"":" [Receipt]";
    r.row(it.getString("date")+" · "+it.getString("merchant")+" · "+it.getString("bucket")+receiptMark,rm(it.getLong("amount")),r.bodyPaint,r.boldPaint);
   }

   if(r.page!=null){r.canvas.drawText("MoneyLens · Page "+r.pageNum,MARGIN,820,r.mutedPaint);r.doc.finishPage(r.page);}
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   r.doc.writeTo(out);
   return out.toByteArray();
  }finally{
   r.doc.close();
  }
 }
}
