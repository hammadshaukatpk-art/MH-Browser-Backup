package com.mh.browser;
import android.os.Bundle;
import android.webkit.WebView;
import android.os.Build;
/** Separate process and WebView data directory for private browsing. */
public class PrivateBrowserActivity extends BrowserActivity {
 @Override public void onCreate(Bundle state){
  if(Build.VERSION.SDK_INT>=28)WebView.setDataDirectorySuffix("private");
  privateOnly=true;
  super.onCreate(state);
 }
}
