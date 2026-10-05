package com.zane.zanebox.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import com.zane.zanebox.data.ZaneStore
import com.zane.zanebox.data.AppData
import org.json.JSONArray
import org.json.JSONObject

data class RuntimeSnapshot(val state:Int=0,val started:Long=0,val generation:Long=0,val txRate:Long=0,val rxRate:Long=0,val txTotal:Long=0,val rxTotal:Long=0,val error:String="") {
    fun json():String = JSONObject().put("state",state).put("started",started).put("generation",generation).put("txRate",txRate).put("rxRate",rxRate).put("txTotal",txTotal).put("rxTotal",rxTotal).put("error",error).toString()
    companion object { fun parse(s:String):RuntimeSnapshot { val o=JSONObject(s);return RuntimeSnapshot(o.optInt("state"),o.optLong("started"),o.optLong("generation"),o.optLong("txRate"),o.optLong("rxRate"),o.optLong("txTotal"),o.optLong("rxTotal"),o.optString("error")) } }
}
class ServiceClient(context:Context,private val targetClass:Class<*>?=null) {
    private val context=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val commands=Channel<suspend ()->Unit>(Channel.UNLIMITED)
    init { scope.launch { for(task in commands) { try { task() } catch(e:Exception) { event.emit(e.message ?: "服务操作失败") } } } }
    private fun submit(task:suspend ()->Unit) { commands.trySend(task) }
    private val state=MutableStateFlow(RuntimeSnapshot())
    val snapshot:StateFlow<RuntimeSnapshot> = state
    private val event=MutableSharedFlow<String>(extraBufferCapacity=32)
    val events:SharedFlow<String> = event
    private val tests=MutableStateFlow<Map<Long,Int>>(emptyMap())
    val testResults:StateFlow<Map<Long,Int>> = tests
    private val testingState=MutableStateFlow<Set<Long>>(emptySet())
    val testingNodes:StateFlow<Set<Long>> = testingState
    private val assetState=MutableStateFlow(0L)
    val assetRevision:StateFlow<Long> = assetState
    private val logState=MutableStateFlow<List<String>>(emptyList());val logs:StateFlow<List<String>> = logState
    private val ipState=MutableStateFlow("");val exitIp:StateFlow<String> = ipState
    private val trafficState=MutableStateFlow("{}");val traffic:StateFlow<String> = trafficState
    private val connectionState=MutableStateFlow("{}");val connections:StateFlow<String> = connectionState
    private val speedState=MutableStateFlow("");val speedResult:StateFlow<String> = speedState
    private val validationState=MutableStateFlow<Map<String,String>>(emptyMap());val validationResults:StateFlow<Map<String,String>> = validationState
    private val panelState=MutableStateFlow("");val panelUrl:StateFlow<String> = panelState
    private val stunState=MutableStateFlow("{}");val stunResult:StateFlow<String> = stunState
    @Volatile private var remote:IRuntime?=null
    private var bound=false
    private var boundClass:Class<*>?=null
    private fun serviceClass():Class<*> = targetClass ?: RuntimeServiceTarget.serviceClass(context)
    private var wasConnected=false
    private val callback=object:IRuntimeCallback.Stub() {
        override fun onSnapshot(json:String) { runCatching {
            val next=RuntimeSnapshot.parse(json);state.value=next
            if(next.state==2 && !wasConnected && targetClass==null && com.zane.zanebox.ZaneApplication.mainProcess)com.zane.zanebox.subscription.SubscriptionScheduler.onConnectionChanged(context,true)
            wasConnected=next.state==2
        } }
        override fun onEvent(kind:String,payload:String) { when(kind) {
            "test" -> { val o=JSONObject(payload);tests.value=tests.value+(o.getLong("id") to o.getInt("ping")) }
            "testing" -> { val a=JSONArray(payload);testingState.value=(0 until a.length()).map { a.getLong(it) }.toSet() }
            "assetInstalled" -> { assetState.value++;event.tryEmit(payload) }
            "logs" -> { val a=JSONArray(payload);logState.value=(0 until a.length()).map { a.getString(it) } }
            "ip" -> ipState.value=payload
            "traffic" -> trafficState.value=payload
            "connections" -> connectionState.value=payload
            "speed" -> speedState.value=payload
            "validation" -> { val o=JSONObject(payload);validationState.update { it+(o.getString("name") to o.optString("error")) } }
            "panel" -> panelState.value=payload
            "stun" -> stunState.value=payload
            "restored" -> { event.tryEmit("备份已恢复");val o=JSONObject(payload);if(o.optBoolean("modeChanged"))submit { ensureMode();if(o.optBoolean("running"))startRemote() } }
            else -> event.tryEmit(payload)
        } }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,service:IBinder) { remote=IRuntime.Stub.asInterface(service);scope.launch { runCatching { remote?.registerCallback(callback);remote?.getSnapshot()?.let { state.value=RuntimeSnapshot.parse(it) } }.onFailure { event.emit("服务连接失败") } } }
        override fun onServiceDisconnected(name:ComponentName) { remote=null;clearPendingTests();state.value=RuntimeSnapshot(error="后台服务已断开") }
        // A dead binding never reconnects by itself (e.g. after the APK is updated); rebind so later commands work.
        override fun onBindingDied(name:ComponentName) { remote=null;clearPendingTests();submit { if(bound) { runCatching { context.unbindService(this) };bound=false };connect() } }
    }
    private fun clearPendingTests() {
        testingState.value=emptySet()
        if(speedState.value.isNotBlank() && !runCatching { JSONObject(speedState.value).optBoolean("done") }.getOrDefault(true))speedState.value=JSONObject().put("stage","error").put("done",true).put("error","后台服务已断开，请重新测速").toString()
    }
    fun connect() { if(!bound) { boundClass=serviceClass();bound=context.bindService(Intent(context,boundClass),connection,Context.BIND_AUTO_CREATE) } }
    private suspend fun ensureMode() {
        val next=serviceClass()
        if(bound && boundClass!=next) {
            remote?.command("stop","")
            withTimeout(30000) { while(state.value.state in listOf(1,2,3))delay(50) }
            runCatching { remote?.unregisterCallback(callback) };context.unbindService(connection);bound=false;remote=null
            state.value=RuntimeSnapshot()
        }
        connect()
        withTimeout(20000) { while(remote==null)delay(50) }
    }
    suspend fun readSnapshot():RuntimeSnapshot { ensureMode();return RuntimeSnapshot.parse(remote!!.getSnapshot()) }
    private fun command(action:String,payload:String="") { submit { ensureMode();remote!!.command(action,payload) } }
    private fun startRemote() { ContextCompat.startForegroundService(context,Intent(context,boundClass).setAction("foreground"));remote!!.command("start","") }
    fun start() { submit { ensureMode();startRemote() } }
    fun stop()=command("stop")
    fun reload() { submit { val changed=boundClass!=serviceClass();val running=state.value.state==2;ensureMode();if(changed && running)startRemote() else remote?.command("reload","") } }
    fun selectNode(id:Long)=command("select",id.toString())
    fun testNodes(ids:List<Long>) { tests.value=emptyMap();command("test",JSONArray(ids).toString()) }
    fun cancelTests()=command("cancelTests")
    fun installAsset(filename:String)=command("asset",filename)
    fun refreshLogs()=command("logs")
    fun clearLogs()=command("clearLogs")
    fun systemLogs()=command("systemLogs")
    fun queryExitIp()=command("ip")
    fun refreshTraffic()=command("traffic")
    fun resetTraffic()=command("resetTraffic")
    fun setTrafficEnabled(enabled:Boolean)=command("stats",enabled.toString())
    fun refreshConnections()=command("connections")
    fun closeConnection(id:String)=command("closeConnection",id)
    fun closeAllConnections()=command("closeAllConnections")
    fun speedTest(nodeId:Long,mode:String="simple")=command("speed",JSONObject().put("id",nodeId).put("mode",mode).toString())
    fun cancelSpeedTest()=command("cancelSpeed")
    fun openPanel() { panelState.value="";command("panel") }
    fun stun(server:String)=command("stun",server)
    fun cancelStun()=command("cancelStun")
    fun validateConfig(name:String,json:String) { submit {
        val filename="validate-${java.util.UUID.randomUUID()}.json"
        val file=android.util.AtomicFile(java.io.File(context.noBackupFilesDir,filename))
        try { val bytes=json.toByteArray(Charsets.UTF_8);require(bytes.size<=64*1024*1024);val output=file.startWrite();try { output.write(bytes);file.finishWrite(output) } catch(e:Exception) { file.failWrite(output);throw e };ensureMode();remote!!.command("validate",JSONObject().put("file",filename).put("name",name).toString()) }
        catch(e:Exception) { file.delete();validationState.update { it+(name to (e.message ?: "验证失败")) } }
    } }
    suspend fun checkConfig(json:String) {
        val name="save-${java.util.UUID.randomUUID()}"
        validateConfig(name,json)
        val error=withTimeout(30000) { validationResults.first { it.containsKey(name) }.getValue(name) }
        validationState.update { it-name }
        require(error.isBlank()) { error }
    }
    fun restore(data:AppData) { submit {
        val name="restore-${java.util.UUID.randomUUID()}.json"
        val stage=android.util.AtomicFile(java.io.File(context.noBackupFilesDir,name))
        try {
            val bytes=data.validate().toJson().toByteArray(Charsets.UTF_8);require(bytes.size<=64*1024*1024)
            val output=stage.startWrite();try { output.write(bytes);stage.finishWrite(output) } catch(e:Exception) { stage.failWrite(output);throw e }
            ensureMode();(remote ?: error("后台服务尚未就绪")).command("restore",name)
        } catch(e:Exception) { stage.delete();event.emit(e.message ?: "恢复失败") }
    } }
    fun close() { commands.close();runCatching { remote?.unregisterCallback(callback) };if(bound) { context.unbindService(connection);bound=false };remote=null;scope.cancel() }
    companion object {
        suspend fun isConnected(context:Context):Boolean {
            @Suppress("DEPRECATION") val running=(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(50).firstOrNull { it.foreground && it.service.className in setOf(ZaneVpnService::class.java.name,ZaneProxyService::class.java.name) } ?: return false
            val target=if(running.service.className==ZaneProxyService::class.java.name)ZaneProxyService::class.java else ZaneVpnService::class.java
            val client=ServiceClient(context,target)
            return try { withTimeout(5000) { client.readSnapshot().state==2 } } catch(_:Exception) { false } finally { client.close() }
        }
    }
}
