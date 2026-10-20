package com.mh.browser;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Request filtering with offline community ad-hosts plus curated EasyList/EasyPrivacy
 * network rules. Filter decisions happen before the WebView fetches a resource.
 * First-party login/video CDNs are deliberately protected against broad host blocks.
 */
final class AdBlockRules {
 private static volatile Set<String> blockedHosts=Collections.emptySet();
 private static volatile Map<String,List<String>> hostPaths=Collections.emptyMap();
 private static volatile Set<String> filterHosts=Collections.emptySet();
 private static final String[] AD_HOSTS={
  "doubleclick.net","googlesyndication.com","googleadservices.com",
  "adnxs.com","adsrvr.org","criteo.com","criteo.net","taboola.com",
  "outbrain.com","pubmatic.com","rubiconproject.com","openx.net",
  "smartadserver.com","adform.net","casalemedia.com","media.net",
  "mgid.com","popads.net","popcash.net","propellerads.com",
  "exoclick.com","exosrv.com","adsterra.com","onclickads.net",
  "hilltopads.net","revcontent.com","bidvertiser.com","adroll.com",
  "bidswitch.net","adskeeper.com","trafficjunky.net"
 };
 private static final String[] TRACKERS={
  "google-analytics.com","analytics.google.com","scorecardresearch.com",
  "hotjar.com","clarity.ms","mixpanel.com","segment.io",
  "amplitude.com","quantserve.com"
 };
 // These are media/authentication delivery hosts, NOT ad blocking exemptions for
 // all resources on the main social network websites.
 private static final String[] MEDIA_HOSTS={
  "googlevideo.com","ytimg.com","youtubei.googleapis.com",
  "fbcdn.net","fbsbx.com","cdninstagram.com","tiktokcdn.com",
  "tiktokv.com","byteoversea.com","nflxvideo.net","nflximg.net",
  "nflxso.net","scdn.co","sndcdn.com","vimeocdn.com"
 };
 private static final String[] TRUSTED_SOCIAL={
  "youtube.com","youtu.be","facebook.com","instagram.com",
  "tiktok.com","pinterest.com","netflix.com","google.com"
 };
 private AdBlockRules(){}
 static synchronized void initialize(Context context){
  if(!blockedHosts.isEmpty())return;
  Set<String> hosts=new HashSet<>();
  Map<String,List<String>> paths=new HashMap<>();
  try(BufferedReader r=new BufferedReader(new InputStreamReader(
      context.getApplicationContext().getAssets().open("ad_hosts.txt"),"UTF-8"))){
   String line;
   while((line=r.readLine())!=null){
    String h=line.trim().toLowerCase(Locale.ROOT);
    if(h.length()>3&&h.length()<240&&!h.startsWith("#")&&!h.contains(" ")&&!h.contains("/"))hosts.add(h);
   }
  }catch(Exception ignored){}
  try(BufferedReader r=new BufferedReader(new InputStreamReader(
      context.getApplicationContext().getAssets().open("ad_filters.txt"),"UTF-8"))){
   String line;
   while((line=r.readLine())!=null){
    int separator=line.indexOf('|');
    if(separator<1)continue;
    String host=line.substring(0,separator);
    String path=line.substring(separator+1);
    if(host.length()>3&&path.length()>2)
     paths.computeIfAbsent(host,k->new ArrayList<>()).add(path);
   }
  }catch(Exception ignored){}
  try(BufferedReader r=new BufferedReader(new InputStreamReader(
      context.getApplicationContext().getAssets().open("ad_filter_hosts.txt"),"UTF-8"))){
   String line;
   Set<String> domains=new HashSet<>();
   while((line=r.readLine())!=null){
    String h=line.trim().toLowerCase(Locale.ROOT);
    if(h.length()>3&&h.length()<240&&!h.contains("/")&&!h.contains(" "))domains.add(h);
   }
   filterHosts=Collections.unmodifiableSet(domains);
  }catch(Exception ignored){}
  blockedHosts=Collections.unmodifiableSet(hosts);
  hostPaths=Collections.unmodifiableMap(paths);
 }
 private static boolean matches(String host,String[] list){
  for(String d:list)if(host.equals(d)||host.endsWith("."+d))return true;
  return false;
 }
 private static boolean blockedDomain(String host){
  Set<String> hosts=blockedHosts;
  if(hosts.contains(host))return true;
  for(int dot=host.indexOf('.');dot>=0;dot=host.indexOf('.',dot+1))
   if(hosts.contains(host.substring(dot+1)))return true;
  return false;
 }
 private static boolean blockedPath(String host,String path){
  Map<String,List<String>> rules=hostPaths;
  String candidate=host;
  while(true){
   List<String> entries=rules.get(candidate);
   if(entries!=null)for(String token:entries)if(path.contains(token))return true;
   int dot=candidate.indexOf('.');
   if(dot<0)break;
   candidate=candidate.substring(dot+1);
  }
  return false;
 }
 private static boolean filterDomain(String host){
  Set<String> list=filterHosts;
  if(list.contains(host))return true;
  for(int i=host.indexOf('.');i>=0;i=host.indexOf('.',i+1))
   if(list.contains(host.substring(i+1)))return true;
  return false;
 }
 /**
  * A video-player tap must not hijack the original browser tab to an unrelated
  * advertiser. Called only for a brief interval after an actual player gesture.
  */
 static boolean isCrossSiteAdRedirect(Uri destination,String original){
  if(destination==null||original==null)return false;
  String target=destination.getHost(),origin;
  try{origin=Uri.parse(original).getHost();}catch(Exception e){return false;}
  if(target==null||origin==null)return false;
  target=target.toLowerCase(Locale.ROOT);
  origin=origin.toLowerCase(Locale.ROOT);
  if(target.equals(origin)||target.endsWith("."+origin)||origin.endsWith("."+target))return false;
  if(matches(target,MEDIA_HOSTS)||matches(target,TRUSTED_SOCIAL))return false;
  // Legitimate cross-site players may use a different CDN; block known ad
  // destinations and suspicious redirects, not all external navigation.
  if(matches(target,AD_HOSTS)||blockedDomain(target)||filterDomain(target))return true;
  String path=destination.getPath();if(path==null)path="";
  path=path.toLowerCase(Locale.ROOT);
  // When a player is clicked, an unrelated top-level site switch is a
  // common ad hijack. The original page remains available in its tab.
  return true;
 }
 /** Stop known ad/tracker landing pages even when a click attempts top-level navigation. */
 static boolean shouldBlockNavigation(Uri uri,boolean ads){
  if(!ads||uri==null)return false;
  String scheme=uri.getScheme(),host=uri.getHost();
  if(host==null||(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme)))return false;
  host=host.toLowerCase(Locale.ROOT);
  if(matches(host,MEDIA_HOSTS)||matches(host,TRUSTED_SOCIAL))return false;
  return matches(host,AD_HOSTS)||blockedDomain(host)||filterDomain(host);
 }
 static boolean shouldBlock(WebResourceRequest request,String pageUrl,boolean ads,boolean trackers){
  if(request==null||request.isForMainFrame()||(!ads&&!trackers))return false;
  Uri url=request.getUrl();
  if(url==null)return false;
  String scheme=url.getScheme();
  if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))return false;
  String host=url.getHost();
  if(host==null)return false;
  host=host.toLowerCase(Locale.ROOT);
  if(matches(host,MEDIA_HOSTS))return false;
  String pageHost=null;
  try{pageHost=Uri.parse(pageUrl).getHost();}catch(Exception ignored){}
  if(pageHost!=null)pageHost=pageHost.toLowerCase(Locale.ROOT);
  // Broad hosts rules must not disable first-party sites, their login flows or players.
  boolean firstParty=pageHost!=null&&(host.equals(pageHost)||host.endsWith("."+pageHost)||pageHost.endsWith("."+host));
  String path=url.getEncodedPath();
  if(path==null)path="";
  path=path.toLowerCase(Locale.ROOT);
  String query=url.getEncodedQuery();
  if(query!=null&&query.length()<1000)path+="?"+query.toLowerCase(Locale.ROOT);
  if(ads&&(blockedPath(host,path)||isExplicitAdEndpoint(path)))return true;
  if(firstParty)return false;
  if(matches(host,TRUSTED_SOCIAL))return false;
  return (ads&&(matches(host,AD_HOSTS)||blockedDomain(host)||filterDomain(host)))
    ||(trackers&&matches(host,TRACKERS));
 }
 private static boolean isExplicitAdEndpoint(String path){
  // Narrow, ad-only endpoints on otherwise trusted hosts.
  return path.startsWith("/pagead/")||path.startsWith("/ads/")||
    path.startsWith("/adserver/")||path.startsWith("/adservice/")||
    path.startsWith("/advertising/")||path.startsWith("/ad_delivery/")||
    path.startsWith("/ad-delivery/")||path.startsWith("/preroll/")||
    path.startsWith("/midroll/")||path.startsWith("/vast/")||
    path.startsWith("/vmap/");
 }
 static String cosmeticScript(){
  return "(function(){"+
   "var id='mh-browser-ad-css',old=document.getElementById(id);"+
   "if(!window.MHAdBlockEnabled){if(old)old.remove();return;}"+
   "if(old)return;"+
   "var css='"+
    "ins.adsbygoogle,.adsbygoogle,[data-ad-slot],[data-ad-client],"+
    "[id^=\\\"google_ads_iframe\\\"],[id^=\\\"div-gpt-ad\\\"],"+
    ".ad-banner,.ad-container,.advertisement,.advertising-banner,"+
    ".google-auto-placed,.adsense,.adsbygoogle-noablate,"+
    "ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,"+
    "ytd-in-feed-ad-layout-renderer,yt-ad-slot-renderer,"+
    "ytd-rich-item-renderer:has(yt-ad-slot-renderer),"+
    "ytd-item-section-renderer:has(ytd-promoted-video-renderer),"+
    "#player-ads,#masthead-ad,.ytp-ad-overlay-container,"+
    "[data-testid=\\\"placementTracking\\\"],"+
    "iframe[src*=\\\"doubleclick.net\\\"],iframe[src*=\\\"googlesyndication.com\\\"]"+
   "{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;}';"+
   "var el=document.createElement('style');el.id=id;el.textContent=css;"+
   "(document.head||document.documentElement).appendChild(el);"+
   "})()";
 }
}
