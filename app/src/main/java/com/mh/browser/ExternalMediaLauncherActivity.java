package com.mh.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.InputStream;

/** Explicit notification/activity entry point: launches the system's external media app chooser.
 * An explicit PendingIntent avoids using an implicit chooser as the notification's PendingIntent. */
public class ExternalMediaLauncherActivity extends Activity {
 private Uri file;
 private String mime;
 private String name;
 private boolean launched=false;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  file=getIntent().getData();
  mime=getIntent().getStringExtra("mime");
  name=getIntent().getStringExtra("name");
  if(file==null){error("This download does not have a saved file address.");return;}
  try(InputStream in=getContentResolver().openInputStream(file)){
   if(in==null){error("The downloaded file is missing or unavailable.");return;}
  }catch(Exception e){error("Cannot read the downloaded file. It may have been moved or deleted.");return;}
  openExternal();
 }
 private void openExternal(){
  if(launched)return;
  launched=true;
  try{
   String type=MediaOpenHelper.mimeFor(this,file,mime,name);
   Intent view=new Intent(Intent.ACTION_VIEW);
   view.setDataAndType(file,type);
   view.addCategory(Intent.CATEGORY_DEFAULT);
   view.setClipData(ClipData.newRawUri("MH Browser download",file));
   view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   Intent chooser=Intent.createChooser(view,"Play using");
   chooser.setClipData(ClipData.newRawUri("MH Browser download",file));
   chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   startActivity(chooser);
   finish();
  }catch(Exception e){
   launched=false;
   error("Android could not open a video player: "+e.getMessage());
  }
 }
 private void share(){
  try{
   Intent send=new Intent(Intent.ACTION_SEND);
   send.setType(MediaOpenHelper.mimeFor(this,file,mime,name));
   send.putExtra(Intent.EXTRA_STREAM,file);
   send.setClipData(ClipData.newRawUri("MH Browser download",file));
   send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   startActivity(Intent.createChooser(send,"Send file to another app"));
   finish();
  }catch(Exception e){Toast.makeText(this,"No compatible app available",Toast.LENGTH_LONG).show();}
 }
 private void error(String detail){
  if(isFinishing())return;
  new AlertDialog.Builder(this)
   .setTitle("Cannot open downloaded video")
   .setMessage(detail+"\n\nInstall or select a video player such as your phone's Videos app or VLC. You can also open the file from Files → Downloads → MH Browser.")
   .setPositiveButton("Close",(d,w)->finish())
   .setNeutralButton("Share file",(d,w)->share())
   .setOnCancelListener(d->finish())
   .show();
 }
}
