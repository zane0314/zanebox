package com.zane.zanebox.subscription

import java.net.URLDecoder

/**
 * Share links in the wild often carry raw spaces, emoji, '%' or '|' that java.net.URI rejects.
 * Userinfo ends at the last '@' before the query so unescaped '@' and '/' in passwords survive.
 */
internal data class ShareUri(val scheme:String,val rawUserInfo:String?,val host:String,val port:Int,val rawPath:String,val rawQuery:String?,val rawFragment:String?) {
    val query:Map<String,String> get() = parseQuery(rawQuery)
    companion object {
        fun parse(raw:String):ShareUri {
            val schemeEnd=raw.indexOf("://");require(schemeEnd>0) { "链接缺少协议" }
            val scheme=raw.substring(0,schemeEnd).lowercase()
            var rest=raw.substring(schemeEnd+3)
            val fragment=rest.indexOf('#').takeIf { it>=0 }?.let { index -> rest.substring(index+1).also { rest=rest.substring(0,index) } }
            val query=rest.indexOf('?').takeIf { it>=0 }?.let { index -> rest.substring(index+1).also { rest=rest.substring(0,index) } }
            val at=rest.lastIndexOf('@')
            val userInfo=if(at>=0) rest.substring(0,at) else null
            val hostPart=if(at>=0) rest.substring(at+1) else rest
            val slash=hostPart.indexOf('/')
            val authority=if(slash>=0) hostPart.substring(0,slash) else hostPart
            val path=if(slash>=0) hostPart.substring(slash) else ""
            val host:String;val portText:String?
            if(authority.startsWith('[')) {
                val close=authority.indexOf(']');require(close>1) { "IPv6 地址无效" }
                host=authority.substring(1,close)
                portText=authority.substring(close+1).takeIf { it.isNotEmpty() }?.let { require(it.startsWith(':')) { "端口无效" };it.substring(1) }
            } else {
                val colon=authority.lastIndexOf(':')
                host=if(colon>=0) authority.substring(0,colon) else authority
                portText=if(colon>=0) authority.substring(colon+1) else null
            }
            require(host.isNotBlank() && host.none { it.isWhitespace() }) { "服务器无效" }
            val port=portText?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("端口无效") } ?: -1
            return ShareUri(scheme,userInfo,host,port,path,query,fragment)
        }
        fun decode(value:String):String = try { URLDecoder.decode(value.replace("+","%2B"),"UTF-8") } catch(_:IllegalArgumentException) { value }
        fun parseQuery(raw:String?):Map<String,String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=',"")) }
    }
}
