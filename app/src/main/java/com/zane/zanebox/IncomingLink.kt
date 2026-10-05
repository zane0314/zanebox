package com.zane.zanebox

import com.zane.zanebox.subscription.ShareUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class IncomingLink(val text:String="",val subscriptionUrl:String="",val name:String="导入订阅") {
    companion object {
        fun parse(value:String):IncomingLink {
            require(value.length<=8*1024*1024) { "导入链接过长" }
            val schemeEnd=value.indexOf("://");require(schemeEnd>0) { "导入链接无协议" }
            val scheme=value.substring(0,schemeEnd).lowercase()
            val rest=value.substring(schemeEnd+3)
            val host=rest.substringBefore('?').substringBefore('/').substringBefore('#').lowercase()
            val query=ShareUri.parseQuery(rest.substringAfter('?',"").substringBefore('#').takeIf { rest.contains('?') })
            if((scheme=="sn" && host=="subscription") || (scheme=="clash" && host=="install-config") || (scheme=="zanebox" && host=="subscription")) {
                val url=query["url"]?.trim() ?: error("订阅链接缺少url")
                val target=url.toHttpUrlOrNull();require(target!=null && target.host.isNotBlank()) { "订阅地址必须为HTTP或HTTPS" }
                return IncomingLink(subscriptionUrl=url,name=query["name"].orEmpty().ifBlank { "导入订阅" }.take(256))
            }
            if(scheme=="zanebox" && host=="import")return IncomingLink(text=query["text"] ?: error("导入链接缺少text"))
            require(scheme in setOf("ss","vmess","vless","trojan","socks","socks4","socks5","http","https","hysteria","hysteria2","hy2","tuic","anytls","ssh","juicity","snell","shadowtls")) { "当前内核不支持此导入协议" }
            return IncomingLink(text=value)
        }
    }
}
