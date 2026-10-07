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
import com.zane.zanebox.subscription.SubscriptionScheduler
import com.zane.zanebox.subscription.SubscriptionPlan
import com.zane.zanebox.subscription.SubscriptionUpdater
import com.zane.zanebox.subscription.update
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
    private val subscriptionApply=Mutex()
    private val commands=Channel<Pair<RuntimeCommand,String>>(Channel.UNLIMITED)
    private val current=AtomicReference(RuntimeSnapshot())
    private val callbacks=RemoteCallbackList<IRuntimeCallback>()
    private lateinit var store:ZaneStore
    private lateinit var platform:NativePlatform
    private lateinit var engine:SingBoxEngine
    @Volatile private var appliedData:AppData?=null
    private var appliedConfig:String?=null
    private val power by lazy {getSystemService(android.os.PowerManager::class.java)}
    private var idle=false
    @Volatile private var subscriptionBusy=false
    private var ruleScheduleSignature=""
    private val ready=CompletableDeferred<Unit>()
    private val visibleClients=ConcurrentHashMap<String,Long>()
    private var sampler:Job?=null
    private var tests:Job?=null
    private var speed:SpeedTestSession?=null
    private var speedJob:Job?=null
    private var ruleUpdates:Job?=null
    private var subscriptionUpdates:Job?=null
    @Volatile private var destroyed=false
    private var ownsSubscriptionAlarm=false
    private val subscriptionAlarm by lazy { PendingIntent.getBroadcast(this,2,Intent("$packageName.SUBSCRIPTION_UPDATE").setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
    private var stunJob:Job?=null
    private val stunAccess=Mutex()
    @Volatile private var stunVersion=0L
    private var sessionTx=0L
    private var sessionRx=0L
    private var lastProxySelection=""
    @Volatile private var lastAutoNode=0L
    @Volatile private var cachedSecret:String?=null
    @Volatile private var apiPort=9090
    private var publishedFlags=""
    private var notificationText=""
    private var inForeground=false
    private var channelReady=false
    private var wakeLock:android.os.PowerManager.WakeLock?=null
    private val wakeReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==Intent.ACTION_SCREEN_ON && current.get().connectionState==RuntimeState.CONNECTED)dispatch("wakeReset","")
            if(intent.action=="$packageName.SUBSCRIPTION_UPDATE")scope.launch {ready.await();checkSubscriptionUpdates()}
            if(intent.action==android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)scope.launch {ready.await();access.withLock {syncIdle()}}
        }
    }
    private val traffic=RuntimeTraffic({store.snapshot()}) {store.putSetting("trafficData",it)}
    private val connectionBytes get()=traffic.connectionBytes
    private val counters get()=traffic.counters
    private val binder=object:IRuntime.Stub() {
        override fun getSnapshot()=current.get().json()
        override fun registerCallback(callback:IRuntimeCallback) { callbacks.register(callback);callback.onSnapshot(current.get().json());callback.onEvent("autoSelection",lastAutoNode.toString()) }
        override fun unregisterCallback(callback:IRuntimeCallback) { callbacks.unregister(callback) }
        override fun command(action:String,payload:String) { dispatch(action,payload) }
    }
    fun create() {
        store=ZaneStore(this,autoLoad=false)
        platform=NativePlatform(this,vpn) { selector,tag ->
            if(selector.startsWith("merge-")) {
                val id=selector.removePrefix("merge-").toLongOrNull();val node=tag.removePrefix("node-").toLongOrNull()
                if(id!=null && node!=null) scope.launch { store.update { d -> d.copy(merges=d.merges.map { if(it.id==id) it.copy(selectedId=node) else it }) };event("message","分流组选择已更新") }
            }
        }
        engine=SingBoxEngine(platform)
        androidx.core.content.ContextCompat.registerReceiver(this,wakeReceiver,IntentFilter(Intent.ACTION_SCREEN_ON).apply{addAction("$packageName.SUBSCRIPTION_UPDATE");addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)},androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            try {
                access.withLock {
                ensureActive();store.reload();ensureActive();platform.initialize();ensureActive()
                val saved=store.snapshot().setting("trafficData")
                if(saved.isNotBlank())runCatching { val data=JSONObject(saved);listOf("apps","domains","nodes").forEach { counters.put(it,data.optJSONObject(it) ?: JSONObject()) } }
                ready.complete(Unit)
                }
                for((action,payload) in commands)execute(action,payload)
            } catch(e:CancellationException) {ready.cancel(e);throw e}
            catch(e:Exception) {ready.completeExceptionally(e);try {failed(e);event("message",safeError(e))} finally {owner.stopSelf()}}
            finally {if(destroyed)withContext(NonCancellable) {access.withLock {cleanupCore()}}}
        }
    }
    fun bind():IBinder = binder
    fun startCommand(intent:Intent?):Int {
        notification(if(intent?.action=="stop")"正在断开" else if(current.get().connectionState==RuntimeState.CONNECTED)"已连接" else "正在连接")
        if(intent?.action=="stop")dispatch("stop","")
        else if(intent?.action!="foreground")dispatch("start","")
        return Service.START_STICKY
    }
    private fun dispatch(action:String,payload:String) {
        if(action=="uiVisible") {
            val (token,value)=payload.split('|',limit=2).takeIf{it.size==2} ?: return
            if(!token.matches(Regex("[0-9a-f-]{36}")))return
            if(value=="true")visibleClients[token]=SystemClock.elapsedRealtime() else visibleClients.remove(token)
            return
        }
        if(action in setOf("ip","validate","logs","systemLogs","connections","panel"))scope.launch {ready.await();RuntimeCommand.fromWire(action)?.let {execute(it,payload)}}
        else RuntimeCommand.fromWire(action)?.let {commands.trySend(it to payload)}
    }
    private suspend fun execute(action:RuntimeCommand,payload:String) {
            val began=SystemClock.elapsedRealtime()
            try { when(action) {
                RuntimeCommand.START -> access.withLock { if(current.get().connectionState !in listOf(RuntimeState.STARTING,RuntimeState.CONNECTED,RuntimeState.STOPPING)) startCore() }
                RuntimeCommand.STOP -> access.withLock { stopCore() }
                RuntimeCommand.RELOAD,RuntimeCommand.AUTO_RELOAD -> access.withLock {reloadCore(action==RuntimeCommand.AUTO_RELOAD)}
                RuntimeCommand.SUBSCRIPTION_UPDATED -> applySubscription(payload)
                RuntimeCommand.LIVE_SETTINGS -> access.withLock {applyLiveSettings(store.snapshot());if(current.get().connectionState==RuntimeState.CONNECTED)startRuleUpdates()}
                RuntimeCommand.SELECT -> access.withLock { selectNode(payload.toLong()) }
                RuntimeCommand.SELECT_AUTO -> access.withLock {
                    val before=store.snapshot()
                    check(current.get().connectionState !in listOf(RuntimeState.STARTING,RuntimeState.STOPPING)) { "请等待连接操作完成" }
                    val previous=appliedData
                    val base=if(current.get().connectionState==RuntimeState.CONNECTED)previous ?: before else before
                    val next=base.copy(settings=base.settings+("homeAutoSelect" to "true"))
                    val config=runtimeConfig(next);engine.validate(config)
                    if(!before.bool("homeAutoSelect")) {
                        val running=current.get().connectionState==RuntimeState.CONNECTED
                        store.update { it.copy(settings=it.settings+("homeAutoSelect" to "true")) }
                        try { if(running) { stopCore(terminal=false,syncSelection=false);startCore(next,config) };event("message","已启用自动选择") }
                        catch(e:Exception) { store.update { it.copy(settings=it.settings+("homeAutoSelect" to before.setting("homeAutoSelect","false"))) };if(running)runCatching{startCore(previous)};throw e }
                    }
                }
                RuntimeCommand.RESTORE -> access.withLock { restoreData(payload) }
                RuntimeCommand.VALIDATE -> { val args=JSONObject(payload);val name=args.getString("name");val filename=args.getString("file");require(filename.matches(Regex("validate-[0-9a-f-]{36}\\.json")));val file=File(noBackupFilesDir,filename);val error=try { require(file.length() in 1..64*1024*1024L);engine.validate(file.readText());"" } catch(e:Exception) { safeError(e) } finally { file.delete() };event("validation",JSONObject().put("name",name).put("error",error).toString()) }
                RuntimeCommand.TEST -> startTests(JSONArray(payload))
                RuntimeCommand.CANCEL_TESTS -> { tests?.cancel();engine.cancelTests();event("testing","[]");event("message","测速已取消") }
                RuntimeCommand.LOGS -> { val log=File(cacheDir,"neko.log");event("logs",JSONArray(LogTail.read(log)).toString()) }
                RuntimeCommand.CLEAR_LOGS -> { File(cacheDir,"neko.log").writeText("");event("logs","[]") }
                RuntimeCommand.SYSTEM_LOGS -> { val process=ProcessBuilder("logcat","-d","-t","500").redirectErrorStream(true).start();val lines=process.inputStream.bufferedReader().use { it.readLines().takeLast(500) };process.waitFor();event("logs",JSONArray(lines).toString()) }
                RuntimeCommand.WAKE_RESET -> access.withLock { if(current.get().connectionState==RuntimeState.CONNECTED && store.snapshot().bool("wakeResetConnections")) {Libcore.resetAllConnections(true);android.util.Log.i("LinksRuntime","wakeReset applied=true thread=${Thread.currentThread().name}")} }
                RuntimeCommand.ASSET -> access.withLock { installAsset(payload) }
                RuntimeCommand.IP -> queryIp()
                RuntimeCommand.TRAFFIC -> event("traffic",traffic.trafficJson())
                RuntimeCommand.RESET_TRAFFIC -> { synchronized(counters) { listOf("apps","domains","nodes").forEach { counters.put(it,JSONObject()) } };traffic.persistTraffic();event("traffic",traffic.trafficJson());event("message","累计统计已清空") }
                RuntimeCommand.STATS -> { store.update { it.copy(settings=it.settings+("statsEnabled" to payload.toBoolean().toString())) };event("message","统计状态已保存") }
                RuntimeCommand.CONNECTIONS -> event("connections",api("/connections"))
                RuntimeCommand.PANEL -> { check(current.get().connectionState==RuntimeState.CONNECTED) { "请先连接代理" };check(store.snapshot().bool("clashApi") || store.snapshot().bool("statsEnabled")) { "请先启用 Clash API 或流量统计" };api("/version");val port=apiPort;event("panel","http://127.0.0.1:$port/ui/#/?hostname=http%3A%2F%2F127.0.0.1%3A$port&secret=${ownerSecret()}") }
                RuntimeCommand.CLOSE_CONNECTION -> { require(payload.matches(Regex("[a-zA-Z0-9-]{1,128}")));api("/connections/$payload","DELETE");event("connections",api("/connections")) }
                RuntimeCommand.CLOSE_ALL_CONNECTIONS -> { api("/connections","DELETE");event("connections",api("/connections")) }
                RuntimeCommand.SPEED -> startSpeed(JSONObject(payload))
                RuntimeCommand.CANCEL_SPEED -> { speed?.cancel();speedJob?.cancel();event("speed",JSONObject().put("stage","cancelled").put("done",true).put("error","速度测试已取消").toString());event("message","速度测试已取消") }
                RuntimeCommand.STUN -> startStun(payload)
                RuntimeCommand.CANCEL_STUN -> { stunVersion++;stunJob?.cancel();event("stun",JSONObject().put("running",false).put("error","已取消").toString()) }
            } } catch(e:TimeoutCancellationException) { event("message","操作超时，请重试");if(action==RuntimeCommand.START)owner.stopSelf() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { event("message",safeError(e));if(action==RuntimeCommand.STUN)event("stun",JSONObject().put("running",false).put("error",safeError(e)).toString());if(action==RuntimeCommand.START) { failed(e);owner.stopSelf() } }
            finally {android.util.Log.d("LinksRuntime","command=${action.wire} elapsedMs=${SystemClock.elapsedRealtime()-began}")}
    }
    private fun safeError(e:Throwable):String = (e.message ?: e.javaClass.simpleName).replace(Regex("(?i)(password|secret|uuid|token)([\"'\\s:=]+)[^,}\\s]+"),"$1$2[隐藏]").take(300)
    private fun event(kind:String,payload:String) = synchronized(callbacks) {
        val n=callbacks.beginBroadcast()
        try { repeat(n) { runCatching { callbacks.getBroadcastItem(it).onEvent(kind,payload) } } } finally { callbacks.finishBroadcast() }
    }
    private fun publish(snapshot:RuntimeSnapshot,data:AppData=store.snapshot()) {
        val value=snapshot.copy(pendingManual=snapshot.connectionState==RuntimeState.CONNECTED && appliedData?.let { com.zane.zanebox.ui.hasManualSettingsPending(it,data) }==true)
        current.set(value)
        // Rate samples republish every second; the tile and persisted flags only need connection transitions.
        val flags="${value.connectionState==RuntimeState.CONNECTED}|${vpn==null}"
        val changed=synchronized(this) { (flags!=publishedFlags).also { publishedFlags=flags } }
        if(changed) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("connected",value.connectionState==RuntimeState.CONNECTED).putString("serviceMode",if(vpn==null)"proxy" else "vpn").apply();if(Build.VERSION.SDK_INT>=24)android.service.quicksettings.TileService.requestListeningState(this,android.content.ComponentName(this,QuickTileService::class.java)) }
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
        val root=JSONObject(ConfigBuilder.build(com.zane.zanebox.config.withBuiltinSmartRules(data) { assets.open(it).bufferedReader().use { reader->reader.readText() } },Purpose.MAIN,runtimeSecret=ownerSecret()))
        root.optJSONObject("experimental")?.optJSONObject("clash_api")?.apply {
            put("external_ui",File(filesDir,"core-assets/yacd").absolutePath)
            put("access_control_allow_origin",JSONArray().put("http://127.0.0.1:${data.setting("apiPort")}"))
            put("access_control_allow_private_network",false)
        }
        return root.toString()
    }
    private fun applyLiveSettings(data:AppData,running:Boolean=current.get().connectionState==RuntimeState.CONNECTED) {
        val signature=data.settings.filterKeys {it.startsWith("smartUrl.") || it in setOf("rulesUpdateInterval","rulesUpdateDelay")}.toString()
        if(running && signature!=ruleScheduleSignature) {ruleScheduleSignature=signature;startRuleUpdates()}
        Libcore.setNetworkChangeResetConnections(data.bool("networkReset"))
        if(!running || !data.bool("acquireWakeLock"))releaseWakeLock()
        else if(wakeLock==null)wakeLock=(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"zanebox:proxy").apply {setReferenceCounted(false);acquire()}
    }
    private suspend fun applySubscription(payload:String) {
        val args=JSONObject(payload);val request=args.getString("request");val id=args.getLong("groupId")
        fun completed(error:String="")=event("subscriptionApplied",JSONObject().put("request",request).put("error",error).toString())
        scope.launch {
            try {withTimeout(7*60000L) {subscriptionApply.withLock {
                var ownedTest:Job?=null
                try {
                    ownedTest=access.withLock {
                        check(reloadCore(automatic=true,subscription=true)) { "订阅已保存，但内核应用失败，原连接已保留" }
                        val data=store.snapshot()
                        val ids=data.nodes.filter { it.groupId==id && data.groups.any { g->g.id==id && g.enabled } }.map{it.id}
                        startTests(JSONArray(ids))
                    }
                    // Wait outside the command queue, so stop/select stay responsive.
                    ownedTest?.join()
                    completed(if(ownedTest?.isCancelled==true)"订阅已应用，延迟测试被取消" else "")
                } finally {ownedTest?.takeIf{it.isActive}?.let { job ->
                    job.cancel()
                    if(tests===job){engine.cancelTests();event("testing","[]")}
                    withContext(NonCancellable){withTimeoutOrNull(5000){job.join()}}
                }}
            }}} catch(e:TimeoutCancellationException) {completed("订阅自动应用或测速超时，将自动重试")}
            catch(e:CancellationException) { throw e } catch(e:Exception) {completed(safeError(e))}
        }
    }
    private suspend fun reloadCore(automatic:Boolean,subscription:Boolean=false):Boolean {
        if(current.get().connectionState!=RuntimeState.CONNECTED){event("message","设置已保存，下次连接时应用");return true}
        val previous=appliedData ?: error("运行配置不可用")
        val saved=store.snapshot()
        val incoming=if(subscription)saved.copy(settings=saved.settings+com.zane.zanebox.ui.manualApplyDefaults.mapValues { (key,default)->previous.setting(key,default) }) else saved
        if(automatic && !subscription && com.zane.zanebox.ui.hasManualSettingsPending(previous,incoming)) {
            event("message","存在需要手动应用的设置，请手动应用修改使配置生效");return false
        }
        val next=try {runtimeConfig(incoming).also{engine.validate(it)}} catch(e:Exception) {
            event("message","配置校验失败，已保留原连接：${safeError(e)}；请应用修改后重试");return false
        }
        if(automatic && next==appliedConfig) {
            applyLiveSettings(incoming);appliedData=incoming
            event("message","设置已应用，无需重启");return true
        }
        stopCore(terminal=false,syncSelection=false)
        try {startCore(incoming,next);event("message",if(automatic)"设置已自动应用" else "设置已应用")}
        catch(failure:Exception) {
            try {startCore(previous);event("message","重载失败，已恢复原连接；保存设置仍待应用修改：${safeError(failure)}")}
            catch(recovery:Exception) {event("message","重载失败，恢复原连接也失败：${safeError(recovery)}")}
            return false
        }
        return true
    }
    private suspend fun startCore(overrideData:AppData?=null,preparedConfig:String?=null) {
        val previous=current.get();publish(RuntimeSnapshot(state=RuntimeState.STARTING.code,generation=previous.generation+1));notification("正在连接")
        try {
            check(vpn==null || VpnService.prepare(this)==null) { "请先授权VPN" }
            // InitCore extracts geoip/geosite databases and then YACD asynchronously; YACD's index marks the end.
            // Local geo rule-sets and the panel both need these files, so this only waits on a first run.
            withTimeout(30000) { while(!File(filesDir,"core-assets/yacd/index.html").isFile)delay(50) }
            val data=overrideData ?: store.snapshot();val config=preparedConfig ?: runtimeConfig(data)
            platform.metered=data.bool("meteredNetwork")
            val httpProxy=data.bool("appendHttpProxy")
            require(!httpProxy || !data.bool("disableMixedInbound")) { "追加 HTTP 代理需要开启本地 mixed 入口" }
            require(!httpProxy || Build.VERSION.SDK_INT>=29) { "追加 HTTP 代理需要 Android 10 或更新版本" }
            platform.httpProxyPort=if(httpProxy)data.setting("mixedPort").toInt() else 0
            platform.httpProxyBypass=data.setting("httpProxyBypass").split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }
            apiPort=data.setting("apiPort").toIntOrNull() ?: 9090
        Libcore.setNetworkChangeResetConnections(data.bool("networkReset"))
            engine.start(config)
            applyLiveSettings(data,running=true)
            appliedData=data;appliedConfig=config;syncIdle()
            lastProxySelection=engine.configuredProxyDefault;lastAutoNode=0;event("autoSelection","0")
            sessionTx=0;sessionRx=0
            val inbounds=JSONObject(config).optJSONArray("inbounds") ?: JSONArray()
            val mixed=(0 until inbounds.length()).map{inbounds.getJSONObject(it)}.firstOrNull{it.optString("type") in listOf("mixed","http")}
            val mixedHost=when(val listen=mixed?.optString("listen","127.0.0.1").orEmpty()){ "","0.0.0.0"->"127.0.0.1";"::"->"::1";else->listen }
            val snapshot=RuntimeSnapshot(state=RuntimeState.CONNECTED.code,started=SystemClock.elapsedRealtime(),generation=current.get().generation,mixedHost=mixedHost,mixedPort=mixed?.optInt("listen_port") ?:0)
            publish(snapshot);notification("已连接")
            startSampler(snapshot.generation)
            armSubscriptionAlarm()
            startRuleUpdates()
            val warnings=ConfigBuilder.targetWarnings(data)
            if(warnings.isNotEmpty()) event("message",warnings.joinToString("\n")) else event("message","已连接")
        } catch(e:Exception) { failed(e);throw e }
    }
    private suspend fun installAsset(filename:String) {
        require(filename.matches(Regex("asset-[0-9a-f-]{36}-(geoip|geosite)\\.db"))) { "资源暂存名无效" }
        val stage=File(noBackupFilesDir,filename)
        try {
            check(current.get().connectionState !in listOf(RuntimeState.STARTING,RuntimeState.STOPPING)) { "请等待连接操作完成" }
            withTimeout(30000) { while(!File(filesDir,"core-assets/yacd/index.html").isFile)delay(50) }
            require(stage.length() in 16..64*1024*1024L)
            val bytes=stage.readBytes();val kind=filename.substringAfterLast('-').removeSuffix(".db")
            val code=com.zane.zanebox.config.geoAssetCode(kind,bytes)
            val asset=AtomicFile(File(filesDir,"core-assets/$kind.db"))
            val previous=if(asset.baseFile.exists())asset.openRead().use { it.readBytes() } else null
            fun write(content:ByteArray) { val output=asset.startWrite();try { output.write(content);asset.finishWrite(output) } catch(e:Exception) { asset.failWrite(output);throw e } }
            val restart=current.get().connectionState==RuntimeState.CONNECTED
            val runningData=if(restart)appliedData ?: error("运行配置不可用") else null
            try {
                if(restart)stopCore(terminal=false)
                write(bytes)
                val config=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","direct")))
                    .put("route",JSONObject().put("final","direct").put("rule_set",JSONArray().put(JSONObject().put("type","local").put("tag","asset-check").put("format","binary").put("path","$kind:$code")))
                        .put("rules",JSONArray().put(JSONObject().put("rule_set",JSONArray().put("asset-check")).put("action","route").put("outbound","direct"))))
                engine.validate(config.toString())
                if(restart)startCore(runningData)
            } catch(e:Exception) {
                try {if(previous!=null)write(previous)else asset.delete();if(restart)startCore(runningData)}
                catch(recovery:Exception) {e.addSuppressed(recovery);throw IllegalStateException("资源更新失败，恢复原文件或代理失败：${safeError(recovery)}",e)}
                throw IllegalStateException("资源更新失败，已恢复原文件"+(if(restart)"和代理连接" else "")+"：${safeError(e)}",e)
            }
            event("assetInstalled","$kind 已更新并通过内核校验"+(if(restart)"，代理已重启" else "，下次连接时生效"))
        } finally { stage.delete() }
    }
    private fun failed(error:Exception) {
        releaseWakeLock()
        sampler?.cancel();ruleUpdates?.cancel();runCatching { engine.close() }
        publish(RuntimeSnapshot(state=RuntimeState.FAILED.code,generation=current.get().generation,error=safeError(error)),appliedData ?: store.data.value)
        foregroundStop()
    }
    private fun stopCore(terminal:Boolean=true,syncSelection:Boolean=true) {
        if(terminal)subscriptionUpdates?.cancel()
        releaseWakeLock()
        if(current.get().connectionState==RuntimeState.STOPPED) { if(terminal) {cancelSubscriptionAlarm();foregroundStop();owner.stopSelf()};return }
        val data=store.snapshot();val selections=readSelections(data);if(syncSelection)syncDefaultSelection(data,selections)
        if(data.bool("statsEnabled"))runCatching { traffic.sampleConnections(api("/connections")) }
        val old=current.get();publish(old.copy(state=RuntimeState.STOPPING.code));sampler?.cancel();sampler=null;ruleUpdates?.cancel();ruleUpdates=null
        try { recordTraffic(data,engine.close(),selections);traffic.persistTraffic();connectionBytes.clear() } finally { publish(RuntimeSnapshot(generation=old.generation+1,txTotal=sessionTx,rxTotal=sessionRx));if(terminal) {cancelSubscriptionAlarm();foregroundStop();owner.stopSelf()};event("message","已断开") }
    }
    private suspend fun selectNode(id:Long) {
        val before=store.snapshot();val node=before.nodes.firstOrNull { it.id==id } ?: error("节点不存在")
        require(before.groups.any { it.id==node.groupId && it.enabled }) { "节点分组已禁用" }
        check(current.get().connectionState !in listOf(RuntimeState.STARTING,RuntimeState.STOPPING)) { "请等待连接操作完成" }
        if(current.get().connectionState==RuntimeState.CONNECTED) { collectTraffic(before);publish(current.get().copy(txTotal=sessionTx,rxTotal=sessionRx)) }
        val running=current.get().connectionState==RuntimeState.CONNECTED
        val previous=appliedData
        val selectionBase=if(running)previous ?: before else before
        val selectionData=selectionBase.copy(settings=selectionBase.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to (selectionBase.nodes.firstOrNull{it.id==id}?.groupId ?: node.groupId).toString())+("homeAutoSelect" to "false"))
        if(running)require(selectionBase.nodes.any{it.id==id}) { "节点尚未应用，请先应用修改" }
        store.update { it.copy(settings=it.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to node.groupId.toString())+("homeAutoSelect" to "false")) }
        try { if(current.get().connectionState==RuntimeState.CONNECTED && (selectionBase.bool("homeAutoSelect") || engine.configuredProxyDefault=="home-auto" || !engine.select("node-$id"))) { stopCore(terminal=false,syncSelection=false);startCore(selectionData) };syncAppliedSelection(id);event("autoSelection","0");event("message","默认节点已切换") }
        catch(e:Exception) { store.update { it.copy(settings=it.settings+("selectedNodeId" to before.selectedNodeId.toString())+("selectedGroupId" to before.selectedGroupId.toString())+("homeAutoSelect" to before.setting("homeAutoSelect","false"))) };if(running && current.get().connectionState!=RuntimeState.CONNECTED)runCatching{startCore(previous)};throw e }
    }
    private suspend fun restoreData(name:String) {
        require(name.matches(Regex("restore-[0-9a-f-]{36}\\.json"))) { "恢复文件名无效" }
        val file=File(noBackupFilesDir,name)
        try {
            require(file.length() in 1..64*1024*1024L) { "恢复文件大小无效" }
            val incoming=AppData.fromJson(file.readText(Charsets.UTF_8))
            if(incoming.nodes.isNotEmpty())engine.validate(runtimeConfig(incoming))
            val running=current.get().connectionState==RuntimeState.CONNECTED
            val previousRuntime=appliedData
            val modeChanged=incoming.setting("serviceMode")!=if(vpn==null)"proxy" else "vpn"
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
                if(running)runCatching { startCore(previousRuntime) }.onFailure { event("message","恢复已回滚，原服务需要手动重连") }
                throw failure
            }
        } finally { file.delete() }
    }
    private fun syncIdle() {
        if(current.get().connectionState!=RuntimeState.CONNECTED && appliedData==null)return
        val next=SamplingPolicy.pauseCore(power.isDeviceIdleMode,subscriptionBusy,appliedConfig.orEmpty().contains("\"wireguard\""))
        if(next)engine.sleep() else engine.wake()
        if(idle!=next)android.util.Log.i("LinksRuntime","idle=$next")
        idle=next
    }
    private fun startSampler(token:Long) {
        sampler?.cancel()
        sampler=scope.launch {
            var last=SystemClock.elapsedRealtime();var selections=JSONObject()
            var lastControllers=0L;var lastPersist=last;var lastSubscription=0L;var statsError=false
            while(isActive && current.get().generation==token && current.get().connectionState==RuntimeState.CONNECTED) {
                val interval=SamplingPolicy.interval(power.isInteractive,visibleClients.values.any {SystemClock.elapsedRealtime()-it<15000})
                delay(interval)
                val data=store.snapshot();val now=SystemClock.elapsedRealtime()
                if(now-lastSubscription>=30000) {lastSubscription=now;checkSubscriptionUpdates()}
                val poll=now-lastControllers>=if(interval==1000L)5000L else 30000L
                if(poll) {lastControllers=now;readSelections(data).takeIf { it.length()>0 }?.let { selections=it }}
                val connections=if(poll && data.bool("statsEnabled"))try {api("/connections").also {statsError=false}} catch(e:Exception) {
                    if(!statsError){event("message","连接统计采样失败：${safeError(e)}");android.util.Log.w("LinksRuntime","connection statistics failed",e);statsError=true};null
                } else null
                access.withLock {
                    if(current.get().connectionState!=RuntimeState.CONNECTED || current.get().generation!=token) return@withLock
                    val sampledAt=SystemClock.elapsedRealtime();val duration=(sampledAt-last).coerceAtLeast(1);last=sampledAt
                    syncDefaultSelection(data,selections)
                    val traffic=engine.trackedTags.associateWith { tag -> engine.query(tag,"uplink").coerceAtLeast(0) to engine.query(tag,"downlink").coerceAtLeast(0) }
                    val (tx,rx)=recordTraffic(data,traffic,selections)
                    publish(current.get().copy(txRate=tx*1000/duration,rxRate=rx*1000/duration,txTotal=sessionTx,rxTotal=sessionRx),data)
                    notification("↑ ${tx*1000/duration} B/s  ↓ ${rx*1000/duration} B/s",rate=true,data=data)
                    connections?.let {try {this@ZaneRuntime.traffic.sampleConnections(it)} catch(e:Exception) {if(!statsError){event("message","连接统计解析失败：${safeError(e)}");statsError=true}}}
                    val persist=sampledAt-lastPersist>=60000
                    if(persist) {this@ZaneRuntime.traffic.persistTraffic();lastPersist=sampledAt}
                    android.util.Log.d("LinksRuntime","sample interval=$interval tags=${engine.trackedTags.size} controllers=$poll persisted=$persist")
                }
            }
        }
    }
    private fun collectTraffic(data:AppData):Pair<Long,Long> {
        val traffic=engine.trackedTags.associateWith { tag -> engine.query(tag,"uplink").coerceAtLeast(0) to engine.query(tag,"downlink").coerceAtLeast(0) }
        val selections=readSelections(data);syncDefaultSelection(data,selections)
        return recordTraffic(data,traffic,selections)
    }
    private fun readSelections(data:AppData):JSONObject {
        if(!data.bool("statsEnabled") && !data.bool("clashApi") && !data.bool("homeAutoSelect"))return JSONObject()
        val result=JSONObject();val pending=java.util.ArrayDeque(engine.trackedTags+"proxy");val seen=HashSet<String>()
        while(pending.isNotEmpty()) {
            val tag=pending.removeFirst()
            if(tag=="direct" || tag.startsWith("node-") || !seen.add(tag))continue
            val item=runCatching {JSONObject(api("/proxies/"+java.net.URLEncoder.encode(tag,"UTF-8")))}.getOrNull() ?: continue
            result.put(tag,item)
            item.optString("now").takeIf {it.isNotBlank()}?.let{pending.add(it)}
        }
        return result
    }
    private fun syncDefaultSelection(data:AppData,selections:JSONObject) {
        val tag=selections.optJSONObject("proxy")?.optString("now").orEmpty()
        if(tag.isBlank())return
        if(tag=="home-auto") {
            val id=selections.optJSONObject(tag)?.optString("now")?.removePrefix("node-")?.toLongOrNull() ?: 0L
            if(id!=lastAutoNode) { lastAutoNode=id;event("autoSelection",id.toString()) }
            return
        }
        val previous=lastProxySelection;lastProxySelection=tag
        if(previous.isBlank() || previous==tag)return
        val id=tag.takeIf { it.startsWith("node-") }?.removePrefix("node-")?.substringBefore('-')?.toLongOrNull() ?: return
        val node=data.nodes.firstOrNull { it.id==id && data.groups.any { g->g.id==it.groupId && g.enabled } } ?: return
        syncAppliedSelection(id)
        if(data.selectedNodeId==id && data.selectedGroupId==node.groupId && !data.bool("homeAutoSelect"))return
        store.update { latest ->
            val currentNode=latest.nodes.firstOrNull { it.id==id && latest.groups.any { g->g.id==it.groupId && g.enabled } }
            if(currentNode==null)latest else latest.copy(settings=latest.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to currentNode.groupId.toString())+("homeAutoSelect" to "false"))
        }
        if(data.bool("homeAutoSelect") && current.get().connectionState==RuntimeState.CONNECTED)dispatch("select",id.toString())
        event("message","默认节点已与内核同步")
    }
    private fun syncAppliedSelection(id:Long) {
        appliedData=appliedData?.let { active ->
            active.nodes.firstOrNull{it.id==id}?.let{node->active.copy(settings=active.settings+("selectedNodeId" to id.toString())+("selectedGroupId" to node.groupId.toString())+("homeAutoSelect" to "false"))} ?: active
        }
        appliedConfig=appliedData?.let {runCatching {runtimeConfig(it)}.getOrNull()}
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
        traffic.filterKeys { it!="direct" || data.bool("showDirectSpeed") }.forEach { (tag,bytes) ->
            val (up,down)=bytes
            tx+=up;rx+=down
            if(data.bool("statsEnabled"))nodeId(tag)?.let { id->this.traffic.addCounter("nodes",id.toString(),up,down,50) }
        }
        sessionTx+=tx;sessionRx+=rx
        return tx to rx
    }
    private fun api(path:String,method:String="GET"):String {
        check(current.get().connectionState==RuntimeState.CONNECTED) { "请先连接代理" }
        val connection=URL("http://127.0.0.1:$apiPort$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout=2000;connection.readTimeout=2000;connection.instanceFollowRedirects=false;connection.requestMethod=method
            connection.setRequestProperty("Authorization","Bearer ${ownerSecret()}")
            require(connection.responseCode in 200..299) { "控制接口请求失败 (${connection.responseCode})" }
            return if(connection.responseCode==204) "{}" else connection.inputStream.use { input -> val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096);val limit=if(path=="/connections")TrafficSamples.MAX_BYTES else 1024*1024;while(true) { val n=input.read(bytes);if(n<0)break;require(out.size()+n<=limit) { "控制响应过大" };out.write(bytes,0,n) };out.toString("UTF-8") }
        } finally { connection.disconnect() }
    }
    private fun startTests(ids:JSONArray):Job? {
        tests?.cancel();engine.cancelTests()
        val data=store.snapshot();val generation=current.get().generation
        val selected=(0 until ids.length()).map { ids.getLong(it) }.distinct()
        require(selected.size<=10000)
        if(selected.isEmpty()) { event("testing","[]");event("message","没有可测速的节点");return null }
        val outbounds=data.nodes.associate { it.id to it.outbound }
        event("testing",JSONArray(selected).toString())
        event("message","正在测试 ${selected.size} 个节点")
        tests=scope.launch {
            val semaphore=Semaphore((data.setting("testConcurrency").toIntOrNull() ?:4).coerceIn(1,16))
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
                    val ping=try { engine.test(ConfigBuilder.build(data,Purpose.TEST,id),data.setting("testUrl"),data.setting("testTimeout").toInt().coerceIn(1000,60000)) } catch(e:Exception) { if(e is CancellationException)throw e;-1 }
                    ensureActive()
                    pending.add(NodeStatusUpdate(id,outbound,ping,if(ping>0)3 else 1))
                } } } }
            } finally { flusher.cancel();withContext(NonCancellable) { flush() } }
            event("testing","[]")
            event("message","测速完成：${selected.size} 个节点")
        }
        return tests
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
        val connected=current.get().connectionState==RuntimeState.CONNECTED;val token=current.get().generation;val data=store.snapshot()
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
            val interval=when(data.setting("rulesUpdateInterval")){"off"->Long.MAX_VALUE;"6h"->6L*3600000;"12h"->12L*3600000;"3d"->3L*86400000;"7d"->7L*86400000;else->24L*3600000}
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
            val wait=if(interval==Long.MAX_VALUE)Long.MAX_VALUE else store.snapshot().settings.filterKeys { it.startsWith("smartUrl.") }.mapNotNull { (key,url) ->
                if(url.isBlank() || com.zane.zanebox.subscription.SubscriptionClient.ruleSetFormat(url)=="binary")null
                else ((store.snapshot().setting("smartUpdated.${key.substringAfter('.')}","0").toLongOrNull() ?: 0)+interval-System.currentTimeMillis()).coerceAtLeast(300000)
            }.minOrNull() ?: Long.MAX_VALUE
            delay(wait)
        } }
    }
    @Synchronized private fun cancelSubscriptionAlarm() {
        if(ownsSubscriptionAlarm){getSystemService(android.app.AlarmManager::class.java).cancel(subscriptionAlarm);ownsSubscriptionAlarm=false}
    }
    @Synchronized private fun armSubscriptionAlarm() {
        val alarms=getSystemService(android.app.AlarmManager::class.java)
        if(destroyed){cancelSubscriptionAlarm();return}
        val next=if(current.get().connectionState==RuntimeState.CONNECTED)store.snapshot().groups.filter{runCatching{SubscriptionPlan.scheduled(it)}.getOrDefault(false)}.minOfOrNull{SubscriptionPlan.nextAt(it)} else null
        if(next==null)cancelSubscriptionAlarm()
        else {
            val at=maxOf(next,System.currentTimeMillis()+1000)
            if(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms())try {
                alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,subscriptionAlarm)
            } catch(_:SecurityException) {alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,subscriptionAlarm)}
            else alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,at,subscriptionAlarm)
            ownsSubscriptionAlarm=true
        }
    }
    @Synchronized private fun checkSubscriptionUpdates() {
        if(destroyed || current.get().connectionState!=RuntimeState.CONNECTED || subscriptionUpdates?.isActive==true)return
        val due=store.snapshot().groups.filter{runCatching{SubscriptionPlan.scheduled(it) && SubscriptionPlan.nextAt(it)<=System.currentTimeMillis()}.getOrDefault(false)}
        if(due.isEmpty()){armSubscriptionAlarm();return}
        subscriptionUpdates=scope.launch {
            subscriptionBusy=true
            access.withLock {syncIdle()}
            try {
                for(group in due) {
                    if(current.get().connectionState!=RuntimeState.CONNECTED)break
                    val lock=(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"zanebox:subscription").apply{setReferenceCounted(false);acquire(8*60000L)}
                    try {withTimeout(8*60000L) {
                        val pending=JSONObject(group.options).optJSONObject("subscriptionRuntime")?.optString("state") in setOf("applying","apply-error")
                        val updated=if(pending)group else SubscriptionUpdater.update(store,group,this@ZaneRuntime,connected=true,automatic=true)
                        // The :bg process must not initialize a second WorkManager instance.
                        SubscriptionScheduler.apply(this@ZaneRuntime,store,updated,automatic=true,schedule=false)
                    }} catch(e:TimeoutCancellationException) {event("message","后台订阅更新超时，将自动重试")}
                    catch(e:CancellationException) {throw e}
                    catch(e:Exception) {event("message","后台订阅更新未完成，将自动重试：${safeError(e)}")}
                    finally {if(lock.isHeld)lock.release()}
                }
            } finally {subscriptionBusy=false;withContext(NonCancellable) {access.withLock {syncIdle()}};armSubscriptionAlarm()}
        }
    }
    /** Status changes always go through startForeground; per-second rate text only re-posts when it actually changed. */
    @Synchronized private fun notification(text:String,rate:Boolean=false,data:AppData=appliedData ?: store.data.value) {
        val group=if(data.bool("showGroupInNotification"))data.groups.firstOrNull { it.id==data.selectedGroupId }?.name.orEmpty() else ""
        val title=if(group.isBlank())"Links" else "Links · $group"
        val key="$title|$text"
        if(rate && (!inForeground || key==notificationText)) return
        notificationText=key
        val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(!channelReady && Build.VERSION.SDK_INT>=26) { manager.createNotificationChannel(NotificationChannel("proxy","Links 代理",NotificationManager.IMPORTANCE_LOW));channelReady=true }
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,owner.javaClass).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val built=NotificationCompat.Builder(this,"proxy").setSmallIcon(R.drawable.ic_zanebox).setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"断开",stop).build()
        if(rate) manager.notify(1,built) else { owner.startForeground(1,built);inForeground=true }
    }
    @Synchronized private fun foregroundStop() { inForeground=false;notificationText="";if(Build.VERSION.SDK_INT>=24)owner.stopForeground(Service.STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") owner.stopForeground(true) }
    fun revoke() { dispatch("stop","") }
    fun destroy() {
        destroyed=true
        cancelSubscriptionAlarm()
        releaseWakeLock();runCatching { unregisterReceiver(wakeReceiver) }
        commands.close();scope.cancel();engine.cancelTests();speed?.cancel()
        // onDestroy runs on the main thread; a start stuck in native code must not turn into an ANR.
        val finished=runBlocking(Dispatchers.IO) {withTimeoutOrNull(4000) {access.withLock {cleanupCore()}}}
        if(finished==null)CoroutineScope(Dispatchers.IO).launch {access.withLock {cleanupCore()}}
        callbacks.kill()
    }
    private fun cleanupCore() {
        try {
            if(ready.isCompleted && !ready.isCancelled)runCatching {
                val data=store.snapshot();val selections=readSelections(data)
                recordTraffic(data,engine.close(),selections);traffic.persistTraffic()
            }
        } finally {runCatching {engine.close()};runCatching {platform.close()};store.close()}
    }

    private fun releaseWakeLock() { wakeLock?.let { if(it.isHeld)it.release() };wakeLock=null }
}
