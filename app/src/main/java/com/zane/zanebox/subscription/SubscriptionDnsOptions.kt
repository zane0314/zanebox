package com.zane.zanebox.subscription

import com.zane.zanebox.data.AppData
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Applies the original serverDnsResolver option to dialing node hostnames, not subscription fetching. */
object SubscriptionDnsOptions {
    fun server(address:String,tag:String):JSONObject {
        val value=address.trim();val result=JSONObject().put("tag",tag)
        if(value=="local") return result.put("type","local")
        val uri=URI(if(value.contains("://")) value else "udp://$value")
        require(uri.host!=null && uri.userInfo==null && uri.fragment==null) { "节点 DNS 地址无效" }
        val type=when(uri.scheme) { "udp","tcp","tls","https","quic" -> uri.scheme;"h3" -> "http3";else -> error("节点 DNS 协议不支持") }
        result.put("type",type).put("server",uri.host).put("detour","direct")
        if(uri.port>=0) { require(uri.port in 1..65535);result.put("server_port",uri.port) }
        if(type in listOf("https","http3")) result.put("path",uri.rawPath.ifBlank { "/dns-query" })
        if(!uri.host.contains(':') && uri.host.split('.').any { it.toIntOrNull()==null }) result.put("domain_resolver","dns-direct")
        return result
    }
    fun apply(root:JSONObject,data:AppData) {
        val resolvers=data.groups.associate { it.id to JSONObject(it.options).optString("serverDnsResolver").trim() }.filterValues { it.isNotBlank() }
        val definitions=mutableMapOf<Long,JSONObject>()
        if(resolvers.isEmpty()) return
        val dns=root.optJSONObject("dns") ?: JSONObject().also { root.put("dns",it) }
        val servers=dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers",it) }
        val used=mutableSetOf<Long>()
        listOfNotNull(root.optJSONArray("outbounds"),root.optJSONArray("endpoints")).forEach { array -> for(i in 0 until array.length()) {
            val outbound=array.getJSONObject(i);val id=Regex("node-([0-9]+)(?:-.*)?").matchEntire(outbound.optString("tag"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val groupId=data.nodes.find { it.id==id }?.groupId ?: continue
            if(groupId !in resolvers) continue
            val host=outbound.optString("server")
            val hasDomain=host.isNotBlank() && !host.contains(':') && host.split('.').any { it.toIntOrNull()==null }
            val peerDomain=outbound.optJSONArray("peers")?.let { peers -> (0 until peers.length()).any { val address=peers.getJSONObject(it).optString("address");address.isNotBlank() && !address.contains(':') && address.split('.').any { part -> part.toIntOrNull()==null } } } ?: false
            if(hasDomain || peerDomain) { val definition=definitions.getOrPut(groupId) { server(resolvers.getValue(groupId),"subscription-dns-$groupId") };outbound.put("domain_resolver",definition.getString("tag"));used.add(groupId) }
        } }
        used.forEach { servers.put(definitions.getValue(it)) }
    }
}
