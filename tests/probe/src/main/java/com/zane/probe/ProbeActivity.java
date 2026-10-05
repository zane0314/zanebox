package com.zane.probe;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
public class ProbeActivity extends Activity {
 @Override public void onCreate(Bundle b) { super.onCreate(b);
 final String url=getIntent().getStringExtra("url"),nonce=getIntent().getStringExtra("nonce");
 new Thread(()->{String result;try { URL target=new URL(url);Log.i("ZaneProbe",nonce+" DNS="+java.util.Arrays.toString(java.net.InetAddress.getAllByName(target.getHost())));HttpURLConnection c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("Connection","close");result=new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8);c.disconnect(); } catch(Exception e){result="ERROR:"+e.getClass().getSimpleName()+":"+e.getMessage();} Log.i("ZaneProbe",nonce+"="+result+" uid="+android.os.Process.myUid());runOnUiThread(this::finish);}).start(); }
}
