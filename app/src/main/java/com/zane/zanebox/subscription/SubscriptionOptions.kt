package com.zane.zanebox.subscription

import com.zane.zanebox.data.NodeIdentity
import org.json.JSONObject
import java.util.regex.Pattern

data class SubscriptionOptions(
    val autoUpdate:Boolean=false,
    val autoUpdateDelay:Int=1440,
    val updateWhenConnectedOnly:Boolean=false,
    val customUserAgent:String="",
    val filterMode:Int=0,
    val filterRegex:String="",
    val deduplication:Boolean=false,
    val forceResolve:Boolean=false,
    val serverDnsResolver:String=""
) {
    fun select(nodes:List<ParsedNode>):List<ParsedNode> {
        val pattern=if(filterMode!=0 && filterRegex.isNotBlank()) Pattern.compile(filterRegex) else null
        val filtered=if(pattern==null) nodes else nodes.filter { node -> val match=pattern.matcher(node.name).find();if(filterMode==1) match else !match }
        val selected=if(deduplication) filtered.distinctBy { NodeIdentity.key(it.outbound) } else filtered
        require(selected.isNotEmpty()) { "订阅过滤后没有有效节点，已保留原节点" }
        return selected
    }
    companion object {
        fun parse(text:String):SubscriptionOptions {
            val o=JSONObject(text)
            val result=SubscriptionOptions(o.optBoolean("autoUpdate"),o.optInt("autoUpdateDelay",1440),o.optBoolean("updateWhenConnectedOnly"),o.optString("customUserAgent"),o.optInt("filterMode"),o.optString("filterRegex"),o.optBoolean("deduplication"),o.optBoolean("forceResolve"),o.optString("serverDnsResolver").trim())
            require(result.autoUpdateDelay in 1..525600) { "订阅间隔必须为 1..525600 分钟" }
            require(result.filterMode in 0..2) { "订阅过滤模式无效" }
            require(result.filterRegex.length<=4096) { "订阅过滤正则过长" }
            if(result.filterMode!=0 && result.filterRegex.isNotBlank()) Pattern.compile(result.filterRegex)
            require(result.customUserAgent.length<=1024 && result.customUserAgent.all { it.code in 32..126 }) { "订阅 User-Agent 必须为单行 ASCII 文本" }
            require(result.serverDnsResolver.length<=2048 && !result.serverDnsResolver.contains('\n') && !result.serverDnsResolver.contains('\r')) { "节点 DNS 解析器无效" }
            if(result.serverDnsResolver.isNotBlank()) SubscriptionDnsOptions.server(result.serverDnsResolver,"validation")
            return result
        }
        /** Include all user fields, including future ones; exclude our operational journal. */
        fun signature(text:String):String = JSONObject(text).apply { remove("subscriptionRuntime");remove("subscriptionLastUpdated") }.let { NodeIdentity.key(JSONObject().put("options",it).toString()) }
    }
}
