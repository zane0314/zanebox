package com.zane.zanebox

import org.junit.Assert.*
import org.junit.Test

class IncomingLinkTest {
    @Test fun importsEncodedSubscriptionAndNativeNodeUris() {
        val link=IncomingLink.parse("sn://subscription?url=https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Dsynthetic%26x%3D1&name=%E8%AE%A2%E9%98%85")
        assertEquals("https://example.com/sub?token=synthetic&x=1",link.subscriptionUrl);assertEquals("订阅",link.name)
        val node="socks://127.0.0.1:1080#fixture";assertEquals(node,IncomingLink.parse(node).text)
        assertEquals("https://example.com/sub",IncomingLink.parse("clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub").subscriptionUrl)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsSubscriptionFileAccess() { IncomingLink.parse("sn://subscription?url=file%3A%2F%2F%2Fetc%2Fpasswd") }
    @Test fun preservesEncodedTextPayload() { assertEquals("socks://127.0.0.1:1080",IncomingLink.parse("zanebox://import?text=socks%3A%2F%2F127.0.0.1%3A1080").text) }
}
