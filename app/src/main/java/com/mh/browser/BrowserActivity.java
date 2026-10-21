package com.mh.browser;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import org.json.*;
import android.app.DownloadManager;
import android.os.Environment;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.graphics.drawable.Drawable;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewCompat;
public class BrowserActivity extends Activity {
 final int BG=0xff071326,PANEL=0xff10223a,BLUE=0xff269cff,WHITE=Color.WHITE,GRAY=0xffa4b6ce;
 LinearLayout root,head,body,bar; EditText address; boolean showingSettings=false;int screen=0;
 View fullscreenVideo;WebChromeClient.CustomViewCallback fullscreenCallback;FrameLayout videoLayer,webPageLayer;
 TextView mediaOverlay,fullscreenMediaOverlay;
 boolean mediaPlaying=false, mediaAvailable=false, privateOnly=false; int historyFilter=0,downloadFilter=0;
 boolean lightTheme=false,downloadSelecting=false;
 final Set<String> selectedDownloadKeys=new HashSet<>();
 final LruCache<String,Bitmap> shortcutIconCache=new LruCache<>(24);
 final Map<String,java.util.List<MediaDownloadEngine.Choice>> mediaFormatCache=new HashMap<>();
 final Map<String,String> mediaTitleCache=new HashMap<>();
 final Map<String,String> pageMovieTitleCache=new HashMap<>();
 final Map<String,JSONArray> directMediaCache=new HashMap<>();
 final Map<WebView,TextView> tabTitleViews=new HashMap<>();
 final Map<WebView,Integer> mediaInjectionStages=new java.util.WeakHashMap<>();
 final Map<WebView,String> pageForFiltering=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
 final Map<WebView,Long> recentPlayerTap=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
 AlertDialog activeMediaDialog;
 LinearLayout activeDirectMediaOptions;
 String activeMediaPage;
 final Map<WebView,Set<String>> networkMediaSeen=new java.util.WeakHashMap<>();
 final Map<WebView,String> lastWorkingPage=new java.util.WeakHashMap<>();
 final Map<WebView,Long> videoConnectionRecovery=new java.util.WeakHashMap<>();
 final Set<String> mediaInspecting=new HashSet<>();
 final Handler downloadUiHandler=new Handler(Looper.getMainLooper());
 final Map<String,TextView> downloadStatusViews=new HashMap<>();
 final Map<String,ProgressBar> downloadProgressViews=new HashMap<>();
 final Map<String,String> downloadRenderedStates=new HashMap<>();
 ScrollView downloadsScrollView;
 int videoWidth=0,videoHeight=0,orientationBeforeVideo=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
 String detectedMediaTitle="",detectedMediaPage="";
 final Runnable downloadUiTick=new Runnable(){@Override public void run(){
  if(screen!=8||isFinishing()||isDestroyed())return;
  JSONArray entries=readArray("downloads");int count=0;
  for(int i=0;i<entries.length();i++){
   JSONObject item=entries.optJSONObject(i);if(item==null||item.optLong("id")!=-2||!downloadMatches(item.optLong("time")))continue;
   count++;
   String task=item.optString("task");
   String currentState=item.optString("state","Pending");
   String renderedState=downloadRenderedStates.get(task);
   // The progress row is not clickable until a completed file has an Open action.
   // Rebuild the entry as soon as the worker publishes the file; do not require
   // changing Today/Week filters to create the Open button.
   if(renderedState!=null&&!renderedState.equals(currentState)){
    refreshCompletedDownloadRows();return;
   }
   TextView status=downloadStatusViews.get(task);
   ProgressBar progress=downloadProgressViews.get(task);
   if(status!=null){
    String state=item.optString("state","Pending");
    status.setText(downloadProgressLabel(item));
   }
   if(progress!=null){
    String state=item.optString("state");
    progress.setIndeterminate("Preparing".equals(state));
    if(!"Preparing".equals(state)){
     if(android.os.Build.VERSION.SDK_INT>=24)progress.setProgress(item.optInt("progress",0),true);
     else progress.setProgress(item.optInt("progress",0));
    }
    progress.setVisibility("Downloading".equals(state)||"Preparing".equals(state)||"Paused".equals(state)?View.VISIBLE:View.GONE);
   }
  }
  if(count!=downloadStatusViews.size()){refreshCompletedDownloadRows();return;}
  downloadUiHandler.postDelayed(this,500);
 }};
 final Map<String,ArrayList<MediaFormatListener>> mediaListeners=new HashMap<>();
 interface MediaFormatListener {void ready(String title,java.util.List<MediaDownloadEngine.Choice> choices);void failed(String error);}
 boolean isMediaPage(String u){
  if(u==null)return false;
  try{
   Uri p=Uri.parse(u);String h=p.getHost();if(h==null)return false;
   h=h.toLowerCase(Locale.ROOT);String path=p.getPath();if(path==null)path="";
   if(h.equals("youtu.be"))return path.length()>1;
   if(h.equals("youtube.com")||h.endsWith(".youtube.com"))
    return (path.equals("/watch")&&p.getQueryParameter("v")!=null)||path.startsWith("/shorts/")||path.startsWith("/live/");
   if(h.equals("facebook.com")||h.endsWith(".facebook.com"))
    return path.startsWith("/watch")||path.startsWith("/reel/")||path.contains("/videos/")||path.startsWith("/share/v/");
   if(h.equals("instagram.com")||h.endsWith(".instagram.com"))
    return path.startsWith("/reel/")||path.startsWith("/tv/");
   if(h.equals("tiktok.com")||h.endsWith(".tiktok.com"))
    return path.contains("/video/")||path.startsWith("/v/");
   if(h.equals("soundcloud.com")||h.endsWith(".soundcloud.com"))return path.split("/").length>=3;
   if(h.equals("vimeo.com")||h.endsWith(".vimeo.com"))return path.matches("/[0-9]+.*");
   if(h.equals("dailymotion.com")||h.endsWith(".dailymotion.com"))return path.startsWith("/video/");
   return path.matches("(?i).*\\.(mp3|m4a|mp4|webm|ogg|wav|mov)$");
  }catch(Exception e){return false;}
 }
 void signalMediaFound(WebView w){
  if(tabs.isEmpty()||w!=active())return;
  mediaAvailable=true;updateMediaOverlay();
  MediaDownloadEngine.warmup(getApplicationContext());
  String u=w.getUrl();if(isMediaPage(u))prefetchMediaFormats(u);
  if(u!=null&&u.startsWith("http"))w.evaluateJavascript(mediaSourceScript(),raw->{
   if(!u.equals(w.getUrl()))return;
   JSONArray direct=parseMediaSources(raw);
   if(direct.length()>0)mergeDirectMedia(u,direct);
  });
 }
 void prefetchMediaFormats(String u){inspectFormatsCached(u,null);}
 void inspectFormatsCached(String u,MediaFormatListener listener){
  if(u==null||!u.startsWith("http")){if(listener!=null)listener.failed("No media page is open.");return;}
  if(mediaFormatCache.containsKey(u)){
   if(listener!=null)listener.ready(mediaTitleCache.getOrDefault(u,"Available formats"),mediaFormatCache.get(u));return;
  }
  if(listener!=null)mediaListeners.computeIfAbsent(u,k->new ArrayList<>()).add(listener);
  if(!mediaInspecting.add(u))return;
  MediaDownloadEngine.inspect(getApplicationContext(),u,new MediaDownloadEngine.Callback(){
   @Override public void ready(String title,java.util.List<MediaDownloadEngine.Choice> choices){
    runOnUiThread(()->{
     mediaInspecting.remove(u);mediaFormatCache.put(u,choices);mediaTitleCache.put(u,title);
     ArrayList<MediaFormatListener> waiting=mediaListeners.remove(u);
     if(waiting!=null)for(MediaFormatListener item:waiting)item.ready(title,choices);
    });
   }
   @Override public void failed(String error){
    runOnUiThread(()->{
     mediaInspecting.remove(u);ArrayList<MediaFormatListener> waiting=mediaListeners.remove(u);
     if(waiting!=null)for(MediaFormatListener item:waiting)item.failed(error);
    });
   }
   @Override public void progress(int p,String line){}
   @Override public void completed(String name,String uri,String mime){}
  });
 }
 ArrayList<Tab> tabs=new ArrayList<>();int index=-1; boolean privateNext=false;
 android.content.SharedPreferences prefs; String proxyUser="",proxyPassword="";
 static class Tab {WebView web; boolean incognito,desktop; Tab(WebView w,boolean p,boolean d){web=w;incognito=p;desktop=d;}}
 LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
 LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
 int themeText(int color){
  if(!lightTheme)return color;
  if(color==WHITE)return 0xff172b40;
  if(color==GRAY)return 0xff53677e;
  int alpha=Color.alpha(color),red=Color.red(color),green=Color.green(color),blue=Color.blue(color);
  if(alpha<180)return color;
  if(red>145&&green>145&&blue>145)return 0xff344a60;
  if(blue>green&&green>red&&blue>135)return 0xff16638e;
  return color;
 }
 int themeSurface(int color){
  if(!lightTheme)return color;
  if(color==Color.TRANSPARENT||Color.alpha(color)<180)return color;
  if(color==WHITE||color==0xffe8f2ff||color==0xfff1f6ff)return 0xffffffff;
  if(color==BG)return 0xfff6f9fd;
  if(color==PANEL)return 0xffe8f0f8;
  int red=Color.red(color),green=Color.green(color),blue=Color.blue(color);
  if(red>green+16&&red>blue+10)return 0xfff3dfe3;
  if(blue>red&&blue>green&&green>red+10&&green>65)return 0xffd7e8f6;
  if(red<110&&green<145&&blue<175)return 0xffe6eef7;
  if(red<160&&green<190&&blue<220)return 0xffeff4fa;
  return color;
 }
 void applyThemeBars(){
  getWindow().setStatusBarColor(lightTheme?0xfff6f9fd:BG);
  getWindow().setNavigationBarColor(lightTheme?0xffe8f0f8:BG);
  int flags=lightTheme?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:0;
  if(lightTheme&&Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
  getWindow().getDecorView().setSystemUiVisibility(flags);
  if(root!=null)root.setBackgroundColor(themeSurface(BG));
  if(bar!=null)bar.setBackgroundColor(themeSurface(PANEL));
 }
 TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(themeText(color));t.setGravity(Gravity.CENTER_VERTICAL);return t;}
 GradientDrawable bg(int color){GradientDrawable g=new GradientDrawable();g.setColor(themeSurface(color));g.setCornerRadius(24);return g;}
 @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("mh",0);lightTheme=prefs.getBoolean("lightTheme",false);MediaEngineUpdater.check(getApplicationContext());CookieManager.getInstance().setAcceptCookie(true);getWindow().setStatusBarColor(lightTheme?0xfff6f9fd:BG);getWindow().setNavigationBarColor(lightTheme?0xffe8f0f8:BG);
 root=column();root.setBackgroundColor(themeSurface(BG));setContentView(root);if(Build.VERSION.SDK_INT>=35){root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets z=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(z.left,z.top,z.right,z.bottom);return insets;});}getWindow().getDecorView().setSystemUiVisibility(lightTheme?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0):0);head=column();root.addView(head);body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));bar=row();bar.setBackgroundColor(themeSurface(PANEL));root.addView(bar);
 if(prefs.getBoolean("proxyActive",false)&&WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
  String host=prefs.getString("proxyHost",""),port=prefs.getString("proxyPort","");
  if(!host.isEmpty()&&!port.isEmpty()){try{
   ProxyConfig config=new ProxyConfig.Builder().addProxyRule(prefs.getString("proxyScheme","http")+"://"+host+":"+port).build();
   ProxyController.getInstance().setProxyOverride(config,(command)->runOnUiThread(command),()->{});
  }catch(Exception ignored){}}
 }if(privateOnly){CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();createTab(true);}else restoreTabs();
 // Build and paint the browser before loading large ad-filter lists.
 // Saved pages are restored only after those lists are ready, so ads do not slip through.
 new Thread(()->{AdBlockRules.initialize(getApplicationContext());runOnUiThread(this::loadDeferredTabs);},"mh-adblock-startup").start();
 // Display the browser immediately without any custom splash delay.
 root.requestApplyInsets();
 if(!privateOnly&&getIntent()!=null&&getIntent().getBooleanExtra("openDownloads",false)){
  getIntent().removeExtra("openDownloads");
  downloads();
 }
}

 int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
 GradientDrawable grad(int a,int b,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{themeSurface(a),themeSurface(b)});g.setCornerRadius(dp(radius));return g;}
 TextView action(String glyph,int size,Runnable run){TextView t=text(glyph,size,WHITE);t.setGravity(Gravity.CENTER);t.setOnClickListener(v->run.run());return t;}
 void nav(String s,Runnable run){TextView t=action(s,25,run);bar.addView(t,new LinearLayout.LayoutParams(0,dp(58),1));}
 void controls(){bar.removeAllViews();bar.setPadding(dp(8),dp(2),dp(8),dp(2));nav("‹",()->{if(active().canGoBack())active().goBack();else navigate("about:blank");});nav("›",()->{if(active().canGoForward())active().goForward();});nav("⌂",()->navigate("about:blank"));nav("▣",this::tabActions);nav("☰",this::menu);}

 WebView active(){return tabs.get(index).web;}
 String url(String s){s=s.trim();if(s.isEmpty())return "about:blank";if(s.startsWith("https://")||s.startsWith("http://")||s.equals("about:blank"))return s;if(s.contains(".")&&!s.contains(" "))return "https://"+s;return "https://www.google.com/search?q="+Uri.encode(s);}
 void navigate(String target){
  String dest=url(target);
  if(!tabs.get(index).incognito&&!dest.equals("about:blank"))prefs.edit().putString("lastUrl",dest).apply();
  WebView w=active();
  if(dest.equals("about:blank")){w.loadUrl(dest);display();return;}
  // Keep WebView attached during navigation; reparenting it repeatedly flashes.
  if(prefs.getBoolean("dnt",false)){
   java.util.Map<String,String> h=new java.util.HashMap<>();h.put("DNT","1");w.loadUrl(dest,h);
  }else w.loadUrl(dest);
  if(screen!=0)display();
 }

 void header(){
  head.setBackgroundColor(themeSurface(BG));
  LinearLayout r=row();r.setPadding(dp(14),dp(10),dp(14),dp(9));head.addView(r);
  TextView lock=text("⌕",25,0xff71d6ff);lock.setGravity(Gravity.CENTER);
  LinearLayout search=row();search.setBackground(grad(0xff192e4a,0xff14253f,17));
  r.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
  search.addView(lock,new LinearLayout.LayoutParams(dp(42),-1));
  address=new EditText(this);address.setSingleLine();address.setHint("Search or type URL");address.setTextColor(themeText(WHITE));
  address.setHintTextColor(themeText(0xffb1c1d6));address.setTextSize(14);address.setPadding(0,0,dp(8),0);
  address.setBackgroundColor(Color.TRANSPARENT);search.addView(address,new LinearLayout.LayoutParams(0,-1,1));
  address.setImeOptions(2);
  address.setOnEditorActionListener((v,a,e)->{navigate(address.getText().toString());address.clearFocus();
    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);return true;});
  TextView reload=action("⟳",25,()->active().reload());r.addView(reload,new LinearLayout.LayoutParams(dp(40),dp(48)));
 }

 // Translate Facebook-only deep links into public web URLs when possible.
 // Never open the Facebook app: public videos should remain in this WebView.
 String facebookWebUrl(Uri link){
  if(link==null)return null;
  String host=link.getHost();if(host==null)host="";
  host=host.toLowerCase(java.util.Locale.ROOT);
  String id=link.getQueryParameter("v");
  if(id==null||id.isEmpty())id=link.getQueryParameter("video_id");
  if(id==null||id.isEmpty())id=link.getQueryParameter("id");
  java.util.List<String> segments=link.getPathSegments();
  if((id==null||id.isEmpty())&&!segments.isEmpty()&&(host.equals("video")||host.equals("watch")))
   id=segments.get(0);
  if((host.equals("video")||host.equals("watch"))&&id!=null&&!id.isEmpty())
   return "https://www.facebook.com/watch/?v="+Uri.encode(id);
  if(host.equals("facewebmodal")){
   String href=link.getQueryParameter("href");
   if(href!=null&&(href.startsWith("https://")||href.startsWith("http://"))){
    try{
     String h=Uri.parse(href).getHost();
     if(h!=null&&(h.equalsIgnoreCase("facebook.com")||h.toLowerCase(java.util.Locale.ROOT).endsWith(".facebook.com")))
      return href;
    }catch(Exception ignored){}
   }
  }
  if(host.equals("native_post")){
   String story=link.getQueryParameter("story_fbid");
   String owner=link.getQueryParameter("id");
   if(story!=null&&!story.isEmpty()&&owner!=null&&!owner.isEmpty())
    return "https://www.facebook.com/permalink.php?story_fbid="+Uri.encode(story)+"&id="+Uri.encode(owner);
  }
  return null;
 }
 boolean onFacebookWebsite(WebView view){
  String current=view.getUrl();
  if(current==null)return false;
  try{
   String host=Uri.parse(current).getHost();
   return host!=null&&(host.equalsIgnoreCase("facebook.com")||host.toLowerCase(java.util.Locale.ROOT).endsWith(".facebook.com"));
  }catch(Exception ignored){return false;}
 }
 // Broken HTTPS video hosts cannot be fixed by bypassing certificate checks.
 // Return to the prior player when possible, without hiding unrelated errors.
 void recoverFailedVideoHost(WebView view,String url,String message){
  if(view!=active())return;
  long now=SystemClock.elapsedRealtime();
  Long prior=videoConnectionRecovery.get(view);
  if(prior!=null&&now-prior<4500)return;
  videoConnectionRecovery.put(view,now);
  String previous=lastWorkingPage.get(view);
  String current=url==null?"":url;
  boolean hasHistory=view.canGoBack();
  boolean hasSaved=previous!=null&&!previous.equals(current);
  if(hasHistory){
   view.post(()->{
    if(view.canGoBack())view.goBack();
    Toast.makeText(this,"Video host connection failed. Try another source on the original page.",Toast.LENGTH_LONG).show();
   });
  }else if(hasSaved){
   view.post(()->{
    view.loadUrl(previous);
    Toast.makeText(this,"Video host connection failed. Try another source.",Toast.LENGTH_LONG).show();
   });
  }else{
   themed().setTitle("Video server unavailable")
    .setMessage("This video host failed its secure connection. MH Browser cannot bypass a broken HTTPS server. Return to the original website and choose another video source.")
    .setPositiveButton("Back to website",(d,n)->{
     if(view.canGoBack())view.goBack();
     else if(tabs.size()>1)closeTab(index);
    })
    .setNegativeButton("Close",null).show();
  }
 }
 void createTab(boolean inc){if(inc&&!privateOnly){if(Build.VERSION.SDK_INT<28){dialog("Private browsing","Isolated private browsing requires Android 9 or newer on this build.");return;}startActivity(new Intent(this,PrivateBrowserActivity.class));
 // Keep the normal browser visible underneath the isolated private activity.
 overridePendingTransition(0,0);return;}if(privateOnly)inc=true;WebView w=new WebView(this);w.setBackgroundColor(WHITE);WebSettings s=w.getSettings();s.setJavaScriptEnabled(prefs.getBoolean("javascript",true));s.setLoadsImagesAutomatically(prefs.getBoolean("images",true));s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setAllowFileAccess(false);s.setSafeBrowsingEnabled(true);s.setSupportZoom(prefs.getBoolean("zoom",true));s.setBuiltInZoomControls(prefs.getBoolean("zoom",true));s.setDisplayZoomControls(false);s.setJavaScriptCanOpenWindowsAutomatically(prefs.getBoolean("popups",false));s.setSupportMultipleWindows(true);if(prefs.getBoolean("desktop",false)){s.setUserAgentString(desktopAgent());s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);}if(inc)s.setCacheMode(WebSettings.LOAD_NO_CACHE);
 // Inject before page scripts, including cross-origin video-player frames.
 if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){
  try{WebViewCompat.addDocumentStartJavaScript(w,earlyPageGuardScript(),java.util.Collections.singleton("*"));}
  catch(Exception ignored){}
 }
 CookieManager.getInstance().setAcceptThirdPartyCookies(w,!inc&&!prefs.getBoolean("blockCookies",true));
 w.setDownloadListener((link,userAgent,disposition,mime,length)->{
  String filename=(mime!=null&&(mime.startsWith("video/")||mime.startsWith("audio/")))?mediaFilename(link,mime):URLUtil.guessFileName(link,disposition,mime);
  themed().setTitle("Download file").setMessage(filename+" • "+(length>0?(length/1024)+" KB":"Size unknown"))
   .setNegativeButton("Cancel",null).setPositiveButton("Download",(d,n)->startDownload(link,filename,mime)).show();
 });
 w.addJavascriptInterface(new Object(){
  @JavascriptInterface public boolean adBlockEnabled(){return prefs.getBoolean("adBlocker",true);}
  @JavascriptInterface public boolean popupsAllowed(){return prefs.getBoolean("popups",false);}
  @JavascriptInterface public void playing(){runOnUiThread(()->{if(w==active()){mediaPlaying=true;signalMediaFound(w);}});}
  @JavascriptInterface public void mediaInfo(int width,int height,String title){
   if(width<=0||height<=0||width>16384||height>16384)return;
   String label=title==null?"":title.trim();
   if(label.length()>180)label=label.substring(0,180);
   final String detected=label;
   runOnUiThread(()->{
    if(tabs.isEmpty()||w!=active())return;
    videoWidth=width;videoHeight=height;
    if(!detected.isEmpty()){detectedMediaTitle=detected;detectedMediaPage=w.getUrl();}
    if(fullscreenVideo!=null)applyVideoOrientation();
   });
  }
  @JavascriptInterface public void found(){runOnUiThread(()->signalMediaFound(w));}
  @JavascriptInterface public void pageChanged(){runOnUiThread(()->{if(w==active()){mediaPlaying=false;videoWidth=0;videoHeight=0;detectedMediaTitle="";detectedMediaPage="";mediaAvailable=isMediaPage(w.getUrl());updateMediaOverlay();if(mediaAvailable)prefetchMediaFormats(w.getUrl());}});}
  @JavascriptInterface public void stopped(){runOnUiThread(()->{if(w==active()){mediaPlaying=false;updateMediaOverlay();}});}
  @JavascriptInterface public void playerTap(){recentPlayerTap.put(w,SystemClock.elapsedRealtime());}
  @JavascriptInterface public void candidateWithRef(String link,String referer){
   if(link==null||link.length()>4096||referer==null||referer.length()>4096)return;
   runOnUiThread(()->{
    try{Uri u=Uri.parse(link);if(isNetworkMediaUrl(u))noteNetworkMedia(w,u,referer);}
    catch(Exception ignored){}
   });
  }
  @JavascriptInterface public void candidate(String link){
   if(link==null||link.length()>4096)return;
   runOnUiThread(()->{
    try{Uri u=Uri.parse(link);if(isNetworkMediaUrl(u))noteNetworkMedia(w,u);}
    catch(Exception ignored){}
   });
  }
 },"MHMedia");
 w.setWebChromeClient(new WebChromeClient(){
  // Do not let click-to-play advertising scripts open pop-under tabs or
  // replace the video page. Normal same-tab navigation is handled separately.
  @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
   // Reject popunders completely unless the user explicitly enabled popups.
   if(!prefs.getBoolean("popups",false)||!isUserGesture||resultMsg==null)return false;
   createTab(privateOnly);
   android.webkit.WebView.WebViewTransport transport=(android.webkit.WebView.WebViewTransport)resultMsg.obj;
   transport.setWebView(active());resultMsg.sendToTarget();
   return true;
  }
  @Override public void onShowCustomView(View view,CustomViewCallback callback){showVideo(view,callback);}
  @Override public void onProgressChanged(WebView view,int progress){
   if(progress<20)return;
   int stage=progress>=80?2:1;
   Integer last=mediaInjectionStages.get(view);
   if(last!=null&&last>=stage)return;
   mediaInjectionStages.put(view,stage);
   if(view.getUrl()!=null&&view.getUrl().startsWith("http")){
    view.evaluateJavascript(mediaObserverScript(),null);
    view.evaluateJavascript(mediaProbeScript(),null);
    applyAdCosmetics(view);
   }
  }
  @Override public void onHideCustomView(){hideVideo();}
  @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
   themed().setTitle("Website message").setMessage(message).setPositiveButton("OK",(d,n)->result.confirm()).setOnCancelListener(d->result.cancel()).show();return true;
  }
  @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb){
   if(!prefs.getBoolean("location",false)){cb.invoke(origin,false,false);return;}
   boolean granted=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;
   if(!granted){cb.invoke(origin,false,false);runOnUiThread(()->dialog("Location permission","Enable precise location for MH Browser in Android App Settings."));return;}
   runOnUiThread(()->themed().setTitle("Location permission").setMessage("Allow "+origin+" to access your device location?")
    .setNegativeButton("Deny",(d,n)->cb.invoke(origin,false,false))
    .setPositiveButton("Allow",(d,n)->cb.invoke(origin,true,false)).show());
  }
 });
 w.setWebViewClient(new WebViewClient(){
  @Override public void onReceivedHttpAuthRequest(WebView view,HttpAuthHandler handler,String host,String realm){
   if(prefs.getBoolean("proxyActive",false)&&host.equalsIgnoreCase(prefs.getString("proxyHost",""))&&!proxyUser.isEmpty()&&!proxyPassword.isEmpty()){handler.proceed(proxyUser,proxyPassword);}
   else handler.cancel();
  }
  @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
   if(!request.isForMainFrame())return false;
   Uri target=request.getUrl();String scheme=target.getScheme();
   if(scheme==null)return false;
   if(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https")){
    if(prefs.getBoolean("adBlocker",true)){
     if(AdBlockRules.shouldBlockNavigation(target,true))return true;
     Long tapped=recentPlayerTap.get(view);
     if(tapped!=null&&SystemClock.elapsedRealtime()-tapped<5000&&
        AdBlockRules.isCrossSiteAdRedirect(target,pageForFiltering.get(view)))return true;
    }
    return false;
   }
   if(scheme.equalsIgnoreCase("about"))return false;
   if(scheme.equalsIgnoreCase("fb")){
    // Facebook uses fb:// to offer its app. Never hand this to Android.
    // If the public Facebook page is already loading, just suppress the app prompt.
    if(onFacebookWebsite(view))return true;
    String web=facebookWebUrl(target);
    if(web!=null)view.loadUrl(web);
    else Toast.makeText(BrowserActivity.this,"This Facebook link has no public web address",Toast.LENGTH_SHORT).show();
    return true;
   }
   if(scheme.equalsIgnoreCase("intent")){
    try{
     Intent app=Intent.parseUri(target.toString(),Intent.URI_INTENT_SCHEME);
     String pkg=app.getPackage();
     String fallback=app.getStringExtra("browser_fallback_url");
     Uri data=app.getData();
     boolean facebookIntent=(pkg!=null&&pkg.toLowerCase(java.util.Locale.ROOT).startsWith("com.facebook."))
       ||(data!=null&&"fb".equalsIgnoreCase(data.getScheme()));
     if(facebookIntent){
      // Ignore Facebook's automatic app-launch intent while its page is open.
      if(onFacebookWebsite(view))return true;
      String web=data==null?null:facebookWebUrl(data);
      if(web==null&&fallback!=null&&(fallback.startsWith("https://")||fallback.startsWith("http://"))){
       Uri fbFallback=Uri.parse(fallback);String h=fbFallback.getHost();
       if(h!=null&&(h.equalsIgnoreCase("facebook.com")||h.toLowerCase(java.util.Locale.ROOT).endsWith(".facebook.com")))web=fallback;
      }
      if(web!=null)view.loadUrl(web);
      return true;
     }
     // Non-Facebook intents keep the existing supported external-app behavior.
     app.addCategory(Intent.CATEGORY_BROWSABLE);
     app.setComponent(null);app.setSelector(null);
     try{startActivity(app);return true;}catch(android.content.ActivityNotFoundException noHandler){}
     if(fallback!=null&&(fallback.startsWith("https://")||fallback.startsWith("http://"))){view.loadUrl(fallback);return true;}
    }catch(Exception ignored){}
    Toast.makeText(BrowserActivity.this,"This link needs another app",Toast.LENGTH_SHORT).show();
    return true;
   }
   try{
    Intent app=new Intent(Intent.ACTION_VIEW,target);
    app.addCategory(Intent.CATEGORY_BROWSABLE);
    startActivity(app);return true;
   }catch(Exception ignored){}
   Toast.makeText(BrowserActivity.this,"No app available to open this link",Toast.LENGTH_SHORT).show();
   return true;
  }
  @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler handler,android.net.http.SslError error){
   handler.cancel(); // Never ignore certificate errors to force a video to play.
   String failing=error==null?null:error.getUrl();
   // A broken ad, image or video subresource must not kick users off a healthy page.
   try{
    Uri failed=Uri.parse(failing==null?"":failing);
    Uri main=Uri.parse(v.getUrl()==null?"":v.getUrl());
    if(failed.getHost()!=null&&failed.getHost().equalsIgnoreCase(main.getHost()))
     recoverFailedVideoHost(v,failing,"SSL connection failed");
   }catch(Exception ignored){}
  }
  @Override public void onReceivedError(WebView v,WebResourceRequest request,android.webkit.WebResourceError error){
   if(!request.isForMainFrame())return;
   String detail=error==null?"":String.valueOf(error.getDescription());
   int code=error==null?0:error.getErrorCode();
   if(code==WebViewClient.ERROR_FAILED_SSL_HANDSHAKE||detail.toLowerCase(java.util.Locale.ROOT).contains("ssl_protocol_error")||detail.toLowerCase(java.util.Locale.ROOT).contains("ssl handshake"))
    recoverFailedVideoHost(v,request.getUrl().toString(),detail);
  }
  @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap icon){
   Long tapped=recentPlayerTap.get(v);
   if(AdBlockRules.shouldBlockNavigation(Uri.parse(u==null?"":u),prefs.getBoolean("adBlocker",true))||
      (prefs.getBoolean("adBlocker",true)&&tapped!=null&&
       SystemClock.elapsedRealtime()-tapped<5000&&
       AdBlockRules.isCrossSiteAdRedirect(Uri.parse(u==null?"":u),pageForFiltering.get(v)))){
    v.stopLoading();
    if(v.canGoBack())v.goBack();
    return;
   }
   if(u!=null)pageForFiltering.put(v,u);
   mediaInjectionStages.remove(v);
   networkMediaSeen.remove(v);
   if(v==active()){
    mediaPlaying=false;mediaAvailable=isMediaPage(u);updateMediaOverlay();
    if(isMediaPage(u))prefetchMediaFormats(u);
    if(!showingSettings&&screen==0&&webPageLayer==null&&u!=null&&!"about:blank".equals(u)){
     v.post(()->{if(v==active()&&screen==0&&webPageLayer==null&&!showingSettings)display();});
    }
    if(address!=null&&u!=null)address.setText(u.equals("about:blank")?"":u);
   }
  }
  @Override public void onPageFinished(WebView v,String u){
   if(v==active()&&!showingSettings){
    if(screen==0&&webPageLayer==null&&u!=null&&!"about:blank".equals(u))display();
    if(address!=null)address.setText(u.equals("about:blank")?"":u);
    if(isMediaPage(u)){mediaAvailable=true;updateMediaOverlay();prefetchMediaFormats(u);}
   }
   // Keep a safe return point when a third-party video host fails.
   if(u!=null&&(u.startsWith("https://")||u.startsWith("http://"))){
    Long recovery=videoConnectionRecovery.get(v);
    if(recovery==null||SystemClock.elapsedRealtime()-recovery>5000)lastWorkingPage.put(v,u);
   }
   refreshTabTitle(v);
   if(v==active()&&u!=null&&u.startsWith("http"))capturePageMovieTitle(v,u,null);
 v.evaluateJavascript(mediaObserverScript(),null);v.evaluateJavascript(mediaProbeScript(),null);applyAdCosmetics(v);for(Tab t:tabs)if(t.web==v&&!t.incognito&&u.startsWith("http")){addHistory(u,v.getTitle());break;}}@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
   if(AdBlockRules.shouldBlock(req,pageForFiltering.get(v),prefs.getBoolean("adBlocker",true),prefs.getBoolean("trackers",true)))
    return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
   if(!req.isForMainFrame()&&isNetworkMediaRequest(req)){
    String referer=req.getRequestHeaders()==null?null:req.getRequestHeaders().get("Referer");
    v.post(()->noteNetworkMedia(v,req.getUrl(),referer));
   }
   return null;
  }});
 tabs.add(new Tab(w,inc,prefs.getBoolean("desktop",false)));index=tabs.size()-1;display();}
 void display(){
  showingSettings=false;screen=0;head.removeAllViews();tabStrip();body.removeAllViews();
  webPageLayer=null;mediaOverlay=null;
  if(active().getUrl()==null||active().getUrl().equals("about:blank")){address=null;home();}
  else{
   header();WebView w=active();
   if(w.getParent() instanceof ViewGroup)((ViewGroup)w.getParent()).removeView(w);
   FrameLayout layer=new FrameLayout(this);webPageLayer=layer;
   body.addView(layer,new LinearLayout.LayoutParams(-1,-1));
   layer.addView(w,new FrameLayout.LayoutParams(-1,-1));
   mediaOverlay=makeMediaOverlay();
   FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(59),dp(59),Gravity.END|Gravity.BOTTOM);
   mp.setMargins(dp(12),dp(12),dp(17),dp(85));layer.addView(mediaOverlay,mp);
   mediaOverlay.setVisibility(mediaPlaying||mediaAvailable||isMediaPage(w.getUrl())?View.VISIBLE:View.GONE);
   address.setText(w.getUrl());
  }
  controls();
 }
 void tabStrip(){
  tabTitleViews.clear();
  HorizontalScrollView sc=new HorizontalScrollView(this);sc.setHorizontalScrollBarEnabled(false);
  sc.setFillViewport(false);sc.setBackgroundColor(themeSurface(0xff0a1b31));head.addView(sc,new LinearLayout.LayoutParams(-1,dp(51)));
  LinearLayout row=row();row.setPadding(dp(10),dp(5),dp(10),dp(5));sc.addView(row);
  for(int i=0;i<tabs.size();i++){
   final int n=i;Tab t=tabs.get(i);LinearLayout pill=row();
   pill.setPadding(dp(9),0,dp(3),0);pill.setBackground(grad(i==index?0xff245e88:0xff182b44,i==index?0xff183e65:0xff12243a,13));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(138),dp(39));lp.rightMargin=dp(7);row.addView(pill,lp);
   String name=t.web.getTitle();if(name==null||name.isEmpty()||"about:blank".equals(t.web.getUrl()))name="New tab";
   TextView title=text((t.incognito?"◉ ":"")+name,12,WHITE);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
   pill.addView(title,new LinearLayout.LayoutParams(0,-1,1));tabTitleViews.put(t.web,title);title.setOnClickListener(v->{index=n;display();});
   TextView x=action("×",20,()->closeTab(n));pill.addView(x,new LinearLayout.LayoutParams(dp(27),-1));
  }
  TextView add=action("+",27,()->createTab(false));add.setBackground(bg(PANEL));row.addView(add,new LinearLayout.LayoutParams(dp(39),dp(39)));
 }
 void refreshTabTitle(WebView w){
  TextView title=tabTitleViews.get(w);
  if(title==null)return;
  String name=w.getTitle();
  if(name==null||name.isEmpty()||"about:blank".equals(w.getUrl()))name="New tab";
  boolean inc=false;
  for(Tab t:tabs)if(t.web==w){inc=t.incognito;break;}
  title.setText((inc?"◉ ":"")+name);
 }
 void closeTab(int n){
  if(n<0||n>=tabs.size())return;
  mediaPlaying=false;mediaAvailable=false;updateMediaOverlay();
  Tab old=tabs.remove(n);old.web.stopLoading();
  if(old.web.getParent() instanceof ViewGroup)((ViewGroup)old.web.getParent()).removeView(old.web);
  old.web.destroy();
  if(tabs.isEmpty()){
   index=-1;
   if(privateOnly){
    // Closing the final private tab ends the isolated window and reveals
    // the existing main-browser Activity (no app restart or splash).
    finish();overridePendingTransition(0,0);return;
   }
   createTab(false);
  }else{
   if(n<index)index--;
   else if(n==index)index=Math.min(n,tabs.size()-1);
   display();
  }
 }
 void tabActions(){String[] options={"New tab","New private tab","Close current tab","Switch tab"};themed().setTitle("Tabs").setItems(options,(d,n)->{if(n==0)createTab(false);else if(n==1)createTab(true);else if(n==2)closeTab(index);else tabPicker();}).show();}
 void tabPicker(){String[] names=new String[tabs.size()];for(int i=0;i<tabs.size();i++){String t=tabs.get(i).web.getTitle();names[i]=(tabs.get(i).incognito?"◉ ":"")+(t==null?"New tab":t);}themed().setTitle("Switch tab").setSingleChoiceItems(names,index,(d,n)->{index=n;d.dismiss();display();}).setNegativeButton("Close",null).show();}


 void home(){
  ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setVerticalScrollBarEnabled(false);body.addView(sc);
  LinearLayout c=column();c.setPadding(dp(19),dp(20),dp(19),dp(20));sc.addView(c);
  LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);c.addView(brand);
  ImageView logo=new ImageView(this);logo.setImageResource(com.mh.browser.R.drawable.app_logo);
  brand.addView(logo,new LinearLayout.LayoutParams(dp(70),dp(70)));
  LinearLayout brandWords=column();brandWords.setPadding(dp(10),0,0,0);brand.addView(brandWords,new LinearLayout.LayoutParams(0,-2,1));
  TextView title=text(tabs.get(index).incognito?"Private Browsing":"MH Browser",23,WHITE);title.setTypeface(null,Typeface.BOLD);brandWords.addView(title);
  brandWords.addView(text("Fast  •  Private  •  Secure",12,0xffa8bcd5));
  LinearLayout profile=column();profile.setGravity(Gravity.CENTER);brand.addView(profile,new LinearLayout.LayoutParams(dp(70),dp(76)));
  ImageView google=new ImageView(this);google.setImageResource(R.drawable.google_g);
  google.setBackground(grad(WHITE,0xfff1f6ff,30));google.setPadding(dp(6),dp(6),dp(6),dp(6));
  profile.addView(google,new LinearLayout.LayoutParams(dp(42),dp(42)));
  TextView account=text("Google",10,WHITE);account.setGravity(Gravity.CENTER);profile.addView(account);
  String googleCookies=CookieManager.getInstance().getCookie("https://accounts.google.com/");
  boolean sessionHint=!privateOnly&&googleCookies!=null&&(googleCookies.contains("SID=")||googleCookies.contains("HSID="));
  TextView login=text("Login Gmail",9,GRAY);login.setGravity(Gravity.CENTER);profile.addView(login);
  profile.setOnClickListener(v->googleAccount());

  if(tabs.get(index).incognito){
    TextView warning=text("Private session • separate browser storage. Close all private tabs to end this session.",13,0xff9bdbff);
    warning.setPadding(dp(12),dp(12),dp(12),dp(12));warning.setBackground(bg(PANEL));c.addView(warning);
  }
  TextView caption=text("Discover the web",19,WHITE);caption.setTypeface(null,Typeface.BOLD);
  LinearLayout.LayoutParams cap=new LinearLayout.LayoutParams(-1,-2);cap.topMargin=dp(30);c.addView(caption,cap);
  EditText query=new EditText(this);query.setSingleLine();query.setTextColor(themeText(WHITE));query.setTextSize(16);
  query.setHintTextColor(themeText(0xffb7c8de));query.setHint("⌕   Search the web or enter a URL");
  query.setPadding(dp(17),0,dp(14),0);query.setBackground(grad(0xff1a3355,0xff122b47,16));
  LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,dp(56));qlp.topMargin=dp(12);c.addView(query,qlp);
  query.setImeOptions(2);query.setOnEditorActionListener((v,a,e)->{navigate(query.getText().toString());return true;});
  LinearLayout section=row();LinearLayout.LayoutParams sec=new LinearLayout.LayoutParams(-1,-2);sec.topMargin=dp(26);c.addView(section,sec);
  TextView shortcuts=text("Quick access",18,WHITE);shortcuts.setTypeface(null,Typeface.BOLD);section.addView(shortcuts,new LinearLayout.LayoutParams(0,-2,1));
  TextView edit=text("EDIT",12,0xff62caff);section.addView(edit);edit.setOnClickListener(v->editShortcuts());
  JSONArray links=shortcuts();int count=links.length()+1;
  for(int start=0;start<count;start+=4){
    LinearLayout line=row();line.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams llp=new LinearLayout.LayoutParams(-1,-2);llp.topMargin=dp(14);c.addView(line,llp);
    for(int j=0;j<4;j++){
      int n=start+j;if(n>=count){TextView empty=text("",11,WHITE);line.addView(empty,new LinearLayout.LayoutParams(0,dp(84),1));continue;}
      JSONObject obj=n<links.length()?links.optJSONObject(n):null;
      String label=obj==null?"Add":obj.optString("name","Site");
      String target=obj==null?"":obj.optString("url","");
      LinearLayout item=column();item.setGravity(Gravity.CENTER);line.addView(item,new LinearLayout.LayoutParams(0,dp(84),1));
      if(obj==null){
       TextView circle=text("+",24,WHITE);circle.setTypeface(null,Typeface.BOLD);circle.setGravity(Gravity.CENTER);
       circle.setBackground(grad(0xff21547d,0xff21547d,20));
       item.addView(circle,new LinearLayout.LayoutParams(dp(47),dp(47)));
      }else{
       FrameLayout iconFrame=new FrameLayout(this);
       iconFrame.setBackground(grad(WHITE,0xffe8f2ff,18));
       item.addView(iconFrame,new LinearLayout.LayoutParams(dp(47),dp(47)));
       TextView fallback=text(label.isEmpty()?"?":label.substring(0,1).toUpperCase(Locale.ROOT),21,0xff20446b);
       fallback.setTypeface(null,Typeface.BOLD);fallback.setGravity(Gravity.CENTER);
       iconFrame.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
       ImageView official=new ImageView(this);official.setScaleType(ImageView.ScaleType.FIT_CENTER);
       official.setPadding(dp(7),dp(7),dp(7),dp(7));iconFrame.addView(official,new FrameLayout.LayoutParams(-1,-1));
       loadShortcutIcon(official,target);
      }
      TextView lab=text(label,11,0xffd7e7fb);lab.setGravity(Gravity.CENTER);lab.setSingleLine(true);
      lab.setEllipsize(android.text.TextUtils.TruncateAt.END);item.addView(lab,new LinearLayout.LayoutParams(-1,dp(29)));
      item.setOnClickListener(v->{if(obj==null)shortcutForm(-1);else navigate(target);});
      item.setOnLongClickListener(v->{if(obj!=null)shortcutOptions(n);return true;});
    }
  }
  FrameLayout ad=new FrameLayout(this);ad.setBackground(grad(0xff153f68,0xff0b2039,19));
  LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(177));hp.topMargin=dp(24);c.addView(ad,hp);
  View art=new View(this){
   android.graphics.Paint p=new android.graphics.Paint(3);
   @Override protected void onDraw(android.graphics.Canvas canvas){
    super.onDraw(canvas);float w=getWidth(),h=getHeight();
    p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(2));
    for(int i=0;i<8;i++){p.setColor(i%2==0?0x5570d5ff:0x4478a8f8);
     canvas.drawLine(w*.58f+dp(i*14),-dp(20),w*.12f+dp(i*21),h+dp(20),p);}
    p.setStrokeWidth(dp(3));p.setColor(0x5593d9ff);
    canvas.drawCircle(w*.89f,h*.24f,dp(43),p);canvas.drawCircle(w*.89f,h*.24f,dp(28),p);
    p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(0x2267d9ff);
    canvas.drawCircle(w*.9f,h*.83f,dp(58),p);
   }
  };ad.addView(art,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout hero=column();hero.setPadding(dp(19),dp(15),dp(15),dp(12));ad.addView(hero,new FrameLayout.LayoutParams(-1,-1));
  TextView tag=text("ADVERTISING SPACE",11,0xff8feaff);tag.setTypeface(null,Typeface.BOLD);hero.addView(tag);
  TextView htitle=text("Advertising - MH Browser",21,WHITE);htitle.setTypeface(null,Typeface.BOLD);
  LinearLayout.LayoutParams ht=new LinearLayout.LayoutParams(-1,-2);ht.topMargin=dp(11);hero.addView(htitle,ht);
  hero.addView(text("For Advertising, Contact with Us",13,0xffd5e8fa));
  TextView go=text("Contact with Us  →",14,0xff9beaff);go.setTypeface(null,Typeface.BOLD);
  LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=dp(17);hero.addView(go,gp);
  ad.setOnClickListener(v->showAdvertisingContact());
  LinearLayout footer=row();footer.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=dp(28);c.addView(footer,fp);
  TextView priv=text("◉  Privacy controls",13,0xffa5c8f2);footer.addView(priv,new LinearLayout.LayoutParams(0,-2,1));
  priv.setOnClickListener(v->settings());
  TextView newTab=text("+  New tab",13,0xff8bdfff);footer.addView(newTab);newTab.setOnClickListener(v->createTab(false));
 }


 // Advertising contact panel follows the same themed dialogs as other app actions.
 void showAdvertisingContact(){
  LinearLayout panel=column();
  panel.setPadding(dp(20),dp(22),dp(20),dp(9));
  panel.setGravity(Gravity.CENTER_HORIZONTAL);
  TextView heading=text("Advertising - MH Browser",21,WHITE);
  heading.setTypeface(null,Typeface.BOLD);heading.setGravity(Gravity.CENTER);
  panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
  TextView subtitle=text("Team Support",13,0xff92dcff);
  subtitle.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams sub=new LinearLayout.LayoutParams(-1,-2);
  sub.topMargin=dp(8);panel.addView(subtitle,sub);
  View separator=new View(this);separator.setBackgroundColor(0x445a96c2);
  LinearLayout.LayoutParams sep=new LinearLayout.LayoutParams(-1,dp(1));
  sep.topMargin=dp(20);sep.bottomMargin=dp(18);panel.addView(separator,sep);
  addAdvertisingContact(panel,"Hammad","+92 343 4824609","923434824609");
  addAdvertisingContact(panel,"Masoom","+92 341 4928858","923414928858");
  TextView thanks=text("Thanks for your support.",13,0xffaec7de);
  thanks.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams foot=new LinearLayout.LayoutParams(-1,-2);
  foot.topMargin=dp(12);foot.bottomMargin=dp(10);panel.addView(thanks,foot);
  AlertDialog dialog=themed().setView(panel).setPositiveButton("Close",null).create();
  dialog.show();styleDialog(dialog);
 }
 void addAdvertisingContact(LinearLayout panel,String name,String number,String digits){
  LinearLayout card=row();
  card.setPadding(dp(13),dp(12),dp(12),dp(12));
  card.setBackground(grad(0xff1a405b,0xff123047,14));
  LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
  cp.bottomMargin=dp(11);panel.addView(card,cp);
  // Exact WhatsApp logo from the supplied reference, transparent background.
  ImageView icon=new ImageView(this);
  icon.setImageResource(R.drawable.whatsapp_contact);
  icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
  icon.setBackgroundColor(Color.TRANSPARENT);
  icon.setPadding(0,0,0,0);
  card.addView(icon,new LinearLayout.LayoutParams(dp(43),dp(43)));
  LinearLayout info=column();info.setPadding(dp(13),0,0,0);
  card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
  TextView contact=text(name,16,WHITE);contact.setTypeface(null,Typeface.BOLD);info.addView(contact);
  TextView phone=text(number+"  •  WhatsApp",12,0xffa5d9ed);
  LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(-1,-2);pl.topMargin=dp(3);info.addView(phone,pl);
  TextView arrow=text("›",24,0xff6fe7ae);arrow.setGravity(Gravity.CENTER);
  card.addView(arrow,new LinearLayout.LayoutParams(dp(23),dp(43)));
  card.setContentDescription("WhatsApp "+name+" "+number);
  card.setOnClickListener(v->{
   try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+digits)));}
   catch(Exception e){Toast.makeText(this,"Unable to open WhatsApp link",Toast.LENGTH_SHORT).show();}
  });
 }
 void loadShortcutIcon(ImageView image,String target){
  Uri parsed=Uri.parse(target);String host=parsed.getHost();
  if(host==null||host.isEmpty())return;
  host=host.toLowerCase(Locale.ROOT);
  image.setTag(host);
  Bitmap cached=shortcutIconCache.get(host);
  if(cached!=null){image.setImageBitmap(cached);return;}
  final String domain=host;
  new Thread(()->{
   java.net.HttpURLConnection connection=null;
   try{
    String iconUrl="https://www.google.com/s2/favicons?domain="+Uri.encode(domain)+"&sz=128";
    connection=(java.net.HttpURLConnection)new java.net.URL(iconUrl).openConnection();
    connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
    connection.setRequestProperty("Accept","image/png,image/*");
    if(connection.getResponseCode()!=200)return;
    if(connection.getContentLength()>262144)return;
    Bitmap bmp;
    try(java.io.InputStream stream=connection.getInputStream()){
     bmp=BitmapFactory.decodeStream(stream);
    }
    if(bmp==null)return;
    shortcutIconCache.put(domain,bmp);
    final Bitmap icon=bmp;
    runOnUiThread(()->{if(image.getParent()!=null&&domain.equals(image.getTag()))image.setImageBitmap(icon);});
   }catch(Exception ignored){}
   finally{if(connection!=null)connection.disconnect();}
  }).start();
 }
 void tabList(){
  head.removeAllViews();body.removeAllViews();
  LinearLayout h=row();h.setPadding(dp(15),dp(10),dp(15),dp(10));head.addView(h);
  TextView back=action("‹",28,this::display);h.addView(back,new LinearLayout.LayoutParams(dp(40),dp(44)));
  TextView title=text("Tabs  ("+tabs.size()+")",22,WHITE);title.setTypeface(null,Typeface.BOLD);h.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  ScrollView sc=new ScrollView(this);downloadsScrollView=sc;body.addView(sc);LinearLayout items=column();items.setPadding(dp(15),dp(10),dp(15),dp(16));sc.addView(items);
  for(int i=0;i<tabs.size();i++){
    final int n=i;Tab t=tabs.get(i);LinearLayout r=row();r.setPadding(dp(15),dp(12),dp(15),dp(12));
    r.setBackground(grad(0xff183654,0xff0f243e,15));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(88));lp.bottomMargin=dp(10);items.addView(r,lp);
    LinearLayout info=column();r.addView(info,new LinearLayout.LayoutParams(0,-2,1));
    info.addView(text(t.incognito?"◉  PRIVATE TAB":"🌐  BROWSER TAB",11,0xff7dd8ff));
    TextView name=text(t.web.getTitle()==null?"New Tab":t.web.getTitle(),16,WHITE);name.setSingleLine(true);info.addView(name);
    info.setOnClickListener(v->{index=n;display();});
    TextView close=action("×",26,()->{closeTab(n);if(!isFinishing()&&!isDestroyed()&&!tabs.isEmpty())tabList();});
    r.addView(close,new LinearLayout.LayoutParams(dp(45),dp(50)));
  }
  Button plus=new Button(this);plus.setText("+ New tab");items.addView(plus);plus.setOnClickListener(v->createTab(false));
  Button priv=new Button(this);priv.setText("+ Private tab");items.addView(priv);priv.setOnClickListener(v->createTab(true));
 }


 void menu(){
  ScrollView sc=new ScrollView(this);sc.setFillViewport(false);
  LinearLayout list=column();list.setPadding(dp(16),dp(16),dp(16),dp(14));sc.addView(list);
  LinearLayout brand=column();brand.setGravity(Gravity.CENTER);list.addView(brand);
  ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.app_logo);
  brand.addView(logo,new LinearLayout.LayoutParams(dp(58),dp(58)));
  TextView heading=text("MH Browser",21,WHITE);heading.setTypeface(null,Typeface.BOLD);heading.setGravity(Gravity.CENTER);
  brand.addView(heading);
  TextView sub=text("Fast  •  Private  •  Secure",11,GRAY);sub.setGravity(Gravity.CENTER);brand.addView(sub);
  menuLabel(list,"TABS & NAVIGATION");
  AlertDialog d=themed().setView(sc).create();
  menuItem(list,"＋","New tab","Open another browser tab",()->createTab(false),d);
  menuItem(list,"◉","New private tab","Private browsing preview",()->createTab(true),d);
  menuItem(list,"▣","Switch tabs","Choose an open tab",this::tabPicker,d);
  menuLabel(list,"YOUR BROWSING");
  menuItem(list,"☆","Bookmarks","Save and manage websites",this::bookmarks,d);
  menuItem(list,"◷","History","Today, previous days, weeks and more",this::history,d);
  menuItem(list,"↓","Downloads","Files saved by Android",this::downloads,d);
  menuLabel(list,"TOOLS & SETTINGS");
  menuItem(list,"⚙","Settings","Customize MH Browser",this::settings,d);
  menuItem(list,"◐","Theme",lightTheme?"Light mode • switch to Dark":"Dark mode • switch to Light",this::themeSettings,d);
  menuItem(list,"▤","Desktop site",tabs.get(index).desktop?"Enabled for this tab":"Request a desktop layout",this::toggleDesktop,d);
  menuItem(list,"↗","Share page","Send this website to another app",()->{
   Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,active().getUrl());
   startActivity(Intent.createChooser(share,"Share page"));
  },d);
  d.show();styleDialog(d);
  if(d.getWindow()!=null){android.util.DisplayMetrics m=getResources().getDisplayMetrics();d.getWindow().setLayout((int)(m.widthPixels*0.91f),Math.min(dp(690),(int)(m.heightPixels*0.83f)));}
 }
 void menuLabel(LinearLayout list,String label){
  TextView v=text(label,11,0xff68d5ff);v.setTypeface(null,Typeface.BOLD);
  v.setPadding(dp(8),dp(18),0,dp(8));list.addView(v);
 }
 void menuItem(LinearLayout list,String symbol,String label,String desc,Runnable action,AlertDialog d){
  LinearLayout r=row();r.setPadding(dp(11),dp(9),dp(9),dp(9));r.setBackground(grad(0xff193653,0xff10263e,13));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(6);list.addView(r,lp);
  TextView icon=text(symbol,23,0xff82dfff);icon.setGravity(Gravity.CENTER);r.addView(icon,new LinearLayout.LayoutParams(dp(41),dp(47)));
  LinearLayout words=column();r.addView(words,new LinearLayout.LayoutParams(0,-2,1));
  TextView name=text(label,15,WHITE);name.setTypeface(null,Typeface.BOLD);words.addView(name);
  words.addView(text(desc,11,GRAY));r.addView(text("›",23,GRAY));
  r.setOnClickListener(v->{d.dismiss();action.run();});
 }
 String desktopAgent(){
  String ua=WebSettings.getDefaultUserAgent(this);
  return ua.replaceAll("\\(Linux; Android[^)]*\\)","(X11; Linux x86_64)").replace(" Mobile "," ").replace("; wv","");
 }
 void toggleDesktop(){
  Tab t=tabs.get(index);t.desktop=!t.desktop;
  WebSettings w=t.web.getSettings();
  w.setUserAgentString(t.desktop?desktopAgent():WebSettings.getDefaultUserAgent(this));
  w.setUseWideViewPort(t.desktop);w.setLoadWithOverviewMode(t.desktop);
  if(t.web.getUrl()!=null&&t.web.getUrl().startsWith("http"))t.web.reload();
  Toast.makeText(this,t.desktop?"Desktop site requested":"Mobile site requested",Toast.LENGTH_SHORT).show();
 }
 TextView makeMediaOverlay(){
  TextView button=action("↓",27,this::mediaDownloads);
  button.setContentDescription("Download available audio or video");
  button.setTypeface(null,Typeface.BOLD);
  button.setBackground(grad(0xff238dc4,0xff12567e,19));
  button.setElevation(dp(9));
  return button;
 }
 void updateMediaOverlay(){
  if(mediaOverlay!=null)mediaOverlay.setVisibility(mediaPlaying||mediaAvailable||(!tabs.isEmpty()&&isMediaPage(active().getUrl()))?View.VISIBLE:View.GONE);
  if(fullscreenMediaOverlay!=null)fullscreenMediaOverlay.setVisibility(View.VISIBLE);
 }
 void applyVideoOrientation(){
  if(fullscreenVideo==null)return;
  boolean portrait=videoWidth>0&&videoHeight>0?videoWidth<=videoHeight:isVerticalMediaPage(tabs.isEmpty()?"":active().getUrl());
  // Standard players default to landscape while their metadata loads; known shorts stay portrait.
  int desired=portrait?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
  if(getRequestedOrientation()!=desired)setRequestedOrientation(desired);
 }
 boolean isVerticalMediaPage(String url){
  if(url==null)return false;
  try{
   Uri u=Uri.parse(url);String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
   String path=u.getPath()==null?"":u.getPath().toLowerCase(Locale.ROOT);
   return (host.contains("youtube.com")&&path.startsWith("/shorts/"))||
    (host.contains("instagram.com")&&(path.startsWith("/reel/")||path.startsWith("/reels/")))||
    (host.contains("facebook.com")&&path.startsWith("/reel/"))||
    (host.contains("tiktok.com")&&path.contains("/video/"));
  }catch(Exception ignored){return false;}
 }
 void showVideo(View v,WebChromeClient.CustomViewCallback cb){
  if(fullscreenVideo!=null){cb.onCustomViewHidden();return;}
  orientationBeforeVideo=getRequestedOrientation();
  fullscreenVideo=v;fullscreenCallback=cb;
  videoLayer=new FrameLayout(this);videoLayer.setBackgroundColor(Color.BLACK);
  if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);
  videoLayer.addView(v,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
  fullscreenMediaOverlay=makeMediaOverlay();
  FrameLayout.LayoutParams downloadPosition=new FrameLayout.LayoutParams(dp(59),dp(59),Gravity.END|Gravity.BOTTOM);
  downloadPosition.setMargins(dp(12),dp(12),dp(17),dp(45));
  videoLayer.addView(fullscreenMediaOverlay,downloadPosition);
  updateMediaOverlay();
  ((ViewGroup)getWindow().getDecorView()).addView(videoLayer,new ViewGroup.LayoutParams(-1,-1));
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
  applyVideoOrientation();
  if(!tabs.isEmpty())active().evaluateJavascript("if(window.mhMediaScan)window.mhMediaScan();",null);
 }
 void hideVideo(){
  if(fullscreenVideo==null)return;
  if(videoLayer!=null){videoLayer.removeAllViews();((ViewGroup)getWindow().getDecorView()).removeView(videoLayer);}
  fullscreenVideo=null;videoLayer=null;fullscreenMediaOverlay=null;
  WebChromeClient.CustomViewCallback cb=fullscreenCallback;fullscreenCallback=null;
  if(cb!=null)cb.onCustomViewHidden();
  getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  getWindow().getDecorView().setSystemUiVisibility(0);
  setRequestedOrientation(orientationBeforeVideo);
 }
 class MhDialogBuilder extends AlertDialog.Builder {
 MhDialogBuilder(){super(BrowserActivity.this,lightTheme?android.R.style.Theme_Material_Light_Dialog_Alert:android.R.style.Theme_Material_Dialog_Alert);}
 @Override public AlertDialog show(){AlertDialog d=super.show();styleDialog(d);return d;}
}
void styleDialog(AlertDialog d){
 if(d.getWindow()!=null)d.getWindow().setBackgroundDrawable(grad(0xff17324e,0xff10243c,18));
 if(d.getButton(-1)!=null)d.getButton(-1).setTextColor(themeText(0xff6ad9ff));
 if(d.getButton(-2)!=null)d.getButton(-2).setTextColor(themeText(0xffb6c9de));
 if(d.getButton(-3)!=null)d.getButton(-3).setTextColor(themeText(0xff6ad9ff));
}
AlertDialog.Builder themed(){return new MhDialogBuilder();}
 void dialog(String title,String message){themed().setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
 void settingRow(LinearLayout list,String label,String desc,Runnable act){
 LinearLayout card=row();card.setPadding(dp(14),dp(13),dp(13),dp(13));card.setBackground(grad(0xff17334f,0xff10253e,14));
 LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(9);list.addView(card,lp);
 LinearLayout content=column();card.addView(content,new LinearLayout.LayoutParams(0,-2,1));
 TextView name=text(label,16,WHITE);name.setTypeface(null,Typeface.BOLD);content.addView(name);
 TextView subtitle=text(desc,12,GRAY);subtitle.setPadding(0,dp(3),0,0);content.addView(subtitle);
 TextView arrow=text("›",24,0xff6fcfff);arrow.setGravity(Gravity.CENTER);card.addView(arrow,new LinearLayout.LayoutParams(dp(28),dp(40)));
 card.setOnClickListener(v->act.run());
}
 void switchRow(LinearLayout list,String label,String desc,String pref,boolean def){LinearLayout r=row();r.setPadding(20,12,12,12);r.setBackground(bg(PANEL));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=9;list.addView(r,lp);LinearLayout c=column();r.addView(c,new LinearLayout.LayoutParams(0,-2,1));c.addView(text(label,16,WHITE));c.addView(text(desc,12,GRAY));Switch s=new Switch(this);s.setChecked(prefs.getBoolean(pref,def));r.addView(s);s.setOnCheckedChangeListener((b,v)->{
  prefs.edit().putBoolean(pref,v).apply();
  for(Tab t:tabs){WebSettings ws=t.web.getSettings();
   if("javascript".equals(pref))ws.setJavaScriptEnabled(v);
   if("images".equals(pref))ws.setLoadsImagesAutomatically(v);
   if("zoom".equals(pref)){ws.setSupportZoom(v);ws.setBuiltInZoomControls(v);}
   if("popups".equals(pref)){ws.setJavaScriptCanOpenWindowsAutomatically(v);ws.setSupportMultipleWindows(true);}
   if("blockCookies".equals(pref))CookieManager.getInstance().setAcceptThirdPartyCookies(t.web,!v&&!t.incognito);
   if("adBlocker".equals(pref)){
    applyAdCosmetics(t.web);
    // A reload re-evaluates blocked network requests and document-start guards.
    if(t.web==active()&&t.web.getUrl()!=null&&t.web.getUrl().startsWith("http"))t.web.reload();
   }
  }
  if("location".equals(pref)&&v&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
   requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},201);
  }
 });}


 JSONArray readArray(String key){try{return new JSONArray(prefs.getString(key,"[]"));}catch(Exception e){return new JSONArray();}}
 void storeArray(String key,JSONArray a){prefs.edit().putString(key,a.toString()).apply();}
 JSONArray shortcuts(){
  String saved=prefs.getString("shortcuts",null);if(saved!=null){try{return new JSONArray(saved);}catch(Exception ignored){}}
  JSONArray a=new JSONArray();String[][] d={{"Google","https://www.google.com"},{"YouTube","https://www.youtube.com"},{"Amazon","https://www.amazon.com"},{"eBay","https://www.ebay.com"},{"AliExpress","https://www.aliexpress.com"},{"Facebook","https://www.facebook.com"},{"X","https://x.com"}};
  for(String[] item:d){JSONObject o=new JSONObject();try{o.put("name",item[0]);o.put("url",item[1]);a.put(o);}catch(Exception ignored){}}return a;
 }
 void editShortcuts(){
  JSONArray a=shortcuts();String[] names=new String[a.length()+1];
  for(int i=0;i<a.length();i++)names[i]=a.optJSONObject(i).optString("name","Shortcut");
  names[a.length()]="+ Add shortcut";
  themed().setTitle("Edit quick access").setItems(names,(d,n)->{
   if(n==a.length())shortcutForm(-1);else shortcutOptions(n);
  }).setNegativeButton("Done",null).show();
 }
 void shortcutOptions(int n){themed().setTitle(shortcuts().optJSONObject(n).optString("name"))
  .setItems(new String[]{"Edit shortcut","Remove shortcut"},(d,i)->{
   if(i==0)shortcutForm(n);else{JSONArray a=shortcuts(),b=new JSONArray();for(int k=0;k<a.length();k++)if(k!=n)b.put(a.opt(k));storeArray("shortcuts",b);display();}
  }).setNegativeButton("Cancel",null).show();}
 void shortcutForm(int position){
  JSONObject old=position<0?null:shortcuts().optJSONObject(position);
  LinearLayout c=column();c.setPadding(dp(22),dp(10),dp(22),dp(6));
  EditText name=new EditText(this),link=new EditText(this);
  name.setSingleLine(true);name.setHint("Shortcut name");name.setTextColor(themeText(WHITE));
  link.setSingleLine(true);link.setHint("https://example.com");link.setTextColor(themeText(WHITE));link.setInputType(17);
  if(old!=null){name.setText(old.optString("name"));link.setText(old.optString("url"));}
  c.addView(name);c.addView(link);
  LinearLayout preview=row();preview.setPadding(dp(8),dp(8),dp(8),dp(8));
  ImageView iconPreview=new ImageView(this);iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
  iconPreview.setPadding(dp(5),dp(5),dp(5),dp(5));iconPreview.setBackground(grad(WHITE,0xffeaf3ff,13));
  preview.addView(iconPreview,new LinearLayout.LayoutParams(dp(46),dp(46)));
  TextView previewText=text("Website logo preview",13,GRAY);previewText.setPadding(dp(12),0,0,0);
  preview.addView(previewText);c.addView(preview);
  if(old!=null)loadShortcutIcon(iconPreview,old.optString("url",""));
  link.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence text,int start,int count,int after){}
   public void onTextChanged(CharSequence text,int start,int before,int count){
    String typed=text.toString().trim();
    iconPreview.setImageDrawable(null);
    if(typed.startsWith("https://")||typed.startsWith("http://")){
     String host=Uri.parse(typed).getHost();
     if(host!=null&&host.contains("."))loadShortcutIcon(iconPreview,typed);
    }
   }
   public void afterTextChanged(android.text.Editable text){}
  });
  AlertDialog d=themed().setTitle(position<0?"Add shortcut":"Edit shortcut").setView(c).setNegativeButton("Cancel",null)
   .setPositiveButton("Save",null).create();
  d.setOnShowListener(v->{styleDialog(d);d.getButton(-1).setOnClickListener(w->{
   String nm=name.getText().toString().trim(),u=link.getText().toString().trim();
   if(nm.isEmpty()){name.setError("Enter a name");return;}
   if(!u.startsWith("https://")&&!u.startsWith("http://")){link.setError("Enter an HTTP or HTTPS URL");return;}
   Uri parsed=Uri.parse(u);if(parsed.getHost()==null){link.setError("Invalid URL");return;}
   try{JSONArray a=shortcuts();JSONObject o=new JSONObject();o.put("name",nm);o.put("url",u);
    if(position<0)a.put(o);else a.put(position,o);storeArray("shortcuts",a);d.dismiss();display();
   }catch(Exception e){link.setError("Could not save shortcut");}
  });});d.show();
 }
 void saveTabs(){
  if(privateOnly)return;
  JSONArray a=new JSONArray();int selectedNormal=0;
  for(int i=0;i<tabs.size();i++){
   Tab t=tabs.get(i);if(t.incognito)continue;
   if(i==index)selectedNormal=a.length();
   String u=t.web.getUrl();a.put(u==null?"about:blank":u);
  }
  prefs.edit().putString("tabs",a.toString()).putInt("selectedTab",selectedNormal).apply();
 }
 final ArrayList<String> deferredTabUrls=new ArrayList<>();
 void restoreTabs(){
  JSONArray a=prefs.getBoolean("restore",true)?readArray("tabs"):new JSONArray();
  if(a.length()==0){createTab(false);return;}
  for(int i=0;i<a.length()&&i<12;i++){
   createTab(false);
   deferredTabUrls.add(a.optString(i,"about:blank"));
  }
  index=Math.max(0,Math.min(tabs.size()-1,prefs.getInt("selectedTab",0)));display();
 }
 void loadDeferredTabs(){
  if(isFinishing()||isDestroyed())return;
  for(int i=0;i<deferredTabUrls.size()&&i<tabs.size();i++){
   String u=deferredTabUrls.get(i);
   if(u.startsWith("http"))tabs.get(i).web.loadUrl(u);
  }
  deferredTabUrls.clear();
 }

 void addHistory(String url,String title){
  if(url==null||!url.startsWith("http"))return;
  JSONArray old=readArray("history"),out=new JSONArray();
  JSONObject first=old.optJSONObject(0);
  if(first!=null&&url.equals(first.optString("url"))){
   try{first.put("title",title==null||title.trim().isEmpty()?url:title);first.put("time",System.currentTimeMillis());}catch(Exception ignored){}
   out.put(first);for(int i=1;i<old.length()&&out.length()<500;i++)out.put(old.opt(i));
  }else{
   JSONObject item=new JSONObject();try{item.put("url",url);item.put("title",title==null||title.isEmpty()?url:title);item.put("time",System.currentTimeMillis());}catch(Exception ignored){}
   out.put(item);for(int i=0;i<old.length()&&out.length()<500;i++)out.put(old.opt(i));
  }
  storeArray("history",out);
 }
 String itemUrl(JSONArray a,int i){
  JSONObject o=a.optJSONObject(i);return o==null?a.optString(i,""):o.optString("url","");
 }
 String itemTitle(JSONArray a,int i){
  JSONObject o=a.optJSONObject(i);return o==null?a.optString(i,""):o.optString("title",o.optString("url",""));
 }
 String historyGroup(long t){
  if(t<=0)return "Earlier (legacy entries)";
  Calendar now=Calendar.getInstance(),start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
  long today=start.getTimeInMillis();
  if(t>=today)return "Today";
  if(t>=today-86400000L)return "Yesterday";
  if(t>=today-7*86400000L)return "Previous 7 days";
  start.set(Calendar.DAY_OF_MONTH,1);if(t>=start.getTimeInMillis())return "This month";
  start.set(Calendar.DAY_OF_YEAR,1);if(t>=start.getTimeInMillis())return "This year";
  return "Older";
 }
 String friendlyUrl(String u){
  try{Uri parsed=Uri.parse(u);String host=parsed.getHost();return (host==null?u:host.replaceFirst("^www\\.",""));}
  catch(Exception e){return u;}
 }
 void clearNavigationStacks(){for(Tab tab:tabs)tab.web.clearHistory();}
 void history(){
  showingSettings=true;screen=6;head.removeAllViews();body.removeAllViews();sectionHeader("Browsing history",this::display);
  JSONArray entries=readArray("history");
  ScrollView sc=new ScrollView(this);body.addView(sc);
  LinearLayout list=column();list.setPadding(dp(15),dp(10),dp(15),dp(25));sc.addView(list);

  String[] periods={"Today","Previous Day","Week","Monthly","Yearly","All"};
  HorizontalScrollView filterScroll=new HorizontalScrollView(this);filterScroll.setHorizontalScrollBarEnabled(false);list.addView(filterScroll);
  LinearLayout filters=row();filters.setPadding(0,dp(3),0,dp(10));filterScroll.addView(filters);
  for(int i=0;i<periods.length;i++){final int n=i;
   TextView chip=action(periods[i],12,()->{historyFilter=n;history();});
   chip.setPadding(dp(13),0,dp(13),0);
   chip.setBackground(grad(historyFilter==i?0xff2477ac:0xff172e48,historyFilter==i?0xff1b567e:0xff10253e,15));
   LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-2,dp(39));clp.rightMargin=dp(7);filters.addView(chip,clp);
  }
  if(entries.length()==0){TextView empty=text("No browsing history yet",17,GRAY);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(55),dp(12),dp(40));list.addView(empty);return;}
  TextView sub=text("Select individual pages or whole periods to remove them. Private tabs are not recorded.",12,GRAY);
  sub.setPadding(dp(5),dp(6),dp(5),dp(16));list.addView(sub);
  ArrayList<Integer> selected=new ArrayList<>();HashMap<Integer,CheckBox> checks=new HashMap<>();
  LinkedHashMap<String,ArrayList<Integer>> groups=new LinkedHashMap<>();
  for(int i=0;i<entries.length();i++){
   JSONObject obj=entries.optJSONObject(i);long t=obj==null?0:obj.optLong("time",0);

   Calendar cal=Calendar.getInstance(),start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
   long today=start.getTimeInMillis();boolean include=true;
   if(historyFilter==0)include=t>=today;
   else if(historyFilter==1)include=t>=today-86400000L&&t<today;
   else if(historyFilter==2)include=t>=today-7*86400000L;
   else if(historyFilter==3){start.set(Calendar.DAY_OF_MONTH,1);include=t>=start.getTimeInMillis();}
   else if(historyFilter==4){start.set(Calendar.DAY_OF_YEAR,1);include=t>=start.getTimeInMillis();}
   if(!include)continue;
   String g=historyGroup(t);if(!groups.containsKey(g))groups.put(g,new ArrayList<>());groups.get(g).add(i);
  }
  if(groups.isEmpty()){TextView empty=text("No visits for this period",16,GRAY);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(45),0,dp(35));list.addView(empty);}
  for(Map.Entry<String,ArrayList<Integer>> group:groups.entrySet()){
   LinearLayout heading=row();heading.setPadding(dp(5),dp(14),dp(5),dp(10));list.addView(heading);
   TextView label=text(group.getKey(),17,0xff77d5ff);label.setTypeface(null,Typeface.BOLD);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
   TextView select=text("SELECT PERIOD",11,0xffa1e9ff);heading.addView(select);
   select.setOnClickListener(v->{
    boolean all=true;for(int n:group.getValue())if(!selected.contains(n))all=false;
    for(int n:group.getValue()){if(all)selected.remove(Integer.valueOf(n));else if(!selected.contains(n))selected.add(n);}
    for(int n:group.getValue())if(checks.containsKey(n))checks.get(n).setChecked(!all);
   });
   for(int n:group.getValue()){
    JSONObject obj=entries.optJSONObject(n);String url=itemUrl(entries,n),name=itemTitle(entries,n);
    LinearLayout card=row();card.setPadding(dp(12),dp(11),dp(12),dp(11));card.setBackground(grad(0xff152e4a,0xff0d2239,14));
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(7);list.addView(card,cp);
    CheckBox check=new CheckBox(this);checks.put(n,check);check.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff67d6ff));card.addView(check);
    check.setOnCheckedChangeListener((v,on)->{if(on){if(!selected.contains(n))selected.add(n);}else selected.remove(Integer.valueOf(n));});
    LinearLayout info=column();card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
    TextView title=text(name,14,WHITE);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(title);
    TextView detail=text(friendlyUrl(url),12,GRAY);detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(detail);
    if(obj!=null&&obj.optLong("time",0)>0){
     TextView time=text(new java.text.SimpleDateFormat("MMM d, h:mm a",Locale.getDefault()).format(new Date(obj.optLong("time"))),10,0xff81a7ca);info.addView(time);
    }
    info.setOnClickListener(v->{display();navigate(url);});
   }
  }
  LinearLayout actions=row();actions.setPadding(0,dp(18),0,0);list.addView(actions);
  TextView remove=action("Delete selected",15,()->{
   if(selected.isEmpty()){Toast.makeText(this,"Select history entries first",Toast.LENGTH_SHORT).show();return;}
   themed().setTitle("Delete selected history?").setMessage(selected.size()+" visit(s) will be removed.")
    .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,n)->{
     JSONArray kept=new JSONArray();for(int k=0;k<entries.length();k++)if(!selected.contains(k))kept.put(entries.opt(k));
     storeArray("history",kept);clearNavigationStacks();history();
    }).show();
  });remove.setBackground(grad(0xff1d547a,0xff163a59,12));actions.addView(remove,new LinearLayout.LayoutParams(0,dp(48),1));
  TextView all=action("Clear all",15,()->themed().setTitle("Clear all browsing history?")
   .setMessage("This also clears Back and Forward history in every open tab.")
   .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,n)->{
    storeArray("history",new JSONArray());clearNavigationStacks();history();
   }).show());
  LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(48),1);ap.leftMargin=dp(10);all.setBackground(grad(0xff5a2b43,0xff472136,12));actions.addView(all,ap);
 }
 void openGoogleInTab(String url){
  createTab(privateOnly);
  navigate(url);
 }
 void googleAccount(){
  // Go straight to Google's own sign-in page inside the active browser profile.
  // Android WebView is an embedded browser: Google may independently refuse authentication.
  openGoogleInTab("https://accounts.google.com/");
 }
 void bookmarks(){
  showingSettings=true;screen=7;head.removeAllViews();body.removeAllViews();sectionHeader("Bookmarks",this::display);
  ScrollView sc=new ScrollView(this);body.addView(sc);
  LinearLayout list=column();list.setPadding(dp(16),dp(12),dp(16),dp(20));sc.addView(list);
  TextView add=action("☆  Save current page",16,()->{
   String u=active().getUrl();if(u==null||!u.startsWith("http")){dialog("Bookmarks","Open a website first.");return;}
   JSONArray a=readArray("bookmarks");for(int i=0;i<a.length();i++)if(u.equals(itemUrl(a,i))){Toast.makeText(this,"Already bookmarked",Toast.LENGTH_SHORT).show();return;}
   JSONObject obj=new JSONObject();try{obj.put("url",u);obj.put("title",active().getTitle()==null?friendlyUrl(u):active().getTitle());}catch(Exception ignored){}
   a.put(obj);storeArray("bookmarks",a);bookmarks();
  });
  add.setBackground(grad(0xff23557c,0xff153653,14));list.addView(add,new LinearLayout.LayoutParams(-1,dp(52)));
  JSONArray a=readArray("bookmarks");
  if(a.length()==0){TextView empty=text("Your saved websites will appear here.",14,GRAY);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(35),0,dp(30));list.addView(empty);}
  for(int i=0;i<a.length();i++){
   final int n=i;String u=itemUrl(a,i);LinearLayout row=row();row.setPadding(dp(13),dp(12),dp(9),dp(12));
   row.setBackground(grad(0xff152d49,0xff0d2239,14));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(9);list.addView(row,rp);
   LinearLayout info=column();row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
   TextView name=text(itemTitle(a,i),15,WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(name);
   info.addView(text(friendlyUrl(u),12,GRAY));info.setOnClickListener(v->{display();navigate(u);});
   TextView del=action("×",24,()->themed().setTitle("Remove bookmark?").setMessage(itemTitle(a,n))
    .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,k)->{
     JSONArray b=new JSONArray();for(int x=0;x<a.length();x++)if(x!=n)b.put(a.opt(x));storeArray("bookmarks",b);bookmarks();
    }).show());row.addView(del,new LinearLayout.LayoutParams(dp(45),dp(46)));
  }
 }
 void network(){
  String type="Not connected";
  try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
   android.net.Network net=cm.getActiveNetwork();NetworkCapabilities caps=cm.getNetworkCapabilities(net);
   if(caps!=null)type=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Mobile data":"Other network";
  }catch(Exception ignored){}
  String local="Unavailable";try{for(java.util.Enumeration<java.net.NetworkInterface> nets=java.net.NetworkInterface.getNetworkInterfaces();nets.hasMoreElements();){java.net.NetworkInterface net=nets.nextElement();if(!net.isUp()||net.isLoopback())continue;for(java.util.Enumeration<java.net.InetAddress> addresses=net.getInetAddresses();addresses.hasMoreElements();){java.net.InetAddress ip=addresses.nextElement();if(ip instanceof java.net.Inet4Address&&!ip.isLoopbackAddress()){local=ip.getHostAddress();break;}}if(!local.equals("Unavailable"))break;}}catch(Exception ignored){}
  String info="Connection: "+type+"\nLocal IPv4: "+local+"\nTime zone: "+java.util.TimeZone.getDefault().getID()+"\nCountry (device locale): "+Locale.getDefault().getDisplayCountry()+"\nRegion (device locale): "+Locale.getDefault().toString();
  themed().setTitle("Network diagnostics").setMessage(info+"\n\nPublic IP and approximate region require a separate online lookup.")
   .setNegativeButton("Close",null).setPositiveButton("Check public IP",(d,n)->lookupPublicIp()).show();
 }

 // Detect media on dynamically rendered pages, SPA routes and supported embeds.
 // A media element's presence triggers the button; the download engine separately
 // determines whether the site provides an accessible, non-DRM source.
 String earlyPageGuardScript(){
  return "(function(){\n if(window.__mhEarlyGuard)return;\n window.__mhEarlyGuard=true;\n window.MHAdBlockEnabled=(window.MHMedia&&window.MHMedia.adBlockEnabled)?window.MHMedia.adBlockEnabled():true;\n var realOpen=window.open;\n window.open=function(){\n   if(window.MHMedia&&window.MHMedia.adBlockEnabled&&window.MHMedia.adBlockEnabled()&&(!window.MHMedia.popupsAllowed||!window.MHMedia.popupsAllowed()))return null;\n   return realOpen.apply(window,arguments);\n };\n function send(u){\n   try{\n     if(!u||typeof u!=='string'||u.length>4096||!/^https?:\\/\\//i.test(u))return;\n     var x=new URL(u,location.href),p=x.pathname.toLowerCase();\n     if(!/\\.(mp4|m4v|webm|mov|mp3|m4a|aac|wav|ogg|opus|flac|m3u8)$/.test(p)&&\n        !(/(\\/master|\\/playlist|\\/index|\\/hls|\\/manifest)$/.test(p)&&/m3u8|hls|format=mp4/i.test(x.search)))return;\n     if(window.MHMedia&&window.MHMedia.candidateWithRef)\n       window.MHMedia.candidateWithRef(x.href,location.href);\n   }catch(e){}\n }\n var lastVideoSignature='';\n function reportVideo(m){\n   try{\n     if(!m||m.tagName!=='VIDEO'||!m.videoWidth||!m.videoHeight||!window.MHMedia||!window.MHMedia.mediaInfo)return;\n     var tag=Array.from(document.querySelectorAll('meta[property],meta[name]')).find(function(e){var k=e.getAttribute('property')||e.getAttribute('name');return k==='og:title'||k==='twitter:title';});\n     var title=(tag&&tag.content)||document.title||'';\n     var signature=m.videoWidth+'x'+m.videoHeight+'|'+title;\n     if(signature===lastVideoSignature)return;\n     lastVideoSignature=signature;\n     MHMedia.mediaInfo(m.videoWidth,m.videoHeight,String(title).slice(0,180));\n   }catch(e){}\n }\n function scan(){\n   try{\n     document.querySelectorAll('video,audio,video source,audio source').forEach(function(m){send(m.currentSrc||m.src);});\n     var videos=Array.from(document.querySelectorAll('video'));\n     var current=videos.find(function(m){return !m.paused&&m.videoWidth&&m.videoHeight;})||videos.find(function(m){return m.videoWidth&&m.videoHeight;});\n     if(current)reportVideo(current);\n   }catch(e){}\n }\n document.addEventListener('loadedmetadata',function(e){reportVideo(e.target);},true);\n try{\n   new PerformanceObserver(function(items){\n     items.getEntries().forEach(function(item){send(item.name);});\n   }).observe({entryTypes:['resource']});\n }catch(e){}\n document.addEventListener('pointerdown',function(e){\n   try{\n     var el=e.target;\n     var embeddedPlayer=(window.top!==window)&&(/\\/(embed|player|watch|video|stream)\\b/i.test(location.pathname)||!!document.querySelector(\'video,audio,.jwplayer,.plyr,.video-js,.dplayer\'));\n     if(embeddedPlayer||(el&&el.closest&&el.closest('video,audio,.jwplayer,.plyr,.video-js,.vjs-control-bar,.jw-video,.jw-display,.dplayer,.art-video-player,[class*=\"video-player\"],[id*=\"video-player\"]')))\n       if(window.MHMedia&&window.MHMedia.playerTap)window.MHMedia.playerTap();\n   }catch(e){}\n },true);\n document.addEventListener('play',function(e){\n   if(e.target&&/^(VIDEO|AUDIO)$/.test(e.target.tagName)){\n     lastVideoSignature='';reportVideo(e.target);scan();\n     if(window.MHMedia&&window.MHMedia.playing)window.MHMedia.playing();\n   }\n },true);\n document.addEventListener('DOMContentLoaded',scan);\n setInterval(scan,1800);\n})();"+"document.addEventListener(\'DOMContentLoaded\',function(){"+AdBlockRules.cosmeticScript()+"});";
 }
 void applyAdCosmetics(WebView w){
  if(w==null||w.getUrl()==null||!w.getUrl().startsWith("http"))return;
  w.evaluateJavascript("window.MHAdBlockEnabled="+(prefs.getBoolean("adBlocker",true)?"true":"false")+";",null);
  w.evaluateJavascript(AdBlockRules.cosmeticScript(),null);
 }
 String mediaObserverScript(){
  return "(function(){"+
   "if(!document.documentElement||!window.MHMedia)return;"+
   "if(window.mhMediaInit){if(window.mhMediaScan)window.mhMediaScan();return;}"+
   "window.mhMediaInit=true;var found=false,lastHref=location.href;"+
   "function scan(){try{"+
   "if(location.href!==lastHref){lastHref=location.href;found=false;MHMedia.pageChanged();}"+
   "var els=document.querySelectorAll('video,audio');"+
   "var embeds=document.querySelectorAll('iframe[src],embed[src],object[data]');"+
   "var hasEmbed=false;embeds.forEach(function(el){var u=el.getAttribute('src')||el.getAttribute('data')||'';"+
   "if(/(youtube\\.com\\/embed|youtu\\.be\\/|player\\.vimeo\\.com|dailymotion\\.com\\/embed|facebook\\.com\\/plugins\\/video|instagram\\.com\\/reel|tiktok\\.com\\/embed|soundcloud\\.com\\/player|streamable\\.com\\/e\\/|\\/embed\\/|\\/player\\/)/i.test(u))hasEmbed=true;});"+
   "var meta=Array.from(document.querySelectorAll('meta[property],meta[name]')).some(function(m){return /^(og:video|og:audio|twitter:player)(:|$)/.test(m.getAttribute('property')||m.getAttribute('name')||'');});"+
   "var linked=Array.from(document.querySelectorAll('a[href]')).some(function(a){return /\\.(mp4|webm|mp3|m4a|ogg|wav)(\\?|$)/i.test(a.href||'');});"+
   "if((els.length||hasEmbed||meta||linked)&&!found){found=true;MHMedia.found();}"+
   "els.forEach(function(el){if(el.dataset.mhTracked)return;el.dataset.mhTracked='1';"+
   "el.addEventListener('play',function(){MHMedia.playing();});"+
   "el.addEventListener('playing',function(){MHMedia.playing();});"+
   "el.addEventListener('pause',function(){MHMedia.stopped();});});"+
   "}catch(e){}}"+
   "window.mhMediaScan=scan;scan();"+
   "var scanScheduled=false;function queueScan(){if(scanScheduled)return;scanScheduled=true;setTimeout(function(){scanScheduled=false;scan();},220);}"+
   "new MutationObserver(queueScan).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','poster']});"+
   "document.addEventListener('play',function(e){if(e.target&&/^(VIDEO|AUDIO)$/.test(e.target.tagName))MHMedia.playing();},true);"+
   "window.addEventListener('popstate',scan);"+
   "setInterval(scan,2200);"+
   "})()";
 }
 boolean isNetworkMediaRequest(WebResourceRequest request){
  if(request==null||request.isForMainFrame())return false;
  if(isNetworkMediaUrl(request.getUrl()))return true;
  java.util.Map<String,String> headers=request.getRequestHeaders();
  if(headers==null)return false;
  String accept=headers.get("Accept");
  if(accept!=null&&(accept.contains("video/")||accept.contains("audio/")||accept.contains("application/vnd.apple.mpegurl")))return true;
  String dest=headers.get("Sec-Fetch-Dest");
  if("video".equalsIgnoreCase(dest)||"audio".equalsIgnoreCase(dest))return true;
  String range=headers.get("Range");
  String path=request.getUrl()==null?null:request.getUrl().getPath();
  return range!=null&&path!=null&&path.matches("(?i).*/(video|audio|stream|media|file|play|hls)/.*");
 }
 boolean isNetworkMediaUrl(Uri u){
  if(u==null)return false;
  String scheme=u.getScheme();if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))return false;
  String path=u.getPath();if(path==null)return false;
  path=path.toLowerCase(Locale.ROOT);
  if(path.matches(".*\\.(mp4|m4v|webm|mov|mp3|m4a|aac|wav|ogg|opus|flac|m3u8)$"))return true;
  String raw=u.toString().toLowerCase(Locale.ROOT);
  if(raw.contains(".m3u8?")||raw.contains(".m3u8&")||raw.contains("format=m3u8")||raw.contains("type=m3u8"))return true;
  // Common signed player URLs omit file extensions.
  String query=u.getEncodedQuery();if(query==null)query="";
  query=query.toLowerCase(Locale.ROOT);
  return (path.endsWith("/master")||path.endsWith("/playlist")||path.endsWith("/index")||
    path.endsWith("/hls")||path.endsWith("/manifest"))
    &&(query.contains("m3u8")||query.contains("hls")||query.contains("format=mp4"));
 }
 String mediaProbeScript(){
  return "(function(){if(!window.MHMedia||!window.MHMedia.candidate)return;"+
   "var seen=window.mhCandidateSeen||(window.mhCandidateSeen={});"+
   "function send(u){try{if(!u||typeof u!=='string'||u.length>4096||seen[u]||!/^https?:\\/\\//i.test(u))return;"+
   "var x=new URL(u,location.href),p=x.pathname.toLowerCase();"+
   "if(!/\\.(mp4|m4v|webm|mov|mp3|m4a|aac|wav|ogg|opus|flac|m3u8)$/.test(p))return;"+
   "seen[u]=1;MHMedia.candidate(x.href);}catch(e){}}"+
   "document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){send(e.currentSrc||e.src);});"+
   "try{performance.getEntriesByType('resource').slice(-200).forEach(function(e){send(e.name);});}catch(e){}"+
   "if(!window.mhProbeTimer){window.mhProbeTimer=setInterval(function(){"+
   "document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){send(e.currentSrc||e.src);});"+
   "try{performance.getEntriesByType('resource').slice(-100).forEach(function(e){send(e.name);});}catch(e){}"+
   "},1800);}"+
   "})()";
 }
 void mergeDirectMedia(String page,JSONArray discovered){
  if(page==null||discovered==null)return;
  JSONArray merged=directMediaCache.get(page);
  if(merged==null){merged=new JSONArray();directMediaCache.put(page,merged);}
  Set<String> seen=new HashSet<>();
  for(int i=0;i<merged.length();i++){
   JSONObject obj=merged.optJSONObject(i);if(obj!=null)seen.add(obj.optString("url"));
  }
  for(int i=0;i<discovered.length()&&merged.length()<16;i++){
   JSONObject obj=discovered.optJSONObject(i);
   if(obj!=null&&!seen.contains(obj.optString("url"))){
    merged.put(obj);seen.add(obj.optString("url"));
   }
  }
 }
 void noteNetworkMedia(WebView v,Uri media){noteNetworkMedia(v,media,null);}
 void noteNetworkMedia(WebView v,Uri media,String referer){
  if(tabs.isEmpty()||v!=active()||media==null)return;
  String page=v.getUrl(),link=media.toString();
  if(page==null||!page.startsWith("http"))return;
  Set<String> seen=networkMediaSeen.get(v);
  if(seen==null){seen=new HashSet<>();networkMediaSeen.put(v,seen);}
  if(seen.contains(link))return;
  if(seen.size()>=32)return;
  seen.add(link);
  mediaAvailable=true;updateMediaOverlay();
  String path=media.getPath()==null?"":media.getPath().toLowerCase(Locale.ROOT);
  if(path.endsWith(".mpd"))return; // DASH may be DRM protected: do not offer raw manifest downloads
  String kind=(path.endsWith(".m3u8")||link.toLowerCase(Locale.ROOT).contains("m3u8"))?"HLS":path.matches(".*\\.(mp3|m4a|aac|wav|ogg|opus|flac)$")?"Audio":"Video";
  JSONArray direct=directMediaCache.get(page);
  if(direct==null){direct=new JSONArray();directMediaCache.put(page,direct);}
  if(direct.length()>=12)return;
  for(int i=0;i<direct.length();i++)if(link.equals(direct.optJSONObject(i).optString("url")))return;
  JSONObject o=new JSONObject();
  try{o.put("url",link);o.put("kind",kind);o.put("type",kind.equals("Audio")?"audio/*":kind.equals("HLS")?"application/vnd.apple.mpegurl":"video/*");o.put("label",kind.equals("HLS")?"Streaming playlist":"Detected stream");if(referer!=null&&referer.startsWith("http"))o.put("referer",referer);direct.put(o);}
  catch(Exception ignored){}
  if(activeMediaDialog!=null&&activeMediaDialog.isShowing()&&page.equals(activeMediaPage)&&activeDirectMediaOptions!=null)
   showDirectChoices(activeDirectMediaOptions,direct,activeMediaDialog);
 }
 String embeddedMediaScript(){
  return "(function(){var out=[],seen={};"+
   "function add(u,label){try{u=new URL(u,location.href).href;if(!/^https?:\\/\\//i.test(u)||seen[u])return;"+
   "seen[u]=1;out.push({url:u,kind:'Video',label:label||'Embedded player'});}catch(e){}}"+
   "document.querySelectorAll('iframe[src]').forEach(function(f){var u=f.src||'';"+
   "if(!u||!/^https?:\\/\\//i.test(u))return;"+
   "var player=/\\/(embed|player|watch|video|stream)\\b/i.test(u)||"+
   "f.hasAttribute('allowfullscreen')||/autoplay|encrypted-media/.test(f.getAttribute('allow')||'');"+
   "if(player)add(u,'Embedded player');});"+
   "document.querySelectorAll('meta[property],meta[name]').forEach(function(m){"+
   "var key=m.getAttribute('property')||m.getAttribute('name')||'';"+
   "if(/^(og:video(:url)?|twitter:player)$/.test(key))add(m.content,'Video source');});"+
   "return JSON.stringify(out);})()";
 }
 String mediaSourceScript(){
  return "(function(){var out=[],seen={};"+
   "document.querySelectorAll('video,audio').forEach(function(m){"+
   "var sources=[m].concat(Array.from(m.querySelectorAll('source')));"+
   "sources.forEach(function(x){var u=x===m?(m.currentSrc||m.src):x.src;"+
   "if(!u||!/^https?:\\/\\//i.test(u)||seen[u])return;"+
   "var typ=(x.getAttribute('type')||m.getAttribute('type')||'').toLowerCase();"+
   "var path;try{path=new URL(u).pathname.toLowerCase()}catch(e){return}"+
   "var audio=m.tagName.toLowerCase()==='audio'||typ.indexOf('audio/')===0||/\\.(mp3|m4a|aac|wav|ogg|opus|flac)$/.test(path);"+
   "var video=typ.indexOf('video/')===0||/\\.(mp4|webm|mov|m4v|3gp)$/.test(path);"+
   "var hls=/\\.m3u8$/.test(path)||/mpegurl/.test(typ);"+
   "if(!audio&&!video&&!hls)return;"+
   "seen[u]=1;var q=x.getAttribute('label')||x.getAttribute('res')||x.getAttribute('size')||'';"+
   "if(!q&&m.videoHeight&&m.tagName.toLowerCase()==='video')q=m.videoHeight+'p';"+
   "out.push({url:u,type:typ,kind:hls?'HLS':audio?'Audio':'Video',label:q});"+
   "});});"+
   "document.querySelectorAll('a[href]').forEach(function(a){var u=a.href;if(!u||seen[u]||!/^https?:\\/\\//i.test(u))return;"+
   "var path;try{path=new URL(u).pathname.toLowerCase()}catch(e){return}"+
   "if(!/\\.(mp4|webm|mov|m4v|mp3|m4a|aac|wav|ogg|opus|flac)$/.test(path))return;"+
   "seen[u]=1;var audio=/\\.(mp3|m4a|aac|wav|ogg|opus|flac)$/.test(path);"+
   "out.push({url:u,type:audio?'audio/*':'video/*',kind:audio?'Audio':'Video',label:'Direct file'});"+
   "});return JSON.stringify(out);})()";
 }
 JSONArray parseMediaSources(String raw){
  JSONArray valid=new JSONArray();
  try{
   String decoded=new JSONArray("["+raw+"]").optString(0,"[]");
   JSONArray found=new JSONArray(decoded);
   for(int i=0;i<found.length();i++){
    JSONObject o=found.optJSONObject(i);if(o==null)continue;
    String link=o.optString("url");
    if(!link.startsWith("https://")&&!link.startsWith("http://"))continue;
    if(link.matches("(?i).*\\.mpd(\\?.*)?$"))continue;
    if(!"Video".equals(o.optString("kind"))&&!"Audio".equals(o.optString("kind"))&&!"HLS".equals(o.optString("kind")))continue;
    valid.put(o);
   }
  }catch(Exception ignored){}
  return valid;
 }
 void refreshMediaAvailability(){
  if(tabs.isEmpty())return;
  WebView current=active();
  current.evaluateJavascript(mediaSourceScript(),raw->{
   if(tabs.isEmpty()||current!=active())return;
   mediaAvailable=mediaAvailable||parseMediaSources(raw).length()>0;
   updateMediaOverlay();
  });
 }
 String pageMovieTitleScript(){
  return "(function(){try{"+
   "var title='';"+
   "var headings=document.querySelectorAll('main h1,article h1,h1,[class*=movie-title],[class*=film-title],[class*=video-title]');"+
   "for(var i=0;i<headings.length&&i<12;i++){var h=headings[i];var t=(h.innerText||h.textContent||'').trim();"+
   "if(t.length>1&&t.length<150&&h.getClientRects().length&& !/^(movies|series|home|search|watch|login|menu)$/i.test(t)){title=t;break;}}"+
   "if(!title){var m=document.querySelector('meta[property=\\\"og:title\\\"]');if(m)title=m.content||'';}"+
   "if(!title)title=document.title||'';"+
   "return title.slice(0,180);}catch(e){return document.title||'';}})()";
 }
 void capturePageMovieTitle(WebView web,String page,Runnable ready){
  if(web==null||page==null||!page.startsWith("http")){if(ready!=null)ready.run();return;}
  web.evaluateJavascript(pageMovieTitleScript(),raw->{
   if(page.equals(web.getUrl())){
    try{
     String title=new JSONArray("["+raw+"]").optString(0,"").trim();
     if(!title.isEmpty()&&!title.matches("(?i)^(Video|Watch|Home|Player|Movies|Series|SomiFlix|YouTube|Facebook|Instagram)$"))
      pageMovieTitleCache.put(page,title);
    }catch(Exception ignored){}
   }
   if(ready!=null)ready.run();
  });
 }
 void mediaDownloads(){
  if(tabs.isEmpty())return;
  WebView web=active();String initialUrl=web.getUrl();
  if(initialUrl==null||!initialUrl.startsWith("http"))return;
  // Resolve the visible movie heading first, not the iframe's generic player title.
  capturePageMovieTitle(web,initialUrl,()->{
   if(!isFinishing()&&!isDestroyed()&&web==active())showMediaDownloadPanel(initialUrl,web);
  });
 }
 void showMediaDownloadPanel(String initialUrl,WebView web){
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
  scroll.setVerticalScrollBarEnabled(true);
  LinearLayout layout=column();layout.setPadding(dp(16),dp(8),dp(16),dp(14));scroll.addView(layout);
  LinearLayout heading=row();heading.setPadding(dp(8),dp(5),dp(8),dp(9));layout.addView(heading);
  TextView symbol=text("↓",30,0xff74d8ff);symbol.setGravity(Gravity.CENTER);
  symbol.setBackground(grad(0xff235a81,0xff123755,14));heading.addView(symbol,new LinearLayout.LayoutParams(dp(54),dp(54)));
  LinearLayout words=column();words.setPadding(dp(12),0,0,0);heading.addView(words,new LinearLayout.LayoutParams(0,-2,1));
  TextView title=text("Media Downloader",20,WHITE);title.setTypeface(null,Typeface.BOLD);words.addView(title);
  words.addView(text("Video quality  •  Audio formats",12,GRAY));
  TextView movieName=text("Source title: "+mediaTitleForDownload(web.getUrl()),13,WHITE);
  movieName.setTypeface(null,Typeface.BOLD);
  movieName.setPadding(dp(12),dp(6),dp(12),dp(8));
  layout.addView(movieName);
  TextView state=text("Choose a format below to download.",13,0xffa7c7e4);
  state.setPadding(dp(12),dp(12),dp(12),dp(12));
  state.setBackground(grad(0xff1b3a57,0xff112943,12)); // Internal detection status, not user-facing.
  LinearLayout options=column();LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,-2);op.topMargin=dp(12);layout.addView(options,op);
  LinearLayout directOptions=column();options.addView(directOptions);
  LinearLayout quickOptions=column();options.addView(quickOptions);
  state.setText("Choose a quality immediately. Exact available resolutions update automatically.");
  // Only confirmed extractor formats and detected streams are selectable.
  AlertDialog dialog=themed().setView(scroll).setNegativeButton("Close",null).create();
  settingRow(layout,"↓  Downloads • Pause / Resume","View download progress and pause or resume active transfers",()->{
   if(dialog.isShowing())dialog.dismiss();
   if(fullscreenVideo!=null)hideVideo();
   downloads();
  });
  dialog.show();styleDialog(dialog);
  activeMediaDialog=dialog;activeDirectMediaOptions=directOptions;activeMediaPage=web.getUrl();
  dialog.setOnDismissListener(d->{
   if(activeMediaDialog==dialog){activeMediaDialog=null;activeDirectMediaOptions=null;activeMediaPage=null;}
  });
  if(dialog.getWindow()!=null){android.util.DisplayMetrics m=getResources().getDisplayMetrics();
   dialog.getWindow().setLayout((int)(m.widthPixels*0.92f),WindowManager.LayoutParams.WRAP_CONTENT);
   scroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1,-2));
   scroll.setMinimumHeight(0);
  }
  final boolean[] verifiedFormats={false};
  final boolean[] embeddedSearching={false};
  final boolean[] embeddedOptionsAdded={false};
  final String[] lastFormatError={""};
  boolean knownMediaPage=isMediaPage(initialUrl)||isNetworkMediaUrl(Uri.parse(initialUrl));
  if(mediaFormatCache.containsKey(initialUrl)){
   verifiedFormats[0]=true;
   state.setText("Available video and audio formats detected.");
   addFormatChoices(quickOptions,initialUrl,mediaFormatCache.get(initialUrl),dialog);
  }else state.setText("Checking playable video and audio formats. Available options appear below while detection runs…");
  if(!verifiedFormats[0]){
   // No technical detection text in the user-facing panel.
  }
  JSONArray readyDirect=directMediaCache.get(initialUrl);
  if(readyDirect!=null)showDirectChoices(directOptions,readyDirect,dialog);
  web.evaluateJavascript(embeddedMediaScript(),raw->{
   if(!dialog.isShowing())return;
   JSONArray candidates=parseMediaSources(raw);
   if(candidates.length()==0)return;
   TextView sub=text("EMBEDDED VIDEO PLAYERS",12,0xff74d8ff);
   sub.setPadding(dp(8),dp(12),0,dp(8));options.addView(sub);
   for(int i=0;i<candidates.length()&&i<6;i++){
    JSONObject c=candidates.optJSONObject(i);if(c==null)continue;
    String link=c.optString("url"),label=c.optString("label","Player");
    if(link.isEmpty()||link.equals(initialUrl))continue;
    settingRow(options,"▶  "+label,"Open this player's video and audio format choices",()->{
     dialog.dismiss();showMediaDownloadPanel(link,web);
    });
    if(i<6&&!verifiedFormats[0]){
     if(!embeddedOptionsAdded[0]){
      embeddedOptionsAdded[0]=true;
      quickOptions.removeAllViews();
      // Omit temporary detection notices.
      // Never offer an unsupported generic page as a confirmed download.
     }
     embeddedSearching[0]=true;
     inspectFormatsCached(link,new MediaFormatListener(){
      @Override public void ready(String mediaTitle,java.util.List<MediaDownloadEngine.Choice> choices){
       if(!dialog.isShowing()||verifiedFormats[0])return;
       verifiedFormats[0]=true;
       mediaTitleCache.put(link,mediaTitle);
       state.setText("Detected embedded video: "+mediaTitle+" • Choose a resolution or audio format.");
       quickOptions.removeAllViews();
       addFormatChoices(quickOptions,link,choices,dialog);
      }
      @Override public void failed(String error){
       if(!dialog.isShowing()||verifiedFormats[0])return;
       lastFormatError[0]=error;
       state.setText("Embedded player detected. Could not verify its formats yet. Try playing the video, then tap the player or a detected stream.");
      }
     });
    }
   }
  });
  // Start lookup immediately using the current permalink; DOM scanning is independent.
  // This removes two sequential WebView JavaScript round trips from the critical path.
  inspectFormatsCached(initialUrl,new MediaFormatListener(){
   @Override public void ready(String mediaTitle,java.util.List<MediaDownloadEngine.Choice> choices){
    if(!dialog.isShowing())return;
    if(verifiedFormats[0])return;
    verifiedFormats[0]=true;
    state.setText("Available video and audio formats for: "+mediaTitle);
    quickOptions.removeAllViews();
    addFormatChoices(quickOptions,initialUrl,choices,dialog);
   }
   @Override public void failed(String error){
    if(!dialog.isShowing())return;
    if(verifiedFormats[0])return;
    lastFormatError[0]=error;
    state.setText(embeddedSearching[0]?"Checking the embedded player's formats. Available quality options will appear if the host exposes them.":"Page inspection unavailable. Play the video to capture its stream, or retry once the player loads.");
    // Do not show retry/diagnostic rows in the media choice list.
   }
  });
  // Direct media links, if any, appear without delaying the network format lookup.
  web.evaluateJavascript(mediaSourceScript(),sourceRaw->{
   if(!dialog.isShowing())return;
   JSONArray direct=parseMediaSources(sourceRaw);
   if(direct.length()>0){
    mergeDirectMedia(initialUrl,direct);
    showDirectChoices(directOptions,directMediaCache.get(initialUrl),dialog);
    if(!verifiedFormats[0]){
     for(int i=0;i<direct.length();i++){
      JSONObject media=direct.optJSONObject(i);
      if(media==null||!"HLS".equals(media.optString("kind")))continue;
      String playlist=media.optString("url");
      if(playlist.isEmpty())continue;
      inspectFormatsCached(playlist,new MediaFormatListener(){
       @Override public void ready(String mediaTitle,java.util.List<MediaDownloadEngine.Choice> choices){
        if(!dialog.isShowing()||verifiedFormats[0])return;
        verifiedFormats[0]=true;
        state.setText("Detected stream: "+mediaTitle+" • Select an available resolution or audio format.");
        quickOptions.removeAllViews();
        addFormatChoices(quickOptions,playlist,choices,dialog);
       }
       @Override public void failed(String error){
        if(!dialog.isShowing()||verifiedFormats[0])return;
        state.setText("Direct stream detected. Use the direct source below; individual resolutions were not exposed by this player.");
       }
      });
      break;
     }
    }
   }
  });
 }
 String mediaTitleForDownload(String pageUrl){
  String title="";
  // The parent movie page heading takes precedence over embedded-player metadata.
  String parentPage=tabs.isEmpty()?null:active().getUrl();
  if(parentPage!=null)title=pageMovieTitleCache.getOrDefault(parentPage,"");
  if(title.isEmpty()&&pageUrl!=null)title=pageMovieTitleCache.getOrDefault(pageUrl,"");
  if(title.isEmpty()&&pageUrl!=null&&pageUrl.equals(detectedMediaPage))title=detectedMediaTitle;
  if(title.matches("(?i)^(Video|Watch|Home|Player|Embedded Player|YouTube|Facebook|Instagram|TikTok)$"))title="";
  if(title.isEmpty()&&pageUrl!=null)title=mediaTitleCache.getOrDefault(pageUrl,"");
  if(title.matches("(?i)^(Available formats|Video|Watch|Home|Player)$"))title="";
  if(title.isEmpty()&&!tabs.isEmpty())title=active().getTitle();
  if(title==null)return "";
  title=title.replaceFirst("(?i)\\s*[-|•]\\s*(YouTube|Facebook|Instagram|TikTok|Vimeo)\\s*$","").trim();
  if(title.matches("(?i)^(YouTube|Facebook|Instagram|TikTok|Video|Watch|Home|Available formats|Media|Player|MH Browser)$"))return "";
  title=title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();
  if(title.length()>140)title=title.substring(0,140).trim();
  return title.replaceAll("[. ]+$","");
 }
 String mediaFilename(String link,String mime){
  String fallback=URLUtil.guessFileName(link,null,mime);
  String title=mediaTitleForDownload(tabs.isEmpty()?null:active().getUrl());
  if(title.isEmpty())return fallback;
  int dot=fallback.lastIndexOf('.');
  String ext=dot>=0?fallback.substring(dot+1).toLowerCase(Locale.ROOT):"";
  if(!ext.matches("mp4|m4v|webm|mov|mkv|mp3|m4a|aac|wav|ogg|opus|flac|3gp")){
   String m=mime==null?"":mime.toLowerCase(Locale.ROOT);
   ext=m.contains("audio/mpeg")?"mp3":m.contains("audio/mp4")?"m4a":m.contains("webm")?"webm":m.contains("ogg")?"ogg":m.startsWith("audio/")?"m4a":"mp4";
  }
  return title+"."+ext;
 }
 void requestDownloadNotifications(){
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
   requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},302);
 }
 void showDirectChoices(LinearLayout container,JSONArray direct,AlertDialog dialog){
  container.removeAllViews();
  hlsRequests.remove(dialog);hlsRendered.remove(dialog);hlsUnknown.remove(dialog);
  if(direct==null||direct.length()==0)return;
  LinearLayout videos=column();container.addView(videos);
  LinearLayout audios=column();container.addView(audios);
  for(int i=0;i<direct.length();i++){
   JSONObject source=direct.optJSONObject(i);if(source==null)continue;
   String link=source.optString("url"),mime=source.optString("type");
   String kind=source.optString("kind","Video"),label=source.optString("label");
   if(link.isEmpty())continue;
   if("HLS".equals(kind)){
    addHlsResolutions(videos,audios,link,source.optString("referer",""),dialog);
    continue;
   }
   LinearLayout destination=kind.toLowerCase(Locale.ROOT).contains("audio")?audios:videos;
   Set<String> rendered=hlsRendered.computeIfAbsent(dialog,k->new HashSet<>());
   if(!rendered.add("direct:"+link))continue;
   String headerKey=destination==videos?"video-heading":"audio-heading";
   if(rendered.add(headerKey)){
    TextView heading=text(destination==videos?"DETECTED MEDIA • VIDEO":"DETECTED MEDIA • AUDIO",12,0xff74d8ff);
    heading.setPadding(dp(8),dp(12),0,dp(8));destination.addView(heading,0);
   }
   String display="↓  "+kind+(label.isEmpty()?" • Original quality":" • "+label);
   settingRow(destination,display,"Save detected direct media file",()->{
    requestDownloadNotifications();
    if(dialog.isShowing())dialog.dismiss();
    startDownload(link,mediaFilename(link,mime),mime,source.optString("referer",""));
   });
  }
 }
 final Map<AlertDialog,Set<String>> hlsRequests=new java.util.WeakHashMap<>();
 final Map<AlertDialog,Set<String>> hlsRendered=new java.util.WeakHashMap<>();
 final Map<AlertDialog,View> hlsUnknown=new java.util.WeakHashMap<>();
 void addHlsResolutions(LinearLayout videos,LinearLayout audios,String playlist,String sourceReferer,AlertDialog dialog){
  Set<String> requested=hlsRequests.computeIfAbsent(dialog,k->new HashSet<>());
  if(!requested.add(playlist))return;
  WebView w=tabs.isEmpty()?null:active();
  String page=w==null?"":w.getUrl();
  String referer=sourceReferer.startsWith("http")?sourceReferer:page;
  String agent=w==null?"":w.getSettings().getUserAgentString();
  HlsFormatInspector.inspect(getApplicationContext(),playlist,referer,agent,new HlsFormatInspector.Callback(){
   @Override public void ready(java.util.List<HlsFormatInspector.Variant> formats){
    runOnUiThread(()->{
     if(!dialog.isShowing()||videos.getParent()==null)return;
     Set<String> rendered=hlsRendered.computeIfAbsent(dialog,k->new HashSet<>());
     for(HlsFormatInspector.Variant variant:formats){
      String key=variant.height>0?"height:"+variant.height:"unknown";
      if(variant.height==0&&rendered.stream().anyMatch(k->k.startsWith("height:")))continue;
      if(variant.height>0){
       View unknown=hlsUnknown.remove(dialog);
       if(unknown!=null&&unknown.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup)unknown.getParent()).removeView(unknown);
       rendered.remove("unknown");
      }
      if(!rendered.add(key))continue;
      if(rendered.add("video-heading")){
       TextView heading=text("DETECTED MEDIA • VIDEO",12,0xff74d8ff);
       heading.setPadding(dp(8),dp(12),0,dp(8));videos.addView(heading,0);
      }
      settingRow(videos,"▣  "+(variant.height>0?"Video • "+variant.height+"p (HLS)":"Video • source quality (resolution not advertised)"),"Download this accessible stream quality",()->{
       requestDownloadNotifications();
       dialog.dismiss();
       MediaDownloadService.enqueue(getApplicationContext(),variant.url,
        new MediaDownloadEngine.Choice(variant.label,0,null),referer,agent,mediaTitleForDownload(page));
       Toast.makeText(BrowserActivity.this,"Video download queued • See Downloads",Toast.LENGTH_LONG).show();
      });
      if(variant.height==0)hlsUnknown.put(dialog,videos.getChildAt(videos.getChildCount()-1));
     }
     if(rendered.add("audio-heading")){
      TextView heading=text("DETECTED MEDIA • AUDIO",12,0xff74d8ff);
      heading.setPadding(dp(8),dp(12),0,dp(8));audios.addView(heading,0);
     }
     if(rendered.add("audio-mp3"))settingRow(audios,"♫  Audio • MP3","Extract audio from this accessible stream",()->{
      requestDownloadNotifications();
      dialog.dismiss();
      MediaDownloadService.enqueue(getApplicationContext(),playlist,
       new MediaDownloadEngine.Choice("Audio • MP3",0,"mp3"),referer,agent,mediaTitleForDownload(page));
     });
     if(rendered.add("audio-m4a"))settingRow(audios,"♫  Audio • M4A","Extract audio from this accessible stream",()->{
      requestDownloadNotifications();
      dialog.dismiss();
      MediaDownloadService.enqueue(getApplicationContext(),playlist,
       new MediaDownloadEngine.Choice("Audio • M4A",0,"m4a"),referer,agent,mediaTitleForDownload(page));
     });
    });
   }
   @Override public void failed(String error){/* Omit unavailable stream candidates without warnings. */}
  });
 }
 void addFormatChoices(LinearLayout options,String url,java.util.List<MediaDownloadEngine.Choice> choices,AlertDialog dialog){
  if(choices==null||choices.isEmpty())return;
  TextView videoLabel=text("DETECTED MEDIA • VIDEO",12,0xff74d8ff);
  videoLabel.setPadding(dp(8),dp(16),0,dp(8));options.addView(videoLabel);
  for(MediaDownloadEngine.Choice c:choices){
   if(c.audioFormat!=null)continue;
   settingRow(options,"▣  "+c.label,"Download available video and audio",()->confirmMediaChoice(url,c,dialog));
  }
  TextView audioLabel=text("DETECTED MEDIA • AUDIO",12,0xff74d8ff);
  audioLabel.setPadding(dp(8),dp(16),0,dp(8));options.addView(audioLabel);
  for(MediaDownloadEngine.Choice c:choices){
   if(c.audioFormat==null)continue;
   settingRow(options,"♫  "+c.label,"Extract or convert audio from this media",()->confirmMediaChoice(url,c,dialog));
  }
 }
 void confirmMediaChoice(String url,MediaDownloadEngine.Choice choice,AlertDialog panel){
  // One tap on a format starts the foreground service; no extra confirmation/loading dialog.
  if(panel.isShowing())panel.dismiss();
  downloadMediaFile(url,choice);
 }
 void directMediaSources(JSONArray sources){
  ArrayList<String> labels=new ArrayList<>();
  for(int i=0;i<sources.length();i++){
   JSONObject item=sources.optJSONObject(i);if(item==null)continue;
   labels.add(item.optString("kind")+(item.optString("label").isEmpty()?" • Original source":" • "+item.optString("label")));
  }
  if(labels.isEmpty()){dialog("Media downloads","No downloadable direct media files are available.");return;}
  themed().setTitle("Direct media files")
   .setItems(labels.toArray(new String[0]),(d,which)->{
    JSONObject item=sources.optJSONObject(which);if(item==null)return;
    String link=item.optString("url"),mime=item.optString("type");
    String filename=mediaFilename(link,mime);
    themed().setTitle("Save original file?").setMessage(filename)
     .setNegativeButton("Cancel",null)
     .setPositiveButton("Download",(x,y)->startDownload(link,filename,mime)).show();
   }).setNegativeButton("Cancel",null).show();
 }
 void inspectMediaFormats(String mediaUrl,JSONArray directSources){
  LinearLayout loading=column();loading.setPadding(dp(22),dp(16),dp(22),dp(16));
  ProgressBar spinner=new ProgressBar(this);loading.addView(spinner);
  TextView info=text("Checking available video resolutions and audio formats…",14,GRAY);
  info.setPadding(0,dp(14),0,0);loading.addView(info);
  AlertDialog wait=themed().setTitle("Checking media formats").setView(loading)
   .setNegativeButton("Cancel",null).create();
  wait.show();styleDialog(wait);
  MediaDownloadEngine.inspect(getApplicationContext(),mediaUrl,new MediaDownloadEngine.Callback(){
   @Override public void ready(String title,java.util.List<MediaDownloadEngine.Choice> choices){
    runOnUiThread(()->{
     if(isFinishing()||isDestroyed())return;
     wait.dismiss();
     String[] names=new String[choices.size()];
     for(int i=0;i<choices.size();i++)names[i]=choices.get(i).label;
     themed().setTitle("Download • "+title)
      .setItems(names,(d,n)->{
       MediaDownloadEngine.Choice choice=choices.get(n);
       themed().setTitle("Start download?")
        .setMessage(choice.label+"\n\nDownload only media you own or have permission to save. Some websites may reject extraction.")
        .setNegativeButton("Cancel",null)
        .setPositiveButton("Download",(x,y)->downloadMediaFile(mediaUrl,choice)).show();
      }).setNegativeButton("Cancel",null).show();
    });
   }
   @Override public void failed(String error){
    runOnUiThread(()->{
     if(isFinishing()||isDestroyed())return;
     wait.dismiss();
     if(directSources.length()>0){
      themed().setTitle("Media format lookup failed").setMessage(error)
       .setNegativeButton("Close",null)
       .setPositiveButton("Show direct files",(d,n)->directMediaSources(directSources)).show();
     }else dialog("Media formats unavailable",error);
    });
   }
   @Override public void progress(int percent,String line){}
   @Override public void completed(String name,String uri,String mime){}
  });
 }
 void downloadMediaFile(String mediaUrl,MediaDownloadEngine.Choice choice){
  try{
   String title=mediaTitleForDownload(tabs.isEmpty()?null:active().getUrl());
   if(title.isEmpty())title=mediaTitleCache.getOrDefault(mediaUrl,"");
   WebView w=tabs.isEmpty()?null:active();
   MediaDownloadService.enqueue(getApplicationContext(),mediaUrl,choice,w==null?"":w.getUrl(),w==null?null:w.getSettings().getUserAgentString(),title);
   Toast.makeText(this,"Download started. Track progress in Downloads or Android notifications.",Toast.LENGTH_LONG).show();
   if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},302);
   }
  }catch(Exception e){dialog("Download unavailable","Could not start background download: "+e.getMessage());}
 }
 void lookupPublicIp(){
  themed().setTitle("Public IP lookup").setMessage("This sends your public IP to ipwho.is to retrieve approximate IP-based country, region and city. Continue?")
   .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,n)->{
    new Thread(()->{
     String result="Lookup unavailable";
     try{
      java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("https://ipwho.is/").openConnection();
      c.setConnectTimeout(6500);c.setReadTimeout(6500);
      try(java.io.InputStream in=c.getInputStream();java.util.Scanner scan=new java.util.Scanner(in,"UTF-8").useDelimiter("\\A")){
       JSONObject data=new JSONObject(scan.hasNext()?scan.next():"{}");
       if(data.optBoolean("success",false))result="Public IP: "+data.optString("ip")+"\nCountry: "+data.optString("country")+"\nRegion: "+data.optString("region")+"\nCity: "+data.optString("city")+"\nTime zone: "+data.optJSONObject("timezone").optString("id");
      }finally{c.disconnect();}
     }catch(Exception ignored){}
     final String response=result;runOnUiThread(()->dialog("Public IP information",response+"\nLocation is approximate and based on public IP, not GPS."));
    }).start();
   }).show();
 }
 // Old saved download records sometimes contain a second percentage in their detail.
 // Keep exactly one percent in the UI, followed by optional speed/status text.
 String downloadProgressLabel(JSONObject item){
  String state=item.optString("state","Pending");
  int pct=item.optInt("progress",0);
  String detail=item.optString("detail","");
  if("Preparing".equals(state))return "Preparing download • Connecting to media…";
  if("Paused".equals(state))return "Paused • "+pct+"%"+(detail.contains("MB /")?cleanDownloadDetail(detail):"");
  if("Downloading".equals(state))return "Downloading • "+pct+"%"+cleanDownloadDetail(detail);
  if("Completed".equals(state))return "Completed • 100%";
  if("Failed".equals(state))return "Failed"+cleanDownloadDetail(detail);
  return state;
 }
 String cleanDownloadDetail(String detail){
  if(detail==null)return "";
  String clean=detail.trim().replaceFirst("^\\d{1,3}%\\s*(?:[•|\\-]\\s*)?","");
  if(clean.isEmpty()||clean.equals("Download complete"))return "";
  return " • "+clean;
 }
 void refreshCompletedDownloadRows(){
  if(screen!=8||isFinishing()||isDestroyed())return;
  int vertical=downloadsScrollView==null?0:downloadsScrollView.getScrollY();
  downloads();
  if(downloadsScrollView!=null){
   ScrollView updated=downloadsScrollView;
   updated.post(()->{if(screen==8&&downloadsScrollView==updated)updated.scrollTo(0,vertical);});
  }
 }
 void downloads(){
  showingSettings=true;screen=8;head.removeAllViews();body.removeAllViews();sectionHeader("Downloads",this::display);
  ScrollView sc=new ScrollView(this);downloadsScrollView=sc;body.addView(sc);LinearLayout list=column();list.setPadding(dp(15),dp(12),dp(15),dp(22));sc.addView(list);
  String[] labels={"Today","Previous Day","Week","Monthly","Yearly","All","Choose date"};
  HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.setHorizontalScrollBarEnabled(false);list.addView(horizontal);
  LinearLayout filters=row();horizontal.addView(filters);
  for(int i=0;i<labels.length;i++){final int n=i;TextView chip=action(labels[i],12,()->{
    if(n==6){Calendar now=Calendar.getInstance();new DatePickerDialog(this,(picker,y,m,d)->{
     Calendar chosen=Calendar.getInstance();chosen.set(y,m,d,0,0,0);chosen.set(Calendar.MILLISECOND,0);
     prefs.edit().putLong("downloadDate",chosen.getTimeInMillis()).apply();downloadFilter=6;selectedDownloadKeys.clear();downloads();
    },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show();}
    else{downloadFilter=n;selectedDownloadKeys.clear();downloads();}
   });chip.setPadding(dp(13),0,dp(13),0);chip.setBackground(grad(downloadFilter==i?0xff2373a8:PANEL,downloadFilter==i?0xff1a4f7a:PANEL,14));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(39));lp.rightMargin=dp(7);filters.addView(chip,lp);
  }
  LinearLayout selectionToolbar=row();selectionToolbar.setPadding(dp(3),dp(12),dp(3),dp(4));list.addView(selectionToolbar);
  TextView selectMode=text(downloadSelecting?"✓  Done selecting":"☑  Select downloads",14,0xff7dd9ff);
  selectMode.setTypeface(null,Typeface.BOLD);selectMode.setPadding(dp(10),dp(12),dp(10),dp(12));
  selectMode.setBackground(grad(0xff183b59,0xff112942,11));
  selectionToolbar.addView(selectMode,new LinearLayout.LayoutParams(0,-2,1));
  selectMode.setOnClickListener(v->{downloadSelecting=!downloadSelecting;selectedDownloadKeys.clear();refreshCompletedDownloadRows();});
  if(downloadSelecting){
   TextView selectAll=text("Select all",13,0xff7dd9ff);
   selectAll.setGravity(Gravity.CENTER);selectAll.setPadding(dp(12),dp(12),dp(12),dp(12));
   selectionToolbar.addView(selectAll);
   selectAll.setOnClickListener(v->{
    JSONArray entries=readArray("downloads");
    ArrayList<String> visible=new ArrayList<>();
    for(int j=0;j<entries.length();j++){JSONObject entry=entries.optJSONObject(j);if(entry!=null&&downloadMatches(entry.optLong("time")))visible.add(downloadKey(entry));}
    boolean all=selectedDownloadKeys.containsAll(visible);
    if(all)selectedDownloadKeys.removeAll(visible);else selectedDownloadKeys.addAll(visible);
    refreshCompletedDownloadRows();
   });
  }
  if(downloadSelecting){
   TextView deleteSelected=text("Delete selected ("+selectedDownloadKeys.size()+")",14,WHITE);
   deleteSelected.setGravity(Gravity.CENTER);deleteSelected.setPadding(dp(13),dp(13),dp(13),dp(13));
   deleteSelected.setBackground(grad(0xff914044,0xff61262e,11));
   LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,-2);dl.topMargin=dp(8);list.addView(deleteSelected,dl);
   deleteSelected.setOnClickListener(v->deleteSelectedDownloads());
  }
  downloadUiHandler.removeCallbacks(downloadUiTick);
  downloadStatusViews.clear();downloadProgressViews.clear();downloadRenderedStates.clear();
  JSONArray a=readArray("downloads");int shown=0;String lastDate="";
  DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
  for(int i=a.length()-1;i>=0;i--){
   JSONObject item=a.optJSONObject(i);if(item==null)continue;long t=item.optLong("time"),id=item.optLong("id");
   if(!downloadMatches(t))continue;shown++;
   String name=item.optString("name","Downloaded file");
   String day=new java.text.SimpleDateFormat("EEEE, MMM d, yyyy",Locale.getDefault()).format(new Date(t));
   if(!day.equals(lastDate)){
    lastDate=day;LinearLayout dayRow=row();dayRow.setPadding(dp(5),dp(17),dp(5),dp(5));list.addView(dayRow);
    TextView groupTitle=text(day,14,0xff7dd9ff);groupTitle.setTypeface(null,Typeface.BOLD);dayRow.addView(groupTitle,new LinearLayout.LayoutParams(0,-2,1));
    if(downloadSelecting){
     final String targetDay=day;
     TextView selectDay=text("SELECT DAY",11,0xff7dd9ff);selectDay.setPadding(dp(8),dp(5),dp(8),dp(5));dayRow.addView(selectDay);
     selectDay.setOnClickListener(v->{
      JSONArray entries=readArray("downloads");ArrayList<String> dayKeys=new ArrayList<>();
      java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEEE, MMM d, yyyy",Locale.getDefault());
      for(int j=0;j<entries.length();j++){JSONObject e=entries.optJSONObject(j);if(e!=null&&downloadMatches(e.optLong("time"))&&targetDay.equals(format.format(new Date(e.optLong("time")))))dayKeys.add(downloadKey(e));}
      boolean all=selectedDownloadKeys.containsAll(dayKeys);
      if(all)selectedDownloadKeys.removeAll(dayKeys);else selectedDownloadKeys.addAll(dayKeys);
      refreshCompletedDownloadRows();
     });
    }
   }
   LinearLayout card=row();card.setPadding(dp(14),dp(12),dp(10),dp(12));card.setBackground(grad(0xff193753,0xff10253e,13));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(9);list.addView(card,lp);
   if(downloadSelecting){
    String key=downloadKey(item);
    CheckBox check=new CheckBox(this);check.setButtonTintList(android.content.res.ColorStateList.valueOf(lightTheme?0xff176c9b:0xff68d8ff));
    check.setChecked(selectedDownloadKeys.contains(key));card.addView(check);
    check.setOnCheckedChangeListener((button,on)->{
     if(on)selectedDownloadKeys.add(key);else selectedDownloadKeys.remove(key);
     refreshCompletedDownloadRows();
    });
   }
   LinearLayout info=column();card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
   TextView title=text("↓  "+name,15,WHITE);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(title);
   String date=new java.text.SimpleDateFormat("MMM d, yyyy • h:mm a",Locale.getDefault()).format(new Date(t));
   int status=id==-1?DownloadManager.STATUS_SUCCESSFUL:0;
   if(id==-2){
    String savedState=item.optString("state","Pending");
    status="Completed".equals(savedState)?DownloadManager.STATUS_SUCCESSFUL:
      "Failed".equals(savedState)?DownloadManager.STATUS_FAILED:DownloadManager.STATUS_RUNNING;
   }else if(id!=-1)try(android.database.Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))){
    if(cursor!=null&&cursor.moveToFirst())status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
   }catch(Exception ignored){}
   String state=status==DownloadManager.STATUS_SUCCESSFUL?"Completed":status==DownloadManager.STATUS_FAILED?"Failed":status==DownloadManager.STATUS_RUNNING?"Downloading":"Pending";
   TextView statusLine=text(id==-2?downloadProgressLabel(item):date+" • "+state,12,GRAY);
   info.addView(statusLine);
   if(id==-2){
    String task=item.optString("task");downloadStatusViews.put(task,statusLine);
    downloadRenderedStates.put(task,item.optString("state","Pending"));
    ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setMax(100);
    p.setProgress(item.optInt("progress",0));
    LinearLayout.LayoutParams progressLp=new LinearLayout.LayoutParams(-1,dp(5));progressLp.topMargin=dp(7);
    info.addView(p,progressLp);
    String liveState=item.optString("state","");
    p.setIndeterminate("Preparing".equals(liveState));
    p.setVisibility("Preparing".equals(liveState)||"Downloading".equals(liveState)||"Paused".equals(liveState)?View.VISIBLE:View.GONE);
    downloadProgressViews.put(task,p);
   }
   if(!downloadSelecting&&id==-2&&("Preparing".equals(item.optString("state"))||"Downloading".equals(item.optString("state"))||"Paused".equals(item.optString("state")))){
    String task=item.optString("task");boolean paused="Paused".equals(item.optString("state"));
    LinearLayout controls=column();controls.setPadding(dp(5),0,0,0);card.addView(controls);
    TextView toggle=text(paused?"▶ Resume":"Ⅱ Pause",12,WHITE);
    toggle.setGravity(Gravity.CENTER);toggle.setPadding(dp(10),dp(11),dp(10),dp(11));
    toggle.setBackground(grad(0xff2373a8,0xff1a4f7a,10));controls.addView(toggle);
    toggle.setOnClickListener(v->{toggle.setEnabled(false);MediaDownloadService.setPaused(this,task,!paused);downloadUiHandler.postDelayed(this::refreshCompletedDownloadRows,450);});
    TextView remove=text("✕ Delete",12,WHITE);
    remove.setGravity(Gravity.CENTER);remove.setPadding(dp(10),dp(9),dp(10),dp(9));
    LinearLayout.LayoutParams removeLp=new LinearLayout.LayoutParams(-1,-2);removeLp.topMargin=dp(6);
    remove.setBackground(grad(0xff914044,0xff61262e,10));controls.addView(remove,removeLp);
    remove.setOnClickListener(v->themed().setTitle("Delete download?").setMessage("Stop this transfer and discard its unfinished data?")
     .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{
      MediaDownloadService.deleteDownload(this,task);
      downloadUiHandler.postDelayed(this::refreshCompletedDownloadRows,300);
     }).show());
   }
   if(!downloadSelecting&&status==DownloadManager.STATUS_SUCCESSFUL){
    View.OnClickListener openDownloaded=v->{
     try{
      String savedUri=item.optString("uri","");
      Uri uri=id<0?(savedUri.isEmpty()?null:Uri.parse(savedUri)):dm.getUriForDownloadedFile(id);
      if(uri==null)throw new IllegalStateException("Saved file link is missing");
      MediaOpenHelper.open(this,uri,item.optString("mime",""),name);
     }catch(Exception e){dialog("Open download","Unable to open the saved file: "+e.getMessage());}
    };
    card.setClickable(true);
    card.setOnClickListener(openDownloaded);
    TextView openButton=text("▶  Open",13,WHITE);
    openButton.setGravity(Gravity.CENTER);
    openButton.setPadding(dp(10),dp(12),dp(10),dp(12));
    openButton.setBackground(grad(0xff2373a8,0xff1a4f7a,10));
    card.addView(openButton);
    openButton.setOnClickListener(openDownloaded);
   }
  }
  if(shown==0){TextView empty=text("No downloads for this period",16,GRAY);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(8),dp(55),dp(8),dp(40));list.addView(empty);}
  downloadUiHandler.postDelayed(downloadUiTick,500);
 }
 String downloadKey(JSONObject item){
  String task=item.optString("task","");
  if(item.optLong("id")==-2&&!task.isEmpty())return "task:"+task;
  return "saved:"+item.optLong("id")+":"+item.optLong("time")+":"+item.optString("uri","")+":"+item.optString("name","");
 }
 void deleteSelectedDownloads(){
  if(selectedDownloadKeys.isEmpty()){Toast.makeText(this,"Select downloads to delete first",Toast.LENGTH_SHORT).show();return;}
  final Set<String> targets=new HashSet<>(selectedDownloadKeys);
  themed().setTitle("Delete "+targets.size()+" download(s)?")
   .setMessage("Selected downloads will be removed from this list. Active transfers will stop. Saved files will also be deleted from device storage where permitted.")
   .setNegativeButton("Cancel",null).setPositiveButton("Delete",(dialog,which)->{
    JSONArray current=readArray("downloads");JSONArray kept=new JSONArray();
    DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
    for(int j=0;j<current.length();j++){
     JSONObject entry=current.optJSONObject(j);
     if(entry==null)continue;
     if(!targets.contains(downloadKey(entry))){kept.put(entry);continue;}
     long id=entry.optLong("id",-1);
     if(id==-2){
      String task=entry.optString("task","");
      String state=entry.optString("state","");
      if("Downloading".equals(state)||"Preparing".equals(state)||"Paused".equals(state))
       MediaDownloadService.deleteDownload(this,task);
      else deleteStoredDownload(entry);
     }else if(id>=0){
      try{dm.remove(id);}catch(Exception ignored){}
     }else deleteStoredDownload(entry);
    }
    storeArray("downloads",kept);
    selectedDownloadKeys.clear();downloadSelecting=false;
    refreshCompletedDownloadRows();
   }).show();
 }
 void deleteStoredDownload(JSONObject entry){
  String raw=entry.optString("uri","");
  if(raw.isEmpty())return;
  try{
   Uri uri=Uri.parse(raw);
   if("content".equalsIgnoreCase(uri.getScheme()))getContentResolver().delete(uri,null,null);
   // Never delete arbitrary file:// paths supplied by a webpage.
  }catch(Exception e){android.util.Log.w("MHBrowser","Could not remove stored download",e);}
 }
 boolean downloadMatches(long t){
  Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
  long today=start.getTimeInMillis();
  if(downloadFilter==0)return t>=today;
  if(downloadFilter==1)return t>=today-86400000L&&t<today;
  if(downloadFilter==2)return t>=today-7*86400000L;
  if(downloadFilter==3){start.set(Calendar.DAY_OF_MONTH,1);return t>=start.getTimeInMillis();}
  if(downloadFilter==4){start.set(Calendar.DAY_OF_YEAR,1);return t>=start.getTimeInMillis();}
  if(downloadFilter==6){long d=prefs.getLong("downloadDate",today);return t>=d&&t<d+86400000L;}
  return true;
 }
 void startDownload(String link,String filename,String mime){startDownload(link,filename,mime,null);}
 void startDownload(String link,String filename,String mime,String sourceReferer){
  try{
   Uri u=Uri.parse(link);
   if(!"https".equalsIgnoreCase(u.getScheme())&&!"http".equalsIgnoreCase(u.getScheme())){
    dialog("Download unavailable","Only HTTP/HTTPS files are supported.");return;
   }
   WebView current=tabs.isEmpty()?null:active();
   String agent=current==null?"MH Browser":current.getSettings().getUserAgentString();
   String referer=sourceReferer!=null&&!sourceReferer.isEmpty()?sourceReferer:current==null?"":current.getUrl();
   String cookie=CookieManager.getInstance().getCookie(link);
   // Use our own foreground download service for website files, just like media.
   // Do not enqueue Android DownloadManager: that produces a separate system notification.
   MediaDownloadService.enqueueDirect(getApplicationContext(),link,filename,mime,agent,cookie,referer);
   Toast.makeText(this,"Download added to MH Browser",Toast.LENGTH_SHORT).show();
   if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},302);
  }catch(Exception e){dialog("Download failed","Could not start MH Browser download: "+e.getMessage());}
 }
 void general(){
  showingSettings=true;screen=2;head.removeAllViews();body.removeAllViews();sectionHeader("General",this::settings);
  LinearLayout l=settingContent();
  settingRow(l,"Search engine","Google Search",()->dialog("Search engine","Google Search is selected for V1.0."));
  switchRow(l,"Restore last page","Reopen the last normal URL on launch","restore",true);
  switchRow(l,"Enable JavaScript","Required for many websites","javascript",true);
  switchRow(l,"Load images","Show images on websites","images",true);
  switchRow(l,"Desktop mode by default","Request desktop user agent for new tabs","desktop",false);
  settingRow(l,"Home page","MH Browser start page",()->dialog("Home page","MH Browser start page is the default home page."));
 }
 void siteSettings(){
  showingSettings=true;screen=4;head.removeAllViews();body.removeAllViews();sectionHeader("Site settings",this::settings);
  LinearLayout l=settingContent();
  switchRow(l,"Allow website location","Requires Android location permission; websites must ask","location",false);
  switchRow(l,"Allow JavaScript popups","Blocked by default","popups",false);
  settingRow(l,"Location accuracy","Uses Android location, not public IP location",()->dialog("Location","Device location and public IP geolocation are separate."));
 }
 void multiWindow(){
  dialog("Split screen","Open Android Recent Apps, tap the MH Browser app icon and select Split screen if your device supports it.");
 }
 void advanced(){
  showingSettings=true;screen=5;head.removeAllViews();body.removeAllViews();sectionHeader("Advanced",this::settings);
  LinearLayout l=settingContent();
  switchRow(l,"Enable zoom","Pinch to zoom on websites","zoom",true);

  settingRow(l,"Network diagnostics","View active connection type",this::network);
  settingRow(l,"Browsing history","View and delete visits by date",this::history);
  settingRow(l,"Browser engine","Android System WebView",()->dialog("Browser engine","MH Browser uses the installed Android System WebView, which is Chromium-based."));
  settingRow(l,"Update media download engine","Refresh supported website extractors (internet required)",this::updateMediaEngine);
 }

 void updateMediaEngine(){
  String last=prefs.getString("mediaEngineLastResult","No previous update check recorded.");
  themed().setTitle("Media engine updates")
   .setMessage("MH Browser checks for yt-dlp extractor updates automatically about once per day when opened. Updates require internet access and cannot guarantee every site will work.\n\nLast result: "+last)
   .setNegativeButton("Close",null)
   .setPositiveButton("Check now",(d,n)->{
    AlertDialog wait=themed().setTitle("Checking extractor updates").setMessage("Checking the latest available release…").create();
    wait.show();styleDialog(wait);
    MediaEngineUpdater.update(getApplicationContext(),true,message->runOnUiThread(()->{
     if(!isFinishing()&&!isDestroyed()){wait.dismiss();dialog("Media engine",message);}
    }));
   }).show();
 }
 void sectionHeader(String label,Runnable back){
 LinearLayout r=row();r.setPadding(dp(10),dp(12),dp(14),dp(12));r.setBackground(grad(0xff102940,0xff071326,12));head.addView(r);
 r.addView(action("‹",28,back),new LinearLayout.LayoutParams(dp(48),dp(45)));
 TextView t=text(label,22,WHITE);t.setTypeface(null,Typeface.BOLD);r.addView(t);
}
 LinearLayout settingContent(){ScrollView sc=new ScrollView(this);body.addView(sc);LinearLayout l=column();l.setPadding(dp(15),dp(10),dp(15),dp(20));sc.addView(l);return l;}

 void settings(){
  showingSettings=true;screen=1;head.removeAllViews();body.removeAllViews();
  LinearLayout h=row();h.setPadding(dp(12),dp(8),dp(16),dp(8));head.addView(h);
  h.addView(action("‹",28,this::display),new LinearLayout.LayoutParams(dp(45),dp(48)));
  TextView title=text("Settings",23,WHITE);title.setTypeface(null,Typeface.BOLD);h.addView(title);
  ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);body.addView(sc);
  LinearLayout list=column();list.setPadding(dp(15),dp(8),dp(15),dp(20));sc.addView(list);
  TextView general=text("GENERAL",12,0xff64caff);general.setPadding(dp(8),dp(15),0,dp(13));list.addView(general);
  settingRow(list,"⚙  General","Search, JavaScript, images and startup",this::general);
  settingRow(list,"◐  Theme",lightTheme?"Light appearance selected":"Original dark appearance selected",this::themeSettings);
  settingRow(list,"◉  Privacy & Security","Tracker protection, cookies, safe browsing",()->privacy());
  switchRow(list,"🛡  Ad Blocker","Block known advertising domains • On by default","adBlocker",true);
  settingRow(list,"⇄  Proxy settings","HTTP, HTTPS, SOCKS5 and optional credentials",this::proxy);
  settingRow(list,"▣  Site settings","Location access and website permissions",this::siteSettings);
  settingRow(list,"↓  Downloads","Android Download Manager",this::downloads);
  TextView more=text("BROWSING & DISPLAY",12,0xff64caff);more.setPadding(dp(8),dp(22),0,dp(13));list.addView(more);
  switchRow(list,"♫  Background playback","Allow supported media to continue in background","audio",true);
  settingRow(list,"▤  Multi-window / Split screen","Use Android system split screen",this::multiWindow);
  switchRow(list,"⊘  Do Not Track","Send DNT: 1 on direct page navigation","dnt",false);
  settingRow(list,"⚙  Advanced","Zoom, network and history controls",this::advanced);
  settingRow(list,"ⓘ  About MH Browser","Version 1.0 • MasoomDev",()->dialog("About MH Browser","MH Browser\nVersion 1.0\n\nCompany: MasoomDev\nDeveloped By: MH Developers"));
 }
 void themeSettings(){
  showingSettings=true;screen=10;head.removeAllViews();body.removeAllViews();
  sectionHeader("Theme",this::settings);
  LinearLayout list=settingContent();
  TextView intro=text("Choose your browser appearance",18,WHITE);
  intro.setTypeface(null,Typeface.BOLD);intro.setPadding(dp(8),dp(16),dp(8),dp(10));list.addView(intro);
  TextView desc=text("Dark keeps the original MH Browser colors. Light uses a bright background with readable dark text. Websites retain their own appearance.",13,GRAY);
  desc.setPadding(dp(8),0,dp(8),dp(20));list.addView(desc);
  themeOption(list,"☾  Dark","Original navy and blue MH Browser theme",!lightTheme,false);
  themeOption(list,"☼  Light","Bright panels with contrasting dark text",lightTheme,true);
 }
 void themeOption(LinearLayout list,String label,String desc,boolean checked,boolean chooseLight){
  LinearLayout card=row();card.setPadding(dp(15),dp(17),dp(13),dp(17));
  card.setBackground(grad(checked?0xff246493:0xff17334f,checked?0xff174569:0xff10253e,15));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);list.addView(card,lp);
  LinearLayout words=column();card.addView(words,new LinearLayout.LayoutParams(0,-2,1));
  TextView name=text(label,17,WHITE);name.setTypeface(null,Typeface.BOLD);words.addView(name);
  words.addView(text(desc,12,GRAY));
  TextView marker=text(checked?"✓":"○",23,checked?0xff63d4ff:GRAY);card.addView(marker);
  card.setOnClickListener(v->{
   if(lightTheme==chooseLight)return;
   lightTheme=chooseLight;prefs.edit().putBoolean("lightTheme",lightTheme).apply();
   applyThemeBars();controls();themeSettings();
  });
 }
 void privacy(){
  showingSettings=true;screen=3;head.removeAllViews();body.removeAllViews();
  LinearLayout h=row();h.setPadding(dp(12),dp(8),dp(12),dp(8));head.addView(h);
  h.addView(action("‹",28,this::settings),new LinearLayout.LayoutParams(dp(45),dp(48)));
  TextView title=text("Privacy & Security",22,WHITE);title.setTypeface(null,Typeface.BOLD);h.addView(title);
  ScrollView sc=new ScrollView(this);body.addView(sc);LinearLayout list=column();list.setPadding(dp(15),dp(15),dp(15),dp(20));sc.addView(list);
  switchRow(list,"Block known trackers","Limited built-in domain blocklist","trackers",true);
  switchRow(list,"Ad Blocker","Block known ad requests on websites • On by default","adBlocker",true);
  switchRow(list,"Block third-party cookies","Takes effect when opening a new tab","blockCookies",true);
  settingRow(list,"Safe Browsing","Android WebView Safe Browsing is enabled",()->dialog("Safe Browsing","Helps protect against known unsafe sites."));
  settingRow(list,"HTTPS protection","Mixed HTTP content blocked on HTTPS pages",()->dialog("HTTPS","Connections are protected by HTTPS when supported. This does not hide your identity or IP address."));
  settingRow(list,"Clear browsing data","Delete cookies, history, cache and web storage",()->themed().setTitle("Clear browsing data?").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,n)->{CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();for(Tab t:tabs)t.web.clearCache(true);storeArray("history",new JSONArray());clearNavigationStacks();prefs.edit().remove("lastUrl").apply();}).show());
  settingRow(list,"Private browsing limitations","Incognito isolation is not yet production-ready",()->dialog("Incognito","Current private tabs may share site cookies with normal tabs. Do not use this preview for sensitive private sessions."));
 }


 void proxy(){
  LinearLayout c=column();c.setPadding(dp(18),dp(8),dp(18),dp(8));
  TextView info=text("Choose the proxy protocol and server. Authentication depends on WebView support; credentials are not saved.",13,GRAY);
  c.addView(info);
  TextView protocol=text("PROXY TYPE",12,0xff80d7ff);protocol.setPadding(0,dp(9),0,dp(6));c.addView(protocol);
  Spinner type=new Spinner(this);String[] protocols={"HTTP","HTTPS","SOCKS5"};
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,protocols);
  type.setAdapter(adapter);String saved=prefs.getString("proxyScheme","http");type.setSelection("https".equals(saved)?1:"socks5".equals(saved)?2:0);c.addView(type);
  EditText host=new EditText(this),port=new EditText(this),username=new EditText(this),password=new EditText(this);
  host.setSingleLine(true);port.setSingleLine(true);username.setSingleLine(true);password.setSingleLine(true);port.setInputType(2);
  password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
  host.setHint("Proxy server / IP");port.setHint("Port (1–65535)");username.setHint("Username (optional)");password.setHint("Password (optional)");
  host.setText(prefs.getString("proxyHost",""));port.setText(prefs.getString("proxyPort",""));username.setText(prefs.getString("proxyUser",""));
  c.addView(host);c.addView(port);c.addView(username);c.addView(password);
  if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
   dialog("Proxy unavailable","Your Android System WebView does not support proxy override.");return;
  }
  AlertDialog d=themed().setTitle("Proxy settings").setView(c)
   .setNegativeButton("Cancel",null)
   .setNeutralButton("Disable proxy",(x,n)->ProxyController.getInstance().clearProxyOverride((command)->runOnUiThread(command),()->{
     proxyUser="";proxyPassword="";prefs.edit().putBoolean("proxyActive",false).apply();
     Toast.makeText(this,"Proxy disabled",Toast.LENGTH_SHORT).show();
   }))
   .setPositiveButton("Apply proxy",null).create();
  d.setOnShowListener(v->{styleDialog(d);d.getButton(-1).setOnClickListener(w->{
   String h=host.getText().toString().trim(),p=port.getText().toString().trim();
   if(h.isEmpty()||h.contains(" ")||h.contains("/")||h.contains("@")){host.setError("Enter a valid hostname or IP");return;}
   int number;try{number=Integer.parseInt(p);}catch(Exception e){port.setError("Invalid port");return;}
   if(number<1||number>65535){port.setError("Port must be 1–65535");return;}
   String scheme=new String[]{"http","https","socks5"}[type.getSelectedItemPosition()];
   ProxyConfig config;
   try{config=new ProxyConfig.Builder().addProxyRule(scheme+"://"+h+":"+number).build();}
   catch(Exception e){dialog("Proxy unsupported","The selected proxy type is not supported by your WebView.");return;}
   proxyUser=username.getText().toString().trim();proxyPassword=password.getText().toString();
   ProxyController.getInstance().setProxyOverride(config,(command)->runOnUiThread(command),()->{
    prefs.edit().putString("proxyHost",h).putString("proxyPort",p).putString("proxyScheme",scheme)
     .putString("proxyUser",proxyUser).putBoolean("proxyActive",true).apply();
    Toast.makeText(this,"Proxy rule applied • verify connection and authentication",Toast.LENGTH_LONG).show();
   });d.dismiss();
  });});d.show();
 }
 @Override public void onBackPressed(){
  if(fullscreenVideo!=null){hideVideo();return;}
  if(screen==6||screen==7||screen==8){display();return;}if(screen>=2){settings();return;}if(screen==1){display();return;}
  if(active().canGoBack()){active().goBack();return;}
  if(!"about:blank".equals(active().getUrl())){navigate("about:blank");return;}
  if(privateOnly){finish();overridePendingTransition(0,0);return;}
  themed().setTitle("Exit MH Browser?").setMessage("Are you sure you want to exit?")
   .setNegativeButton("Stay",null).setPositiveButton("Exit",(d,i)->finish()).show();
 }
 @Override protected void onPause(){saveTabs();super.onPause();if(!prefs.getBoolean("audio",true))for(Tab t:tabs)t.web.onPause();CookieManager.getInstance().flush();}
 @Override protected void onResume(){super.onResume();for(Tab t:tabs)t.web.onResume();if(screen==8&&downloadsScrollView!=null)refreshCompletedDownloadRows();}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent!=null&&intent.getBooleanExtra("openDownloads",false)){intent.removeExtra("openDownloads");downloads();}}
 @Override protected void onDestroy(){downloadUiHandler.removeCallbacks(downloadUiTick);if(fullscreenVideo!=null)hideVideo();for(Tab t:tabs)if(t.incognito){t.web.clearHistory();t.web.clearCache(true);}if(privateOnly){CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();}super.onDestroy();}
}