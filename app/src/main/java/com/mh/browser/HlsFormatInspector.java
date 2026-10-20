package com.mh.browser;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads public, non-DRM HLS master playlists and exposes actual advertised resolutions. */
public final class HlsFormatInspector {
 private HlsFormatInspector(){}
 public static final class Variant {
  public final String url,label;
  public final int height;
  Variant(String url,String label,int height){this.url=url;this.label=label;this.height=height;}
 }
 public interface Callback {
  void ready(List<Variant> formats);
  void failed(String detail);
 }
 public static void inspect(Context context,String playlist,String referer,String agent,Callback callback){
  new Thread(()->{
   HttpURLConnection conn=null;
   try{
    URL target=new URL(playlist);
    if(!target.getProtocol().matches("https?"))throw new IllegalArgumentException("Unsupported playlist protocol");
    conn=(HttpURLConnection)target.openConnection();
    conn.setConnectTimeout(9000);conn.setReadTimeout(12000);
    conn.setRequestProperty("User-Agent",agent==null||agent.isEmpty()?"Mozilla/5.0":agent);
    if(referer!=null&&referer.startsWith("http"))conn.setRequestProperty("Referer",referer);
    String cookies=CookieManager.getInstance().getCookie(playlist);
    if(cookies!=null&&!cookies.isEmpty())conn.setRequestProperty("Cookie",cookies);
    if(conn.getResponseCode()!=200)throw new IllegalStateException("Playlist HTTP "+conn.getResponseCode());
    URL resolved=conn.getURL();
    if(!resolved.getProtocol().matches("https?"))throw new IllegalStateException("Invalid redirect");
    BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));
    List<Variant> variants=new ArrayList<>();
    Set<String> seen=new HashSet<>();
    String line,previous=null;int lines=0;
    boolean hls=false;
    while((line=reader.readLine())!=null&&++lines<5000){
     if(line.length()>8192)continue;
     line=line.trim();
     if(line.equals("#EXTM3U"))hls=true;
     if(line.startsWith("#EXT-X-STREAM-INF:")){previous=line;continue;}
     if(previous!=null&&!line.isEmpty()&&!line.startsWith("#")){
      int height=0;
      java.util.regex.Matcher m=java.util.regex.Pattern.compile("RESOLUTION=([0-9]+)x([0-9]+)").matcher(previous);
      if(m.find())height=Integer.parseInt(m.group(2));
      URL variant=new URL(resolved,line);
      if(variant.getProtocol().matches("https?")&&seen.add(variant.toString())){
       String label=height>0?"Video • "+height+"p (HLS)":"Video • adaptive HLS stream";
       variants.add(new Variant(variant.toString(),label,height));
      }
      previous=null;
     }
    }
    reader.close();
    if(!hls)throw new IllegalStateException("Not an HLS playlist");
    if(variants.isEmpty())variants.add(new Variant(playlist,"Video • original HLS quality",0));
    variants.sort((a,b)->Integer.compare(b.height,a.height));
    callback.ready(variants);
   }catch(Exception e){callback.failed(e.getMessage()==null?"Playlist unavailable":e.getMessage());}
   finally{if(conn!=null)conn.disconnect();}
  },"mh-hls-formats").start();
 }
}
