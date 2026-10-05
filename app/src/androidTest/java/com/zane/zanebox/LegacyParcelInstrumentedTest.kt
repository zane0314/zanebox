package com.zane.zanebox

import android.os.Parcel
import com.zane.zanebox.backup.BackupManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

/** Uses real framework Parcel bytes, not a JVM imitation or original user data. */
class LegacyParcelInstrumentedTest {
    @Test fun frameworkRuleAndPreferenceParcelMatchesLegacyReader() {
        fun record(write:(Parcel)->Unit):String { val parcel=Parcel.obtain();return try { write(parcel);Base64.getEncoder().encodeToString(parcel.marshall()) } finally { parcel.recycle() } }
        val rule=record { p ->
            p.writeLong(7);p.writeString("中文规则");p.writeString("{\"domain_suffix\":[\"override.example\"]}");p.writeLong(4);p.writeInt(1)
            p.writeString("domain.example");p.writeString("192.0.2.0/24")
            listOf("443","","tcp","","tls","").forEach { p.writeString(it) }
            p.writeLong(-1);p.writeInt(2);p.writeString("com.example.one");p.writeString("com.example.two");p.writeInt(1)
        }
        val setting=record { p -> p.writeString("logLevel");p.writeInt(3);p.writeByteArray(ByteBuffer.allocate(4).putInt(3).array()) }
        val root=JSONObject().put("version",1).put("profiles",JSONArray()).put("groups",JSONArray()).put("rules",JSONArray().put(rule)).put("settings",JSONArray().put(setting))
        val data=BackupManager().`import`(root.toString().toByteArray())
        assertEquals("中文规则",data.rules.single().name);assertEquals("direct",data.rules.single().outbound);assertTrue(data.rules.single().prioritize)
        assertEquals("com.example.one\ncom.example.two",data.rules.single().packages);assertEquals("debug",data.setting("logLevel"))
        assertEquals("override.example",JSONObject(data.rules.single().advanced).getJSONObject("customRule").getJSONArray("domain_suffix").getString(0))
    }
}
