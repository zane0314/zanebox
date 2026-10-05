package com.zane.zanebox.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import com.zane.zanebox.MainActivity
import com.zane.zanebox.R
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.core.NativePlatform
import com.zane.zanebox.core.SingBoxEngine
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.NodeStatusUpdate
import com.zane.zanebox.data.ZaneStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import libcore.Libcore
import libcore.SpeedTestSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class ZaneRuntime(private val owner:Service,private val vpn:VpnService?):ContextWrapper(owner) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val access=Mutex()
    private val commands=Channel<Pair<String,String>>(Channel.UNLIMITED)
    private val current=AtomicReference(RuntimeSnapshot())
    private val callbacks=RemoteCallbackList<IRuntimeCallback>()
    private lateinit var store:ZaneStore
    private lateinit var platform:NativePlatform
    private lateinit var engine:SingBoxEngine
    private var sampler:Job?=null
    private var tests:Job?=null
    private var speed:SpeedTestSession?=null
    private var speedJob:Job?=null
    private var ruleUpdates:Job?=null
    private var stunJob:Job?=null
    private val stunAccess=Mutex()
    @Volatile private var stunVersion=0L
    private var sessionTx=0L
    private var sessionRx=0L
    private var lastProxySelection=""
    @Volatile private var cachedSecret:String?=null
    @Volatile private var apiPort=9090
    private var publishedFlags=""
    private var notificationText=""
    private var inForeground=false
    private var channelReady=false
    private var wakeLock:android.os.PowerManager.WakeLock?=null
    private val wakeReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==Intent.ACTION_SCREEN_ON && current.get().state==2 && store.snapshot().bool("wakeResetConnections",false))dispatch("wakeReset","")
        }
    }
    private val connectionBytes=LinkedHashMap<String,Pair<Long,Long>>()
    private val counters=JSONObject().put("apps",JSONObject()).put("domains",JSONObject()).put("nodes",JSONObject())
    private val binder=object:IRuntime.Stub() {
        override fun getSnapshot()=current.get().json()
        override fun registerCallback(callback:IRuntimeCallback) { callbacks.register(callback);callback.onSnapshot(current.get().json()) }
        override fun unregisterCallback(callback:IRuntimeCallback) { callbacks.unregister(callback) }
        override fun command(action:String,payload:String) { dispatch(action,payload) }
    }
    fun create() {
        store=ZaneStore(this)
        seedRules()
        platform=NativePlatform(this,vpn) { selector,tag ->
            if(selector.startsWith("merge-")) {
                val id=selector.removePrefix("merge-").toLongOrNull();val node=tag.removePrefix("node-").toLongOrNull()
                if(id!=null && node!=null) scope.launch { store.update { d -> d.copy(merges=d.merges.map { if(it.id==id) it.copy(selectedId=node) else it }) };event("message","分流组选择已更新") }
            }
        }
        platform.initialize()
        engine=SingBoxEngine(platform)
        androidx.core.content.ContextCompat.registerReceiver(this,wakeReceiver,IntentFilter(Intent.ACTION_SCREEN_ON),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        val saved=store.snapshot().setting("trafficData")
        if(saved.isNotBlank()) runCatching { val data=JSONObject(saved);listOf("apps","domains","nodes").forEach { counters.put(it,data.optJSONObject(it) ?: JSONObject()) } }
        scope.launch { for((action,payload) in commands) execute(action,payload) }
    }
    private fun seedRules() {
        val files=mapOf("youtube" to listOf("YouTube.list"),"telegram" to listOf("Telegram.list"),"netflix" to listOf("Netflix.list"),"disney" to listOf("Disney.list"),"tiktok" to listOf("TikTok.list"),"x" to listOf("Twitter.list"),"meta" to listOf("Facebook.list","Instagram.list"),"spotify" to listOf("Spotify.list"),"google" to listOf("Google.list"),"ai" to listOf("OpenAI.list"))
        store.update { data ->
            val settings=data.settings.toMutableMap()
            files.forEach { (key,names) -> if(!settings.containsKey("smartRules.$key")) settings["smartRules.$key"]=names.joinToString("\n") { assets.open("anybox-rules/$it").bufferedReader().use { r->r.readText() } } }
            data.copy(settings=settings)
        }
    }
    fun bind():IBinder = binder
    fun startCommand(intent:Intent?):Int {
        notification(if(intent?.action=="stop")"正在断开" else if(current.get().state==2)"已连接" else "正在连接")
        if(intent?.action=="stop")dispatch("stop","")
        else if(intent?.action!="foreground")dispatch("start","")
        return Service.START_STICKY
    }
    private fun dispatch(action:String,payload:String) {
        commands.trySend(action to payload)
    }
    private suspend fun execute(action:String,payload:String) {
            try { when(action) {
                "start" -> access.withLock { if(current.get().state !in listOf(1,2,3)) startCore() }
                "stop" -> access.withLock { stopCore() }
                "reload" -> access.withLock { if(current.get().state==2) { stopCore(terminal=false);startCore() } else event("message","设置已保存，连接时应用") }
                "select" -> access.withLock { selectNode(payload.toLong()) }
                "restore" -> access.withLock { restoreData(payload) }
                "validate" -> { val args=JSONObject(payload);val name=args.getString("name");val filename=args.getString("file");require(filename.matches(Regex("validate-[0-9a-f-]{36}\\.json")));val file=File(noBackupFilesDir,filename);val error=try { require(file.length() in 1..64*1024*1024L);engine.validate(file.readText());"" } catch(e:Exception) { safeError(e) } finally { file.delete() };event("validation",JSONObject().put("name",name).put("error",error).toString()) }
                "test" -> startTests(JSONArray(payload))
                "cancelTests" -> { tests?.cancel();engine.cancelTests();event("testing","[]");event("message","测速已取消") }
                "logs" -> { val log=File(cacheDir,"neko.log");event("logs",JSONArray(if(log.exists()) log.readLines().takeLast(500) else emptyList<String>()).toString()) }
                "clearLogs" -> { File(cacheDir,"neko.log").writeText("");event("logs","[]") }
                "systemLogs" -> { val process=ProcessBuilder("logcat","-d","-t","500").redirectErrorStream(true).start();val lines=process.inputStream.bufferedReader().use { it.readLines().takeLast(500) };process.waitFor();event("logs",JSONArray(lines).toString()) }
                "wakeReset" -> access.withLock { if(current.get().state==2)Libcore.resetAllConnections(true) }
                "asset" -> access.withLock { installAsset(payload) }
                "ip" -> queryIp()
                "traffic" -> event("traffic",trafficJson())
                "resetTraffic" -> { synchronized(counters) { listOf("apps","domains","nodes").forEach { counters.put(it,JSONObject()) } };persistTraffic();event("traffic",trafficJson());event("message","累计统计已清空") }
                "stats" -> { store.update { it.copy(settings=it.settings+("statsEnabled" to payload.toBoolean().toString())) };event("message","统计状态已保存") }
                "connections" -> event("connections",api("/connections"))
                "panel" -> { check(current.get().state==2) { "请先连接代理" };check(store.snapshot().bool("clashApi",false) || store.snapshot().bool("statsEnabled",true)) { "请先启用 Clash API 或流量统计" };api("/version");val port=apiPort;event("panel","http://127.0.0.1:$port/ui/#/?hostname=http%3A%2F%2F127.0.0.1%3A$port&secret=${ownerSecret()}") }
                "closeConnection" -> { require(payload.matches(Regex("[a-zA-Z0-9-]{1,128}")));api("/connections/$payload","DELETE");event("connections",api("/connections")) }
                "closeAllConnections" -> { api("/connections","DELETE");event("connections",api("/connections")) }
                "speed" -> startSpeed(JSONObject(payload))
                "cancelSpeed" -> { speed?.cancel();speedJob?.cancel();event("speed",JSONObject().put("stage","cancelled").put("done",true).put("error","速度测试已取消").toString());event("message","速度测试已取消") }
                "stun" -> startStun(payload)
                "cancelStun" -> { stunVersion++;stunJob?.cancel();event("stun",JSONObject().put("running",false).put("error","已取消").toString()) }
            } } catch(e:TimeoutCancellationException) { event("message","操作超时，请重试");if(action=="start")owner.stopSelf() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { event("message",safeError(e));if(action=="stun")event("stun",JSONObject().put("running",false).put("error",safeError(e)).toString());if(action=="start") { failed(e);owner.stopSelf() } }
    }
    private fun safeError(e:Throwable):String = (e.message ?: e.javaClass.simpleName).replace(Regex("(?i)(password|secret|uuid|token)([\"'\\s:=]+)[^,}\\s]+"),"$1$2[隐藏]").take(300)
    private fun event(kind:String,payload:String) = synchronized(callbacks) {
        val n=callbacks.beginBroadcast()
        try { repeat(n) { runCatching { callbacks.getBroadcastItem(it).onEvent(kind,payload) } } } finally { callbacks.finishBroadcast() }
    }
    private fun publish(value:RuntimeSnapshot) {
        current.set(value)
        // Rate samples republish every second; the tile and persisted flags only need connection transitions.
        val flags="${value.state==2}|${vpn==null}"
        val changed=synchronized(this) { (flags!=publishedFlags).also { publishedFlags=flags } }
        if(changed) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("connected",value.state==2).putString("serviceMode",if(vpn==null)"proxy" else "vpn").apply();if(Build.VERSION.SDK_INT>=24)android.service.quicksettings.TileService.requestListeningState(this,android.content.ComponentName(this,QuickTileService::class.java)) }
        synchronized(callbacks) { val n=callbacks.beginBroadcast();try { repeat(n) { runCatching { callbacks.getBroadcastItem(it).onSnapshot(value.json()) } } } finally { callbacks.finishBroadcast() } } }
    private fun ownerSecret():String {
        cachedSecret?.let { return it }
        val file=AtomicFile(File(noBackupFilesDir,"clash-api.secret"))
        if(!file.baseFile.exists()) {
            val bytes=ByteArray(32);SecureRandom().nextBytes(bytes)
            val secret=bytes.joinToString("") { "%02x".format(it) }
            val out=file.startWrite()
            try { android.system.Os.fchmod(out.fd,384);out.write(secret.toByteArray());file.finishWrite(out) }
            catch(e:Exception) { file.failWrite(out);throw e }
        }
        return file.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }.also { require(it.matches(Regex("[0-9a-f]{64}"))) { "控制凭证损坏" };cachedSecret=it }
    }
    private fun runtimeConfig(data:AppData):String {
        val root=JSONObject(ConfigBuilder.build(data,Purpose.MAIN,runtimeSecret=ownerSecret()))
        root.optJSONObject("experimental")?.optJSONObject("clash_api")?.apply {
            put("external_ui",File(filesDir,"core-assets/yacd").absolutePath)
            put("access_control_allow_origin",JSONArray().put("http://127.0.0.1:${data.setting("apiPort","9090")}"))
            put("access_control_allow_private_network",false)
        }
        return root.toString()
    }
    private suspend fun startCore() {
        val previous=current.get();publish(RuntimeSnapshot(state=1,generation=previous.generation+1));notification("正在连接")
        try {
            check(vpn==null || VpnService.prepare(this)==null) { "请先授权VPN" }
            // InitCore extracts geoip/geosite databases and then YACD asynchronously; YACD's index marks the end.
            // Local geo rule-sets and the panel both need these files, so this only waits on a first run.
            withTimeout(30000) { while(!File(filesDir,"core-assets/yacd/index.html").isFile)delay(50) }
            val data=store.snapshot();val config=runtimeConfig(data)
            platform.metered=data.bool("meteredNetwork",false)
            val httpProxy=data.bool("appendHttpProxy",false)
            require(!httpProxy || !data.bool("disableMixedInbound",false)) { "追加 HTTP 代理需要开启本地 mixed 入口" }
            require(!httpProxy || Build.VERSION.SDK_INT>=29) { "追加 HTTP 代理需要 Android 10 或更新版本" }
            platform.httpProxyPort=if(httpProxy)data.setting("mixedPort","2080").toInt() else 0
            platform.httpProxyBypass=data.setting("httpProxyBypass").split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }
            apiPort=data.setting("apiPort","9090").toIntOrNull() ?: 9090
            engine.validate(config)
            Libcore.setNetworkChangeResetConnections(data.bool("networkReset",true))
            engine.start(config)
            if(data.bool("acquireWakeLock",false))wakeLock=(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"zanebox:proxy").apply { setReferenceCounted(false);acquire() }
            lastProxySelection=engine.configuredProxyDefault
            sessionTx=0;sessionRx=0
            val snapshot=RuntimeSnapshot(state=2,started=SystemClock.elapsedRealtime(),generation=current.get().generation)
            publish(snapshot);notification("已连接")
            startSampler(snapshot.generation)
            startRuleUpdates()
            val warnings=ConfigBuilder.warnings(data)
            if(warnings.isNotEmpty()) event("message",warnings.joinToString("\n")) else event("message","已连接")
        } catch(e:Exception) { failed(e);throw e }
    }
    private suspend fun installAsset(filename:String) {
        require(filename.matches(Regex("asset-[0-9a-f-]{36}-(geoip|geosite)\\.db"))) { "资源暂存名无效" }
        val stage=File(noBackupFilesDir,filename)
        try {
            check(current.get().state !in 1..3) { "更新资源前请先断开连接" }
            withTimeout(30000) { while(!File(filesDir,"core-assets/yacd/index.html").isFile)delay(50) }
            require(stage.length() in 16..64*1024*1024L)
            val bytes=stage.readBytes();val kind=filename.substringAfterLast('-').removeSuffix(".db")
            val code=com.zane.zanebox.config.geoAssetCode(kind,bytes)
            val asset=AtomicFile(File(filesDir,"core-assets/$kind.db"))
            val previous=if(asset.baseFile.exists())asset.openRead().use { it.readBytes() } else null
            fun write(content:ByteArray) { val output=asset.startWrite();try { output.write(content);asset.finishWrite(output) } catch(e:Exception) { asset.failWrite(output);throw e } }
            write(bytes)
            try {
                val config=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","direct")))
                    .put("route",JSONObject().put("final","direct").put("rule_set",JSONArray().put(JSONObject().put("type","local").put("tag","asset-check").put("format","binary").put("path","$kind:$code")))
                        .put("rules",JSONArray().put(JSONObject().put("rule_set",JSONArray().put("asset-check")).put("action","route").put("outbound","direct"))))
                engine.validate(config.toString())
            } catch(e:Exception) { if(previous!=null)write(previous)else asset.delete();throw e }
            event("assetInstalled","$kind 已更新并通过内核校验")
        } finally { stage.delete() }
    }
    private fun failed(error:Exception) {
        releaseWakeLock()
        sampler?.cancel();ruleUpdates?.cancel();runCatching { engine.close() }
        publish(RuntimeSnapshot(state=4,generation=current.get().generation,error=safeError(error)))
        foregroundStop()
    }
    private fun stopCore(terminal:Boolean=true) {
        releaseWakeLock()
        if(current.get().state==0) { if(terminal) { foregroundStop();owner.stopSelf() };return }
        val data=store.snapshot();val selections=readSelections(data);syncDefaultSelection(data,selections)
        if(data.bool("statsEnabled",true))runCatching { sampleConnections(api("/connections")) }
        val old=current.get();publish(old.copy(state=3));sampler?.cancel();sampler=null;ruleUpdates?.cancel();ruleUpdates=null
        try { recordTraffic(data,engine.close(),selections);persistTraffic();connectionBytes.clear() } finally { publish(RuntimeSnapshot(generation=old.generation+1,txTotal=sessionTx,rxTotal=sessionRx));if(terminal) { foregroundStop();owner.stopSelf() };event("message","已断开") }
    }
    private suspend fun selectNode(id:Long) {
        val before=store.snapshot();val node=before.nodes.firstOrNull { it.id==id } ?: error("节点不存在")
        require(before.groups.any { it.id==node.groupId && it.enabled }) { "节点分组已禁用" }
        check(current.get().state !in listOf(1,3)) { "请等待连接操作完成" }
        if(current.get().state==2) { collectTraffic(before);publish(current.get().copy(txTotal=sessionTx,rxTotal=sessionRx)) }
        store.update { it.copy(settings=it.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to node.groupId.toString())) }
        try { if(current.get().state==2 && !engine.select("node-$id")) { stopCore(terminal=false);startCore() };event("message","默认节点已切换") }
        catch(e:Exception) { store.update { it.copy(settings=it.settings+("selectedNodeId" to before.selectedNodeId.toString())+("selectedGroupId" to before.selectedGroupId.toString())) };throw e }
    }
    private suspend fun restoreData(name:String) {
        require(name.matches(Regex("restore-[0-9a-f-]{36}\\.json"))) { "恢复文件名无效" }
        val file=File(noBackupFilesDir,name)
        try {
            require(file.length() in 1..64*1024*1024L) { "恢复文件大小无效" }
            val incoming=AppData.fromJson(file.readText(Charsets.UTF_8))
            if(incoming.nodes.isNotEmpty())engine.validate(runtimeConfig(incoming))
            val running=current.get().state==2
            val modeChanged=incoming.setting("serviceMode","vpn")!=store.snapshot().setting("serviceMode","vpn")
            if(running)stopCore(terminal=false)
            val before=store.snapshot()
            val previousCounters=synchronized(counters) { JSONObject(counters.toString()) }
            val previousConnections=LinkedHashMap(connectionBytes)
            try {
                store.replace(incoming)
                synchronized(counters) { val saved=runCatching { JSONObject(incoming.setting("trafficData","{}")) }.getOrDefault(JSONObject());listOf("apps","domains","nodes").forEach { counters.put(it,saved.optJSONObject(it) ?: JSONObject()) } }
                connectionBytes.clear()
                if(running && incoming.nodes.isNotEmpty() && !modeChanged)startCore()
                if(running && (incoming.nodes.isEmpty() || modeChanged)) { foregroundStop();owner.stopSelf() }
                event("restored",JSONObject().put("modeChanged",modeChanged).put("running",running && incoming.nodes.isNotEmpty()).toString())
            } catch(failure:Exception) {
                store.replace(before)
                synchronized(counters) { listOf("apps","domains","nodes").forEach { counters.put(it,previousCounters.getJSONObject(it)) } }
                connectionBytes.clear();connectionBytes.putAll(previousConnections)
                if(running)runCatching { startCore() }.onFailure { event("message","恢复已回滚，原服务需要手动重连") }
                throw failure
            }
        } finally { file.delete() }
    }
    private fun startSampler(token:Long) {
        sampler?.cancel()
        sampler=scope.launch {
            var last=SystemClock.elapsedRealtime();var iteration=0;var selections=JSONObject()
            while(isActive && current.get().generation==token && current.get().state==2) {
                delay(1000)
                iteration++
                val data=store.snapshot()
                // Controller HTTP calls run outside the runtime lock so user commands never queue behind them.
                // Selector state only feeds traffic attribution and panel-selection sync, so 5 s is enough.
                if(iteration%5==1) readSelections(data).takeIf { it.length()>0 }?.let { selections=it }
                val connections=if(iteration%5==0 && data.bool("statsEnabled",true)) runCatching { api("/connections") }.getOrNull() else null
                access.withLock {
                    if(current.get().state!=2 || current.get().generation!=token) return@withLock
                    val now=SystemClock.elapsedRealtime();val duration=(now-last).coerceAtLeast(1);last=now
                    syncDefaultSelection(data,selections)
                    val traffic=engine.trackedTags.associateWith { tag -> engine.query(tag,"uplink").coerceAtLeast(0) to engine.query(tag,"downlink").coerceAtLeast(0) }
                    val (tx,rx)=recordTraffic(data,traffic,selections)
                    publish(current.get().copy(txRate=tx*1000/duration,rxRate=rx*1000/duration,txTotal=sessionTx,rxTotal=sessionRx))
                    notification("↑ ${tx*1000/duration} B/s  ↓ ${rx*1000/duration} B/s",rate=true)
                    connections?.let { runCatching { sampleConnections(it) } }
                    if(iteration%5==0) persistTraffic()
                }
            }
        }
    }
    private fun collectTraffic(data:AppData):Pair<Long,Long> {
        val traffic=engine.trackedTags.associateWith { tag -> engine.query(tag,"uplink").coerceAtLeast(0) to engine.query(tag,"downlink").coerceAtLeast(0) }
        val selections=readSelections(data);syncDefaultSelection(data,selections)
        return recordTraffic(data,traffic,selections)
    }
    private fun readSelections(data:AppData):JSONObject = if(data.bool("statsEnabled",true) || data.bool("clashApi",false))runCatching { JSONObject(api("/proxies")).getJSONObject("proxies") }.getOrDefault(JSONObject()) else JSONObject()
    private fun syncDefaultSelection(data:AppData,selections:JSONObject) {
        val tag=selections.optJSONObject("proxy")?.optString("now").orEmpty()
        if(tag.isBlank())return
        val previous=lastProxySelection;lastProxySelection=tag
        if(previous.isBlank() || previous==tag)return
        val id=tag.takeIf { it.startsWith("node-") }?.removePrefix("node-")?.substringBefore('-')?.toLongOrNull() ?: return
        val node=data.nodes.firstOrNull { it.id==id && data.groups.any { g->g.id==it.groupId && g.enabled } } ?: return
        if(data.selectedNodeId==id && data.selectedGroupId==node.groupId)return
        store.update { latest ->
            val currentNode=latest.nodes.firstOrNull { it.id==id && latest.groups.any { g->g.id==it.groupId && g.enabled } }
            if(currentNode==null)latest else latest.copy(settings=latest.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to currentNode.groupId.toString()))
        }
        event("message","默认节点已与内核同步")
    }
    private fun recordTraffic(data:AppData,traffic:Map<String,Pair<Long,Long>>,selections:JSONObject):Pair<Long,Long> {
        fun nodeId(tag:String):Long? {
            var currentTag=tag
            val seen=HashSet<String>()
            repeat(16) {
                if(!seen.add(currentTag))return null
                if(currentTag.startsWith("node-"))return currentTag.removePrefix("node-").substringBefore('-').toLongOrNull()
                val next=selections.optJSONObject(currentTag)?.optString("now").orEmpty()
                if(next.isBlank())return if(currentTag=="proxy")data.selectedNodeId.takeIf { id->id>0 } else null
                currentTag=next
            }
            return null
        }
        var tx=0L;var rx=0L
        traffic.filterKeys { it!="direct" || data.bool("showDirectSpeed",false) }.forEach { (tag,bytes) ->
            val (up,down)=bytes
            tx+=up;rx+=down
            if(data.bool("statsEnabled",true))nodeId(tag)?.let { id->addCounter("nodes",id.toString(),up,down,50) }
        }
        sessionTx+=tx;sessionRx+=rx
        return tx to rx
    }
    private fun addCounter(type:String,name:String,tx:Long,rx:Long,limit:Int) {
        if(name.isBlank() || (tx==0L && rx==0L))return
        synchronized(counters) {
            val group=counters.getJSONObject(type)
            if(!group.has(name) && group.length()>=limit)return
            val item=group.optJSONObject(name) ?: JSONObject().put("tx",0).put("rx",0)
            item.put("tx",item.optLong("tx")+tx).put("rx",item.optLong("rx")+rx);group.put(name,item)
        }
    }
    private fun sampleConnections(text:String) {
        val active=HashSet<String>()
        for(c in TrafficSamples.parse(text)) {
            val id=c.id;active.add(id)
            val up=c.tx;val down=c.rx;val previous=connectionBytes[id] ?: (0L to 0L)
            addCounter("apps",c.app,(up-previous.first).coerceAtLeast(0),(down-previous.second).coerceAtLeast(0),50)
            addCounter("domains",c.domain,(up-previous.first).coerceAtLeast(0),(down-previous.second).coerceAtLeast(0),100)
            connectionBytes[id]=up to down
        }
        connectionBytes.keys.retainAll(active)
    }
    private fun trafficJson():String = synchronized(counters) {
        val nodes=store.snapshot().nodes.associateBy { it.id.toString() }
        JSONObject().apply { listOf("apps","domains","nodes").forEach { kind -> val values=counters.getJSONObject(kind);put(kind,JSONArray().apply { values.keys().asSequence().forEach { name -> put(JSONObject(values.getJSONObject(name).toString()).put("name",if(kind=="nodes")nodes[name]?.name ?: "节点 $name" else name)) } }) } }.toString()
    }
    private fun persistTraffic() { val data=synchronized(counters) { counters.toString() };store.putSetting("trafficData",data) }
    private fun api(path:String,method:String="GET"):String {
        check(current.get().state==2) { "请先连接代理" }
        val connection=URL("http://127.0.0.1:$apiPort$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout=2000;connection.readTimeout=2000;connection.instanceFollowRedirects=false;connection.requestMethod=method
            connection.setRequestProperty("Authorization","Bearer ${ownerSecret()}")
            require(connection.responseCode in 200..299) { "控制接口请求失败 (${connection.responseCode})" }
            return if(connection.responseCode==204) "{}" else connection.inputStream.use { input -> val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096);val limit=if(path=="/connections")256*1024 else 1024*1024;while(true) { val n=input.read(bytes);if(n<0)break;require(out.size()+n<=limit) { "控制响应过大" };out.write(bytes,0,n) };out.toString("UTF-8") }
        } finally { connection.disconnect() }
    }
    private fun startTests(ids:JSONArray) {
        tests?.cancel();engine.cancelTests()
        val data=store.snapshot();val generation=current.get().generation
        val selected=(0 until ids.length()).map { ids.getLong(it) }.distinct()
        require(selected.size<=10000)
        if(selected.isEmpty()) { event("testing","[]");event("message","没有可测速的节点");return }
        val outbounds=data.nodes.associate { it.id to it.outbound }
        event("testing",JSONArray(selected).toString())
        event("message","正在测试 ${selected.size} 个节点")
        tests=scope.launch {
            val semaphore=Semaphore((data.setting("testConcurrency","4").toIntOrNull() ?:4).coerceIn(1,16))
            val pending=java.util.concurrent.ConcurrentLinkedQueue<NodeStatusUpdate>()
            // One store transaction per second instead of one full-state rewrite per tested node.
            fun flush() {
                val batch=generateSequence { pending.poll() }.toList();if(batch.isEmpty()) return
                store.updateNodeStatus(batch)
                batch.forEach { event("test",JSONObject().put("id",it.id).put("ping",it.ping).toString()) }
            }
            val flusher=launch { while(isActive) { delay(1000);flush() } }
            try {
                coroutineScope { selected.forEach { id -> launch { semaphore.withPermit {
                    val outbound=outbounds[id] ?: return@withPermit
                    val ping=try { engine.test(ConfigBuilder.build(data,Purpose.TEST,id),data.setting("testUrl","https://www.gstatic.com/generate_204"),data.setting("testTimeout","10000").toInt().coerceIn(1000,60000)) } catch(e:Exception) { if(e is CancellationException)throw e;-1 }
                    ensureActive()
                    pending.add(NodeStatusUpdate(id,outbound,ping,if(ping>0)3 else 1))
                } } } }
            } finally { flusher.cancel();withContext(NonCancellable) { flush() } }
            event("testing","[]")
            event("message","测速完成：${selected.size} 个节点")
        }
    }
    private fun startSpeed(request:JSONObject) {
        speed?.cancel();speedJob?.cancel()
        val id=request.getLong("id");val mode=when(request.optString("mode")) { "download"->"download";"upload"->"upload";"full"->"download_upload";else->"simple_download" }
        val data=store.snapshot()
        event("speed",JSONObject().put("stage","connecting").put("done",false).toString())
        speedJob=scope.launch {
            var session:SpeedTestSession?=null
            try {
                session=Libcore.newSpeedTestSession(id.toString(),ConfigBuilder.build(data,Purpose.TEST,id),platform,mode,data.setting("speedTimeout","10000").toInt(),data.setting("speedServerList","https://www.speedtest.net/api/js/servers"),data.setting("speedFallbackList","https://www.speedtest.net/speedtest-servers-static.php"),data.setting("speedDownloadUrl","http://cachefly.cachefly.net/1mb.test"))
                speed=session;session.start();while(isActive) {
                val result=session.result
                event("speed",JSONObject().put("stage",result.stage).put("download",result.downloadBitsPerSecond).put("upload",result.uploadBitsPerSecond).put("latency",result.latencyMs).put("error",result.error).put("done",result.done).toString())
                if(result.done)break
                delay(300)
            } } catch(e:CancellationException) { throw e }
            catch(e:Exception) { event("speed",JSONObject().put("stage","error").put("done",true).put("error",safeError(e)).toString()) }
            finally { session?.let { runCatching { it.cancel();it.close() };if(speed===it)speed=null } }
        }
    }
    private fun startStun(server:String) {
        require(server.length in 3..253 && !server.any { it.isWhitespace() || it in "\"'/?#@" }) { "STUN服务器地址无效" }
        stunJob?.cancel();val version=++stunVersion
        event("stun",JSONObject().put("running",true).toString())
        stunJob=scope.launch {
            try { stunAccess.withLock {
                ensureActive()
                val result=Libcore.stunTest(server)
                ensureActive()
                if(stunVersion==version)event("stun",JSONObject().put("running",false).put("success",result.success).put("text",result.text.take(16384)).toString())
            } } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(stunVersion==version)event("stun",JSONObject().put("running",false).put("error",safeError(e)).toString()) }
        }
    }
    private suspend fun queryIp() {
        val connected=current.get().state==2;val token=current.get().generation;val data=store.snapshot()
        val url=data.setting("exitProbeUrl","https://www.cloudflare.com/cdn-cgi/trace")
        val body=if(connected)withTimeout(8000) { suspendCancellableCoroutine<String> { continuation ->
            val client=Libcore.newHttpClient();client.modernTLS();client.tryBoxOutbound()
            val task=scope.launch {
                try { val request=client.newRequest();request.setURL(url);val text=request.execute().contentString.value;require(text.length<=16384);if(continuation.isActive)continuation.resumeWith(Result.success(text)) }
                catch(e:Exception) { if(continuation.isActive)continuation.resumeWith(Result.failure(e)) }
                finally { client.close() }
            }
            continuation.invokeOnCancellation { client.close();task.cancel() }
        } } else {
        val connection=URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout=6000;connection.readTimeout=6000;connection.instanceFollowRedirects=false
            require(connection.responseCode==200) { "出口查询失败 (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use { r->val out=StringBuilder();val chars=CharArray(2048);while(true) { val n=r.read(chars);if(n<0)break;require(out.length+n<=16384) { "出口查询响应过大" };out.append(chars,0,n) };out.toString() }
        } finally { connection.disconnect() }
        }
        val ip=body.lineSequence().firstOrNull { it.startsWith("ip=") }?.substringAfter('=') ?: runCatching { JSONObject(body).optString("ip") }.getOrDefault(body.trim())
        require(ip.matches(Regex("[0-9a-fA-F.:]+"))) { "出口返回格式无效" }
        if(current.get().generation==token)event("ip",ip)
    }
    private fun startRuleUpdates() {
        ruleUpdates?.cancel();ruleUpdates=scope.launch {
            delay(when(store.snapshot().setting("rulesUpdateDelay","30s")){"0s"->0L;"15s"->15000L;"1m"->60000L;"5m"->300000L;else->30000L})
            while(isActive) {
            val data=store.snapshot();val now=System.currentTimeMillis()
            val interval=when(data.setting("rulesUpdateInterval","24h")){"off"->Long.MAX_VALUE;"6h"->6L*3600000;"12h"->12L*3600000;"3d"->3L*86400000;"7d"->7L*86400000;else->24L*3600000}
            data.settings.filterKeys { it.startsWith("smartUrl.") }.forEach { (key,url) ->
                if(url.isNotBlank() && com.zane.zanebox.subscription.SubscriptionClient.ruleSetFormat(url)!="binary") {
                    val service=key.substringAfter('.');val previous=data.setting("smartUpdated.$service","0").toLongOrNull() ?: 0
                    if(now-previous>=interval) runCatching {
                        val content=com.zane.zanebox.subscription.SubscriptionClient.fetchSmartRules(url,data.settings)
                        require(content.toByteArray().size<=4*1024*1024)
                        store.update { d->d.copy(settings=d.settings+("smartRules.$service" to content)+("smartUpdated.$service" to now.toString())) }
                        event("message","${service} 规则已更新，应用修改后生效")
                    }.onFailure { event("message","规则更新失败：$service") }
                }
            }
            delay(60000)
        } }
    }
    /** Status changes always go through startForeground; per-second rate text only re-posts when it actually changed. */
    @Synchronized private fun notification(text:String,rate:Boolean=false) {
        val data=store.snapshot()
        val group=if(data.bool("showGroupInNotification",false))data.groups.firstOrNull { it.id==data.selectedGroupId }?.name.orEmpty() else ""
        val title=if(group.isBlank())"zanebox" else "zanebox · $group"
        val key="$title|$text"
        if(rate && (!inForeground || key==notificationText)) return
        notificationText=key
        val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(!channelReady && Build.VERSION.SDK_INT>=26) { manager.createNotificationChannel(NotificationChannel("proxy","zanebox 代理",NotificationManager.IMPORTANCE_LOW));channelReady=true }
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,owner.javaClass).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val built=NotificationCompat.Builder(this,"proxy").setSmallIcon(R.drawable.ic_zanebox).setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"断开",stop).build()
        if(rate) manager.notify(1,built) else { owner.startForeground(1,built);inForeground=true }
    }
    @Synchronized private fun foregroundStop() { inForeground=false;notificationText="";if(Build.VERSION.SDK_INT>=24)owner.stopForeground(Service.STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") owner.stopForeground(true) }
    fun revoke() { dispatch("stop","") }
    fun destroy() {
        releaseWakeLock();runCatching { unregisterReceiver(wakeReceiver) }
        commands.close();scope.cancel();engine.cancelTests();speed?.cancel()
        // onDestroy runs on the main thread; a start stuck in native code must not turn into an ANR.
        val finished=runBlocking(Dispatchers.IO) { withTimeoutOrNull(4000) { access.withLock { val data=store.snapshot();val selections=readSelections(data);runCatching { recordTraffic(data,engine.close(),selections) };platform.close();persistTraffic() } } }
        if(finished==null) runCatching { platform.close() }
        callbacks.kill();store.close()
    }
    private fun releaseWakeLock() { wakeLock?.let { if(it.isHeld)it.release() };wakeLock=null }
}
