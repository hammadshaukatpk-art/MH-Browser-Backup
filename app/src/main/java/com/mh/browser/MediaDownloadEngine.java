package com.mh.browser;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import com.yausername.ffmpeg.FFmpeg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Inspects public, unprotected media URLs and downloads user-selected formats. */
public final class MediaDownloadEngine {
 private MediaDownloadEngine(){}
 // A single in-flight extractor run per video. The download worker can reuse
 // the prefetch rather than launching a second costly yt-dlp extraction.
 private static final ConcurrentHashMap<String,CountDownLatch> INSPECT_IN_FLIGHT=new ConcurrentHashMap<>();
 // Warm the native download tools while the user is watching, not after they tap a quality.
 private static final java.util.concurrent.atomic.AtomicBoolean WARMING=new java.util.concurrent.atomic.AtomicBoolean();
 public static void warmup(Context context){
  if(!WARMING.compareAndSet(false,true))return;
  Context app=context.getApplicationContext();
  new Thread(()->{
   boolean acquired=false;
   try{
    MediaEngineUpdater.acquireUse(app);acquired=true;
    FFmpeg.getInstance().init(app);
   }catch(Exception ignored){WARMING.set(false);}
   finally{if(acquired)MediaEngineUpdater.releaseUse();}
  },"mh-media-warmup").start();
 }

