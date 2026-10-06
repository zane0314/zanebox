package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import com.zane.zanebox.subscription.SubscriptionDnsOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class Purpose { MAIN, TEST, EXPORT }

object ConfigBuilder {
    internal fun normalizeTransportHost(outbound:JSONObject) {
        val transport=outbound.optJSONObject("transport") ?: return
        if(transport.optString("type") !in listOf("httpupgrade","xhttp") || transport.optString("host").isNotBlank())return
        val headers=transport.optJSONObject("headers") ?: return
        val host=when(val value=headers.opt("Host")) { is String->value;is JSONArray->value.optString(0);else->"" }
        if(host.isNotBlank()) { transport.put("host",host);headers.remove("Host");if(headers.length()==0)transport.remove("headers") }
    }
    fun ruleSetFormat(url:String):String {
        val path=runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return when { path.endsWith(".srs",true)->"binary";path.endsWith(".json",true)->"source";else->"" }
    }
    private fun smartServices(data:AppData)=smartPolicyKeys(data)
    fun ruleCompatibilityWarnings(service:String,rules:String):List<String> = rules.lines().map {it.substringBefore(',').trim().uppercase()}.filter {it in unsupportedSmartRuleTypes}.distinct().map {"$service：此规则格式暂不支持 $it；该类条目保留但不参与匹配，其余有效规则仍生效"}
    fun warnings(data:AppData):List<String> = targetWarnings(data)+smartServices(data).flatMap {ruleCompatibilityWarnings(it,data.setting("smartRules.$it"))}
    fun targetWarnings(data: AppData): List<String> = smartServices(data).flatMap { service ->
        val target=smartTarget(data,service)
        val missing=if(target=="auto" && smartTargetNodeIds(data,target).isEmpty())listOf("$service: 分流目标没有可用节点，当前使用代理") else emptyList()
        missing
    }

    fun smartTargetNodeIds(data:AppData,target:String):List<Long> {
        val active=data.nodes.filter { n->data.groups.any { it.id==n.groupId && it.enabled } && JSONObject(n.outbound).optString("type")!="custom" }
        val choice=normalizeSmartTarget(target)
        return when {
            choice=="auto"->active.filter {n->data.groups.any{it.id==n.groupId && it.subscriptionUrl.isNotBlank()}}
            choice.startsWith("node:")->active.filter { it.id==choice.substringAfter(':').toLongOrNull() }
            choice.startsWith("group:")->active.filter { it.groupId==choice.substringAfter(':').toLongOrNull() }
            choice.startsWith("merge:")->data.merges.firstOrNull { it.id==choice.substringAfter(':').toLongOrNull() }?.let { m->active.filter { it.id in m.nodeIds || it.groupId in m.groupIds } }.orEmpty()
            choice=="proxy"->active.filter { it.id==data.selectedNodeId }.ifEmpty { active.take(1) }
            else->emptyList()
        }.map { it.id }
    }

