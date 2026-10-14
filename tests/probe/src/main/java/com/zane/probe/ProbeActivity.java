package com.zane.probe;
import android.app.Activity;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
public class ProbeActivity extends Activity {
 @Override public void onCreate(Bundle b) { super.onCreate(b);
 final String url=getIntent().getStringExtra("url"),nonce=getIntent().getStringExtra("nonce");
 new Thread(()->{String result;try { if(getIntent().hasExtra("expected_vpn"))awaitExpectedNetwork(nonce);ConnectivityManager connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network active=connectivity.getActiveNetwork();Log.i("ZaneProbe",nonce+" networkHandle="+(active==null?"none":active.getNetworkHandle())+" capabilities="+(active==null?"none":connectivity.getNetworkCapabilities(active))+" linkProperties="+(active==null?"none":connectivity.getLinkProperties(active)));URL target=new URL(url);Log.i("ZaneProbe",nonce+" DNS="+java.util.Arrays.toString(java.net.InetAddress.getAllByName(target.getHost())));HttpURLConnection c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("Connection","close");result=new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8);c.disconnect(); } catch(Exception e){Log.e("ZaneProbe",nonce+" request failed",e);result="ERROR:"+e.getClass().getSimpleName()+":"+e.getMessage();} Log.i("ZaneProbe",nonce+"="+result+" uid="+android.os.Process.myUid());runOnUiThread(this::finish);}).start(); }
 private void awaitExpectedNetwork(String nonce) throws Exception {
  final ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
  final boolean expected=getIntent().getBooleanExtra("expected_vpn",false);
  final String previous=getIntent().getStringExtra("previous_vpn_network");
  final CountDownLatch ready=new CountDownLatch(1);
  final AtomicReference<Network> matched=new AtomicReference<>();
  ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
   private void check(Network network,NetworkCapabilities capabilities) {
    if(network==null || capabilities==null || !network.equals(cm.getActiveNetwork()))return;
    boolean vpn=capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    if(vpn!=expected || (expected && previous!=null && !previous.isEmpty() && previous.equals(Long.toString(network.getNetworkHandle()))))return;
    matched.set(network);ready.countDown();
   }
   @Override public void onAvailable(Network network) {check(network,cm.getNetworkCapabilities(network));}
   @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities) {check(network,capabilities);}
  };
  boolean registered=false;
  try {
   Network active=cm.getActiveNetwork();
   if(active!=null)callback.onCapabilitiesChanged(active,cm.getNetworkCapabilities(active));
   if(ready.getCount()!=0) {
    cm.registerDefaultNetworkCallback(callback);registered=true;
    active=cm.getActiveNetwork();
    if(active!=null)callback.onCapabilitiesChanged(active,cm.getNetworkCapabilities(active));
    if(!ready.await(8,TimeUnit.SECONDS))throw new java.util.concurrent.TimeoutException("default network did not match expected_vpn="+expected+" previous_vpn_network="+previous);
   }
   Network network=matched.get();
   Log.i("ZaneProbe",nonce+" READY network="+network.getNetworkHandle()+" vpn="+expected);
  } finally {if(registered)cm.unregisterNetworkCallback(callback);}
 }

}
