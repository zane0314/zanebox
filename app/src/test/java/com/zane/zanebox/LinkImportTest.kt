package com.zane.zanebox

import org.junit.Assert.*
import org.junit.Test

class LinkImportTest {
    @Test fun subscriptionUrlsAndProtocolLinksShareOneClassifier() {
        listOf("https://example.test/sub?token=a+b&x=1", "http://example.test:8080/sub").forEach {
            assertEquals(it, IncomingLink.parse(it).subscriptionUrl)
        }
        val authenticatedSubscription="https://fixture-user:fixture-pass@example.test/subscription"
        assertEquals(authenticatedSubscription,IncomingLink.parse(authenticatedSubscription).subscriptionUrl)
        val proxy="http://user:pass@example.test:8080#proxy"
        assertEquals(proxy, IncomingLink.parse(proxy).text)
        val plainProxy="http://127.0.0.1:8080#fixture"
        assertEquals(plainProxy,IncomingLink.parse(plainProxy).text)
        assertEquals("socks://example.test:1080", IncomingLink.parse("socks://example.test:1080").text)
    }
}
