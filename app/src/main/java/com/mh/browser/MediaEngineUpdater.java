package com.mh.browser;

import android.content.Context;
import android.content.SharedPreferences;
import com.yausername.youtubedl_android.YoutubeDL;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Serializes extractor updates against active yt-dlp operations.
 * Updates are attempted automatically and never overwrite an in-use extractor. */
public final class MediaEngineUpdater {
 private MediaEngineUpdater(){}
 private static final ReentrantReadWriteLock GATE=new ReentrantReadWriteLock(true);
 private static final long DAY=24L*60*60*1000,RETRY=60L*60*1000;
 private static volatile long nextAttempt=0;
 public interface Listener{void finished(String status);}
 public static void check(Context context){
  Context app=context.getApplicationContext();
  if(System.currentTimeMillis()<nextAttempt)return;
  new Thread(()->{
   // Let the browser and any immediate media downloads start first.
   try{Thread.sleep(45000L);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
   checkBlocking(app,false);
  },"mh-extractor-auto-update").start();
 }
 /** Called only from media worker threads; acquires a read lock until releaseUse(). */
 public static void acquireUse(Context context)throws Exception{
  Context app=context.getApplicationContext();
  // Never perform a GitHub update/network check on the download critical path.
  // The extractor bundled with the APK remains usable until a background update succeeds.
  GATE.readLock().lock();
  try{YoutubeDL.getInstance().init(app);}
  catch(Exception e){GATE.readLock().unlock();throw e;}
 }
 public static void releaseUse(){GATE.readLock().unlock();}
 public static void update(Context context,boolean force,Listener listener){
  Context app=context.getApplicationContext();
  new Thread(()->{
   String result=checkBlocking(app,force);
   if(listener!=null)listener.finished(result);
  },"mh-extractor-update").start();
 }
 private static String checkBlocking(Context context,boolean force){
  // Skip an automatic update if any extraction or download is running.
  // Explicit user-requested updates still wait for exclusive access.
  if(force)GATE.writeLock().lock();
  else if(!GATE.writeLock().tryLock())return "Extractor update deferred while media is active";
  try{
   long now=System.currentTimeMillis();
   SharedPreferences prefs=context.getSharedPreferences("mh",0);
   if(!force&&now<nextAttempt)return prefs.getString("mediaEngineLastResult","Update check already completed");
   long last=prefs.getLong("mediaEngineLastSuccess",0);
   if(!force&&now-last<DAY&&last>0){
    nextAttempt=last+DAY;
    return prefs.getString("mediaEngineLastResult","Extractor up to date");
   }
   // Set retry backoff before network activity, even when GitHub is unavailable.
   nextAttempt=now+RETRY;
   String message;
   try{
    YoutubeDL engine=YoutubeDL.getInstance();
    engine.init(context);
    YoutubeDL.UpdateStatus status=engine.updateYoutubeDL(context,
      new YoutubeDL.UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"));
    String version=engine.version(context);
    message="Extractor "+String.valueOf(status)+" • version "+(version==null?"bundled":version);
    prefs.edit().putLong("mediaEngineLastSuccess",System.currentTimeMillis())
      .putString("mediaEngineLastResult",message).apply();
    nextAttempt=System.currentTimeMillis()+DAY;
   }catch(Exception e){
    String detail=e.getMessage();
    message="Automatic extractor update failed: "+(detail==null?e.getClass().getSimpleName():detail);
    prefs.edit().putString("mediaEngineLastResult",message).apply();
   }
   return message;
  }finally{GATE.writeLock().unlock();}
 }
}
