package com.zane.zanebox

import com.zane.zanebox.data.Node
import com.zane.zanebox.subscription.SubscriptionParser
import com.zane.zanebox.subscription.repairLegacySocksCredentials
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SocksCredentialsTest {
    private fun out(link:String)=JSONObject(SubscriptionParser.parse(link).single().outbound)
    @Test fun encodedCredentialsAndPlainCredentialsPreserveBoundaries() {
        val credentials="fixture:pass:word?ÿ"
        val encoders=listOf(Base64.getEncoder(),Base64.getEncoder().withoutPadding(),Base64.getUrlEncoder(),Base64.getUrlEncoder().withoutPadding())
        for(scheme in listOf("socks","socks5")) for(encoder in encoders) {
            val o=out("$scheme://${encoder.encodeToString(credentials.toByteArray())}@[2001:db8::1]:1081")
            assertEquals("fixture",o.getString("username"));assertEquals("pass:word?ÿ",o.getString("password"));assertEquals("2001:db8::1",o.getString("server"))
        }
        val plain=out("socks5://us%3Aer:pa%3Ass%40word@example.test")
        assertEquals("us",plain.getString("username"));assertEquals("er:pa:ss@word",plain.getString("password"))
        val anonymous=out("socks5://example.test");assertFalse(anonymous.has("username"));assertFalse(anonymous.has("password"))
        val escaped=out("socks5://Zml4dHVyZTpzZWNyZXQ%3D@example.test");assertEquals("secret",escaped.getString("password"))
        val socks4=out("socks4://Zml4dHVyZTpzZWNyZXQ=@example.test");assertEquals("Zml4dHVyZTpzZWNyZXQ=",socks4.getString("username"))
        for(user in listOf("dXNlcg==","not-base64!","/zo=","Zh==","Zml4dHVyZTpzZWNyZXQ===","Zml4dHVyZTpzZWNyZXQ ")) {
            val o=out("socks://$user@example.test");assertEquals(user,o.getString("username"));assertFalse(o.has("password"))
        }
        assertEquals("Zml4dHVyZTpwYXNz",out("http://Zml4dHVyZTpwYXNz@example.test").getString("username"))
    }
    @Test fun legacyRepairOnlyTouchesProvenMissingCredentials() {
        val encoded=Base64.getEncoder().encodeToString("fixture:secret".toByteArray())
        val link="socks5://$encoded@example.test:1081#source"
        val old=JSONObject().put("type","socks").put("version","5").put("server","example.test").put("server_port",1081).put("username",encoded).put("udp_over_tcp",true)
        val node=Node(7,8,"custom",old.toString(),link,73,12,13,4,1,"{\"custom\":true}")
        val repaired=repairLegacySocksCredentials(node)
        assertEquals("fixture",JSONObject(repaired.outbound).getString("username"));assertEquals("secret",JSONObject(repaired.outbound).getString("password"))
        assertEquals(node.copy(outbound=repaired.outbound),repaired);assertTrue(JSONObject(repaired.outbound).getBoolean("udp_over_tcp"))
        assertSame(repaired,repairLegacySocksCredentials(repaired))
        for(change in listOf("username" to "edited","password" to "edited","server" to "192.0.2.1","version" to "4","server_port" to 1082,"type" to "http")) {
            val edited=node.copy(outbound=JSONObject(node.outbound).put(change.first,change.second).toString())
            assertSame(edited,repairLegacySocksCredentials(edited))
        }
        val nullPassword=node.copy(outbound=JSONObject(node.outbound).put("password",JSONObject.NULL).toString());assertSame(nullPassword,repairLegacySocksCredentials(nullPassword))
        val manual=node.copy(shareLink="");assertSame(manual,repairLegacySocksCredentials(manual))
    }
}
