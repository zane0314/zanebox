package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.subscription.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class SubscriptionRepairTest {
    @Test fun dnsTypesUseDefaultDialerAndKeepBootstrap() {
        listOf("udp","tcp","tls","https","quic","h3").forEach { type ->
            val server=SubscriptionDnsOptions.server("$type://dns.example:853/custom","test")
            assertFalse("$type must not detour through an empty direct outbound",server.has("detour"))
            assertEquals("dns-direct",server.getString("domain_resolver"))
        }
    }
    @Test fun hoppingKeepsGoDurationSemanticsAndRejectsInvalidValues() {
        fun parse(value:String)=JSONObject(SubscriptionParser.parse("hy2://pw@node.example:443?mport=443,8443&hop_interval=${URLEncoder.encode(value,"UTF-8")}").single().outbound).getString("hop_interval")
        mapOf("5" to "5s","1m" to "1m","1h" to "1h","5.5s" to "5.5s","1m30s" to "1m30s",".5m" to ".5m","5.s" to "5.s","5000000000ns" to "5000000000ns").forEach { (value,want) -> assertEquals(want,parse(value)) }
        listOf("1ms","4.999s","-5s","1hs","1d","oops","9223372036854775808ns").forEach { value ->
            try { parse(value);fail("accepted $value") } catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("跳跃间隔")) }
        }
    }
    @Test fun jsonAndClashDurationsShareTheSameValidation() {
        val json=SubscriptionParser.parse("""{"type":"hysteria2","server":"a","hop_interval":"1m30s"}""").single()
        assertEquals("1m30s",JSONObject(json.outbound).getString("hop_interval"))
        val yaml=SubscriptionParser.parse("proxies:\n  - {type: hysteria2, server: a, port: 443, ports: '443,8443', hop-interval: 5.5s}\n").single()
        assertEquals("5.5s",JSONObject(yaml.outbound).getString("hop_interval"))
        try { SubscriptionParser.parse("hy2://pw@node.example:443?hop_interval=1ms");fail("invalid duration ignored without ports") } catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("跳跃间隔")) }
    }
    @Test fun nestedRawBase64VmessAndExtraStopBeforeRecursiveJson() {
        val nested="[".repeat(129)+"0"+"]".repeat(129)
        val payloads=listOf("{\"type\":\"trojan\",\"server\":\"a\",\"data\":$nested}", Base64.getEncoder().encodeToString(nested.toByteArray()),"vmess://"+Base64.getEncoder().encodeToString("{\"data\":$nested}".toByteArray()),"vless://id@a:443?type=xhttp&extra="+URLEncoder.encode("{\"data\":$nested}","UTF-8"))
        payloads.forEach { value -> try { SubscriptionParser.parse(value);fail("deep input accepted") } catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains("128")) } }
    }
    @Test fun androidLiteralWhitespaceCannotHideNesting() {
        val nested="[".repeat(129)+"0"+"]".repeat(129)
        for(prefix in listOf("\u2003","\uFEFF","abc'",">>"," >","/*comment*/>")) {
            val value="{\"x\":"+prefix+"\",nested:"+nested+",tail:foo\"}"
            try { com.zane.zanebox.json.requireJsonDepth(value);fail("guard bypassed by $prefix") }
            catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("128")) }
        }
    }
    @Test fun immediateAndroidSeparatorArrowsKeepBracketsInsideQuotes() {
        val content=",nested:"+"[".repeat(129)+"0"+"]".repeat(129)+",tail:foo"
        for(separator in listOf(":>","=>")) for(quote in listOf("\"","'")) {
            com.zane.zanebox.json.requireJsonDepth("{\"type\":\"socks\",\"server\":\"x\",\"x\"$separator$quote$content$quote}")
        }
    }
    @Test fun guardAcceptsAndroidQuotesCommentsAndDepthBoundary() {
        val brackets="[".repeat(129)+"]".repeat(129)
        listOf("\uFEFF{'x':'$brackets',bare=>\"$brackets\"}","{/*$brackets*/'x':0,#$brackets\n'next':true}","{'x':'escaped\\\" and \\' $brackets'}","[".repeat(128)+"0"+"]".repeat(128)).forEach { com.zane.zanebox.json.requireJsonDepth(it) }
    }
    @Test fun bootstrapRetainsOriginalTlsNameAndHttpHost() {
        listOf("udp","tcp","tls","https","quic","h3").forEach { type ->
            val server=SubscriptionDnsOptions.bootstrapped(SubscriptionDnsOptions.server("$type://dns.example:853/custom","test"),"192.0.2.53")
            assertEquals("192.0.2.53",server.getString("server"));assertFalse(server.has("domain_resolver"))
            if(type !in listOf("udp","tcp"))assertEquals("dns.example",server.getJSONObject("tls").getString("server_name"))
        }
        assertEquals("2001:db8::53",SubscriptionDnsOptions.server("tcp://[2001:db8::53]","test").getString("server"))
        assertEquals("h3",SubscriptionDnsOptions.server("h3://dns.example","test").getString("type"))
    }
    @Test fun nodeCleanupPreservesLiveAndUnknownKeysButRemovesPairedStaleChoices() {
        val keep=mapOf("sort_ping_10" to "30","sort_ping_future" to "keep","groupSelection.group-1" to "node-10","groupSelectionDelay.group-1" to "30","groupSelection.future" to "node-999","groupSelectionDelay.future" to "9","other" to "keep")
        val stale=mapOf("sort_ping_11" to "40","sort_ping_99999999999999999999999" to "2","groupSelection.group-2" to "node-10","groupSelectionDelay.group-2" to "30","groupSelection.merge-3" to "node-999","groupSelectionDelay.merge-3" to "50","groupSelectionDelay.merge-4" to "8")
        val original=AppData(nodes=listOf(Node(10,1,"node","{}")),groups=listOf(Group(1,"group")),merges=listOf(MergeGroup(3,"merge")),settings=keep+stale)
        assertEquals(keep,original.withoutStaleNodeSettings().settings)
        assertEquals(keep+stale,original.settings)
    }
    @Test fun specifiedDnsFailureAbortsPreparationAndPreservesSource() = runBlocking {
        val source=ParsedNode("node","""{"type":"trojan","server":"edge.example","tls":{"enabled":true}}""")
        try { SubscriptionUpdater.prepare(listOf(source),SubscriptionOptions(forceResolve=true,serverDnsResolver="tcp://192.0.2.1"),false) { error("unreachable") };fail("specified resolver failure must not apply nodes") }
        catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("指定节点 DNS")) }
        assertEquals("edge.example",JSONObject(source.outbound).getString("server"))
    }
    @Test fun subscriptionReplacementCleansOnlyRemovedSortKeys() {
        val node=Node(10,1,"keep","""{"type":"trojan","server":"a"}""")
        val group=Group(1,"g","https://example/sub")
        val start=SubscriptionUpdater.begin(AppData(nodes=listOf(node,node.copy(id=11,outbound=node.outbound.replace("\"a\"","\"b\""))),groups=listOf(group),settings=mapOf("sort_ping_10" to "35","sort_ping_11" to "45","sort_ping_999" to "1","sort_ping_future" to "keep")),group,"request",1)
        val result=SubscriptionUpdater.apply(start,start.groups.single(),listOf(ParsedNode(node.name,node.outbound)),"",2) { 12 }
        assertEquals("35",result.settings["sort_ping_10"])
        assertFalse(result.settings.containsKey("sort_ping_11"));assertFalse(result.settings.containsKey("sort_ping_999"))
        assertEquals("keep",result.settings["sort_ping_future"])
    }
}
