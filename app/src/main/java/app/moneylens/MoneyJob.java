package app.moneylens;
import android.app.job.*;
public final class MoneyJob extends JobService {
 private volatile Thread worker;
 @Override public boolean onStartJob(JobParameters p){worker=new Thread(()->{try{Automation.run(this);}catch(Exception e){getSharedPreferences("health",0).edit().putString("workError","Background work could not finish. Open MoneyLens to retry.").apply();}finally{jobFinished(p,false);}});worker.start();return true;}
 @Override public boolean onStopJob(JobParameters p){Thread t=worker;if(t!=null)t.interrupt();return true;}
}
