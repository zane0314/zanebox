package com.zane.zanebox.subscription

import android.content.Context
import androidx.work.*
import com.zane.zanebox.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WorkManager persists disconnected schedules; the active foreground runtime also checks due work. */
object SubscriptionScheduler {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val initialized=AtomicBoolean()
    private const val GROUP_TAG="zanebox-subscription"
    private const val RECONCILE="zanebox-subscription-reconcile"
    private const val RETRY_DELAY=5*60*1000L
    @Volatile var connectionCheck:suspend(Context)->Boolean = { false }
    private val updateEvents=MutableSharedFlow<Long>(extraBufferCapacity=32)
    val updates:SharedFlow<Long> = updateEvents
    internal fun updated(id:Long) { updateEvents.tryEmit(id) }
    @Volatile var onUpdated:suspend(Context,Long)->Unit = { _,_ -> }
    private fun name(id:Long)="zanebox-subscription-$id"
    private fun preferences(context:Context)=context.getSharedPreferences("subscription-scheduler",Context.MODE_PRIVATE)
    fun initialize(context:Context) {
        if(!initialized.compareAndSet(false,true)) return
        val app=context.applicationContext
        scope.launch {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(RECONCILE,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SubscriptionReconcileWorker>(15,TimeUnit.MINUTES).addTag(GROUP_TAG).build())
            reconcile(app)
        }
    }
    suspend fun reconcile(context:Context) = withContext(Dispatchers.IO) { val store=ZaneStore(context);try { reconcile(context,store.snapshot()) } finally { store.close() } }
    suspend fun reconcile(context:Context,data:AppData) = withContext(Dispatchers.IO) {
        val work=WorkManager.getInstance(context);val prefs=preferences(context);val previous=prefs.getStringSet("ids",emptySet()).orEmpty();val active=mutableSetOf<String>()
        data.groups.forEach { group ->
            try {
                if(SubscriptionPlan.scheduled(group)) {
                    active.add(group.id.toString());val signature=signature(group)
                    if(prefs.getString("signature.${group.id}",null)!=signature) enqueue(context,group,ExistingWorkPolicy.REPLACE) else enqueue(context,group,ExistingWorkPolicy.KEEP)
                }
            } catch(_:Exception) { work.cancelUniqueWork(name(group.id)) }
        }
        (previous-active).forEach { id -> work.cancelUniqueWork(name(id.toLong()));prefs.edit().remove("signature.$id").apply() }
        prefs.edit().putStringSet("ids",active).apply()
    }
    // Runtime progress must not REPLACE/cancel the Worker that is still applying it.
    private fun signature(group:Group)=NodeIdentity.key(JSONObject().put("url",group.subscriptionUrl).put("options",SubscriptionOptions.signature(group.options)).toString())
    private fun enqueue(context:Context,group:Group,policy:ExistingWorkPolicy,delay:Long?=null) {
        val wait=delay ?: (SubscriptionPlan.nextAt(group)-System.currentTimeMillis()).coerceAtLeast(0)
        val request=OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to group.id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setInitialDelay(wait,TimeUnit.MILLISECONDS).addTag(GROUP_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(group.id),policy,request)
        preferences(context).edit().putString("signature.${group.id}",signature(group)).apply()
    }
    fun onConnectionChanged(context:Context,connected:Boolean) {
        if(!connected) return
        val app=context.applicationContext
        scope.launch {
            val store=ZaneStore(app)
            try { store.snapshot().groups.forEach { group -> runCatching { val options=SubscriptionOptions.parse(group.options);if(options.updateWhenConnectedOnly && SubscriptionPlan.shouldUpdate(group,System.currentTimeMillis(),true)) enqueue(app,group,ExistingWorkPolicy.REPLACE,0) } } } finally { store.close() }
        }
    }
    internal fun next(context:Context,group:Group) { if(SubscriptionPlan.scheduled(group)) enqueue(context,group,ExistingWorkPolicy.APPEND_OR_REPLACE) }
    suspend fun apply(context:Context,store:ZaneStore,group:Group,automatic:Boolean,schedule:Boolean=true) {
        val id=group.id;val request=JSONObject(group.options).getJSONObject("subscriptionRuntime").getString("request")
        try {
            onUpdated(context,id)
            store.update { SubscriptionUpdater.record(it,id,"success",System.currentTimeMillis(),request=request) }
        } catch(e:CancellationException) { throw e } catch(e:Exception) {
            store.update { SubscriptionUpdater.record(it,id,"apply-error",System.currentTimeMillis(),"订阅已保存，自动应用或延迟测试失败（${e.javaClass.simpleName}）",nextAttempt=System.currentTimeMillis()+retryDelay,request=request) };throw e
        } finally {
            if(schedule)store.snapshot().groups.firstOrNull{it.id==id && SubscriptionPlan.scheduled(it)}?.let { enqueue(context,it,if(automatic)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE) }
            updated(id)
        }
    }
    internal const val retryDelay=RETRY_DELAY
    suspend fun status(context:Context):List<SubscriptionBackgroundStatus> = withContext(Dispatchers.IO) {
        val store=ZaneStore(context)
        try { store.snapshot().groups.filter { it.subscriptionUrl.isNotBlank() }.map { group ->
            val state=JSONObject(group.options).optJSONObject("subscriptionRuntime") ?: JSONObject()
            val scheduled=runCatching { SubscriptionPlan.scheduled(group) }.getOrDefault(false)
            val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork(name(group.id)).get(5,TimeUnit.SECONDS).lastOrNull { !it.state.isFinished }
            SubscriptionBackgroundStatus(group.id,state.optString("state","idle"),state.optLong("lastAttempt"),if(scheduled) SubscriptionPlan.nextAt(group) else 0,state.optString("error"),work?.state?.name ?: "NONE")
        } } finally { store.close() }
    }
}

