package com.mh.browser;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import org.json.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import java.io.*;
import java.net.*;
import android.net.Uri;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Environment;
import androidx.core.content.FileProvider;

public class MediaDownloadService extends Service {
 public static final String CHANNEL="mh_media_downloads_v1";
 private final AtomicInteger active=new AtomicInteger();
 private NotificationManager manager;
 private static volatile MediaDownloadService liveService;
 private volatile String foregroundTask;
 private static final java.util.Set<String> deletedTasks=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private static final java.util.concurrent.ConcurrentHashMap<String,Control> controls=new java.util.concurrent.ConcurrentHashMap<>();
 private static class Control implements MediaDownloadEngine.PauseControl{
  volatile boolean paused,done,cancelled;
  volatile String processId;
  volatile long generation;
  synchronized void change(boolean p){
   if(done||paused==p)return;
   paused=p;
   if(p){
    generation++;
    terminateProcessAsync(processId);
   }
   notifyAll();
  }
  @Override public synchronized void await() throws InterruptedException{while(paused&&!done)wait();if(cancelled)throw new InterruptedException("Download deleted");}
  @Override public boolean isCancelled(){return cancelled;}
  synchronized void cancel(){cancelled=true;done=true;generation++;terminateProcessAsync(processId);notifyAll();}
  @Override public long pauseGeneration(){return generation;}
  @Override public void processStarted(String id){
   processId=id;
   if(paused||cancelled)terminateProcessAsync(id);
  }
  @Override public void processFinished(String id){if(id.equals(processId))processId=null;}
  private static void terminateProcessAsync(String id){
   if(id==null)return;
   new Thread(()->{try{com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(id);}catch(Throwable ignored){}},"mh-stop-media").start();
  }
 }
 public static void deleteDownload(Context context,String task){
  MediaDownloadService live=liveService;
  if(live!=null){live.deleteTask(task);return;}
  try{context.startService(new Intent(context,MediaDownloadService.class).setAction("MH_DELETE").putExtra("task",task));}
  catch(IllegalStateException ex){android.util.Log.w("MHDownloads","Cannot stop inactive download service",ex);}
 }
 public static void setPaused(Context context,String task,boolean paused){
  MediaDownloadService live=liveService;
  if(live!=null){live.change(task,paused);return;}
  try{context.startService(new Intent(context,MediaDownloadService.class).setAction(paused?"MH_PAUSE":"MH_RESUME").putExtra("task",task));}
  catch(IllegalStateException ex){android.util.Log.w("MHDownloads","Cannot control inactive download service",ex);}
 }
 private void deleteTask(String task){
  if(task==null||task.isEmpty())return;
  Control c=controls.get(task);
  deletedTasks.add(task);
  if(c!=null)synchronized(c){c.cancel();}
  // Android owns the foreground-service notification until it is detached.
  // Detach first, then cancel the exact notification for this task.
  if(task.equals(foregroundTask)){
   stopForeground(STOP_FOREGROUND_REMOVE);
   foregroundTask=null;
   for(java.util.Map.Entry<String,Control> entry:controls.entrySet()){
    String other=entry.getKey();Control next=entry.getValue();
    if(other.equals(task)||next.cancelled||next.done)continue;
    foregroundTask=other;
    startForeground(notificationId(other),notification("MH Browser • Downloading","Download in progress",0,true,false,other,next.paused));
    break;
   }
  }
  manager.cancel(notificationId(task));
  synchronized(MediaDownloadService.class){
   android.content.SharedPreferences p=getSharedPreferences("mh",0);
   JSONArray src;try{src=new JSONArray(p.getString("downloads","[]"));}catch(Exception e){src=new JSONArray();}
   JSONArray keep=new JSONArray();for(int i=0;i<src.length();i++){JSONObject item=src.optJSONObject(i);if(item!=null&&!task.equals(item.optString("task")))keep.put(item);}
   p.edit().putString("downloads",keep.toString()).commit();
  }
  bytesByTask.remove(task);estimatedTotals.remove(task);
  File partial=partial(this,task);if(partial.exists())partial.delete();
 }
 private void change(String task,boolean pause){
  Control c=controls.get(task);if(c==null||c.done||deletedTasks.contains(task))return;
  c.change(pause);
  JSONArray a;try{a=new JSONArray(getSharedPreferences("mh",0).getString("downloads","[]"));}catch(Exception e){return;}
  for(int k=0;k<a.length();k++){JSONObject item=a.optJSONObject(k);if(item==null||!task.equals(item.optString("task")))continue;
   String name=item.optString("name","Download");int percent=item.optInt("progress");
   String oldDetail=item.optString("detail","");
   save(task,name,pause?"Paused":"Downloading",percent,pause?(oldDetail.isEmpty()?"Paused by user":oldDetail):(oldDetail.isEmpty()?"Resuming…":oldDetail),null,null);
   manager.notify(notificationId(task),notification(pause?"MH Browser • Paused":"MH Browser • Downloading",oldDetail.isEmpty()?name:oldDetail,percent,true,false,task,pause,name));break;
  }
 }
 private static File partial(Context c,String task){
  File d=new File(c.getFilesDir(),"mh-partials");if(!d.exists())d.mkdirs();return new File(d,task+".part");
 }

