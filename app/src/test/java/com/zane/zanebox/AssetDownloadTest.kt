package com.zane.zanebox

import com.zane.zanebox.subscription.SubscriptionClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class AssetDownloadTest {
    @Test fun runtimeEndpointRoundTripsAndOlderSnapshotsRemainReadable() {
        val value=com.zane.zanebox.runtime.RuntimeSnapshot(state=2,mixedHost="::1",mixedPort=2081)
        assertEquals(value,com.zane.zanebox.runtime.RuntimeSnapshot.parse(value.json()))
        assertEquals(0,com.zane.zanebox.runtime.RuntimeSnapshot.parse("""{"state":2}""").mixedPort)
    }
    @Test fun explicitProxyIsKeptAcrossRedirectsAndDefaultRequestsRemainDirect()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val bytes=byteArrayOf(0,1,2,0xff.toByte())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://unresolvable.invalid/final.db"))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val proxy=Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",server.port))
            assertArrayEquals(bytes,SubscriptionClient.fetchBytes("http://unresolvable.invalid/start.db",proxy=proxy))
            assertEquals("GET http://unresolvable.invalid/start.db HTTP/1.1",server.takeRequest().requestLine)
            assertEquals("GET http://unresolvable.invalid/final.db HTTP/1.1",server.takeRequest().requestLine)
            server.enqueue(MockResponse().setBody("direct"))
            assertEquals("direct",SubscriptionClient.fetch(server.url("/direct").toString()).body)
            assertEquals("GET /direct HTTP/1.1",server.takeRequest().requestLine)
        }finally{server.shutdown()}
    }
}
