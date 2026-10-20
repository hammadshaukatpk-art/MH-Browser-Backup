package com.mh.browser;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.util.Locale;

/** Shared explicit entry point for downloaded-file playback. */
public final class MediaOpenHelper {
 private MediaOpenHelper(){}
 public static String mimeFor(Context context,Uri uri,String storedMime,String name){
  if(storedMime!=null&&!storedMime.trim().isEmpty()&&!"*/*".equals(storedMime))return storedMime;
  String mime=null;
  try{mime=context.getContentResolver().getType(uri);}catch(Exception ignored){}
  if(mime==null&&name!=null){
   int dot=name.lastIndexOf('.');
   if(dot>=0&&dot+1<name.length()){
    mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
   }
  }
  return mime==null?"video/*":mime;
 }
 private static Intent launchIntent(Context context,Uri uri,String mime,String name){
  Intent intent=new Intent(context,ExternalMediaLauncherActivity.class);
  intent.setData(uri);
  intent.putExtra("mime",mimeFor(context,uri,mime,name));
  intent.putExtra("name",name);
  intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
  return intent;
 }
 public static void open(Context context,Uri uri,String mime,String name){
  if(uri==null){Toast.makeText(context,"Downloaded file is unavailable",Toast.LENGTH_LONG).show();return;}
  try{context.startActivity(launchIntent(context,uri,mime,name));}
  catch(Exception e){Toast.makeText(context,"Cannot open player: "+e.getMessage(),Toast.LENGTH_LONG).show();}
 }
 public static PendingIntent notificationClick(Context context,int requestCode,String uri,String mime,String name){
  Uri file=Uri.parse(uri);
  Intent intent=launchIntent(context,file,mime,name);
  intent.setAction("com.mh.browser.OPEN_EXTERNAL_DOWNLOAD."+requestCode);
  return PendingIntent.getActivity(context,requestCode,intent,
    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
}
