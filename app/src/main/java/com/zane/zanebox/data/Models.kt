package com.zane.zanebox.data

import org.json.JSONArray
import org.json.JSONObject

data class Node(val id:Long, val groupId:Long, val name:String, val outbound:String, val shareLink:String="", val ping:Int=-1, val tx:Long=0, val rx:Long=0, val order:Int=0, val status:Int=0, val metadata:String="{}")
data class Group(val id:Long, val name:String, val subscriptionUrl:String="", val enabled:Boolean=true, val order:Int=0, val updatedAt:Long=0, val userInfo:String="", val frontProxy:Long=0, val landingProxy:Long=0, val options:String="{}")
data class RouteRule(val id:Long, val name:String, val domains:String="", val packages:String="", val ipCidrs:String="", val outbound:String="proxy", val enabled:Boolean=true, val order:Int=0, val advanced:String="{}", val prioritize:Boolean=false)
data class MergeGroup(val id:Long, val name:String, val nodeIds:List<Long> = emptyList(), val groupIds:List<Long> = emptyList(), val mode:String="selector", val selectedId:Long=0)
data class AppData(val nodes:List<Node> = emptyList(), val groups:List<Group> = emptyList(), val rules:List<RouteRule> = emptyList(), val merges:List<MergeGroup> = emptyList(), val settings:Map<String,String> = emptyMap()) {
    val selectedNodeId:Long get() = setting("selectedNodeId","0").toLongOrNull() ?: 0L
    val selectedGroupId:Long get() = setting("selectedGroupId","0").toLongOrNull() ?: 0L
    fun setting(key:String, default:String=SettingDefaults.value(key)):String = settings[key] ?: default
    fun bool(key:String, default:Boolean=SettingDefaults.value(key,"false").toBoolean()):Boolean = settings[key]?.toBooleanStrictOrNull() ?: default
    fun toJson():String = JSONObject().apply {
        put("format",1)
        put("nodes",JSONArray().apply { nodes.forEach { n -> put(JSONObject().apply { put("id",n.id);put("groupId",n.groupId);put("name",n.name);put("outbound",JSONObject(n.outbound));put("shareLink",n.shareLink);put("ping",n.ping);put("tx",n.tx);put("rx",n.rx);put("order",n.order);put("status",n.status);put("metadata",JSONObject(n.metadata)) }) } })
        put("groups",JSONArray().apply { groups.forEach { g -> put(JSONObject().apply { put("id",g.id);put("name",g.name);put("subscriptionUrl",g.subscriptionUrl);put("enabled",g.enabled);put("order",g.order);put("updatedAt",g.updatedAt);put("userInfo",g.userInfo);put("frontProxy",g.frontProxy);put("landingProxy",g.landingProxy);put("options",JSONObject(g.options)) }) } })
        put("rules",JSONArray().apply { rules.forEach { r -> put(JSONObject().apply { put("id",r.id);put("name",r.name);put("domains",r.domains);put("packages",r.packages);put("ipCidrs",r.ipCidrs);put("outbound",r.outbound);put("enabled",r.enabled);put("order",r.order);put("advanced",JSONObject(r.advanced));put("prioritize",r.prioritize) }) } })
        put("merges",JSONArray().apply { merges.forEach { m -> put(JSONObject().apply { put("id",m.id);put("name",m.name);put("nodeIds",JSONArray(m.nodeIds));put("groupIds",JSONArray(m.groupIds));put("mode",m.mode);put("selectedId",m.selectedId) }) } })
        put("settings",JSONObject(settings))
    }.toString()
    internal fun validate(previous:AppData?=null):AppData {
        val oldOutbounds=previous?.nodes?.associate { it.id to it.outbound }.orEmpty()
        require(nodes.size<=10000 && groups.size<=10000 && rules.size<=100000 && merges.size<=10000) { "数据超出记录数量限制" }
        fun ids(values:List<Long>) { require(values.all { it>0 } && values.toSet().size==values.size) { "无效或重复记录ID" } }
        ids(nodes.map { it.id });ids(groups.map { it.id });ids(rules.map { it.id });ids(merges.map { it.id })
        val groupIds=groups.map { it.id }.toSet()
        val nodeIds=nodes.map { it.id }.toSet()
        val mergeIds=merges.map { it.id }.toSet()
        nodes.forEach { require(it.groupId in groupIds) { "节点分组不存在" }; if(oldOutbounds[it.id]!=it.outbound) { val value=JSONObject(it.outbound);require(value.optString("type").isNotBlank()) { "节点缺少协议类型" } } }
        groups.forEach { require((it.frontProxy==0L || it.frontProxy in nodeIds)&&(it.landingProxy==0L || it.landingProxy in nodeIds)) { "前置或落地节点不存在" } }
        merges.forEach { require(it.nodeIds.all { id->id in nodeIds }&&it.groupIds.all { id->id in groupIds }) { "合并组成员不存在" };require(it.selectedId==0L || it.selectedId in nodeIds) { "合并组默认节点不存在" } }
        rules.forEach { rule ->
            val target=rule.outbound
            require(when { target in listOf("proxy","direct","block")->true;target.startsWith("node:")->target.substringAfter(':').toLongOrNull() in nodeIds;target.startsWith("group:")->target.substringAfter(':').toLongOrNull() in groupIds;target.startsWith("merge:")->target.substringAfter(':').toLongOrNull() in mergeIds;else->false }) { "规则 ${rule.name} 的目标不存在" }
        }
        require(selectedNodeId==0L || selectedNodeId in nodeIds) { "默认节点不存在" }
        require(selectedGroupId==0L || selectedGroupId in groupIds) { "默认节点组不存在" }
        return this
    }
    companion object {
        fun fromJson(text:String):AppData {
            require(text.toByteArray(Charsets.UTF_8).size<=64*1024*1024) { "数据超过64MiB" }
            val root=JSONObject(text);require(root.optInt("format",1)==1) { "不支持的数据版本" }
            fun <T> records(key:String,read:(JSONObject)->T):List<T> { val a=root.optJSONArray(key) ?: JSONArray();require(a.length()<=100000);return (0 until a.length()).map { read(a.getJSONObject(it)) } }
            fun longs(a:JSONArray?):List<Long> = if(a==null) emptyList() else (0 until a.length()).map { a.getLong(it) }
            val s=root.optJSONObject("settings") ?: JSONObject()
            return AppData(
                records("nodes") { Node(it.getLong("id"),it.getLong("groupId"),it.getString("name"),it.getJSONObject("outbound").toString(),it.optString("shareLink"),it.optInt("ping",-1),it.optLong("tx"),it.optLong("rx"),it.optInt("order"),it.optInt("status"),it.optJSONObject("metadata")?.toString() ?: "{}") },
                records("groups") { Group(it.getLong("id"),it.getString("name"),it.optString("subscriptionUrl"),it.optBoolean("enabled",true),it.optInt("order"),it.optLong("updatedAt"),it.optString("userInfo"),it.optLong("frontProxy"),it.optLong("landingProxy"),it.optJSONObject("options")?.toString() ?: "{}") },
                records("rules") { RouteRule(it.getLong("id"),it.getString("name"),it.optString("domains"),it.optString("packages"),it.optString("ipCidrs"),it.optString("outbound","proxy"),it.optBoolean("enabled",true),it.optInt("order"),it.optJSONObject("advanced")?.toString() ?: "{}",it.optBoolean("prioritize")) },
                records("merges") { MergeGroup(it.getLong("id"),it.getString("name"),longs(it.optJSONArray("nodeIds")),longs(it.optJSONArray("groupIds")),it.optString("mode","selector"),it.optLong("selectedId")) },
                s.keys().asSequence().associateWith { s.getString(it) }
            ).validate()
        }
    }
}

/** AnyBox 2.1.9 Theme.getTheme(1..22) colorPrimary values, in the original preference order. */
internal val themePresetColors=listOf("#f44336","#fb7299","#e91e63","#9c27b0","#673ab7","#3f51b5","#0066cc","#03a9f4","#00bcd4","#009688","#4caf50","#8bc34a","#cddc39","#ffeb3b","#ffc107","#ff9800","#ff5722","#795548","#9e9e9e","#607d8b","#2b2b2b","#00b96b")
