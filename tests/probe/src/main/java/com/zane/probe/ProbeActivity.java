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
 private static final int DNS_ID=0x5a17;
 @Override public void onCreate(Bundle b) { super.onCreate(b);
 final String url=getIntent().getStringExtra("url"),nonce=getIntent().getStringExtra("nonce"),dnsServer=getIntent().getStringExtra("dns_server");
 final boolean dnsMode=dnsServer!=null && !dnsServer.isEmpty();
 new Thread(()->{try { if(getIntent().hasExtra("expected_vpn"))awaitExpectedNetwork(nonce);
   if(dnsMode) {
    dnsProbe(dnsServer,nonce);
    Log.i("ZaneProbe",nonce+"=DNS_OK uid="+android.os.Process.myUid());
   } else {
    ConnectivityManager connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network active=connectivity.getActiveNetwork();Log.i("ZaneProbe",nonce+" networkHandle="+(active==null?"none":active.getNetworkHandle())+" capabilities="+(active==null?"none":connectivity.getNetworkCapabilities(active))+" linkProperties="+(active==null?"none":connectivity.getLinkProperties(active)));URL target=new URL(url);Log.i("ZaneProbe",nonce+" DNS="+java.util.Arrays.toString(java.net.InetAddress.getAllByName(target.getHost())));HttpURLConnection c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("Connection","close");int code=c.getResponseCode();Log.i("ZaneProbe",nonce+" HTTP="+code);String result=new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8);c.disconnect();Log.i("ZaneProbe",nonce+"="+result+" uid="+android.os.Process.myUid());
   }
  } catch(Exception e){Log.e("ZaneProbe",nonce+(dnsMode?" DNS failed":" request failed"),e);Log.i("ZaneProbe",nonce+"=ERROR:"+e.getClass().getSimpleName()+":"+e.getMessage()+" uid="+android.os.Process.myUid());} runOnUiThread(this::finish);}).start(); }
 /** Standard UDP DNS A query for example.com; validates ID, QR, rcode and answer count. */
 private void dnsProbe(String server,String nonce) throws Exception {
  byte[] query=dnsQuery();
  java.net.DatagramSocket socket=new java.net.DatagramSocket();
  try {
   socket.setSoTimeout(5000);
   java.net.InetAddress address=java.net.InetAddress.getByName(server);
   Log.i("ZaneProbe",nonce+" DNS_QUERY server="+server+" name=example.com type=A");
   socket.send(new java.net.DatagramPacket(query,query.length,address,53));
   byte[] buffer=new byte[1500];java.net.DatagramPacket response=new java.net.DatagramPacket(buffer,buffer.length);socket.receive(response);
   int length=response.getLength();if(length<12)throw new IllegalStateException("short DNS response");
   int id=((buffer[0]&0xff)<<8)|(buffer[1]&0xff);int flags=((buffer[2]&0xff)<<8)|(buffer[3]&0xff);int answers=((buffer[6]&0xff)<<8)|(buffer[7]&0xff);
   if(id!=DNS_ID)throw new IllegalStateException("DNS transaction id mismatch");
   if((flags&0x8000)==0)throw new IllegalStateException("DNS response QR not set");
   if((flags&0x000f)!=0)throw new IllegalStateException("DNS rcode="+(flags&0x000f));
   if(answers<=0)throw new IllegalStateException("DNS answer count 0");
  } finally {socket.close();}
 }
 private static byte[] dnsQuery() {
  byte[] query=new byte[29];int i=0;
  query[i++]=(byte)(DNS_ID>>8);query[i++]=(byte)DNS_ID;query[i++]=1;query[i++]=0;query[i++]=0;query[i++]=1;
  for(int n=0;n<6;n++)query[i++]=0;
  for(String label:new String[]{"example","com"}) {query[i++]=(byte)label.length();for(byte value:label.getBytes(StandardCharsets.US_ASCII))query[i++]=value;}
  query[i++]=0;query[i++]=0;query[i++]=1;query[i++]=0;query[i++]=1;
  return query;
 }
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
