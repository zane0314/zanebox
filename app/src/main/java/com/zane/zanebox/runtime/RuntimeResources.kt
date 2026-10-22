package com.zane.zanebox.runtime

import kotlinx.coroutines.*

/** Reserve lifecycle operations synchronously; idle bindings never own the process-global native platform. */
internal class RuntimeResources {
    private val job=SupervisorJob()
    val scope=CoroutineScope(job+Dispatchers.IO)
    private var initialization:Deferred<Unit>?=null
    private var closing:Job?=null
    @Synchronized fun initialize(block:suspend ()->Unit):Deferred<Unit> {
        check(initialization==null)
        if(closing!=null)return CompletableDeferred<Unit>().apply {cancel()}
        return enqueue {withContext(scope.coroutineContext) {block()}}.also {initialization=it}
    }
    @Synchronized fun close(cancelNative:()->Unit,cleanup:suspend ()->Unit):Job {
        closing?.let {return it}
        // Cancel before waiting for our queue slot: an initializer may itself be awaiting cancellation.
        return enqueue(beforeWait={job.cancel()}) {
            try {cancelNative()} finally {job.join();cleanup()}
        }.also {closing=it}
    }
    companion object {
        private val queueLock=Any()
        private val workers=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private var tail:Deferred<Unit> = CompletableDeferred(Unit)
        private fun enqueue(beforeWait:()->Unit={},block:suspend ()->Unit):Deferred<Unit> {
            val barrier=CompletableDeferred<Unit>();val result=CompletableDeferred<Unit>()
            val previous=synchronized(queueLock) {tail.also {tail=barrier}}
            workers.launch {
                try {beforeWait();previous.await();block();result.complete(Unit)}
                catch(e:CancellationException) {result.cancel(e)}
                catch(e:Exception) {result.completeExceptionally(e)}
                finally {
                    // Cancelling a waiting initializer must not let its successors overtake the predecessor.
                    previous.await();barrier.complete(Unit)
                    if(!result.isCompleted)result.cancel()
                }
            }
            return result
        }
    }
}

/** The initializer owns failure reporting; dependent jobs skip work without rethrowing that same failure. */
internal suspend fun withRuntimeReady(ready:Deferred<Unit>,action:suspend ()->Unit) {
    try {ready.await()}
    catch(e:CancellationException) {throw e}
    catch(_:Exception) {return}
    action()
}
