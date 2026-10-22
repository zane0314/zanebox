package com.zane.zanebox.runtime

/** Constant-space fences: superseded rounds still send; explicit cancellation also fences unsent work. */
internal class LatencyRequestFence(private val client:String=java.util.UUID.randomUUID().toString()) {
    private var round=0L
    private var cancelledThrough=0L
    private var ownRequest=""
    private var pending=""
    private var epoch=""
    private var batch=0L
    private var closedThrough=-1L
    private var active=""
    fun issue():String {
        closedThrough=maxOf(closedThrough,batch);active=""
        ownRequest="$client:${++round}";pending=ownRequest
        return ownRequest
    }
    fun canSend(request:String):Boolean {
        val prefix="$client:"
        val issued=request.takeIf {it.startsWith(prefix)}?.removePrefix(prefix)?.toLongOrNull() ?: return false
        return issued>cancelledThrough && issued<=round
    }
    fun isCurrent(request:String)=request==ownRequest
    fun cancel() {cancelledThrough=round;closedThrough=maxOf(closedThrough,batch);ownRequest="";pending="";active=""}
    fun newConnection() {epoch="";batch=0;closedThrough=-1;active=""}
    fun resetBinding() {cancel();newConnection()}
    fun acceptState(process:String,id:Long,request:String,begin:Boolean):Boolean {
        if(epoch.isNotBlank() && epoch!=process)return false
        if(request.startsWith("$client:") && request!=ownRequest)return false
        if(pending.isNotBlank() && request!=pending)return false
        if(id<=closedThrough || id<batch || (!begin && (id!=batch || request!=active)))return false
        if(id==batch && active.isNotBlank() && active!=request)return false
        epoch=process;batch=id;active=request;pending=""
        return true
    }
    fun acceptResult(process:String,id:Long,request:String)=pending.isBlank() && process==epoch && id==batch && id>closedThrough && request==active
}