    fun build(data: AppData, purpose: Purpose = Purpose.MAIN, testNodeId: Long = 0, runtimeSecret: String = ""): String {
        val testing = purpose == Purpose.TEST
        val mode=data.setting("routeMode", "rule"); require(mode in listOf("rule","global","direct")) { "路由模式无效" }
        val serviceMode=data.setting("serviceMode", "vpn");require(serviceMode in listOf("vpn","proxy")) { "服务模式无效" }
        val fakeDns=!testing && mode!="direct" && data.bool("fakeDns",data.bool("fakeDNS", true))
        val eligible=if(testing) data.nodes else data.nodes.filter { n -> data.groups.any { it.id==n.groupId && it.enabled } }
        require(eligible.isNotEmpty()) { "没有启用分组中的可用节点" }
        val auto=!testing && data.bool("homeAutoSelect")
        val homeEligible=if(auto)eligible.filter { JSONObject(it.outbound).optString("type")!="custom" } else eligible
        require(homeEligible.isNotEmpty()) { "没有可自动选择的节点" }
        val chosen=if(testing) eligible.find { it.id==testNodeId } ?: error("测速节点不存在") else homeEligible.find { it.id==data.selectedNodeId } ?: homeEligible.find { it.groupId==data.selectedGroupId } ?: homeEligible.first()
        if(JSONObject(chosen.outbound).optString("type")=="custom") return customConfig(JSONObject(chosen.outbound).getJSONObject("config"),data,purpose,runtimeSecret)
        val required = mutableSetOf<Long>()
        fun include(id: Long) { if (!required.add(id)) return; val n=data.nodes.find { it.id==id } ?: error("代理链引用不存在的节点 $id"); val bean=JSONObject(n.outbound);if(bean.optString("type")=="chain") { val ids=bean.getJSONArray("node_ids");for(i in 0 until ids.length()) include(ids.getLong(i)) }; if(bean.has("detour")) { val tag=bean.getString("detour");data.nodes.find { it.groupId==n.groupId && JSONObject(it.metadata).optString("sourceTag")==tag }?.let { include(it.id) } }; val g=data.groups.find { it.id==n.groupId }; if(g!=null) { if(g.frontProxy>0) include(g.frontProxy); if(g.landingProxy>0) include(g.landingProxy) } }
        if(testing) include(testNodeId) else eligible.forEach { include(it.id) }
        val nodes = data.nodes.filter { it.id in required && JSONObject(it.outbound).optString("type")!="custom" }
        require(nodes.isNotEmpty()) { "没有可用节点" }
        require(!testing || nodes.any { it.id == testNodeId }) { "测速节点不存在" }
        val out = JSONArray()
        val endpoints = JSONArray()
        fun addOutbound(item: JSONObject) {
            normalizeTransportHost(item)
            if(item.optString("type")=="hysteria2")item.optJSONArray("server_ports")?.takeIf { it.length()>0 }?.let { ports ->
                com.zane.zanebox.subscription.SubscriptionParser.applyHysteriaPorts(item,(0 until ports.length()).joinToString(","){ports.getString(it)})
            }
            if(item.optString("type")=="ssh" && item.has("username")) {
                if(!item.has("user"))item.put("user",item.get("username"))
                item.remove("username")
            }
            if(data.bool("globalAllowInsecure",false)) item.optJSONObject("tls")?.put("insecure",true)
            if(data.bool("enableTLSFragment",false)) item.optJSONObject("tls")?.let { tls ->
                if(tls.optBoolean("enabled") && !tls.has("fragment") && !tls.optBoolean("record_fragment")) tls.put("fragment",true)
            }
            if(data.bool("muxEnabled",false) && item.optString("type") in listOf("shadowsocks","vmess","vless","trojan") && !item.has("multiplex")) {
                val streams=data.setting("muxMaxStreams","8").toInt();require(streams>0) { "Mux流数量无效" };val protocol=data.setting("muxProtocol","h2mux");require(protocol in listOf("h2mux","smux","yamux")) { "Mux协议无效" }
                item.put("multiplex",JSONObject().put("enabled",true).put("protocol",protocol).put("max_streams",streams).put("padding",data.bool("muxPadding",false)))
            }
            if(item.optString("type")=="wireguard") endpoints.put(wireguardEndpoint(item)) else out.put(item)
        }
        fun flatten(id:Long, active:MutableSet<Long> = mutableSetOf()):List<JSONObject> {
            require(active.size<64 && active.add(id)) { "代理链引用循环或过深" }
            val node=data.nodes.find { it.id==id } ?: error("代理链节点不存在: $id");val bean=JSONObject(node.outbound)
            val result=if(bean.optString("type")=="chain") { val ids=bean.getJSONArray("node_ids");require(ids.length()>0) { "代理链为空" };(0 until ids.length()).flatMap { flatten(ids.getLong(it),active) } } else { require(bean.optString("type")!="custom") { "完整自定义配置不能嵌入代理链" };listOf(bean) }
            active.remove(id);return result
        }
        nodes.forEach { n ->
            if(JSONObject(n.outbound).optString("type")=="chain") {
                val g=data.groups.find { it.id==n.groupId }?.takeUnless { n.id==it.frontProxy || n.id==it.landingProxy };val chain=flatten(n.id).toMutableList()
                if(g!=null && g.landingProxy>0) chain.add(0,flatten(g.landingProxy).first().also { require(JSONObject(data.nodes.first { it.id==g.landingProxy }.outbound).optString("type")!="chain") { "落地代理不能是链" } })
                chain.forEachIndexed { index,item ->
                    item.put("tag",if(index==0) "node-${n.id}" else "node-${n.id}-chain-$index")
                    if(index<chain.lastIndex) item.put("detour","node-${n.id}-chain-${index+1}") else if(g!=null && g.frontProxy>0) item.put("detour","node-${g.frontProxy}")
                    addOutbound(item)
                }
                return@forEach
            }
            val g=data.groups.find { it.id==n.groupId }?.takeUnless { n.id==it.frontProxy || n.id==it.landingProxy }
            val item=JSONObject(n.outbound).put("tag", "node-${n.id}")
            if(item.has("detour")) { val original=item.getString("detour"); val ref=data.nodes.find { it.groupId==n.groupId && JSONObject(it.metadata).optString("sourceTag")==original }; if(ref!=null) item.put("detour", "node-${ref.id}") }
            if(g!=null && g.frontProxy>0) { require(g.frontProxy!=n.id) { "前置代理不能引用自身" }; item.put("detour", "node-${g.frontProxy}") }
            if(g!=null && g.landingProxy>0) {
                require(g.landingProxy!=n.id) { "落地代理不能引用自身" }
                val landing=data.nodes.find { it.id==g.landingProxy } ?: error("落地代理不存在")
                item.put("tag", "node-${n.id}-hop"); addOutbound(item)
                addOutbound(JSONObject(landing.outbound).put("tag", "node-${n.id}").put("detour", "node-${n.id}-hop"))
            } else addOutbound(item)
        }
        out.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val tags = nodes.map { "node-${it.id}" }.toMutableSet().apply { add("direct") }
        fun groupTag(id: Long) = "group-$id"
        if (!testing) {
            data.groups.filter { it.enabled }.forEach { g ->
                val members = eligible.filter { it.groupId == g.id && JSONObject(it.outbound).optString("type")!="custom" }.map { "node-${it.id}" }
                if (members.isNotEmpty()) {
                    val options=JSONObject(g.options)
                    val selectable=options.optBoolean("groupIsSelector",options.optBoolean("isSelector",true))
                    val groupDefault="node-${chosen.id}".takeIf { it in members } ?:members.first()
                    out.put(JSONObject().put("type", "selector").put("tag", groupTag(g.id)).put("outbounds", JSONArray(if(selectable)members else listOf(groupDefault))).put("default",groupDefault))
                    tags.add(groupTag(g.id))
                }
            }
            data.merges.forEach { g ->
                require(g.mode in listOf("selector", "urltest")) { "合并组模式无效" }
                val members = eligible.filter { (it.id in g.nodeIds || it.groupId in g.groupIds) && JSONObject(it.outbound).optString("type")!="custom" }.map { "node-${it.id}" }.distinct()
                if(members.isEmpty()) return@forEach
                val item = JSONObject().put("type", g.mode).put("tag", "merge-${g.id}").put("outbounds", JSONArray(members))
                if (g.mode == "urltest") urlTestOptions(item,data)
                else if ("node-${g.selectedId}" in members) item.put("default", "node-${g.selectedId}")
                out.put(item); tags.add("merge-${g.id}")
            }
        }
        val selectorGroup=data.groups.firstOrNull { it.id==chosen.groupId && JSONObject(it.options).optBoolean("groupIsSelector",JSONObject(it.options).optBoolean("isSelector",false)) }
        val candidateNodes=if(testing) nodes.filter { it.id==testNodeId } else eligible.filter { JSONObject(it.outbound).optString("type")!="custom" }
        require(candidateNodes.isNotEmpty()) { "没有可用代理候选" }
        val selected = "node-${if (testing) testNodeId else chosen.id}"
        val defaultNode = if (selected in tags) selected else "node-${candidateNodes.first().id}"
        if(auto)out.put(JSONObject().put("type","urltest").put("tag","home-auto").put("outbounds",JSONArray(candidateNodes.map { "node-${it.id}" })).put("url",data.setting("testUrl","https://www.gstatic.com/generate_204")).put("interval","10m").put("idle_timeout","10m").put("tolerance",30))
        out.put(JSONObject().put("type", "selector").put("tag", "proxy").put("outbounds", JSONArray((if(auto)listOf("home-auto")else emptyList())+candidateNodes.filter { auto || selectorGroup==null || it.groupId==selectorGroup.id }.map { "node-${it.id}" })).put("default", if(auto)"home-auto" else defaultNode))
        tags.add("proxy")
        val rules = JSONArray()
        if (!testing) {
            if (data.bool("sniff", true)) rules.put(JSONObject().put("action", "sniff"))
            rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            if(data.bool("resolveDestination",false)) rules.put(JSONObject().put("action","resolve").put("server","dns-direct"))
            if(data.bool("bypassLanInCore",false)) rules.put(JSONObject().put("ip_is_private",true).put("action","route").put("outbound","direct"))
        }
        val dnsRules = JSONArray()
        val priorityRules = JSONArray(); val ordinaryRules=JSONArray()
        val priorityDnsRules=JSONArray();val ordinaryDnsRules=JSONArray()
        val routeDnsServers=linkedMapOf<String,JSONObject>()
        fun routeDnsRule(rule:JSONObject):JSONObject? {
            if(!data.bool("enableDnsRouting",true))return null
            val dest=when(rule.optString("action","route")) { "reject"->"block";"route"->rule.optString("outbound").takeIf{it.isNotBlank()} ?: return null;else->return null }
            val allowed=setOf("domain","domain_suffix","domain_keyword","domain_regex","rule_set","action","outbound")
            if(rule.keys().asSequence().any{it !in allowed})return null
            val refs=rule.optJSONArray("rule_set")
            if(refs!=null && refs.length()>0 && (0 until refs.length()).all{refs.getString(it).startsWith("geoip:")})return null
            val match=JSONObject()
            listOf("domain","domain_suffix","domain_keyword","domain_regex").forEach { key->if(rule.has(key))match.put(key,rule.get(key)) }
            rule.optJSONArray("rule_set")?.let{refs->val domains=(0 until refs.length()).map{refs.getString(it)}.filter{!it.startsWith("geoip:")};if(domains.isNotEmpty())match.put("rule_set",JSONArray(domains))}
            if(match.length()==0)return null
            if(dest=="block")return match.put("action","reject")
            val server=if(dest=="direct")"dns-direct" else "dns-route-$dest".also { tag->routeDnsServers.getOrPut(tag){dnsServer(data.setting("dnsRemote","https://1.1.1.1/dns-query"),tag,dest)} }
            return match.put("action","route").put("server",server)
        }
        val sets = linkedMapOf<String, JSONObject>()
        fun target(value: String): String = when {
            value in listOf("proxy", "direct", "block") -> value
            value.startsWith("node:") -> "node-${value.substringAfter(':')}"
            value.startsWith("group:") -> "group-${value.substringAfter(':')}"
            value.startsWith("merge:") -> "merge-${value.substringAfter(':')}"
            else -> error("未知规则目标 $value")
        }.also { require(it == "block" || it in tags) { "规则引用不存在的目标 $value" } }
        if (!testing && mode == "rule") data.rules.filter { it.enabled }.sortedBy { it.order }.forEach { r ->
            val rule = JSONObject(r.advanced); normalizeAdvanced(rule); val customRule=rule.optJSONObject("customRule");rule.remove("customRule"); require(!rule.has("outbound")) { "高级规则 outbound 必须使用统一目标" }; val domains = mutableListOf<String>(); val suffixes = mutableListOf<String>(); val keywords = mutableListOf<String>(); val regex = mutableListOf<String>(); val refs=rule.optJSONArray("rule_set");val setTags=if(refs==null)mutableListOf<String>() else (0 until refs.length()).map{refs.getString(it)}.toMutableList()
            setTags.filter{it.startsWith("geosite:") || it.startsWith("geoip:")}.forEach { value->
                val kind=value.substringBefore(':');val code=value.substringAfter(':');require(code.matches(Regex("[a-zA-Z0-9_-]+"))) { "规则集名称无效" }
                sets[value]=JSONObject().put("type","local").put("tag",value).put("format","binary").put("path","$kind:$code")
            }
            r.domains.lines().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
                when {
                    value.startsWith("geosite:") || value.startsWith("geoip:") -> {
                        val kind = value.substringBefore(':'); val code = value.substringAfter(':')
                        require(code.matches(Regex("[a-zA-Z0-9_-]+"))) { "规则集名称无效" }
                        // libcore converts "geosite:x"/"geoip:x" local paths from the bundled geo databases, so no network fetch is needed at startup.
                        sets[value] = JSONObject().put("type", "local").put("tag", value).put("format", "binary").put("path", "$kind:$code")
                        setTags.add(value)
                    }
                    value.startsWith("full:") -> domains.add(value.substringAfter(':'))
                    value.startsWith("regexp:") -> regex.add(value.substringAfter(':'))
                    value.startsWith("keyword:") -> keywords.add(value.substringAfter(':'))
                    else -> suffixes.add(value.removePrefix("domain:").removePrefix("+."))
                }
            }
            if (domains.isNotEmpty()) rule.put("domain", JSONArray(domains))
            if (suffixes.isNotEmpty()) rule.put("domain_suffix", JSONArray(suffixes))
            if (keywords.isNotEmpty()) rule.put("domain_keyword", JSONArray(keywords))
            if (regex.isNotEmpty()) rule.put("domain_regex", JSONArray(regex))
            if (setTags.isNotEmpty()) rule.put("rule_set", JSONArray(setTags.distinct()))
            fun values(s: String) = s.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }
            if (values(r.packages).isNotEmpty()) rule.put("package_name", JSONArray(values(r.packages)))
            if (values(r.ipCidrs).isNotEmpty()) rule.put("ip_cidr", JSONArray(values(r.ipCidrs)))
            require(rule.length() > 0 || customRule!=null) { "规则 ${r.name} 没有匹配条件" }
            val dest = target(r.outbound)
            if (dest == "block") rule.put("action", "reject") else if(rule.optString("action", "route")=="route") rule.put("action", "route").put("outbound", dest)
            if(customRule!=null) mergeJson(rule,customRule)
            (if(r.prioritize) priorityRules else ordinaryRules).put(rule)
            routeDnsRule(rule)?.let{(if(r.prioritize)priorityDnsRules else ordinaryDnsRules).put(it)}
        }
        fun append(source: JSONArray) { for(i in 0 until source.length()) rules.put(source.get(i)) }
        append(priorityRules)
        for(i in 0 until priorityDnsRules.length())dnsRules.put(priorityDnsRules.get(i))
        fun addSmartRule(rule:JSONObject,dest:String) {
            rules.put(if(dest=="block")rule.put("action","reject") else rule.put("action","route").put("outbound",dest))
            routeDnsRule(rule)?.let{dnsRules.put(it)}
        }
        if(!testing && mode=="rule") {
            smartServices(data).forEach serviceLoop@ { service ->
                val choice=smartTarget(data,service)
                val dest=when {
                    choice=="auto" -> {
                        val ids=smartTargetNodeIds(data,choice).toSet()
                        val candidates=candidateNodes.filter { it.id in ids }
                        if(candidates.isEmpty()) return@serviceLoop
                        val tag="smart-$service"
                        out.put(urlTestOptions(JSONObject().put("type", "urltest").put("tag", tag).put("outbounds", JSONArray(candidates.map { "node-${it.id}" })),data)); tags.add(tag); tag
                    }
                    else -> { if(choice.startsWith("node:") && candidateNodes.none { it.id.toString()==choice.substringAfter(':') }) return@serviceLoop;runCatching { target(choice) }.getOrElse { return@serviceLoop } }
                }
                val packages=data.setting("smartCustom.$service.packages").lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if(packages.isNotEmpty()) {
                    require(packages.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) }) { "自定义应用包名无效" }
                    val appRule=JSONObject().put("package_name",JSONArray(packages))
                    addSmartRule(appRule,dest)
                }
                val smartUrl=data.setting("smartUrl.$service")
                val format=ruleSetFormat(smartUrl)
                if(format.isNotBlank()) {
                    val tag="smart-set-$service"
                    val body=data.setting("smartRules.$service")
                    val set=if(format=="source" && body.isNotBlank())JSONObject().put("type","inline").put("tag",tag).put("rules",JSONObject(body).getJSONArray("rules"))
                    else {
                        // A fragment changes the native cache URL hash without altering the server request.
                        val url=smartUrl.substringBefore('#')+"#zanebox-update="+data.setting("smartUpdated.$service","0")
                        val interval=when(data.setting("rulesUpdateInterval","24h")){"off"->"876000h";"3d"->"72h";"7d"->"168h";else->data.setting("rulesUpdateInterval","24h")}
                        JSONObject().put("type","remote").put("tag",tag).put("format",format).put("url",url).put("download_detour","direct").put("update_interval",interval)
                    }
                    sets[tag]=set
                    val setRule=JSONObject().put("rule_set",JSONArray().put(tag))
                    addSmartRule(setRule,dest)
                    return@serviceLoop
                }
                data.setting("smartRules.$service", "").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith("//") }.forEach { line ->
                    if(line.substringBefore(',').trim().uppercase() in unsupportedSmartRuleTypes) return@forEach
                    val fields=line.split(',').map { it.trim() }; val rule=JSONObject()
                    val value=fields.getOrElse(1) { fields[0] }
                    when(fields[0].uppercase()) {
                        "DOMAIN" -> rule.put("domain", JSONArray().put(value))
                        "DOMAIN-SUFFIX" -> rule.put("domain_suffix", JSONArray().put(value))
                        "DOMAIN-KEYWORD" -> rule.put("domain_keyword", JSONArray().put(value))
                        "IP-CIDR", "IP-CIDR6" -> rule.put("ip_cidr", JSONArray().put(value))
                        "PROCESS-NAME" -> rule.put("process_name", JSONArray().put(value))
                        else -> { require(fields.size==1) { "智能规则格式不支持: ${fields[0]}" }; rule.put("domain_suffix", JSONArray().put(value)) }
                    }
                    addSmartRule(rule,dest)
                }
            }
        }
        append(ordinaryRules)
        for(i in 0 until ordinaryDnsRules.length())dnsRules.put(ordinaryDnsRules.get(i))
        val final = if (testing) defaultNode else if (mode == "direct") "direct" else "proxy"
        // Process lookup costs a system call per connection; only pay it when statistics or a rule needs the owner.
        val rulesText = rules.toString()
        val findProcess = !testing && (data.bool("statsEnabled", true) || listOf("package_name", "process_name", "process_path", "process_path_regex").any { rulesText.contains("\"$it\"") })
        val route = JSONObject().put("rules", rules).put("final", final).put("auto_detect_interface", true).put("find_process", findProcess).put("default_domain_resolver", "dns-direct")
        if(data.bool("concurrentDial",false))route.put("default_network_strategy","fallback")
        data.setting("domainStrategy").takeIf { it.isNotBlank() }?.let { strategy -> require(strategy in listOf("prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")) { "域名解析策略无效" };route.put("default_domain_resolver",JSONObject().put("server","dns-direct").put("strategy",strategy)) }
        val serverStrategy=data.setting("dnsStrategyServer",data.setting("domainStrategy",""))
        require(serverStrategy in listOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")) { "节点服务器DNS策略无效" }
        if(serverStrategy.isNotBlank()) route.put("default_domain_resolver",JSONObject().put("server","dns-direct").put("strategy",serverStrategy))
        if (sets.isNotEmpty()) route.put("rule_set", JSONArray(sets.values.toList()))
        val dnsServers=JSONArray().put(dnsServer(data.setting("dnsDirect", "local"), "dns-direct", "direct")).put(dnsServer(data.setting("dnsRemote", "https://1.1.1.1/dns-query"), "dns-remote", final))
        routeDnsServers.values.forEach{dnsServers.put(it)}
        val hosts=parseHosts(data.setting("dnsHosts", data.setting("hosts","")))
        if(hosts.length()>0) {
            dnsServers.put(JSONObject().put("type","hosts").put("tag","dns-hosts").put("predefined",hosts))
            val previous=JSONArray(dnsRules.toString());while(dnsRules.length()>0) dnsRules.remove(0)
            dnsRules.put(JSONObject().put("domain",JSONArray(hosts.keys().asSequence().toList())).put("action","route").put("server","dns-hosts"));for(i in 0 until previous.length()) dnsRules.put(previous.get(i))
        }
        if(fakeDns) {
            val fake=JSONObject().put("type","fakeip").put("tag","dns-fake").put("inet4_range","198.18.0.0/15")
            if(data.bool("ipv6",false)) fake.put("inet6_range","fc00::/18")
            dnsServers.put(fake)
            dnsRules.put(JSONObject().put("inbound",JSONArray(buildList { if(serviceMode=="vpn") add("tun-in");if(serviceMode=="proxy" || !data.bool("disableMixedInbound",false)) add("mixed-in");if(data.bool("shareEnabled",false)) add("share-in") })).put("query_type",JSONArray(if(data.bool("ipv6",false)) listOf("A","AAAA") else listOf("A"))).put("action","route").put("server","dns-fake"))
        }
        val dnsStrategy=data.setting("dnsStrategy",if(data.bool("ipv6",false)) "prefer_ipv4" else "ipv4_only")
        require(dnsStrategy in listOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")) { "DNS策略无效" }
        val directStrategy=data.setting("dnsStrategyDirect",dnsStrategy);val remoteStrategy=data.setting("dnsStrategyRemote",dnsStrategy)
        require(listOf(directStrategy,remoteStrategy).all { it in listOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only") }) { "直连/远程DNS策略无效" }
        val filteredDnsRules=JSONArray()
        fun familyFilter(match:JSONObject,strategy:String) {
            if(strategy !in listOf("ipv4_only","ipv6_only")) return
            val reject=JSONObject(match.toString());listOf("server","action","strategy").forEach { reject.remove(it) }
            reject.put("query_type",JSONArray().put(if(strategy=="ipv4_only") "AAAA" else "A")).put("action","predefined").put("rcode","NOERROR")
            filteredDnsRules.put(reject)
        }
        val finalStrategy=if(final=="direct") directStrategy else remoteStrategy
        var familyApplied=false
        for(i in 0 until dnsRules.length()) {
            val rule=dnsRules.getJSONObject(i)
            if(rule.optString("server")=="dns-direct") familyFilter(rule,directStrategy)
            if(rule.optString("server").startsWith("dns-route-") || rule.optString("server")=="dns-remote")familyFilter(rule,remoteStrategy)
            if(rule.optString("server")=="dns-fake") { familyFilter(JSONObject(),finalStrategy);familyApplied=true }
            rule.remove("strategy");filteredDnsRules.put(rule)
        }
        if(!familyApplied) familyFilter(JSONObject(),finalStrategy)
        val dns = JSONObject().put("servers", dnsServers)
            .put("reverse_mapping",true).put("rules", filteredDnsRules).put("final", if (final == "direct") "dns-direct" else "dns-remote").put("strategy", finalStrategy)
        val root = JSONObject().put("log", JSONObject().put("level", data.setting("logLevel", "info"))).put("outbounds", out).put("route", route).put("dns", dns)
        val inbound = JSONArray()
        if (!testing) {
            val addresses = mutableListOf("172.19.0.1/30"); if (data.bool("ipv6", false)) addresses.add("fdfe:dcba:9876::1/126")
            if(serviceMode=="vpn") {
                val tun=JSONObject().put("type", "tun").put("tag", "tun-in").put("address", JSONArray(addresses)).put("mtu", data.setting("mtu", "1500").toInt()).put("stack", data.setting("tunStack", "mixed")).put("auto_route", true).put("strict_route", data.bool("strictRoute",true))
                if(data.bool("perAppEnabled",false)) {
                    val packages=data.setting("perAppPackages").split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }.distinct()
                    require(packages.isNotEmpty()) { "应用分流列表为空" }
                    require(packages.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) }) { "应用包名无效" }
                    val appMode=data.setting("perAppMode","exclude");require(appMode in listOf("include","exclude")) { "应用分流模式无效" }
                    tun.put(if(appMode=="include") "include_package" else "exclude_package",JSONArray(packages))
                }
                if(data.bool("bypassLan",false)) tun.put("route_exclude_address",JSONArray(com.zane.zanebox.core.RouteMath.subtract(listOf("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","169.254.0.0/16","fc00::/7","fe80::/10"),addresses+(if(fakeDns && data.bool("ipv6"))listOf("fc00::/18")else emptyList()))))
                inbound.put(tun)
            }
            if(serviceMode=="proxy" || !data.bool("disableMixedInbound",false)) inbound.put(JSONObject().put("type", "mixed").put("tag", "mixed-in").put("listen", if (data.bool("allowLan", false)) "0.0.0.0" else "127.0.0.1").put("listen_port", port(data.setting("mixedPort", "2080"))))
            if (data.bool("shareEnabled", false)) inbound.put(JSONObject().put("type", "mixed").put("tag", "share-in").put("listen", "0.0.0.0").put("listen_port", port(data.setting("sharePort", "2081"))))
            if (data.bool("clashApi", false) || data.bool("statsEnabled", true) || auto) {
                val api = JSONObject().put("external_controller", "127.0.0.1:${port(data.setting("apiPort", "9090"))}")
                if (purpose == Purpose.MAIN && runtimeSecret.isNotBlank()) api.put("secret", runtimeSecret)
                root.put("experimental", JSONObject().put("clash_api", api))
            }
        }
        if(!testing) {
            val experimental=root.optJSONObject("experimental") ?: JSONObject()
            experimental.put("cache_file",JSONObject().put("enabled",true).put("path","../cache/cache.db").put("store_fakeip",true))
            root.put("experimental",experimental)
        }
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        root.put("inbounds", inbound)
        SubscriptionDnsOptions.apply(root,data)
        if(!testing && data.setting("globalCustomConfig").isNotBlank()) mergeJson(root,JSONObject(data.setting("globalCustomConfig")))
        chosen.let { JSONObject(it.metadata).optJSONObject("customConfig")?.let { custom -> mergeJson(root,custom) } }
        return sanitizeRuntime(root,data,purpose,runtimeSecret)
    }
    private fun urlTestOptions(item:JSONObject,data:AppData):JSONObject {
        val interval=data.setting("urlTestInterval","5m");require(interval.matches(Regex("[1-9][0-9]*(ms|s|m|h)"))) { "URLTest 间隔无效" }
        val tolerance=data.setting("urlTestTolerance","5").toInt();require(tolerance in 0..65535) { "URLTest 容差须为 0–65535" }
        return item.put("url",data.setting("testUrl","https://www.gstatic.com/generate_204")).put("interval",interval).put("tolerance",tolerance)
    }
    internal fun normalizeAdvanced(rule:JSONObject) {
        fun split(text:String)=text.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }
        listOf("port","source_port").forEach { key -> (rule.opt(key) as? String)?.let { text ->
            val ports=mutableListOf<Int>();val ranges=mutableListOf<String>()
            split(text).forEach { value -> if(value.contains(':') || value.contains('-')) { val pair=value.replace('-',':').split(':');require(pair.size==2 && pair.all { it.toIntOrNull() in 1..65535 } && pair[0].toInt()<=pair[1].toInt()) { "端口范围无效" };ranges.add(pair.joinToString(":")) } else ports.add(value.toInt().also { require(it in 1..65535) { "端口范围无效" } }) }
            rule.remove(key);if(ports.isNotEmpty()) rule.put(key,JSONArray(ports));if(ranges.isNotEmpty()) rule.put("${key}_range",JSONArray(ranges))
        } }
        listOf("source_ip_cidr","protocol","rule_set","network","domain","domain_suffix","domain_keyword","domain_regex","process_name","process_path","package_name").forEach { key -> (rule.opt(key) as? String)?.let { text -> rule.put(key,JSONArray(if(key=="domain_regex") text.lines().map { it.trim() }.filter { it.isNotBlank() } else split(text))) } }
        rule.optJSONArray("network")?.let { values->require((0 until values.length()).all { values.getString(it) in listOf("tcp","udp") }) { "网络须为 tcp 或 udp" } }
    }
    internal fun mergeJson(base:JSONObject,custom:JSONObject) {
        custom.keys().asSequence().toList().forEach { rawKey ->
            val value=custom.get(rawKey)
            if(value is JSONArray && (rawKey.startsWith('+') || rawKey.endsWith('+'))) {
                val key=rawKey.removePrefix("+").removeSuffix("+");val original=base.optJSONArray(key) ?: JSONArray();val result=JSONArray()
                val arrays=if(rawKey.startsWith('+')) listOf(value,original) else listOf(original,value)
                arrays.forEach { a -> for(i in 0 until a.length()) result.put(a.get(i)) };base.put(key,result)
            } else if(value is JSONObject && base.optJSONObject(rawKey)!=null) mergeJson(base.getJSONObject(rawKey),value)
            else base.put(rawKey,value)
        }
    }
    private fun sanitizeRuntime(root:JSONObject,data:AppData,purpose:Purpose,secret:String):String {
        root.optJSONObject("dns")?.let { dns ->
            fun migrate(array:JSONArray):JSONArray { val result=JSONArray();for(i in 0 until array.length()) {
                val rule=array.getJSONObject(i);rule.optJSONArray("rules")?.let { rule.put("rules",migrate(it)) }
                val strategy=rule.optString("strategy");rule.remove("strategy")
                if(strategy in listOf("ipv4_only","ipv6_only") && rule.optString("type")!="logical") {
                    val filter=JSONObject(rule.toString());listOf("server","action","disable_cache","rewrite_ttl","client_subnet").forEach { filter.remove(it) };filter.put("query_type",JSONArray().put(if(strategy=="ipv4_only") "AAAA" else "A")).put("action","predefined").put("rcode","NOERROR");result.put(filter)
                }
                result.put(rule)
            };return result }
            dns.optJSONArray("rules")?.let { dns.put("rules",migrate(it)) }
        }

        if(purpose==Purpose.TEST) {
            root.put("inbounds",JSONArray());root.optJSONObject("experimental")?.let { it.remove("clash_api");it.remove("cache_file") }
        } else {
            val inbounds=root.optJSONArray("inbounds") ?: JSONArray()
            if(data.setting("serviceMode","vpn")=="proxy") require((0 until inbounds.length()).none { inbounds.getJSONObject(it).optString("type")=="tun" }) { "自定义覆盖配置含TUN，不能使用本地代理模式" }
            root.optJSONObject("experimental")?.optJSONObject("clash_api")?.let { api ->
                api.remove("secret")
                if(purpose==Purpose.MAIN) { api.put("external_controller","127.0.0.1:${port(data.setting("apiPort","9090"))}");if(secret.isNotBlank()) api.put("secret",secret) }
            }
        }
        return root.toString(2).also { validate(it) }
    }
    private fun customConfig(input:JSONObject,data:AppData,purpose:Purpose,secret:String):String {
        val root=JSONObject(input.toString());val inbounds=root.optJSONArray("inbounds") ?: JSONArray()
        if(data.setting("serviceMode","vpn")=="proxy") require((0 until inbounds.length()).none { inbounds.getJSONObject(it).optString("type")=="tun" }) { "自定义配置包含TUN，不能使用本地代理模式" }
        val experimental=root.optJSONObject("experimental") ?: JSONObject()
        if(purpose==Purpose.TEST) { root.put("inbounds",JSONArray());experimental.remove("clash_api");experimental.remove("cache_file") }
        else {
            val api=experimental.optJSONObject("clash_api")
            if(purpose==Purpose.EXPORT) api?.remove("secret")
            else if(data.bool("statsEnabled",true) || data.bool("clashApi",false) || api!=null) {
                val runtimeApi=api ?: JSONObject();runtimeApi.put("external_controller","127.0.0.1:${port(data.setting("apiPort","9090"))}");runtimeApi.remove("secret");if(secret.isNotBlank()) runtimeApi.put("secret",secret);experimental.put("clash_api",runtimeApi)
            }
        }
        if(experimental.length()>0) root.put("experimental",experimental) else root.remove("experimental")
        return sanitizeRuntime(root,data,purpose,secret)
    }
    private fun parseHosts(text:String):JSONObject {
        if(text.trim().startsWith("{")) return JSONObject(text)
        val map=linkedMapOf<String,MutableList<String>>()
        text.lines().forEach { line -> val words=line.substringBefore('#').trim().split(Regex("\\s+"));if(words.size>1 && words[0].isNotBlank()) {
            val ip=words[0];require(if(ip.contains(':')) ip.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(ip) }.isSuccess else ip.split('.').let { parts -> parts.size==4 && parts.all { it.toIntOrNull() in 0..255 } }) { "Hosts IP无效" }
            words.drop(1).forEach { domain -> require(domain.isNotBlank() && !domain.contains('/')) { "Hosts域名无效" };map.getOrPut(domain) { mutableListOf() }.add(ip) }
        } }
        return JSONObject().apply { map.forEach { (domain,ips) -> put(domain,JSONArray(ips.distinct())) } }
    }
    private fun nodeRegion(data: AppData, id: Long, name: String): String {
        data.setting("nodeRegion.$id").trim().lowercase(java.util.Locale.ROOT).takeIf { it.isNotBlank() }?.let { return it }
        val hints=mapOf("hk" to listOf("香港","HK","Hong Kong","🇭🇰"), "us" to listOf("美国","US","United States","🇺🇸"), "kr" to listOf("韩国","KR","Korea","🇰🇷"), "jp" to listOf("日本","JP","Japan","🇯🇵"), "sg" to listOf("新加坡","SG","Singapore","🇸🇬"), "tw" to listOf("台湾","TW","Taiwan","🇹🇼"))
        return hints.entries.firstOrNull { (_, words) -> words.any { hint->if(hint.matches(Regex("[A-Z]{2}")))Regex("(?i)(?<![a-z])${Regex.escape(hint)}(?![a-z])").containsMatchIn(name) else name.contains(hint, ignoreCase=true) } }?.key ?: ""
    }
    private fun wireguardEndpoint(item: JSONObject): JSONObject {
        if (item.has("address") && item.has("peers")) return item
        val peer = JSONObject().put("address", item.getString("server")).put("port", item.getInt("server_port")).put("public_key", item.getString("peer_public_key")).put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
        listOf("pre_shared_key", "reserved").forEach { if (item.has(it)) peer.put(it, item.get(it)) }
        val result = JSONObject(item.toString()).put("address", item.get("local_address")).put("peers", JSONArray().put(peer))
        listOf("server", "server_port", "peer_public_key", "pre_shared_key", "reserved", "local_address").forEach { result.remove(it) }
        return result
    }
    private fun port(s: String) = s.toInt().also { require(it in 1..65535) { "端口无效" } }
    private fun dnsServer(address: String, tag: String, detour: String): JSONObject {
        val obj = JSONObject().put("tag", tag)
        if (address == "local") return obj.put("type", "local")
        val uri = URI(if (address.contains("://")) address else "udp://$address")
        require(uri.host != null) { "DNS 地址无效" }
        val type = when (uri.scheme) { "https" -> "https"; "tls" -> "tls"; "tcp" -> "tcp"; "udp" -> "udp"; else -> error("DNS 协议不支持") }
        obj.put("type", type).put("server", uri.host)
        if(detour!="direct")obj.put("detour",detour)
        if (uri.port > 0) obj.put("server_port", uri.port)
        if (type == "https") obj.put("path", uri.rawPath.ifBlank { "/dns-query" })
        return obj
    }
    fun validate(config: String) {
        val root = JSONObject(config); val outs = root.optJSONArray("outbounds") ?: JSONArray(); val tags = mutableSetOf<String>()
        for (i in 0 until outs.length()) { val o = outs.getJSONObject(i); require(o.getString("type").isNotBlank()); require(tags.add(o.getString("tag"))) { "重复 outbound tag" } }
        root.optJSONArray("endpoints")?.let { ep -> for (i in 0 until ep.length()) require(tags.add(ep.getJSONObject(i).getString("tag"))) }
        root.optJSONObject("route")?.optString("final")?.takeIf { it.isNotBlank() }?.let { require(it in tags) { "最终路由不存在" } }
        for (i in 0 until outs.length()) { val o = outs.getJSONObject(i); o.optJSONArray("outbounds")?.let { refs -> for (j in 0 until refs.length()) require(refs.getString(j) in tags) { "组引用不存在" } }; if (o.has("detour")) require(o.getString("detour") in tags) }
        val graph=mutableMapOf<String,List<String>>()
        fun edges(o: JSONObject) { val refs=mutableListOf<String>(); o.optString("detour").takeIf { it.isNotBlank() }?.let { refs.add(it) }; o.optJSONArray("outbounds")?.let { a -> for(i in 0 until a.length()) refs.add(a.getString(i)) }; require(refs.all { it in tags }) { "代理链引用不存在" }; graph[o.getString("tag")]=refs }
        for(i in 0 until outs.length()) edges(outs.getJSONObject(i))
        root.optJSONArray("endpoints")?.let { ep -> for(i in 0 until ep.length()) edges(ep.getJSONObject(i)) }
        val done=mutableSetOf<String>(); val active=mutableSetOf<String>()
        fun visit(tag:String) { if(tag in done) return; require(active.add(tag)) { "代理链存在环" }; graph[tag].orEmpty().forEach { visit(it) }; active.remove(tag);done.add(tag) }
        graph.keys.forEach { visit(it) }
        fun checkRule(rule:JSONObject) {
            if(rule.has("outbound")) require(rule.getString("outbound") in tags) { "规则目标不存在" }
            rule.optJSONArray("domain_regex")?.let { a -> for(i in 0 until a.length()) java.util.regex.Pattern.compile(a.getString(i)) }
            rule.optJSONArray("rules")?.let { a -> for(i in 0 until a.length()) checkRule(a.getJSONObject(i)) }
        }
        root.optJSONObject("route")?.optJSONArray("rules")?.let { a -> for(i in 0 until a.length()) checkRule(a.getJSONObject(i)) }
        root.optJSONArray("inbounds"); root.optJSONObject("dns")?.optJSONArray("servers")
    }
}
