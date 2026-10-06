package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.RouteRule

/** NekoBox ProfileManager defaults; broad country rules run after app policies. */
internal fun factoryRouteRules(country:String):List<RouteRule> {
    val rules=mutableListOf(
        RouteRule(1,"屏蔽 QUIC",outbound="block",enabled=false,advanced="""{"port":[443],"network":["udp"]}""",prioritize=true),
        RouteRule(2,"屏蔽广告",domains="geosite:category-ads-all",outbound="block",enabled=false,prioritize=true),
        RouteRule(3,"中国 Play 服务",domains="googleapis.cn",outbound="proxy",enabled=false,prioritize=true),
    )
    val countries=listOf("cn" to "中国")+if(country=="CN")emptyList()else listOf("ir" to "伊朗","ru" to "俄罗斯")
    countries.forEach { (code,name)->
        rules+=RouteRule(rules.size+1L,"绕过${name}域名",domains="geosite:$code",outbound="direct",enabled=false)
        // Existing ConfigBuilder expands geoip/geosite references into native local rule sets.
        rules+=RouteRule(rules.size+1L,"绕过${name} IP",advanced="""{"rule_set":["geoip:$code"]}""",outbound="direct",enabled=false)
    }
    return rules.mapIndexed{index,rule->rule.copy(order=index)}
}

internal fun withFactoryRouteDefaults(data:AppData,country:String):AppData {
    if(data.setting("factoryRouteDefaultsVersion")=="1")return data
    val settings=data.settings+("factoryRouteDefaultsVersion" to "1")
    if(data.rules.isNotEmpty())return data.copy(settings=settings)
    val templates=factoryRouteRules(country)
    val last=(data.nodes.map{it.id}+data.groups.map{it.id}+data.merges.map{it.id}).maxOrNull() ?:0
    require(last<=Long.MAX_VALUE-templates.size){"记录 ID 已耗尽"}
    return data.copy(rules=templates.mapIndexed{index,rule->rule.copy(id=last+index+1)},settings=settings)
}
