package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import org.json.JSONArray
import org.json.JSONObject

internal data class RulePreview(val title:String,val subtitle:String,val count:Int?,val status:String,val sample:List<String>)

internal fun buildRulePreviews(data:AppData,geoPresent:(String)->Boolean={false},builtinText:(String)->String={""},installed:Set<String>?=null):List<RulePreview> = buildList {
    data.rules.sortedBy{it.order}.forEach { rule->
        val json=runCatching{JSONObject(rule.advanced)}.getOrNull()
        val sets=json?.opt("rule_set")?.let{value->when(value){is JSONArray->(0 until value.length()).map{value.optString(it)};JSONObject.NULL->emptyList();else->listOf(value.toString())}}.orEmpty()
        val refs=(sets+rule.domains.split(Regex("[\\s,]+" )).filter{it.startsWith("geosite:") || it.startsWith("geoip:")}).filter{it.isNotBlank()}.distinct()
        refs.forEach { ref->
            val geo=ref.substringBefore(':').takeIf{it in listOf("geoip","geosite")}
            val asset=if(geo==null)"规则集引用，运行加载未验证" else if(geoPresent(geo))"本地数据库存在，运行加载未验证" else "本地数据库尚未就绪"
            add(RulePreview(ref,"${rule.name} · 目标：${rule.outbound}",null,"${if(rule.enabled)"已启用" else "已停用"} · $asset",emptyList()))
        }
    }
    val keys=smartPolicyKeys(data)
    keys.forEach { key->
        val stored=data.settings["smartRules.$key"]
        val url=data.setting("smartUrl.$key")
        val bundled=if(url.isBlank())builtinText(key) else ""
        val text=stored ?: bundled
        val builtin=bundled.isNotBlank() && (stored==null || isBuiltinSmartRuleText(stored,bundled))
        val lines=text.lineSequence().map{it.trim()}.filter{it.isNotBlank() && !it.startsWith('#') && !it.startsWith("//")}.toList()
        val source=when {
            builtin->"内置规则文本已就绪"
            text.isNotBlank() && url.isNotBlank()->"已保存远程规则正文"
            text.isNotBlank()->"已保存自定义规则正文"
            url.isNotBlank()->"仅配置 URL，正文未预览"
            stored!=null->"规则文本为空"
            else->"未配置规则文本"
        }
        val state=if(smartTarget(data,key)=="off")"策略关闭" else if(data.setting("routeMode","rule")!="rule")"当前路由模式不使用此策略" else "策略已配置，运行加载未验证"
        val count=if(text.isNotBlank() && ConfigBuilder.ruleSetFormat(url)=="source")runCatching{JSONObject(text).getJSONArray("rules").length()}.getOrNull() else if(url.isNotBlank() && text.isBlank())null else lines.size
        val packages=effectiveSmartPackages(data,key,installed)
        add(RulePreview(key,"智能应用规则${if(url.isBlank())"" else " · $url"} · ${packages.size} 个有效应用",count,"$source · $state",lines.take(5)))
    }
}
