package com.zane.zanebox.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.subscription.SubscriptionParser
import com.zane.zanebox.subscription.ParseReport
import com.zane.zanebox.subscription.SubscriptionClient
import com.zane.zanebox.subscription.SubscriptionUpdater
import com.zane.zanebox.subscription.SubscriptionOptions
import com.zane.zanebox.subscription.SubscriptionScheduler
import com.zane.zanebox.subscription.update
import com.zane.zanebox.backup.BackupManager
import com.zane.zanebox.backup.WebDavClient
import com.zane.zanebox.backup.WebDavEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = ZaneStore(app)
    val service = ServiceClient(app)
    val data = store.data
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    private var taskCount=0
    val ip get() = service.exitIp
    val webdavEntries=MutableStateFlow<List<WebDavEntry>>(emptyList())
    init {
        service.connect()
        viewModelScope.launch { SubscriptionScheduler.updates.collect { withContext(Dispatchers.IO) { store.reload() };message.value="后台订阅已更新，请点击应用修改" } }
        viewModelScope.launch { data.map { value -> value.groups.map { group -> listOf(group.id,group.enabled,group.subscriptionUrl,group.updatedAt,SubscriptionOptions.signature(group.options)) } }.distinctUntilChanged().drop(1).collect { withContext(Dispatchers.IO) { SubscriptionScheduler.reconcile(getApplication(),store.snapshot()) } } }
        viewModelScope.launch { service.events.collect { message.value = it; withContext(Dispatchers.IO) { store.reload() } } }
        viewModelScope.launch { service.testResults.collect { withContext(Dispatchers.IO) { store.reload() } } }
    }
    fun task(block: suspend () -> Unit) { viewModelScope.launch {
        taskCount++; busy.value = true
        try { withContext(Dispatchers.IO) { block() } } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { message.value = e.message ?: "操作失败" }
        finally { taskCount--; busy.value = taskCount>0 }
    } }
    fun edit(block: (AppData) -> AppData) = task {
        store.update { block(it).withEnabledSelection() }
        message.value = "已保存，请点击右上方应用修改"
    }
    fun setting(key: String, value: String) {
        if (key == "browseGroupId" || key == "theme" || key == "fontScale") task { store.update { it.copy(settings=it.settings+(key to value)) } }
        else edit { it.copy(settings = it.settings + (key to value)) }
    }
    fun importText(text: String, groupId: Long = -1) = task { importNow(text,if(groupId<0) store.snapshot().browseGroupId else groupId) }
    private fun importNow(text: String, groupId: Long = 0) = insertParsed(SubscriptionParser.parseReport(text),groupId)
    private fun insertParsed(report: ParseReport, groupId: Long) {
        val parsed = report.nodes
        require(parsed.isNotEmpty()) { "未找到有效节点" }
        store.update { d ->
            val target = groupId.takeIf { id -> d.groups.any { it.id == id } } ?: store.nextId()
            d.copy(groups = if(d.groups.any { it.id == target }) d.groups else d.groups + Group(target,"导入节点"),
                nodes = d.nodes + parsed.map { Node(store.nextId(),target,it.name,it.outbound,it.shareLink,metadata=it.metadata) }).withEnabledSelection()
        }
        message.value = "已导入 ${parsed.size} 个节点" + (if(report.skipped>0) "，跳过 ${report.skipped} 条无法识别的内容" else "") + "，请点击应用修改"
    }
    /** Content arriving from other apps (VIEW/SEND) is staged here and only applied after the user confirms. */
    val pendingImport = MutableStateFlow<PendingImport?>(null)
    fun offerLink(value:String) = task {
        val link=com.zane.zanebox.IncomingLink.parse(value)
        if(link.subscriptionUrl.isNotBlank()) pendingImport.value=PendingImport.Subscription(link.subscriptionUrl,link.name) else offerTextNow(link.text)
    }
    fun offerText(text:String) = task { offerTextNow(text) }
    private fun offerTextNow(text:String) { pendingImport.value=PendingImport.Nodes(SubscriptionParser.parseReport(text)) }
    fun offerStream(uri:Uri) = task {
        val bytes=getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> readLimited(input) }
        if(isZip(bytes)) { val restored=BackupManager(getApplication()).`import`(bytes);pendingImport.value=PendingImport.Backup(restored) }
        else offerTextNow(bytes.toString(Charsets.UTF_8))
    }
    fun cancelImport() { pendingImport.value=null }
    fun confirmImport() {
        val pending=pendingImport.value ?: return;pendingImport.value=null
        when(pending) {
            is PendingImport.Subscription -> saveGroup(Group(store.nextId(),pending.name,pending.url),true)
            is PendingImport.Nodes -> task { insertParsed(pending.report,store.snapshot().browseGroupId) }
            is PendingImport.Backup -> task { service.restore(pending.data);message.value="备份已验证，正在应用" }
        }
    }
    fun updateGroup(group: Group) = task { updateGroupNow(group) }
    private suspend fun updateGroupNow(group:Group) {
        val context=getApplication<Application>()
        val options=SubscriptionOptions.parse(group.options)
        val connected=service.snapshot.value.state==2 || (options.updateWhenConnectedOnly && SubscriptionScheduler.connectionCheck(context))
        SubscriptionUpdater.update(store,group,context,connected)
        SubscriptionScheduler.reconcile(context,store.snapshot())
        message.value="订阅已更新，请点击应用修改"
    }
    fun saveGroup(group:Group,fetch:Boolean) = task { SubscriptionOptions.parse(group.options);store.update { d->d.copy(groups=d.groups.filter{it.id!=group.id}+group).withEnabledSelection() }; message.value="组已保存，请点击应用修改"; if(fetch && group.subscriptionUrl.isNotBlank()) updateGroupNow(group) }
    private fun dav():WebDavClient { val d=store.snapshot();return WebDavClient(d.setting("webdavUrl"),d.setting("webdavUser"),d.setting("webdavPassword")) }
    fun listWebdav() = task { webdavEntries.value=dav().list() }
    fun uploadWebdav() = task { dav().upload(BackupManager(getApplication()).export(store.snapshot()));webdavEntries.value=dav().list();message.value="云备份已上传" }
    fun restoreWebdav(entry:WebDavEntry) = task { service.restore(BackupManager(getApplication()).`import`(dav().download(entry)));message.value="云备份已验证，正在应用" }
    fun deleteWebdav(entry:WebDavEntry) = task { dav().delete(entry);webdavEntries.value=dav().list() }
    fun updateSmart(key:String) = task { val d=store.snapshot(); val content=SubscriptionClient.fetch(d.setting("smartUrl.$key")).body;store.update { it.copy(settings=it.settings+("smartRules.$key" to content)) };message.value="分流列表已更新，请点击应用修改" }
    fun deleteNode(id:Long) = edit { d ->
        val remaining=d.nodes.filter { it.id!=id }
        d.copy(nodes=remaining,groups=d.groups.map{it.copy(frontProxy=if(it.frontProxy==id)0 else it.frontProxy,landingProxy=if(it.landingProxy==id)0 else it.landingProxy)},settings=cleanSmartTargets(if(d.selectedNodeId==id)d.settings+("selectedNodeId" to (remaining.firstOrNull()?.id ?: 0).toString())else d.settings,setOf("node:$id")),
            rules=d.rules.map { if(it.outbound=="node:$id")it.copy(outbound="proxy")else it },
            merges=d.merges.map { it.copy(nodeIds=it.nodeIds-id,selectedId=if(it.selectedId==id)0 else it.selectedId) })
    }
    fun deleteGroup(id:Long) = edit { d ->
        val removed=d.nodes.filter { it.groupId==id }.map { it.id }.toSet();val remaining=d.nodes.filter { it.groupId!=id }
        d.copy(groups=d.groups.filter{it.id!=id}.map{it.copy(frontProxy=if(it.frontProxy in removed)0 else it.frontProxy,landingProxy=if(it.landingProxy in removed)0 else it.landingProxy)},nodes=remaining,
            settings=cleanSmartTargets(d.settings,setOf("group:$id")+removed.map{"node:$it"})+("selectedGroupId" to if(d.selectedGroupId==id)"0" else d.selectedGroupId.toString())+("selectedNodeId" to if(d.selectedNodeId in removed)(remaining.firstOrNull()?.id ?: 0).toString() else d.selectedNodeId.toString()),
            rules=d.rules.map { if(it.outbound=="group:$id" || removed.any { n -> it.outbound=="node:$n" })it.copy(outbound="proxy")else it },
            merges=d.merges.map { it.copy(groupIds=it.groupIds-id,nodeIds=it.nodeIds.filter{n->n !in removed},selectedId=if(it.selectedId in removed)0 else it.selectedId) })
    }
    fun deleteMerge(id:Long) = edit { d -> d.copy(settings=cleanSmartTargets(d.settings,setOf("merge:$id"))+("smartSourceMergeId" to if(d.setting("smartSourceMergeId")==id.toString())"0" else d.setting("smartSourceMergeId","0")),merges=d.merges.filter { it.id!=id },rules=d.rules.map { if(it.outbound=="merge:$id")it.copy(outbound="proxy")else it }) }
    fun readText(uri: Uri) = task {
        val bytes=getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> readLimited(input) }
        if(isZip(bytes)) pendingImport.value=PendingImport.Backup(BackupManager(getApplication()).`import`(bytes)) else importNow(bytes.toString(Charsets.UTF_8),store.snapshot().browseGroupId)
    }
    fun restore(uri: Uri) = task {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { readLimited(it) }
        service.restore(BackupManager(getApplication()).`import`(bytes)); message.value = "备份已验证，正在应用"
    }
    fun backup(uri: Uri) = task { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(BackupManager(getApplication()).export(store.snapshot())) }; message.value="备份已保存" }
    fun export(uri: Uri) = task { val d=store.snapshot(); getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { out -> out.write(shareText(d.nodes)) }; message.value="节点已导出" }
    fun saveQr(uri:Uri,bitmap:android.graphics.Bitmap) = task { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) { "保存二维码失败" } };message.value="二维码已保存" }
    fun shareQr(bitmap:android.graphics.Bitmap) = task {
        val context=getApplication<Application>();val folder=java.io.File(context.cacheDir,"exports").apply{mkdirs()};val file=java.io.File(folder,"node-qr.png")
        file.outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        withContext(Dispatchers.Main) { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享二维码").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun refreshIp() = service.queryExitIp()
    private fun isZip(bytes:ByteArray)=bytes.size>4 && bytes[0]==80.toByte() && bytes[1]==75.toByte()
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer); if(count<0)break; require(out.size()+count<=64*1024*1024) { "文件超过 64 MiB" };out.write(buffer,0,count) };return out.toByteArray()
    }
    override fun onCleared() { service.close(); store.close(); super.onCleared() }
}