data class SubscriptionBackgroundStatus(val groupId:Long,val state:String,val lastAttempt:Long,val nextAt:Long,val error:String,val workState:String)

class SubscriptionReconcileWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try { SubscriptionScheduler.reconcile(applicationContext);Result.success() } catch(e:CancellationException) { throw e } catch(_:Exception) { Result.retry() }
}

class SubscriptionUpdateWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val id=inputData.getLong("groupId",0);if(id<=0) return Result.failure()
        val store=ZaneStore(applicationContext)
        var expected:Group?=null
        try {
            val group=store.snapshot().groups.find { it.id==id } ?: return Result.success()
            expected=group
            if(!SubscriptionPlan.scheduled(group)) return Result.success()
            val now=System.currentTimeMillis()
            val pendingApply=group.updatedAt>0 && JSONObject(group.options).optJSONObject("subscriptionRuntime")?.optString("state") in setOf("applying","apply-error")
            if(!pendingApply && now<SubscriptionPlan.dueAt(group)) { SubscriptionScheduler.next(applicationContext,group);return Result.success() }
            val options=SubscriptionOptions.parse(group.options)
            val connected=if(options.updateWhenConnectedOnly) withTimeoutOrNull(15000) { SubscriptionScheduler.connectionCheck(applicationContext) } ?: false else false
            if(!pendingApply && options.updateWhenConnectedOnly && !connected) {
                val postponed=store.update { SubscriptionUpdater.record(it,id,"waiting-connection",now,nextAttempt=now+SubscriptionScheduler.retryDelay) }.groups.first { it.id==id }
                SubscriptionScheduler.next(applicationContext,postponed);return Result.success()
            }
            val updated=if(pendingApply)group else SubscriptionUpdater.update(store,group,applicationContext,connected,automatic=true)
            try {SubscriptionScheduler.apply(applicationContext,store,updated,automatic=true)} catch(e:CancellationException) {throw e} catch(_:Exception) {}
            return Result.success()
        } catch(e:CancellationException) { throw e } catch(e:Exception) {
            val now=System.currentTimeMillis()
            val data=store.update { data ->
                val latest=data.groups.find { it.id==id };val previous=expected
                val stale=latest==null || previous==null || latest.subscriptionUrl!=previous.subscriptionUrl || latest.updatedAt!=previous.updatedAt || SubscriptionOptions.signature(latest.options)!=SubscriptionOptions.signature(previous.options) || JSONObject(latest.options).optJSONObject("subscriptionRuntime")?.optString("state")=="updating"
                if(stale) data else SubscriptionUpdater.record(data,id,"error",now,safeSubscriptionError(e),now+SubscriptionScheduler.retryDelay)
            }
            data.groups.find { it.id==id }?.let { runCatching { SubscriptionScheduler.next(applicationContext,it) } }
            return Result.success()
        } finally { store.close() }
    }
}
