package com.zane.zanebox

import com.zane.zanebox.json.requireJsonDepth
import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionParserBoundaryTest {
    @Test fun androidLenientLiteralsCannotHideDeepJson() {
        val nested="[".repeat(129)+"0"+"]".repeat(129)
        for(prefix in listOf("\u2003","\uFEFF","abc'",">>"," >","/*comment*/>")) {
            val text="{\"type\":\"socks\",\"server\":\"x\",\"x\":"+prefix+"\",nested:"+nested+",tail:foo\"}"
            assertTrue("Android must interpret nesting outside quotes",JSONObject(text).has("nested"))
            try { SubscriptionParser.parse(text);fail("deep Android JSON accepted") } catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("128")) }
        }
    }
    @Test fun immediateSeparatorArrowsPreserveLongQuotedNodeValues() {
        val content=",nested:"+"[".repeat(129)+"0"+"]".repeat(129)+",tail:foo"
        for(separator in listOf(":>","=>")) for(quote in listOf("\"","'")) {
            val text="{\"type\":\"socks\",\"server\":\"x\",\"x\"$separator$quote$content$quote}"
            val android=JSONObject(text)
            assertFalse("Immediate separator arrow must precede one quoted value",android.has("nested"))
            assertEquals(content,android.getString("x"))
            val node=JSONObject(SubscriptionParser.parse(text).single().outbound)
            assertEquals("socks",node.getString("type"));assertEquals(content,node.getString("x"))
        }
    }
    @Test fun guardPreservesAndroidQuotesCommentsAndRejectsDecodedInputs() {
        val brackets="[".repeat(129)+"]".repeat(129)
        val quoted="{'type':'socks','server':'x','note':'$brackets',/*comment[}*/'path'=>\"$brackets\"}"
        assertEquals("socks",JSONObject(SubscriptionParser.parse(quoted).single().outbound).getString("type"))
        val nested="[".repeat(129)+"0"+"]".repeat(129)
        val deep="{\"data\":$nested}"
        for(value in listOf(Base64.getEncoder().encodeToString(deep.toByteArray()),"vmess://"+Base64.getEncoder().encodeToString(deep.toByteArray()))) {
            try { SubscriptionParser.parse(value);fail("decoded nesting accepted") } catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("128")) }
        }
        requireJsonDepth("[".repeat(128)+"0"+"]".repeat(128))
    }
}