 private final java.util.concurrent.ConcurrentHashMap<String,Long> lastWriteByTask=new java.util.concurrent.ConcurrentHashMap<>();
 private final java.util.concurrent.ConcurrentHashMap<String,Long> bytesByTask=new java.util.concurrent.ConcurrentHashMap<>();
 private final java.util.concurrent.ConcurrentHashMap<String,Long> estimatedTotals=new java.util.concurrent.ConcurrentHashMap<>();
 @Override public void onCreate(){
  super.onCreate();
  manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  liveService=this;
  if(Build.VERSION.SDK_INT>=26){
   NotificationChannel c=new NotificationChannel(CHANNEL,"Media downloads",NotificationManager.IMPORTANCE_LOW);
   c.setDescription("Download progress and completion for MH Browser");
   manager.createNotificationChannel(c);
  }
 }
 public static void enqueue(Context context,String url,MediaDownloadEngine.Choice choice){
  enqueue(context,url,choice,null,null);
 }
 public static void enqueue(Context context,String url,MediaDownloadEngine.Choice choice,String referer,String agent){
  enqueue(context,url,choice,referer,agent,null);
 }
 public static void enqueue(Context context,String url,MediaDownloadEngine.Choice choice,String referer,String agent,String titleHint){
  Intent intent=new Intent(context,MediaDownloadService.class);
  intent.putExtra("url",url);intent.putExtra("label",choice.label);
  intent.putExtra("height",choice.height);intent.putExtra("audio",choice.audioFormat);
  if(referer!=null)intent.putExtra("referer",referer);
  if(agent!=null)intent.putExtra("userAgent",agent);
  if(titleHint!=null)intent.putExtra("titleHint",titleHint);
  intent.putExtra("task",UUID.randomUUID().toString());
  if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
  else context.startService(intent);
 }
 public static void enqueueDirect(Context context,String url,String filename,String mime,String userAgent,String cookie,String referer){
  Intent intent=new Intent(context,MediaDownloadService.class);
  intent.putExtra("direct",true);intent.putExtra("url",url);
  intent.putExtra("filename",filename);intent.putExtra("mime",mime);
  intent.putExtra("userAgent",userAgent);intent.putExtra("cookie",cookie);
  intent.putExtra("referer",referer);intent.putExtra("task",UUID.randomUUID().toString());
  if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
  else context.startService(intent);
 }
 private static String safeName(String name){
  if(name==null||name.trim().isEmpty())return "download";
  String clean=name.replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]","_").trim();
  if(clean.length()>150)clean=clean.substring(0,150);
  return clean.isEmpty()?"download":clean;
 }
 private void runDirectDownload(String task,int id,int startId,Intent intent){
  String name=safeName(intent.getStringExtra("filename")),mime=intent.getStringExtra("mime");
  File file=partial(this,task);Control c=controls.get(task);boolean success=false;
  try{
   while(true){
    c.await();
    long offset=file.exists()?file.length():0;
    HttpURLConnection connection=(HttpURLConnection)new URL(intent.getStringExtra("url")).openConnection();
    try{
     connection.setInstanceFollowRedirects(true);connection.setConnectTimeout(15000);connection.setReadTimeout(25000);
     for(String[] h:new String[][]{{"User-Agent","userAgent"},{"Cookie","cookie"},{"Referer","referer"}}){
      String value=intent.getStringExtra(h[1]);if(value!=null&&!value.isEmpty())connection.setRequestProperty(h[0],value);
     }
     connection.setRequestProperty("Accept-Encoding","identity");
     if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
     int code=connection.getResponseCode();
     if(code<200||code>=300)throw new IOException("Website returned HTTP "+code);
     if(offset>0&&code!=206){if(!file.delete())throw new IOException("Cannot restart unsupported range response");offset=0;}
     if(code==206){String range=connection.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-"))throw new IOException("Invalid HTTP resume range");}
     if(mime==null||mime.isEmpty()||"*/*".equals(mime)){
      mime=connection.getContentType();if(mime==null||mime.isEmpty())mime="application/octet-stream";
      mime=mime.split(";",2)[0];
     }
     long remain=connection.getContentLengthLong(),total=remain>0?offset+remain:-1,bytes=offset,last=0;
     boolean suspended=false;
     try(InputStream in=new BufferedInputStream(connection.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(file,offset>0))){
      byte[] buffer=new byte[65536];int n;
      while((n=in.read(buffer))!=-1){
       if(c.cancelled)throw new InterruptedException("Download deleted");
       if(c.paused){suspended=true;break;}
       out.write(buffer,0,n);bytes+=n;
       long now=SystemClock.elapsedRealtime();
       if(now-last>=700){
        int percent=total>0?(int)Math.min(99,Math.max(1,bytes*100/total)):1;
        String detail=total>0?formatSize(bytes)+" / "+formatSize(total):formatSize(bytes)+" downloaded";
        synchronized(c){if(!c.cancelled&&!deletedTasks.contains(task)){
         save(task,name,"Downloading",percent,detail,null,null);
         manager.notify(id,notification("MH Browser • Downloading",detail,percent,true,false,task,false,name));
        }}last=now;
       }
      }
      out.flush();
     }
     if(suspended)continue;
     if(file.length()==0)throw new IOException("Downloaded file is empty");
     if(total>0&&file.length()<total)throw new IOException("Incomplete file transfer");
     break;
    }finally{connection.disconnect();}
   }
   c.await();
   if(c.cancelled)throw new InterruptedException("Download deleted");
   Uri stored;
   if(Build.VERSION.SDK_INT>=29){
    ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,mime);
    values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/MH Browser");values.put(MediaStore.Downloads.IS_PENDING,1);
    stored=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    if(stored==null)throw new IOException("Cannot create download");
    try{
     try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(stored)){
      if(out==null)throw new IOException("Cannot write download");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
     }
     ContentValues ready=new ContentValues();ready.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(stored,ready,null,null);
    }catch(Exception ex){getContentResolver().delete(stored,null,null);throw ex;}
   }else{
    File folder=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(folder==null)throw new IOException("Download storage unavailable");
    File dest=new File(folder,name);
    if(dest.exists()){int dot=name.lastIndexOf('.');dest=new File(folder,(dot>0?name.substring(0,dot):name)+"-"+System.currentTimeMillis()+(dot>0?name.substring(dot):""));}
    try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(dest)){
     byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    stored=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",dest);
   }
   if(c.cancelled){getContentResolver().delete(stored,null,null);throw new InterruptedException("Download deleted");}
   success=true;save(task,name,"Completed",100,"Download complete",stored.toString(),mime);
   manager.notify(id,completedNotification(task,name,stored.toString(),mime));
  }catch(InterruptedException ex){Thread.currentThread().interrupt();if(!c.cancelled)save(task,name,"Failed",0,"Download interrupted",null,null);}
  catch(Exception ex){if(!c.cancelled){save(task,name,"Failed",0,ex.getMessage()==null?"Download failed":ex.getMessage(),null,null);
   manager.notify(id,notification("MH Browser • Download failed",shortText(ex.getMessage()),0,false,true));}
  }finally{
   if(success||c.cancelled)file.delete();c.done=true;c.change(false);controls.remove(task,c);lastWriteByTask.remove(task);bytesByTask.remove(task);estimatedTotals.remove(task);finish(startId);
  }
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null)return START_NOT_STICKY;
  if("MH_DELETE".equals(intent.getAction())){deleteTask(intent.getStringExtra("task"));return START_NOT_STICKY;}
  if("MH_PAUSE".equals(intent.getAction())||"MH_RESUME".equals(intent.getAction())){
   change(intent.getStringExtra("task"),"MH_PAUSE".equals(intent.getAction()));return START_NOT_STICKY;
  }
  String url=intent.getStringExtra("url"),task=intent.getStringExtra("task");
  if(url==null||task==null)return START_NOT_STICKY;
  Control control=new Control();controls.put(task,control);
  if(intent.getBooleanExtra("direct",false)){
   String filename=safeName(intent.getStringExtra("filename"));
   int id=notificationId(task);
   active.incrementAndGet();
   foregroundTask=task;
   startForeground(id,notification("MH Browser • Preparing download",filename,0,true,false,task,false));
   save(task,filename,"Preparing",0,"Connecting to website…",null,null);
   new Thread(()->runDirectDownload(task,id,startId,intent),"mh-file-download").start();
   return START_NOT_STICKY;
  }
  String label=intent.getStringExtra("label");
  String titleHint=intent.getStringExtra("titleHint");
  final String displayTitle=titleHint!=null&&!titleHint.trim().isEmpty()?safeName(titleHint):label==null?"Media download":label;
  MediaDownloadEngine.Choice choice=new MediaDownloadEngine.Choice(label==null?"Media":label,
   intent.getIntExtra("height",0),intent.getStringExtra("audio"));
  int id=notificationId(task);
  active.incrementAndGet();
  // Android requires foreground service promotion promptly after startForegroundService.
  foregroundTask=task;
  startForeground(id,notification("MH Browser • Preparing download",displayTitle,0,true,false,task,false));
  save(task,displayTitle,"Preparing",0,"Connecting to media source…",null,null);
  MediaDownloadEngine.download(getApplicationContext(),url,choice,intent.getStringExtra("referer"),intent.getStringExtra("userAgent"),intent.getStringExtra("titleHint"),control,new MediaDownloadEngine.Callback(){
   @Override public void ready(String title,java.util.List<MediaDownloadEngine.Choice> choices){}
   @Override public void progress(int percent,String line){
    if(control.paused||control.cancelled||control.done||deletedTasks.contains(task))return;
    long now=SystemClock.elapsedRealtime();
    Long last=lastWriteByTask.get(task);
    if(last!=null&&now-last<650&&percent<100)return;
    lastWriteByTask.put(task,now);
    String speed=extractSpeed(line);
    String amount=downloadAmount(task,line);
    boolean transferring=percent>0||!amount.isEmpty()||(line!=null&&line.contains("[download]"));
    String detail=transferring?(amount.isEmpty()?(speed.isEmpty()?"Receiving media…":speed):amount+(speed.isEmpty()?"":" • "+speed)):"Connecting to media…";
    synchronized(control){
     if(control.cancelled||deletedTasks.contains(task))return;
     save(task,displayTitle,transferring?"Downloading":"Preparing",percent,detail,null,null);
     manager.notify(id,notification(transferring?"MH Browser • Downloading":"MH Browser • Preparing download",detail,percent,true,false,task,false,displayTitle));
    }
   }
   @Override public void completed(String name,String uri,String mime){
    if(control.cancelled){try{getContentResolver().delete(Uri.parse(uri),null,null);}catch(Exception ignored){}controls.remove(task,control);lastWriteByTask.remove(task);bytesByTask.remove(task);estimatedTotals.remove(task);finish(startId);return;}
    save(task,name,"Completed",100,"Download complete",uri,mime);
    manager.notify(id,completedNotification(task,name,uri,mime));
    control.done=true;control.change(false);controls.remove(task,control);
    lastWriteByTask.remove(task);bytesByTask.remove(task);estimatedTotals.remove(task);
    finish(startId);
   }
   @Override public void failed(String error){
    if(!control.cancelled)save(task,displayTitle,"Failed",0,error,null,null);
    if(!control.cancelled)manager.notify(id,notification("Download failed",shortText(error),0,false,true));
    control.done=true;control.change(false);controls.remove(task,control);
    lastWriteByTask.remove(task);bytesByTask.remove(task);estimatedTotals.remove(task);
    finish(startId);
   }
  });
  return START_NOT_STICKY;
 }
 private synchronized void finish(int startId){
  if(active.decrementAndGet()<=0){
   stopForeground(STOP_FOREGROUND_DETACH);
   foregroundTask=null;
   stopSelfResult(startId);
  }
 }
 private static int notificationId(String task){return 2000+(task.hashCode()&0x3fffffff);}
 private static String formatSize(long bytes){
  double mb=bytes/1000000d;
  if(mb>=1000)return String.format(java.util.Locale.US,"%.2f GB",mb/1000d);
  return String.format(java.util.Locale.US,"%.1f MB",mb);
 }
 private String downloadAmount(String task,String line){
  if(line==null)return "";
  Matcher m=Pattern.compile("MH_STORED_BYTES=([0-9]+)").matcher(line);
  if(!m.find())return "";
  long bytes;try{bytes=Long.parseLong(m.group(1));}catch(Exception ex){return "";}
  bytesByTask.merge(task,bytes,Math::max);
  long stored=bytesByTask.getOrDefault(task,0L);
  // HLS yt-dlp estimates can change between playlist fragments. Capture one
  // plausible initial estimate per transfer and label it approximate.
  if(!estimatedTotals.containsKey(task)){
   Matcher estimate=Pattern.compile("\\bof\\s+~?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(GiB|MiB|KiB|GB|MB|KB)\\b",Pattern.CASE_INSENSITIVE).matcher(line);
   if(estimate.find())try{
    double n=Double.parseDouble(estimate.group(1));
    String unit=estimate.group(2).toLowerCase(java.util.Locale.ROOT);
    double factor=unit.equals("gib")?1073741824d:unit.equals("mib")?1048576d:unit.equals("kib")?1024d:unit.equals("gb")?1000000000d:unit.equals("mb")?1000000d:1000d;
    long candidate=(long)(n*factor);
    if(candidate>=stored&&candidate>0&&candidate<10000000000000L)estimatedTotals.putIfAbsent(task,candidate);
   }catch(Exception ignored){}
  }
  long total=estimatedTotals.getOrDefault(task,0L);
  if(total>0&&total>=stored)return formatSize(stored)+" / ~"+formatSize(total)+" (est.)";
  return formatSize(stored)+" / total unknown";
 }
 private static String extractSpeed(String line){
  if(line==null)return "";
  Matcher m=Pattern.compile("([0-9]+(?:\\.[0-9]+)?\\s*(?:KiB|MiB|GiB|KB|MB|GB)/s)",Pattern.CASE_INSENSITIVE).matcher(line);
  return m.find()?m.group(1):"";
 }
 private Notification completedNotification(String task,String name,String uri,String mime){
  PendingIntent click=MediaOpenHelper.notificationClick(this,notificationId(task),uri,mime,name);
  Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
  return b.setSmallIcon(android.R.drawable.stat_sys_download_done)
   .setContentTitle("Download complete • Tap to play externally")
   .setContentText(shortText(name)).setContentIntent(click)
   .setAutoCancel(true).setOnlyAlertOnce(true)
   .setVisibility(Notification.VISIBILITY_PRIVATE).build();
 }
 private static String shortText(String s){return s==null?"Unknown error":s.length()>100?s.substring(0,100):s;}
 private Notification notification(String title,String message,int percent,boolean ongoing,boolean failed){return notification(title,message,percent,ongoing,failed,null,false);}
 private Notification notification(String title,String message,int percent,boolean ongoing,boolean failed,String task,boolean paused){return notification(title,message,percent,ongoing,failed,task,paused,"MH Browser");}
 private Notification notification(String title,String message,int percent,boolean ongoing,boolean failed,String task,boolean paused,String filename){
  Intent open=new Intent(this,BrowserActivity.class);
  open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  open.putExtra("openDownloads",true);
  PendingIntent click=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
  b.setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle(title).setContentText(shortText(message)).setSubText(shortText(filename))
   .setStyle(new Notification.BigTextStyle().bigText(message))
   .setContentIntent(click).setAutoCancel(!ongoing).setOngoing(ongoing)
   .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE);
  if(ongoing){b.setProgress(100,percent,percent<=0);
   if(task!=null&&!task.isEmpty()){
    Intent action=new Intent(this,MediaDownloadService.class).setAction(paused?"MH_RESUME":"MH_PAUSE").putExtra("task",task);
    PendingIntent command=PendingIntent.getService(this,notificationId(task),action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    b.addAction(new Notification.Action.Builder(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,paused?"Resume":"Pause",command).build());
    Intent delete=new Intent(this,MediaDownloadService.class).setAction("MH_DELETE").putExtra("task",task);
    PendingIntent deleteCommand=PendingIntent.getService(this,notificationId(task)+1,delete,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_delete,"Delete",deleteCommand).build());
   }
  }
  else b.setSmallIcon(failed?android.R.drawable.stat_notify_error:android.R.drawable.stat_sys_download_done);
  return b.build();
 }
 private void save(String task,String name,String state,int percent,String detail,String uri,String mime){
  synchronized(MediaDownloadService.class){
   if(deletedTasks.contains(task))return;
   android.content.SharedPreferences p=getSharedPreferences("mh",0);
   JSONArray a;try{a=new JSONArray(p.getString("downloads","[]"));}catch(Exception ex){a=new JSONArray();}
   JSONObject record=null;
   for(int i=0;i<a.length();i++){
    JSONObject item=a.optJSONObject(i);
    if(item!=null&&task.equals(item.optString("task"))){record=item;break;}
   }
   if(record==null){record=new JSONObject();a.put(record);}
   try{
    record.put("id",-2);record.put("task",task);record.put("name",name);
    if(!record.has("time"))record.put("time",System.currentTimeMillis());
    record.put("state",state);record.put("progress",percent);record.put("detail",detail);
    if(uri!=null)record.put("uri",uri);
    if(mime!=null)record.put("mime",mime);
    p.edit().putString("downloads",a.toString()).commit();
   }catch(Exception ignored){}
  }
 }
 @Override public void onDestroy(){if(liveService==this)liveService=null;super.onDestroy();}
 @Override public android.os.IBinder onBind(Intent i){return null;}
}
