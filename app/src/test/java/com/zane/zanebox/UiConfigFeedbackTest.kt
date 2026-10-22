package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import org.junit.Assert.*
import org.junit.Test

class UiConfigFeedbackTest {
    @Test fun invalidRulePortsKeepTheRangeError() {
        for(value in listOf("abc","999999999999","0","65536")) {
            try { ConfigBuilder.normalizeAdvanced(org.json.JSONObject().put("port",value));fail("port $value") }
            catch(e:IllegalStateException) { assertEquals("端口范围无效",e.message) }
        }
    }

    @Test fun invalidInboundPortsExplainTheIntegerRange() {
        val data=AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(1,"fixture")))
        for(value in listOf("","abc","0","65536","999999999999")) {
            try { ConfigBuilder.build(data.copy(settings=mapOf("mixedPort" to value)));fail("port $value") }
            catch(e:IllegalStateException) { assertEquals("端口须为 1–65535 的整数",e.message) }
        }
        for(value in listOf("1","65535"))ConfigBuilder.validate(ConfigBuilder.build(data.copy(settings=mapOf("mixedPort" to value))))
    }
}
