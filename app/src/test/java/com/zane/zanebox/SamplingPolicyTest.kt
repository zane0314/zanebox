package com.zane.zanebox
import com.zane.zanebox.runtime.SamplingPolicy
import com.zane.zanebox.runtime.TrafficSamples
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SamplingPolicyTest {
    @Test fun screenAndObserversControlRateButAllRouteTargetsAreCounted() {
        assertEquals(1000L,SamplingPolicy.interval(true,true))
        assertEquals(10000L,SamplingPolicy.interval(false,true));assertEquals(10000L,SamplingPolicy.interval(true,false))
        val config=JSONObject("""{"route":{"final":"proxy","rules":[{"outbound":"direct"},{"type":"logical","rules":[{"outbound":"smart-google"}]}]},"outbounds":[{"tag":"node-1"},{"tag":"node-2"}]}""")
        assertEquals(listOf("proxy","direct","smart-google"),SamplingPolicy.trackedTags(config))
    }
    @Test fun idleDoesNotTurnOffWireGuardOrBackgroundSubscriptions() {
        assertTrue(SamplingPolicy.pauseCore(true,false,false))
        assertFalse(SamplingPolicy.pauseCore(true,false,true));assertFalse(SamplingPolicy.pauseCore(true,true,false));assertFalse(SamplingPolicy.pauseCore(false,false,false))
    }
    @Test fun statisticsKeepMoreThan512ConnectionsAnd256KiB() {
        val rows=(1..600).joinToString(","){"""{"id":"$it","upload":1,"metadata":{"host":"example.com","processPath":"${"a".repeat(450)}"}}"""}
        val text="{\"connections\":[$rows]}";assertTrue(text.length>256*1024)
        assertEquals(600,TrafficSamples.parse(text).size)
    }
}
