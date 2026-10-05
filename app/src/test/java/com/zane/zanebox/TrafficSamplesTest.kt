package com.zane.zanebox

import com.zane.zanebox.runtime.TrafficSamples
import org.junit.Assert.*
import org.junit.Test

class TrafficSamplesTest {
    @Test fun stripsCredentialsPathsAndQueryFromDomains() {
        assertEquals("example.com",TrafficSamples.domain(" https://user:password@Example.COM:443/account?secret=x#part "))
        assertEquals("[2001:db8::1]",TrafficSamples.domain("http://[2001:db8::1]:8080/a?q=x"))
        assertEquals("2001:db8::1",TrafficSamples.domain("2001:db8::1"))
    }
    @Test fun ignoresDuplicateConnectionIds() {
        val row="{\"id\":\"a\",\"upload\":5,\"download\":8,\"metadata\":{\"host\":\"https://u:p@example.com/?secret=x\"}}"
        val samples=TrafficSamples.parse("{\"connections\":[$row,$row]}")
        assertEquals(1,samples.size);assertEquals(5L,samples.single().tx);assertEquals("example.com",samples.single().domain)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedConnectionPayload() { TrafficSamples.parse(" ".repeat(256*1024+1)) }
    @Test fun excludesMissingIdsAndNegativeCounters() {
        val samples=TrafficSamples.parse("{\"connections\":[{\"id\":\"\"},{\"id\":\"a\",\"upload\":-5,\"download\":-8}]}")
        assertEquals(1,samples.size);assertEquals(0L,samples.single().tx);assertEquals(0L,samples.single().rx)
    }
}