 public static final class Choice {
  public final String label;
  public final int height;
  public final String audioFormat;
  public Choice(String label,int height,String audioFormat){
   this.label=label;this.height=height;this.audioFormat=audioFormat;
  }
 }
 public interface Callback {
  void ready(String title,List<Choice> choices);
  void progress(int percent,String message);
  void completed(String displayName,String uri,String mime);
  void failed(String message);
 }
 public static void inspect(Context context,String url,Callback callback){
  new Thread(()->{
   boolean acquired=false;
   try{
    MediaEngineUpdater.acquireUse(context);acquired=true;
    File prepared=findCachedInfo(context,url);
    String raw;
    if(prepared!=null){
     raw=readCachedInfo(prepared);
    }else{
     CountDownLatch pending=new CountDownLatch(1);
     CountDownLatch existing=INSPECT_IN_FLIGHT.putIfAbsent(url,pending);
     if(existing!=null){
      existing.await(30,TimeUnit.SECONDS);
      prepared=findCachedInfo(context,url);
      if(prepared==null)throw new IllegalStateException("Format preparation is still running. Please retry.");
      raw=readCachedInfo(prepared);
     }else{
      try{
       // Run only one metadata extraction per video, even when a download
       // is requested while prefetch is in progress.
       YoutubeDLRequest infoRequest=new YoutubeDLRequest(url);
       infoRequest.addOption("--no-playlist");
       infoRequest.addOption("--dump-single-json");
       raw=YoutubeDL.getInstance().execute(infoRequest).getOut().trim();
       cacheExtractedInfo(context,url,raw);
      }finally{
       INSPECT_IN_FLIGHT.remove(url,pending);
       pending.countDown();
      }
     }
    }
    JSONObject info=new JSONObject(raw);
    Set<Integer> heights=new HashSet<>();
    JSONArray formats=info.optJSONArray("formats");
    if(formats!=null)for(int i=0;i<formats.length();i++){
     JSONObject format=formats.optJSONObject(i);if(format==null)continue;
     String codec=format.optString("vcodec","none");
     int h=format.optInt("height",0);
     if(h>=144&&h<=4320&&!codec.equals("none")&&!codec.isEmpty())heights.add(h);
    }
    List<Integer> sorted=new ArrayList<>(heights);
    Collections.sort(sorted,Collections.reverseOrder());
    List<Choice> choices=new ArrayList<>();
    for(int h:sorted)choices.add(new Choice("Video • up to "+h+"p (MP4 preferred)",h,null));
    if(choices.isEmpty())choices.add(new Choice("Video • best available",0,null));
    choices.add(new Choice("Audio • MP3 (192 kbps target)",0,"mp3"));
    choices.add(new Choice("Audio • M4A (best available)",0,"m4a"));
    String title=info.optString("title","Available formats");
    callback.ready(title.isEmpty()?"Available formats":title,choices);
   }catch(Exception ex){callback.failed("Could not inspect formats: "+safeError(ex));}
   finally{if(acquired)MediaEngineUpdater.releaseUse();}
  },"mh-media-inspect").start();
 }
 private static String readCachedInfo(File file)throws Exception{
  StringBuilder data=new StringBuilder((int)Math.min(file.length(),3000000));
  try(BufferedReader reader=new BufferedReader(new FileReader(file))){
   char[] buf=new char[8192];int n;
   while((n=reader.read(buf))!=-1)data.append(buf,0,n);
  }
  return data.toString();
 }
 private static File awaitPrefetchedInfo(Context context,String url)throws InterruptedException{
  File prepared=findCachedInfo(context,url);
  if(prepared!=null)return prepared;
  CountDownLatch pending=INSPECT_IN_FLIGHT.get(url);
  if(pending!=null){
   // Reuse the already-running extraction. This wait is for that same work,
   // not a second extraction; it may still depend on YouTube/network latency.
   pending.await(30,TimeUnit.SECONDS);
  }
  return findCachedInfo(context,url);
 }
 private static final long INFO_TTL_MS=10L*60*1000;
 private static File infoPath(Context context,String url){
  try{
   byte[] digest=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
   StringBuilder key=new StringBuilder();
   for(int i=0;i<16;i++)key.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));
   File folder=new File(context.getCacheDir(),"mh-media-info");
   if(!folder.exists()&&!folder.mkdirs())return null;
   return new File(folder,key+".info.json");
  }catch(Exception e){return null;}
 }
 private static File findCachedInfo(Context context,String url){
  File f=infoPath(context,url);
  if(f==null||!f.isFile()||f.length()<100||f.length()>3000000||
    System.currentTimeMillis()-f.lastModified()>INFO_TTL_MS)return null;
  return f;
 }
 private static void cacheExtractedInfo(Context context,String url,String raw){
  if(raw.length()>3000000||raw.length()<100)return;
  File file=infoPath(context,url);if(file==null)return;
  File temp=new File(file.getParentFile(),file.getName()+".tmp-"+UUID.randomUUID());
  try{
   try(FileOutputStream out=new FileOutputStream(temp)){
    out.write(raw.getBytes(StandardCharsets.UTF_8));
   }
   if(!temp.renameTo(file)){
    if(file.exists())file.delete();
    temp.renameTo(file);
   }
  }catch(Exception ignored){}finally{if(temp.exists())temp.delete();}
 }
 private static YoutubeDLRequest downloadRequest(String url,Choice choice,File staging,File cachedInfo,String referer,String userAgent){
  YoutubeDLRequest req=cachedInfo==null?new YoutubeDLRequest(url):
    new YoutubeDLRequest(Collections.<String>emptyList());
  if(cachedInfo!=null)req.addOption("--load-info-json",cachedInfo.getAbsolutePath());
  req.addOption("--no-playlist");
  if(referer!=null&&referer.startsWith("http"))req.addOption("--referer",referer);
  if(userAgent!=null&&!userAgent.isEmpty())req.addOption("--user-agent",userAgent);
  req.addOption("--no-mtime");
  req.addOption("--concurrent-fragments","4");
  req.addOption("--retries","5");
  req.addOption("--fragment-retries","5");
  req.addOption("--socket-timeout","25");
  req.addOption("-o",new File(staging,"%(title).140B.%(ext)s").getAbsolutePath());
  if(choice.audioFormat!=null){
   req.addOption("-f","bestaudio/best");
   req.addOption("-x");
   req.addOption("--audio-format",choice.audioFormat);
   if("mp3".equals(choice.audioFormat))req.addOption("--audio-quality","192K");
  }else{
   String quality=choice.height>0?"[height<="+choice.height+"]":"";
   req.addOption("-f",
    "bestvideo"+quality+"[vcodec^=avc1][ext=mp4]+bestaudio[ext=m4a]/"+
    "best"+quality+"[ext=mp4][vcodec^=avc1]/"+
    "bestvideo"+quality+"[ext=mp4]+bestaudio[ext=m4a]/"+
    "best"+quality+"[ext=mp4]/"+
    "bestvideo"+quality+"+bestaudio/best"+quality);
   req.addOption("--merge-output-format","mp4");
  }
  return req;
 }
 public interface PauseControl {void await() throws InterruptedException;}
 private static void runDownload(YoutubeDLRequest req,Callback callback,PauseControl control)throws Exception{
  String processId="mh-"+UUID.randomUUID();
  YoutubeDL.getInstance().execute(req,processId,false,(percent,eta,line)->{
   try{control.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
   callback.progress(Math.max(0,Math.min(100,Math.round(percent))),line==null?"Downloading media":line);
   return kotlin.Unit.INSTANCE;
  });
 }
 public static void download(Context context,String url,Choice choice,Callback callback){
  download(context,url,choice,null,null,()->{},callback);
 }
 public static void download(Context context,String url,Choice choice,String referer,String userAgent,Callback callback){download(context,url,choice,referer,userAgent,()->{},callback);}
 public static void download(Context context,String url,Choice choice,String referer,String userAgent,PauseControl control,Callback callback){
  download(context,url,choice,referer,userAgent,null,control,callback);
 }
 public static void download(Context context,String url,Choice choice,String referer,String userAgent,String preferredTitle,PauseControl control,Callback callback){
  new Thread(()->{
   File staging=null;
   boolean acquired=false;
   try{
    callback.progress(0,"Initializing download engine…");
    MediaEngineUpdater.acquireUse(context);acquired=true;
    callback.progress(0,"Connecting to media source…");
    // Usually already initialized during playback; this is an idempotent safety fallback.
    FFmpeg.getInstance().init(context.getApplicationContext());
    File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if(root==null)throw new IllegalStateException("Download storage is unavailable");
    staging=new File(root,"mh-working-"+UUID.randomUUID());
    if(!staging.mkdirs())throw new IllegalStateException("Could not create download directory");
    File prepared=findCachedInfo(context,url);
    if(prepared==null&&INSPECT_IN_FLIGHT.containsKey(url)){
     callback.progress(0,"Reusing video preparation already in progress…");
     prepared=awaitPrefetchedInfo(context,url);
    }
    boolean cached=prepared!=null;
    callback.progress(0,cached?"Connecting to prepared video streams…":"Resolving media streams…");
    try{
     YoutubeDLRequest req=downloadRequest(url,choice,staging,prepared,referer,userAgent);
     runDownload(req,callback,control);
    }catch(Exception cachedError){
     if(!cached)throw cachedError;
     // Cached signed stream URLs can expire or be rejected by the provider.
     // Fall back once to a fresh extraction rather than failing the download.
     File[] partial=staging.listFiles();
     if(partial!=null)for(File part:partial)if(part.isFile())part.delete();
     callback.progress(0,"Refreshing expired media links…");
     runDownload(downloadRequest(url,choice,staging,null,referer,userAgent),callback,control);
    }
    control.await();
    File[] files=staging.listFiles();
    File result=null;int bestScore=-1;
    if(files!=null)for(File f:files){
     if(!f.isFile()||f.length()==0)continue;
     String lower=f.getName().toLowerCase(java.util.Locale.ROOT);
     if(lower.endsWith(".part")||lower.endsWith(".ytdl")||lower.endsWith(".temp")||lower.endsWith(".json"))continue;
     int score;
     if("mp3".equals(choice.audioFormat))score=lower.endsWith(".mp3")?100:0;
     else if("m4a".equals(choice.audioFormat))score=lower.endsWith(".m4a")?100:0;
     else score=lower.endsWith(".mp4")?100:lower.endsWith(".mkv")?85:lower.endsWith(".webm")?80:0;
     if(score==0)continue;
     if(score>bestScore||(score==bestScore&&result!=null&&f.length()>result.length())){
      result=f;bestScore=score;
     }
    }
    if(result==null)throw new IllegalStateException("No playable completed media file was produced");
    // yt-dlp may sometimes report success with an audio-only file on video-only requests.
    // Never label such a file as a successfully downloaded video.
    if(choice.audioFormat==null){
     android.media.MediaMetadataRetriever check=new android.media.MediaMetadataRetriever();
     try{
      check.setDataSource(result.getAbsolutePath());
      String hasVideo=check.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
      if(!"yes".equalsIgnoreCase(hasVideo))
       throw new IllegalStateException("The website returned audio only, not a playable video. Try a different video quality.");
     }finally{try{check.release();}catch(Exception ignored){}}
    }
    String lowerName=result.getName().toLowerCase(java.util.Locale.ROOT);
    String mime=lowerName.endsWith(".mp3")?"audio/mpeg":
      lowerName.endsWith(".m4a")?"audio/mp4":
      lowerName.endsWith(".webm")?"video/webm":
      lowerName.endsWith(".mkv")?"video/x-matroska":"video/mp4";
    if(preferredTitle!=null&&!preferredTitle.trim().isEmpty()){
     String clean=preferredTitle.trim().replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]"," ").replaceAll("\\s+"," ").replaceAll("[. ]+$","");
     if(clean.length()>140)clean=clean.substring(0,140).trim();
     if(!clean.isEmpty()){
      String ext=lowerName.substring(lowerName.lastIndexOf('.')+1);
      File named=new File(staging,clean+"."+ext);
      if(!named.equals(result)&&!named.exists()){
       if(result.renameTo(named))result=named;
      }
     }
    }
    Uri saved=publish(context,result,mime);
    callback.completed(result.getName(),saved.toString(),mime);
   }catch(Exception ex){callback.failed("Download failed. "+safeError(ex));}
   finally{if(staging!=null)deleteTree(staging);if(acquired)MediaEngineUpdater.releaseUse();}
  },"mh-media-download").start();
 }
 private static Uri publish(Context context,File source,String mime)throws Exception{
  if(Build.VERSION.SDK_INT<29){
   // Android 8/9: keep the file in the app's private external downloads folder.
   File dest=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),source.getName());
   copyFile(source,dest);
   return androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".fileprovider",dest);
  }
  ContentValues values=new ContentValues();
  values.put(MediaStore.MediaColumns.DISPLAY_NAME,source.getName());
  values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
  values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/MH Browser");
  values.put(MediaStore.MediaColumns.IS_PENDING,1);
  Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
  if(uri==null)throw new IllegalStateException("Unable to save to Downloads");
  try{
   try(FileInputStream in=new FileInputStream(source);
       OutputStream out=context.getContentResolver().openOutputStream(uri)){
    if(out==null)throw new IllegalStateException("Unable to open Downloads storage");
    byte[] buffer=new byte[32768];int count;
    while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
   }
   ContentValues done=new ContentValues();
   done.put(MediaStore.MediaColumns.IS_PENDING,0);
   context.getContentResolver().update(uri,done,null,null);
   return uri;
  }catch(Exception ex){context.getContentResolver().delete(uri,null,null);throw ex;}
 }
 private static void copyFile(File src,File dest)throws Exception{
  try(FileInputStream in=new FileInputStream(src);java.io.FileOutputStream out=new java.io.FileOutputStream(dest)){
   byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
  }
 }
 private static void deleteTree(File f){
  if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);}
  f.delete();
 }
 private static String safeError(Exception e){
  String message=e.getMessage();
  if(message==null||message.isEmpty())return e.getClass().getSimpleName();
  message=message.replaceAll("(?i)(cookie|authorization|token)=[^\\s&]+","$1=[hidden]");
  // yt-dlp often writes stale-version warnings before the actual fatal error.
  // Surface the last ERROR line instead of misleading users with the warning.
  String[] lines=message.split("\\r?\\n");
  String actual=null;
  for(String line:lines)if(line.trim().startsWith("ERROR:"))actual=line.trim();
  if(actual!=null)message=actual;
  return message.length()>600?message.substring(message.length()-600):message;
 }
}