sealed class PendingImport {
    data class Subscription(val url:String,val name:String):PendingImport()
    data class Nodes(val report:ParseReport):PendingImport()
    data class Backup(val data:AppData):PendingImport()
}

internal fun shareText(nodes:List<Node>):String = if(nodes.all{it.shareLink.isNotBlank()}) nodes.joinToString("\n"){it.shareLink} else org.json.JSONObject().put("outbounds",org.json.JSONArray().apply{nodes.forEach { put(org.json.JSONObject(it.outbound).put("tag",org.json.JSONObject(it.metadata).optString("sourceTag","node-${it.id}").ifBlank{"node-${it.id}"}).put("display_name",it.name)) }}).toString(2)

private fun mergeMetadata(old:String,fresh:String):String = org.json.JSONObject(old).apply { val incoming=org.json.JSONObject(fresh);incoming.keys().forEach{key->put(key,incoming.get(key))} }.toString()

private fun cleanSmartTargets(settings:Map<String,String>,removed:Set<String>):Map<String,String> = settings.mapValues { (key,value) -> if(key.startsWith("smart.") && key.endsWith(".target") && value in removed) "off" else value }

internal fun AppData.withEnabledSelection(): AppData {
    val enabledGroups = groups.filter { it.enabled }.sortedBy { it.order }
    val enabledIds = enabledGroups.map { it.id }.toSet()
    val current = nodes.firstOrNull { it.id == selectedNodeId && it.groupId in enabledIds }
    val selected = current ?: enabledGroups.firstNotNullOfOrNull { group ->
        nodes.filter { it.groupId == group.id }.minByOrNull { it.order }
    }
    val disabledGroups = groups.filterNot { it.enabled }.map { "group:${it.id}" }.toSet()
    val disabledNodes = nodes.filter { it.groupId !in enabledIds }.map { "node:${it.id}" }.toSet()
    var updated = cleanSmartTargets(settings, disabledGroups + disabledNodes)
    if (current == null) updated = updated + mapOf(
        "selectedNodeId" to (selected?.id ?: 0L).toString(),
        "selectedGroupId" to (selected?.groupId ?: 0L).toString()
    )
    return copy(settings = updated)
}

internal val AppData.browseGroupId: Long get() = setting("browseGroupId", "0").toLongOrNull()?.takeIf { id -> id==0L || groups.any { it.id==id } } ?: 0L
