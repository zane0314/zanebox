package com.zane.zanebox

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizationStoreTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun sql(name:String,query:String)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use{ db -> db.rawQuery(query,null).use{it.moveToFirst();it.getString(0)}}
    @Test fun version2MigrationAndSettingsWritesPreservePayloadAndAllParts() {
        val name="v3-${java.util.UUID.randomUUID()}.db"
        val node=Node(1,1,"fixture","""{"type":"socks","server":"127.0.0.1","server_port":9}""")
        val original=AppData(nodes=listOf(node),groups=listOf(Group(1,"g")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "1","plain" to "old"))
        try {
            context.getDatabasePath(name).parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use{db ->
                db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY,payload TEXT NOT NULL,revision INTEGER NOT NULL,status_revision INTEGER NOT NULL,kv_revision INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE sequence(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE node_status(node_id INTEGER PRIMARY KEY,ping INTEGER NOT NULL,status INTEGER NOT NULL,tx INTEGER NOT NULL,rx INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL)")
                db.execSQL("INSERT INTO state VALUES(1,?,4,2,3)",arrayOf(original.toJson()));db.execSQL("INSERT INTO sequence VALUES(1,1)")
                db.execSQL("INSERT INTO node_status VALUES(1,66,3,7,8)");db.execSQL("INSERT INTO kv VALUES('plain','new',3)");db.execSQL("INSERT INTO kv VALUES('trafficData','{}',3)");db.version=2
            }
            ZaneStore(context,name).use{a -> ZaneStore(context,name).use{b ->
                assertEquals("proxy",a.readSetting("serviceMode"));assertEquals("new",a.snapshot().setting("plain"))
                assertEquals(node.copy(ping=66,status=3,tx=7,rx=8),a.snapshot().nodes.single())
                val payload=sql(name,"SELECT payload FROM state");val revision=sql(name,"SELECT revision FROM state")
                repeat(30){a.update {it.copy(settings=it.settings+("browseGroupId" to "${(it.setting("browseGroupId","0").toLong()+1)}"))}}
                assertEquals(payload,sql(name,"SELECT payload FROM state"));assertEquals(revision,sql(name,"SELECT revision FROM state"))
                assertEquals("30",b.snapshot().setting("browseGroupId"));assertEquals("{}",b.snapshot().setting("trafficData"))
                a.update {it.copy(settings=it.settings-"plain")};assertFalse(b.snapshot().settings.containsKey("plain"))
                assertThrows(Exception::class.java){a.update {it.copy(nodes=it.nodes.map{n->n.copy(outbound="bad-json")})}}
                assertEquals(node.outbound,b.snapshot().nodes.single().outbound)
            }}
        } finally {context.deleteDatabase(name)}
    }
    @Test fun lazyConstructionDoesNotOpenDatabaseOnMainThread() {
        val name="lazy-${java.util.UUID.randomUUID()}.db"
        ins.runOnMainSync {ZaneStore(context,name,autoLoad=false).use {assertFalse(context.getDatabasePath(name).exists())}}
        assertFalse(context.getDatabasePath(name).exists())
    }
    @Test fun singleSettingReadDoesNotParseNodes() {
        val name="single-${java.util.UUID.randomUUID()}.db"
        try {
            ZaneStore(context,name).use {it.putSetting("serviceMode","proxy")}
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("UPDATE state SET payload='bad-json',revision=revision+1")}
            ZaneStore(context,name,autoLoad=false).use {assertEquals("proxy",it.readSetting("serviceMode"))}
        } finally {context.deleteDatabase(name)}
    }
}
