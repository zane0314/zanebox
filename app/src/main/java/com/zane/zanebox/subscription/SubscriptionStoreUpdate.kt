package com.zane.zanebox.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.ZaneStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

suspend fun SubscriptionUpdater.update(store:ZaneStore,group:Group,context:Context,connected:Boolean,automatic:Boolean=false):Group = withContext(Dispatchers.IO) {
    val options=SubscriptionOptions.parse(group.options)
    require(group.subscriptionUrl.isNotBlank()) { "此组没有订阅 URL" }
    require(!options.updateWhenConnectedOnly || connected) { "此订阅只在代理已连接时更新，已保留原节点" }
    if(automatic) require(SubscriptionPlan.shouldUpdate(group,System.currentTimeMillis(),connected)) { "此订阅尚未到自动更新时间" }
    val request=UUID.randomUUID().toString()
    val started=store.update { begin(it,group,request,System.currentTimeMillis()) }
    val baseline=started.groups.first { it.id==group.id }
    try {
        val fetched=SubscriptionClient.fetch(baseline.subscriptionUrl,options.customUserAgent,started.settings)
        val parsed=prepare(SubscriptionParser.parse(fetched.body),options,started.bool("ipv6",false),started.setting("dnsStrategyServer",started.setting("domainStrategy"))) { host -> withTimeoutOrNull(10000) { resolve(context,host) } ?: error("节点 DNS 解析超时") }
        store.update { apply(it,baseline,parsed,fetched.userInfo,System.currentTimeMillis()) { store.nextId() } }.groups.first { it.id==group.id }
    } catch(e:CancellationException) {
        store.update { record(it,group.id,"cancelled",System.currentTimeMillis(),request=request) };throw e
    } catch(e:Exception) {
        val stale=e.message?.contains("过期")==true || e.message?.contains("已变更")==true
        store.update { record(it,group.id,if(stale) "stale" else "error",System.currentTimeMillis(),if(stale) "订阅响应已过期" else safeSubscriptionError(e),request=request) };throw e
    }
}

private suspend fun resolve(context:Context,host:String):List<String> = withContext(Dispatchers.IO) {
    val connectivity=context.getSystemService(ConnectivityManager::class.java)
    val networks=connectivity.allNetworks.filter { network -> connectivity.getNetworkCapabilities(network)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }==true }
    val network=networks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true } ?: networks.firstOrNull()
    (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).mapNotNull { it.hostAddress }
}

internal fun safeSubscriptionError(error:Exception):String = when(error) {
    is IllegalArgumentException -> error.message?.takeIf { !it.contains("://") }?.take(256) ?: "订阅内容或选项无效，已保留原节点"
    else -> "订阅更新失败（${error.javaClass.simpleName}），已保留原节点"
}
