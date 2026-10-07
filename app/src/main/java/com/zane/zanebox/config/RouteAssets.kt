package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import com.zane.zanebox.subscription.SubscriptionClient
import org.json.JSONObject

internal suspend fun routeAssetUrl(kind:String,data:AppData,proxy:java.net.Proxy?=null):String {
    require(kind in listOf("geoip","geosite"))
    val provider=data.setting("rulesProvider")
    if(provider=="4")return data.setting(if(kind=="geoip")"rulesGeoipUrl" else "rulesGeositeUrl").also { require(it.isNotBlank()) { "请配置资源 URL" } }
    val repo=when(provider) {
        "0"->"SagerNet/sing-$kind"
        "1"->"soffchen/sing-$kind"
        "2"->{require(kind=="geoip") { "此更新源只提供 GeoIP" };"Chocolate4U/Iran-sing-box"}
        "3"->{require(kind=="geoip") { "此更新源只提供 GeoIP" };"L11R/antizapret-sing-box-geo"}
        else->error("未知资源更新源")
    }
    val release=JSONObject(SubscriptionClient.fetch("https://api.github.com/repos/$repo/releases/latest",settings=data.settings,proxy=proxy).body)
    val assets=release.getJSONArray("assets")
    return (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name")=="$kind.db" }?.getString("browser_download_url") ?: error("更新源没有 $kind.db")
}

/** Read just the format/header; the runtime then validates the database with the existing native reader. */
internal fun geoAssetCode(kind:String,bytes:ByteArray):String {
    require(bytes.size in 16..64*1024*1024) { "资源为空或超过 64 MiB" }
    if(kind=="geoip") {
        val marker=byteArrayOf(0xab.toByte(),0xcd.toByte(),0xef.toByte())+"MaxMind.com".toByteArray()
        require((maxOf(0,bytes.size-131072)..bytes.size-marker.size).any { start->marker.indices.all { bytes[start+it]==marker[it] } }) { "文件不是 sing-box GeoIP 数据库" }
        return "cn"
    }
    require(kind=="geosite" && bytes[0]==0.toByte()) { "文件不是 sing-box Geosite 数据库" }
    var position=1
    fun unsigned():Long {
        var result=0L
        for(shift in 0..63 step 7) {
            require(position<bytes.size) { "Geosite 元数据不完整" };val b=bytes[position++].toInt() and 255
            require(shift<63 || b<=1) { "Geosite 元数据溢出" };result=result or ((b and 127).toLong() shl shift)
            if(b<128)return result
        }
        error("Geosite 元数据无效")
    }
    require(unsigned() in 1..100000) { "Geosite 没有有效规则" }
    val length=unsigned();require(length in 1..256 && position+length<=bytes.size) { "Geosite 规则名无效" }
    return bytes.copyOfRange(position,position+length.toInt()).toString(Charsets.UTF_8).also { require(it.matches(Regex("[a-zA-Z0-9_@.-]+"))) { "Geosite 规则名无效" } }
}

internal val builtinSmartRuleFiles=mapOf("speed" to listOf("Speed"),"youtube" to listOf("YouTube"),"telegram" to listOf("Telegram"),"netflix" to listOf("Netflix"),"disney" to listOf("Disney"),"tiktok" to listOf("TikTok"),"x" to listOf("Twitter"),"meta" to listOf("Instagram","Facebook"),"spotify" to listOf("Spotify"),"google" to listOf("Google"),"ai" to listOf("OpenAI"))

internal fun normalizeSmartTarget(value:String)=if(value.isBlank() || value.startsWith("region:"))"proxy" else value
internal fun smartTarget(data:AppData,key:String)=normalizeSmartTarget(data.setting("smart.$key.target","proxy"))

internal val unsupportedSmartRuleTypes=setOf("USER-AGENT","IP-ASN","OR")
internal fun builtinSmartRuleText(key:String,read:(String)->String):String = builtinSmartRuleFiles[key].orEmpty().joinToString("\n") {read("anybox-rules/$it.list")}
internal fun supportedBuiltinSmartRules(raw:String):String = raw.lines().filter {it.substringBefore(',').trim().uppercase() !in unsupportedSmartRuleTypes}.joinToString("\n")
internal fun isBuiltinSmartRuleText(value:String,bundled:String):Boolean {
    fun normalized(text:String)=text.replace("\r\n","\n").trim()
    return normalized(value)==normalized(bundled) || normalized(value)==normalized(supportedBuiltinSmartRules(bundled))
}

/** Missing defaults use bundled rules; an explicitly saved empty rule list stays empty. */
internal fun withBuiltinSmartRules(data:AppData,read:(String)->String):AppData {
    val defaults=builtinSmartRuleFiles.keys.filter { key->data.setting("smartUrl.$key").isBlank() }
        .mapNotNull {key->
            val stored=data.settings["smartRules.$key"]
            if(stored!=null && stored.isBlank())return@mapNotNull null
            val bundled=builtinSmartRuleText(key,read)
            if(stored!=null && !isBuiltinSmartRuleText(stored,bundled))null
            else supportedBuiltinSmartRules(bundled).let {compatible->if(stored==compatible)null else "smartRules.$key" to compatible}
        }.toMap()
    return if(defaults.isEmpty())data else data.copy(settings=data.settings+defaults)
}

internal fun smartPolicyKeys(data:AppData):List<String> {
    val available=builtinSmartRuleFiles.keys.toList()+(if(data.settings.keys.any{it=="smartRules.custom" || it=="smart.custom.target"})listOf("custom")else emptyList())+data.settings.keys.filter{it.startsWith("smartCustom.") && it.endsWith(".name")}.map{it.removePrefix("smartCustom.").removeSuffix(".name")}.sorted()
    return (data.setting("smartPolicyOrder").lines().filter{it in available}+available).distinct()
}
