package com.mh.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.media.MediaPlayer;

/** A reliable one-tap in-app playback entry point for saved downloads. */
public class DownloadedMediaActivity extends Activity {
 private VideoView video;
 private Uri mediaUri;
 private String mime;
 private boolean errorShown=false;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  getWindow().setStatusBarColor(0xff071326);
  getWindow().setNavigationBarColor(0xff071326);
  mediaUri=getIntent().getData();
  mime=getIntent().getType();
  if(mime==null)mime="video/mp4";
  LinearLayout root=new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setBackgroundColor(0xff071326);
  setContentView(root);
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
  header.setPadding(dp(12),dp(9),dp(12),dp(9));root.addView(header);
  TextView back=button("‹",28);header.addView(back,new LinearLayout.LayoutParams(dp(45),dp(48)));
  back.setOnClickListener(v->finish());
  TextView name=button(getIntent().getStringExtra("name"),16);
  name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
  header.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));
  TextView share=button("↗",25);header.addView(share,new LinearLayout.LayoutParams(dp(48),dp(48)));
  share.setOnClickListener(v->openExternal());
  if(mediaUri==null){showError("The saved file link is unavailable.");return;}
  video=new VideoView(this);
  video.setBackgroundColor(Color.BLACK);
  root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
  MediaController controls=new MediaController(this);
  controls.setAnchorView(video);
  video.setMediaController(controls);
  TextView help=button("Tap the video to show playback controls   •   ↗ Open in another app",12);
  help.setGravity(Gravity.CENTER);help.setPadding(dp(8),dp(10),dp(8),dp(14));
  root.addView(help);
  help.setOnClickListener(v->openExternal());
  video.setOnPreparedListener(player->{
   player.setOnVideoSizeChangedListener((p,w,h)->video.requestLayout());
   video.start();controls.show(3500);
  });
  video.setOnErrorListener((player,what,extra)->{
   if(!errorShown){errorShown=true;showError("This file's codec may not be supported by the built-in Android player. Try another player.");}
   return true;
  });
  try{video.setVideoURI(mediaUri);video.requestFocus();}
  catch(Exception e){showError("Unable to open the downloaded file: "+e.getMessage());}
 }
 private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
 private TextView button(String s,int size){
  TextView t=new TextView(this);t.setText(s==null?"Downloaded media":s);
  t.setTextSize(size);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER_VERTICAL);return t;
 }
 private void showError(String message){
  new AlertDialog.Builder(this).setTitle("Playback unavailable").setMessage(message)
   .setNegativeButton("Close",(d,w)->finish())
   .setPositiveButton("Open with another app",(d,w)->openExternal()).show();
 }
 private void openExternal(){
  if(mediaUri==null)return;
  try{
   Intent intent=new Intent(Intent.ACTION_VIEW);
   intent.setDataAndType(mediaUri,mime);
   intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   intent.setClipData(android.content.ClipData.newRawUri("Downloaded media",mediaUri));
   startActivity(Intent.createChooser(intent,"Play downloaded media"));
  }catch(Exception e){Toast.makeText(this,"No compatible player found",Toast.LENGTH_LONG).show();}
 }
 @Override protected void onPause(){if(video!=null)video.pause();super.onPause();}
 @Override protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
