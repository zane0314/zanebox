package com.zane.zanebox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import libcore.*
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class NativePlatform(private val context:Context,private val vpn:VpnService?,private val selector:(String,String)->Unit) : BoxPlatformInterface,NB4AInterface,NetworkPlatformInterface,LocalDNSTransport {
    private val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile var tun:ParcelFileDescriptor?=null
    /** Android defaults VPN networks to metered on API 29+; the original app exposed this as meteredNetwork (default off). */
    @Volatile var metered:Boolean=false
    @Volatile var httpProxyPort:Int=0
    @Volatile var httpProxyBypass:List<String> = emptyList()
    @Volatile private var underlying:Network?=null
    private val available=ConcurrentHashMap<Network,LinkProperties>()
    private val monitors=ConcurrentHashMap<Long,InterfaceUpdateListener>()
    private val tokens=AtomicLong()
    private var registered=false
    private val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network) { cm.getLinkProperties(network)?.let { available[network]=it };underlying=network;selectNetwork() }
        override fun onLost(network:Network) { available.remove(network);if(underlying==network)underlying=null;selectNetwork() }
        override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties) { available[network]=properties;selectNetwork() }
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) { selectNetwork() }
    }
    fun initialize() {
        go.Seq.setContext(context)
        cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) }==true }.forEach { n -> cm.getLinkProperties(n)?.let { available[n]=it } }
        underlying=cm.activeNetwork?.takeIf { available.containsKey(it) }
            ?: available.keys.sortedWith(compareByDescending<Network> { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true }.thenByDescending { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }.thenBy { it.toString() }).firstOrNull()
        selectNetwork()
        val request=NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        if(Build.VERSION.SDK_INT>=31)cm.registerBestMatchingNetworkCallback(request,callback,android.os.Handler(android.os.Looper.getMainLooper()))
        else cm.requestNetwork(request,callback)
        registered=true
        val assets=java.io.File(context.filesDir,"core-assets").also { it.mkdirs() }
        Libcore.initCore(context.packageName+":bg",context.cacheDir.absolutePath,assets.absolutePath+"/",assets.absolutePath+"/",2048,true,this,this,this)
        Libcore.setNetworkPlatformInterface(this)
    }
    private fun selectNetwork() {
        vpn?.setUnderlyingNetworks(underlying?.let { arrayOf(it) })
        val name=underlying?.let { available[it]?.interfaceName }.orEmpty()
        val index=if(name.isBlank()) -1 else runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        monitors.values.forEach { listener -> runCatching { listener.updateDefaultInterface(name,index) } }
    }
    override fun autoDetectInterfaceControl(fd:Int) {
        if(tun!=null) check(vpn?.protect(fd)==true) { "代理套接字保护失败" }
        underlying?.let { network -> ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) } }
    }
    override fun useProcFS()=Build.VERSION.SDK_INT<29
    override fun findConnectionOwner(ipProtocol:Int,sourceAddress:String,sourcePort:Int,destinationAddress:String,destinationPort:Int):Int {
        if(Build.VERSION.SDK_INT<29) return -1
        return cm.getConnectionOwnerUid(ipProtocol,InetSocketAddress(InetAddress.getByName(sourceAddress),sourcePort),InetSocketAddress(InetAddress.getByName(destinationAddress),destinationPort))
    }
    override fun packageNameByUid(uid:Int):String = context.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    override fun uidByPackageName(packageName:String):Int = if(Build.VERSION.SDK_INT>=24) context.packageManager.getPackageUid(packageName,0) else context.packageManager.getApplicationInfo(packageName,0).uid
    override fun wifiState():String = runCatching {
        @Suppress("DEPRECATION") val info=(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
        val ssid=info.ssid.orEmpty().trim('"').takeUnless { it=="<unknown ssid>" }.orEmpty()
        ssid+","+info.bssid.orEmpty()
    }.getOrDefault(",")
    override fun useOfficialAssets()=false
    override fun selector_OnProxySelected(selectorTag:String,tag:String) { selector(selectorTag,tag) }
    override fun openTun(singTunOptionsJson:String,tunPlatformOptionsJson:String):Long {
        if(com.zane.zanebox.BuildConfig.DEBUG)java.io.File(context.cacheDir,"tun-options.json").writeText(singTunOptionsJson)
        val o=JSONObject(singTunOptionsJson)
        val builder=checkNotNull(vpn) { "仅代理模式没有VPN接口" }.Builder().setSession("zanebox").setMtu(o.optInt("MTU",1500))
        fun strings(key:String):List<String> { val a=o.optJSONArray(key) ?: return emptyList();return (0 until a.length()).map { a.getString(it) } }
        fun prefix(value:String,add:(String,Int)->Unit) { val parts=value.split('/');require(parts.size==2);add(parts[0],parts[1].toInt()) }
        strings("Inet4Address").forEach { prefix(it,builder::addAddress) }
        strings("Inet6Address").forEach { prefix(it,builder::addAddress) }
        val v4=strings("Inet4RouteAddress").ifEmpty { listOf("0.0.0.0/0") };val v6=strings("Inet6RouteAddress").ifEmpty { listOf("::/0") }
        val exclude4=strings("Inet4RouteExcludeAddress");val exclude6=strings("Inet6RouteExcludeAddress")
        val dnsServers=strings("DNSAddress").ifEmpty { listOf("172.19.0.2") }
        if(o.optBoolean("AutoRoute",true)) {
            val legacyExclude=Build.VERSION.SDK_INT<33
            (if(legacyExclude && exclude4.isNotEmpty()) RouteMath.subtract(v4,exclude4) else v4).forEach { prefix(it,builder::addRoute) }
            if(strings("Inet6Address").isNotEmpty()) (if(legacyExclude && exclude6.isNotEmpty()) RouteMath.subtract(v6,exclude6) else v6).forEach { prefix(it,builder::addRoute) }
            // The tun DNS address sits inside 172.16.0.0/12, so it must stay routed when LAN ranges are carved out.
            if(legacyExclude && (exclude4.isNotEmpty() || exclude6.isNotEmpty())) dnsServers.forEach { server -> builder.addRoute(server,if(server.contains(':')) 128 else 32) }
        }
        dnsServers.forEach { builder.addDnsServer(it) }
        if(Build.VERSION.SDK_INT>=29) builder.setMetered(metered)
        if(Build.VERSION.SDK_INT>=29 && httpProxyPort>0) builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1",httpProxyPort,httpProxyBypass))
        val included=strings("IncludePackage")
        if(included.isNotEmpty())(included+context.packageName).distinct().forEach { builder.addAllowedApplication(it) }
        strings("ExcludePackage").filterNot { it==context.packageName }.forEach { builder.addDisallowedApplication(it) }
        if(Build.VERSION.SDK_INT>=33) {
            (strings("Inet4RouteExcludeAddress")+strings("Inet6RouteExcludeAddress")).forEach { value -> val p=value.split('/');builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(p[0]),p[1].toInt())) }
        }
        builder.setBlocking(false)
        val next=builder.establish() ?: error("VPN接口创建失败，请重新授权")
        tun?.close();tun=next
        return next.fd.toLong()
    }
    fun closeTun() { tun?.close();tun=null }
    fun close() { closeTun();monitors.clear();if(registered) { cm.unregisterNetworkCallback(callback);registered=false } }
    override fun startDefaultInterfaceMonitor(listener:InterfaceUpdateListener):Long { val token=tokens.incrementAndGet();monitors[token]=listener;selectNetwork();return token }
    override fun closeDefaultInterfaceMonitor(token:Long) { monitors.remove(token) }
    override fun getInterfaces():NetworkInterfaceIterator {
        val list=JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { j ->
            val props=available.values.firstOrNull { it.interfaceName==j.name }
            val network=available.entries.firstOrNull { it.value.interfaceName==j.name }?.key
            val caps=network?.let { cm.getNetworkCapabilities(it) }
            NetworkInterface().apply {
                name=j.name;index=j.index;mtu=runCatching { j.mtu }.getOrDefault(1500)
                flags=(if(j.isUp) OsConstants.IFF_UP or OsConstants.IFF_RUNNING else 0) or (if(j.isLoopback) OsConstants.IFF_LOOPBACK else 0) or (if(j.supportsMulticast()) OsConstants.IFF_MULTICAST else 0)
                addresses=StringValues(j.interfaceAddresses.map { it.address.hostAddress!!.substringBefore('%')+"/"+it.networkPrefixLength })
                setDNSServer(StringValues(props?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()))
                type=when { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true -> Libcore.InterfaceTypeWIFI;caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true -> Libcore.InterfaceTypeCellular;caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true -> Libcore.InterfaceTypeEthernet;else -> Libcore.InterfaceTypeOther }
                metered=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)!=true
            }
        }
        return object:NetworkInterfaceIterator { var i=0;override fun hasNext()=i<list.size;override fun next()=list[i++];override fun length()=list.size }
    }
    override fun raw()=Build.VERSION.SDK_INT>=29
    override fun networkHandle():Long = if(Build.VERSION.SDK_INT>=23) underlying?.networkHandle ?: 0 else 0
    override fun lookup(ctx:ExchangeContext,network:String,domain:String) {
        try { val addresses=underlying?.getAllByName(domain) ?: InetAddress.getAllByName(domain);ctx.success(addresses.filter { (network!="ip4" || it is java.net.Inet4Address)&&(network!="ip6" || it is java.net.Inet6Address) }.joinToString("\n") { it.hostAddress!!.substringBefore('%') }) }
        catch(_:Exception) { ctx.errorCode(2) }
    }
    override fun exchange(ctx:ExchangeContext,message:ByteArray) { ctx.errorCode(2) }
}
class StringValues(private val values:List<String>) : StringIterator { private var i=0;override fun hasNext()=i<values.size;override fun next()=values[i++];override fun length()=values.size }
