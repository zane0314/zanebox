package com.zane.zanebox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.zane.zanebox.data.AppData
import com.zane.zanebox.subscription.SubscriptionDnsOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import libcore.SubscriptionDNSPlatform
import libcore.SubscriptionDNSQuery
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Subscription node resolution stays on its specified resolver and the physical network. */
object SubscriptionDns {
    suspend fun lookup(context:Context,host:String,resolver:String,data:AppData):List<String> = withTimeoutOrNull(10000) {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val networks=cm.allNetworks.filter { network -> cm.getNetworkCapabilities(network)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }==true }
        val network=networks.firstOrNull { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true } ?: networks.firstOrNull()
        if(resolver.isBlank() || resolver=="local")return@withTimeoutOrNull systemLookup(network,host)
        requireNotNull(network) { "指定节点 DNS 没有可用的物理网络" }
        go.Seq.setContext(context.applicationContext)
        val query=Libcore.newSubscriptionDNSQuery(java.io.File(context.noBackupFilesDir,"protect_path").absolutePath,object:SubscriptionDNSPlatform {
            override fun autoDetectInterfaceControl(fd:Int) { ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) } }
        })
        try {
            val nodeStrategy=if(!data.bool("ipv6",false))"ipv4_only" else data.setting("dnsStrategyServer",data.setting("domainStrategy"))
            val directStrategy=if(!data.bool("ipv6",false))"ipv4_only" else data.setting("dnsStrategyDirect",data.setting("dnsStrategy","prefer_ipv4"))
            val directResolver=data.setting("dnsDirect","local")
            val server=SubscriptionDnsOptions.server(resolver,"subscription-query")
            val resolverHost=server.getString("server")
            val addresses=if(!server.has("domain_resolver"))listOf(resolverHost) else if(directResolver=="local" || directResolver.isBlank())family(systemLookup(network,resolverHost),directStrategy) else {
                // dns-direct bootstraps the selected resolver; only its own hostname uses the system.
                val direct=SubscriptionDnsOptions.server(directResolver,"bootstrap")
                val bootstrap=if(direct.has("domain_resolver"))family(systemLookup(network,direct.getString("server")),directStrategy) else listOf(direct.getString("server"))
                nativeLookup(query,SubscriptionDnsOptions.bootstrapped(direct,bootstrap.first()).toString(),resolverHost,directStrategy)
            }
            require(addresses.isNotEmpty()) { "指定节点 DNS 服务器域名没有解析结果" }
            var failure:Exception?=null
            for(address in addresses) {
                try { return@withTimeoutOrNull nativeLookup(query,SubscriptionDnsOptions.bootstrapped(server,address).toString(),host,nodeStrategy) }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { failure=e }
            }
            throw failure ?: IllegalArgumentException("指定节点 DNS 解析失败")
        } finally { query.cancel() }
    } ?: throw IllegalArgumentException("节点 DNS 解析超时")
    private fun family(addresses:List<String>,strategy:String):List<String> {
        val allowed=addresses.filter { (strategy!="ipv4_only" || !it.contains(':')) && (strategy!="ipv6_only" || it.contains(':')) }
        return if(strategy=="prefer_ipv6")allowed.sortedBy { !it.contains(':') } else allowed.sortedBy { it.contains(':') }
    }
    private suspend fun systemLookup(network:Network?,host:String):List<String> = suspendCancellableCoroutine { continuation ->
        val signal=CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        DnsResolver.getInstance().query(network,host,DnsResolver.FLAG_EMPTY,Executor { it.run() },signal,object:DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer:List<InetAddress>,rcode:Int) { continuation.resume(answer.mapNotNull { it.hostAddress?.substringBefore('%') }) }
            override fun onError(error:DnsResolver.DnsException) { continuation.resumeWithException(error) }
        })
    }
    private suspend fun nativeLookup(query:SubscriptionDNSQuery,server:String,host:String,strategy:String):List<String> = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { query.cancel() }
            launch(Dispatchers.IO) {
                try { continuation.resume(query.lookup(server,host,strategy).lineSequence().filter { it.isNotBlank() }.toList()) }
                catch(e:Exception) { continuation.resumeWithException(e) }
            }
        }
    }
}
