package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkMeasurementSettingsTest {
    @Test fun settingsReachEveryConfigPurposeAndRoundTrip() {
        val base=AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"example.test","server_port":1080}""")),groups=listOf(Group(1,"fixture")))
        assertTrue(base.bool("unifiedDelay")); assertTrue(base.bool("tcpConcurrent"))
        for(enabled in listOf(false,true)) {
            val data=base.copy(settings=mapOf("unifiedDelay" to enabled.toString(),"tcpConcurrent" to enabled.toString()))
            assertEquals(data.settings,AppData.fromJson(data.toJson()).settings)
            for(purpose in Purpose.entries) {
                val config=JSONObject(ConfigBuilder.build(data,purpose,1))
                assertEquals(enabled,config.getJSONObject("experimental").getBoolean("unified_delay"))
                assertEquals(enabled,config.getJSONObject("route").getBoolean("tcp_concurrent"))
            }
        }
        val prefs=preferenceSections(base).flatMap {it.second}
        assertEquals(1,prefs.count {it.key=="testUrl"})
        for(key in listOf("unifiedDelay","tcpConcurrent")) {
            assertTrue(prefs.single {it.key==key}.boolean)
            assertEquals(SettingsApply.AUTO,settingsApply(key))
        }
    }
}
