package com.zane.zanebox

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZaneStoreV2Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun outbound(i:Int)=JSONObject().put("type","vless").put("server","node$i.example.com").put("server_port",443).put("uuid","00000000-0000-0000-0000-${"%012d".format(i)}").put("tls",JSONObject().put("enabled",true).put("server_name","cdn$i.example.com").put("utls",JSONObject().put("enabled",true).put("fingerprint","chrome"))).put("transport",JSONObject().put("type","ws").put("path","/ws$i")).toString()
    private fun large(count:Int)=AppData(nodes=(1..count).map { Node(it.toLong(),1,"节点 $it",outbound(it),shareLink="vless://x@node$it.example.com:443#$it",order=it,metadata="{\"subscriptionIdentity\":\"id$it\"}") },groups=listOf(Group(1,"大订阅",subscriptionUrl="https://example.com/sub")),settings=mapOf("selectedNodeId" to "1","selectedGroupId" to "1","smartRules.ai" to "DOMAIN,ai.example\n".repeat(2000)))
    private fun raw(name:String,sql:String):String = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use { db -> db.rawQuery(sql,null).use { it.moveToFirst();it.getString(0) } }

    @Test fun migratesVersion1PayloadWithoutLoss() {
        val name="migrate-${java.util.UUID.randomUUID()}.db"
        val original=AppData(nodes=listOf(Node(1,1,"a",outbound(1),ping=88,status=3,tx=5,rx=6),Node(2,1,"b",outbound(2))),groups=listOf(Group(1,"g")),
            settings=mapOf("selectedNodeId" to "1","selectedGroupId" to "1","trafficData" to "{\"apps\":{}}","legacy.logical" to "{\"big\":true}","smartRules.ai" to "DOMAIN,a.example","plain" to "x"))
        try {
            val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
                db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,revision INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE sequence(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL)")
                db.execSQL("INSERT INTO state VALUES(1,?,7)",arrayOf(original.toJson()));db.execSQL("INSERT INTO sequence VALUES(1,2)");db.version=1
            }
            ZaneStore(context,name).use { store -> assertEquals(original,store.snapshot());assertEquals(3L,store.nextId()) }
            val payload=raw(name,"SELECT payload FROM state")
            assertFalse(payload.contains("legacy.logical"));assertFalse(payload.contains("trafficData"));assertFalse(payload.contains("\"ping\":88"))
            assertEquals("1",raw(name,"SELECT COUNT(*) FROM node_status"));assertEquals("3",raw(name,"SELECT COUNT(*) FROM kv"))
            ZaneStore(context,name).use { store -> assertEquals(original,store.snapshot()) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun batchedStatusAndColdSettingsAreVisibleAcrossInstances() {
        val name="parts-${java.util.UUID.randomUUID()}.db"
        try {
            ZaneStore(context,name).use { a -> ZaneStore(context,name).use { b ->
                val data=a.replace(large(2000));assertEquals(2000,b.snapshot().nodes.size)
                val revision=raw(name,"SELECT revision FROM state")
                val updates=data.nodes.map { NodeStatusUpdate(it.id,it.outbound,(it.id%300+1).toInt(),3) }+NodeStatusUpdate(5,"{\"type\":\"socks\"}",999,3)
                assertEquals(2000,a.updateNodeStatus(updates))
                assertEquals("测速不得重写主数据",revision,raw(name,"SELECT revision FROM state"))
                assertEquals(6,b.snapshot().nodes.first { it.id==5L }.ping);assertEquals(3,b.snapshot().nodes.first { it.id==2000L }.status)
                a.putSetting("trafficData","{\"nodes\":{}}");assertEquals(revision,raw(name,"SELECT revision FROM state"))
                assertEquals("{\"nodes\":{}}",b.snapshot().setting("trafficData"))
                a.update { it.copy(settings=it.settings-"smartRules.ai",nodes=it.nodes.filter { n -> n.id!=7L }) }
                assertFalse(b.snapshot().settings.containsKey("smartRules.ai"));assertTrue(b.snapshot().nodes.none { it.id==7L })
                assertEquals("1999",raw(name,"SELECT COUNT(*) FROM node_status"))
                b.update { it.copy(nodes=it.nodes.map { n -> if(n.id==8L) n.copy(ping=-1,status=0) else n }) }
                assertEquals(-1,a.snapshot().nodes.first { it.id==8L }.ping);assertEquals(10,a.snapshot().nodes.first { it.id==9L }.ping)
            } }
        } finally { context.deleteDatabase(name) }
    }

    /** Compares the 1.0.0 write pattern (whole JSON state per result) with v2 batched rows on the same 2000-node data. */
    @Test fun twoThousandNodeStoragePerformance() {
        val name="perf-${java.util.UUID.randomUUID()}.db";val legacyName="perf-legacy-${java.util.UUID.randomUUID()}.db"
        val data=large(2000);val result=JSONObject().put("nodes",2000)
        try {
            val legacyPath=context.getDatabasePath(legacyName);legacyPath.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(legacyPath,null).use { db ->
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,revision INTEGER NOT NULL)")
                db.execSQL("INSERT INTO state VALUES(1,?,0)",arrayOf(data.toJson()))
                val samples=60;val start=SystemClock.elapsedRealtimeNanos()
                repeat(samples) { i ->
                    db.beginTransaction()
                    try {
                        val current=db.rawQuery("SELECT payload FROM state WHERE id=1",null).use { it.moveToFirst();AppData.fromJson(it.getString(0)) }
                        val next=current.copy(nodes=current.nodes.map { if(it.id==(i+1).toLong()) it.copy(ping=50,status=3) else it }).validate()
                        db.execSQL("UPDATE state SET payload=?,revision=revision+1 WHERE id=1",arrayOf(next.toJson()));db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                val perResult=(SystemClock.elapsedRealtimeNanos()-start)/1e6/samples
                result.put("legacyMsPerResult",perResult).put("legacyProjected2000Ms",perResult*2000)
                val readStart=SystemClock.elapsedRealtimeNanos();repeat(10) { db.rawQuery("SELECT payload FROM state WHERE id=1",null).use { it.moveToFirst();AppData.fromJson(it.getString(0)) } }
                result.put("legacySnapshotMs",(SystemClock.elapsedRealtimeNanos()-readStart)/1e6/10)
            }
            ZaneStore(context,name).use { store ->
                store.replace(data)
                val start=SystemClock.elapsedRealtimeNanos()
                data.nodes.chunked(50).forEach { batch -> store.updateNodeStatus(batch.map { NodeStatusUpdate(it.id,it.outbound,50,3) }) }
                val batched=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                result.put("v2BatchedTotal2000Ms",batched)
                val cachedStart=SystemClock.elapsedRealtimeNanos();repeat(100) { store.snapshot() }
                result.put("v2CachedSnapshotMs",(SystemClock.elapsedRealtimeNanos()-cachedStart)/1e6/100)
                val trafficStart=SystemClock.elapsedRealtimeNanos();repeat(20) { store.putSetting("trafficData","{\"n\":$it}") }
                result.put("v2TrafficWriteMs",(SystemClock.elapsedRealtimeNanos()-trafficStart)/1e6/20)
                assertTrue(store.snapshot().nodes.all { it.ping==50 })
                assertTrue("批量测速写入应至少快10倍：$result",batched*10<result.getDouble("legacyProjected2000Ms"))
                assertTrue("缓存快照应明显快于整库解析：$result",result.getDouble("v2CachedSnapshotMs")*10<result.getDouble("legacySnapshotMs"))
            }
            File(context.getExternalFilesDir(null),"store-perf-result.json").writeText(result.toString(2))
        } finally { context.deleteDatabase(name);context.deleteDatabase(legacyName) }
    }
}
