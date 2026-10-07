package com.zane.zanebox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NodeStatusUpdate(val id:Long, val outbound:String, val ping:Int, val status:Int)

/**
 * SQLite serializes read-modify-write transactions across UI and :bg processes.
 *
 * Schema v3 keeps the AppData API but stores it in three parts so hot writes stay small:
 * - state.payload: AppData without per-node test/traffic fields and without settings;
 * - node_status: ping/status/tx/rx rows (latency tests write only these);
 * - kv: all settings (including traffic counters, rule lists, legacy migration blobs).
 * Each part has its own revision so a snapshot only re-reads what another process changed.
 */
class ZaneStore(context:Context,databaseName:String="zanebox.db",autoLoad:Boolean=true) : SQLiteOpenHelper(context.applicationContext,databaseName,null,3) {
    private data class NodeState(val ping:Int=-1,val status:Int=0,val tx:Long=0,val rx:Long=0)
    private class Cache(val revision:Long,val statusRevision:Long,val kvRevision:Long,val base:AppData,val baseJson:String,val status:Map<Long,NodeState>,val kv:Map<String,String>,provided:AppData?=null) {
        val merged:AppData by lazy { provided ?: merge(base,status,kv) }
    }
    private val state = MutableStateFlow(AppData())
    val data:StateFlow<AppData> = state
    @Volatile private var cache:Cache?=null
    init { setWriteAheadLoggingEnabled(true);if(autoLoad)reload() }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,revision INTEGER NOT NULL,status_revision INTEGER NOT NULL DEFAULT 0,kv_revision INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sequence(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL)")
        createPartTables(db)
        db.execSQL("INSERT OR IGNORE INTO state(id,payload,revision) VALUES(1,?,0)",arrayOf(AppData().toJson()))
        db.execSQL("INSERT OR IGNORE INTO sequence VALUES(1,0)")
    }
    private fun createPartTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS node_status(node_id INTEGER PRIMARY KEY,ping INTEGER NOT NULL,status INTEGER NOT NULL,tx INTEGER NOT NULL,rx INTEGER NOT NULL)")
        // value NULL is a tombstone so other processes can apply kv changes incrementally by revision.
        db.execSQL("CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        require(oldVersion in 1..2 && newVersion==3) { "数据库迁移未定义：$oldVersion → $newVersion" }
        if(oldVersion==1) {
            db.execSQL("ALTER TABLE state ADD COLUMN status_revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE state ADD COLUMN kv_revision INTEGER NOT NULL DEFAULT 0")
            createPartTables(db)
        }
        val legacy=db.rawQuery("SELECT payload FROM state WHERE id=1",null).use { require(it.moveToFirst());AppData.fromJson(it.getString(0)) }
        val (base,status,settings)=split(legacy)
        if(oldVersion==1)status.forEach { (id,value) -> writeStatus(db,id,value) }
        val revision=db.rawQuery("SELECT kv_revision FROM state",null).use { it.moveToFirst();it.getLong(0)+1 }
        // Existing kv values (including tombstones) are authoritative in v2.
        val existing=db.rawQuery("SELECT key FROM kv",null).use { c -> buildSet {while(c.moveToNext())add(c.getString(0))} }
        settings.filterKeys { it !in existing }.forEach { (key,value) -> writeKv(db,key,value,revision) }
        db.execSQL("UPDATE state SET payload=?,revision=revision+1,kv_revision=? WHERE id=1",arrayOf(base.toJson(),revision))
        if(oldVersion==1)db.execSQL("UPDATE state SET status_revision=1 WHERE id=1")
    }
    private fun load(db:SQLiteDatabase):Cache {
        android.util.Log.d("LinksStore","snapshot thread=${Thread.currentThread().name}")
        // Revisions are read before content: content can only be newer than its label, never older.
        val (revision,statusRevision,kvRevision)=db.rawQuery("SELECT revision,status_revision,kv_revision FROM state WHERE id=1",null).use { require(it.moveToFirst());Triple(it.getLong(0),it.getLong(1),it.getLong(2)) }
        val old=cache
        if(old!=null && old.revision==revision && old.statusRevision==statusRevision && old.kvRevision==kvRevision) return old
        val baseJson:String;val base:AppData
        if(old!=null && old.revision==revision) { baseJson=old.baseJson;base=old.base }
        else { baseJson=db.rawQuery("SELECT payload FROM state WHERE id=1",null).use { require(it.moveToFirst());it.getString(0) };base=AppData.fromJson(baseJson) }
        val status=if(old!=null && old.statusRevision==statusRevision) old.status else HashMap<Long,NodeState>().also { map ->
            db.rawQuery("SELECT node_id,ping,status,tx,rx FROM node_status",null).use { c -> while(c.moveToNext()) map[c.getLong(0)]=NodeState(c.getInt(1),c.getInt(2),c.getLong(3),c.getLong(4)) }
        }
        val kv=if(old!=null && old.kvRevision==kvRevision) old.kv else HashMap(old?.kv ?: emptyMap()).also { map ->
            db.rawQuery("SELECT key,value FROM kv WHERE revision>?",arrayOf((old?.kvRevision ?: -1L).toString())).use { c -> while(c.moveToNext()) { if(c.isNull(1)) map.remove(c.getString(0)) else map[c.getString(0)]=c.getString(1) } }
        }
        return Cache(revision,statusRevision,kvRevision,base,baseJson,status,kv).also { cache=it }
    }
    @Synchronized fun snapshot():AppData {
        val db=readableDatabase
        db.beginTransactionNonExclusive()
        return try { load(db).merged } finally {db.endTransaction()}
    }
    /** Startup mode/boot flags do not need a complete AppData snapshot. Call off the main thread. */
    @Synchronized fun readSetting(key:String,default:String=""):String = readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?",arrayOf(key)).use { if(it.moveToFirst() && !it.isNull(0))it.getString(0) else default }
    fun reload():AppData = snapshot().also { state.value=it }
    @Synchronized fun update(transform:(AppData)->AppData):AppData {
        val db=writableDatabase;db.beginTransaction()
        val next:Cache
        try {
            val current=load(db)
            val result=transform(current.merged).validate(current.merged)
            val (base,status,kv)=split(result)
            val baseJson=if(base==current.base)current.baseJson else base.toJson()
            var revision=current.revision;var statusRevision=current.statusRevision;var kvRevision=current.kvRevision
            if(baseJson!=current.baseJson) { db.execSQL("UPDATE state SET payload=?,revision=revision+1 WHERE id=1",arrayOf(baseJson));revision++ }
            if(status!=current.status) {
                statusRevision++
                current.status.keys.filter { it !in status }.forEach { db.delete("node_status","node_id=?",arrayOf(it.toString())) }
                status.forEach { (id,value) -> if(current.status[id]!=value) writeStatus(db,id,value) }
                db.execSQL("UPDATE state SET status_revision=? WHERE id=1",arrayOf(statusRevision))
            }
            if(kv!=current.kv) {
                kvRevision++
                current.kv.keys.filter { it !in kv }.forEach { writeKv(db,it,null,kvRevision) }
                kv.forEach { (key,value) -> if(current.kv[key]!=value) writeKv(db,key,value,kvRevision) }
                db.execSQL("UPDATE state SET kv_revision=? WHERE id=1",arrayOf(kvRevision))
            }
            val largest=(result.nodes.map { it.id }+result.groups.map { it.id }+result.rules.map { it.id }+result.merges.map { it.id }).maxOrNull() ?: 0
            db.execSQL("UPDATE sequence SET value=MAX(value,?) WHERE id=1",arrayOf(largest))
            db.setTransactionSuccessful()
            next=Cache(revision,statusRevision,kvRevision,base,baseJson,status,kv,result)
        } finally { db.endTransaction() }
        cache=next;state.value=next.merged
        return next.merged
    }
    fun replace(data:AppData):AppData = update { data }
    /** Latency results only touch node_status; a result is dropped if the node was deleted or edited meanwhile. */
    @Synchronized fun updateNodeStatus(updates:List<NodeStatusUpdate>):Int {
        if(updates.isEmpty()) return 0
        val db=writableDatabase;db.beginTransaction()
        var next:Cache?=null;var changed=0
        try {
            val current=load(db);val nodes=current.base.nodes.associateBy { it.id }
            val status=HashMap(current.status)
            updates.forEach { update ->
                if(nodes[update.id]?.outbound!=update.outbound) return@forEach
                val previous=status[update.id] ?: DEFAULT;val value=previous.copy(ping=update.ping,status=update.status)
                if(value==previous) return@forEach
                if(value==DEFAULT) { status.remove(update.id);db.delete("node_status","node_id=?",arrayOf(update.id.toString())) } else { status[update.id]=value;writeStatus(db,update.id,value) }
                changed++
            }
            if(changed>0) {
                db.execSQL("UPDATE state SET status_revision=? WHERE id=1",arrayOf(current.statusRevision+1))
                next=Cache(current.revision,current.statusRevision+1,current.kvRevision,current.base,current.baseJson,status,current.kv)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        next?.let { publish(it) }
        return changed
    }
    /** Cold settings (counters, rule lists, legacy blobs) are written without re-serializing the main payload. */
    @Synchronized fun putSetting(key:String,value:String) {
        val db=writableDatabase;db.beginTransaction()
        var next:Cache?=null
        try {
            val current=load(db)
            if(current.kv[key]!=value) {
                val revision=current.kvRevision+1
                writeKv(db,key,value,revision);db.execSQL("UPDATE state SET kv_revision=? WHERE id=1",arrayOf(revision))
                next=Cache(current.revision,current.statusRevision,revision,current.base,current.baseJson,current.status,current.kv+(key to value))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        next?.let { publish(it) }
    }
    private fun publish(next:Cache) { cache=next;if(state.subscriptionCount.value>0) state.value=next.merged }
    @Synchronized fun nextId():Long {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("UPDATE sequence SET value=value+1 WHERE id=1")
            val value=db.rawQuery("SELECT value FROM sequence WHERE id=1",null).use { require(it.moveToFirst());it.getLong(0) }
            db.setTransactionSuccessful();return value
        } finally { db.endTransaction() }
    }
    private companion object {
        val DEFAULT=NodeState()
        fun split(data:AppData):Triple<AppData,Map<Long,NodeState>,Map<String,String>> {
            val status=HashMap<Long,NodeState>()
            val nodes=data.nodes.map { n -> val value=NodeState(n.ping,n.status,n.tx,n.rx);if(value==DEFAULT) n else { status[n.id]=value;n.copy(ping=-1,status=0,tx=0,rx=0) } }
            return Triple(data.copy(nodes=nodes,settings=emptyMap()),status,data.settings)
        }
        fun merge(base:AppData,status:Map<Long,NodeState>,kv:Map<String,String>):AppData = base.copy(
            nodes=if(status.isEmpty()) base.nodes else base.nodes.map { n -> status[n.id]?.let { n.copy(ping=it.ping,status=it.status,tx=it.tx,rx=it.rx) } ?: n },
            settings=if(kv.isEmpty()) base.settings else base.settings+kv)
        fun writeStatus(db:SQLiteDatabase,id:Long,value:NodeState) {
            db.insertWithOnConflict("node_status",null,ContentValues().apply { put("node_id",id);put("ping",value.ping);put("status",value.status);put("tx",value.tx);put("rx",value.rx) },SQLiteDatabase.CONFLICT_REPLACE)
        }
        fun writeKv(db:SQLiteDatabase,key:String,value:String?,revision:Long) {
            db.insertWithOnConflict("kv",null,ContentValues().apply { put("key",key);if(value==null) putNull("value") else put("value",value);put("revision",revision) },SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
