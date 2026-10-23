package com.zane.zanebox.subscription

import com.zane.zanebox.data.Node
import org.json.JSONObject

/** Repair only the old importer's exact encoded-username shape; user edits remain authoritative. */
fun repairLegacySocksCredentials(node:Node):Node = runCatching {
    val uri=ShareUri.parse(node.shareLink)
    if(uri.scheme !in listOf("socks","socks5")) return node
    val encoded=ShareUri.decode(uri.rawUserInfo ?: return node)
    val credentials=ShareUri.socksUserInfo(uri.rawUserInfo)
    if(credentials==encoded || !credentials.contains(':')) return node
    val out=JSONObject(node.outbound)
    if(out.optString("type")!="socks" || out.optString("version")!="5" ||
        out.optString("server")!=uri.host || out.optInt("server_port")!=(if(uri.port>0)uri.port else 1080) ||
        out.optString("username")!=encoded || (out.has("password") && out.opt("password")!="")) return node
    val parts=credentials.split(':',limit=2)
    node.copy(outbound=out.put("username",parts[0]).put("password",parts[1]).toString())
}.getOrDefault(node)
