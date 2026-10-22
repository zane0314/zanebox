package com.zane.zanebox.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeBoundariesTest {
    @Test fun cancelledRequestsNeverReturnAndUnsentCancelledWorkIsSkipped() {
        val fence=LatencyRequestFence("client")
        val first=fence.issue()
        assertTrue(fence.acceptState("process",1,first,true))
        assertTrue(fence.acceptResult("process",1,first))
        val pending=fence.issue()
        assertTrue(fence.canSend(first))
        fence.cancel()
        assertFalse(fence.canSend(pending))
        repeat(10000) { fence.issue();fence.cancel() }
        assertFalse(fence.acceptState("process",20000,first,true))
        assertFalse(fence.acceptState("process",20001,pending,true))
        assertFalse(fence.acceptResult("process",1,first))
        val next=fence.issue()
        assertTrue(fence.canSend(next))
        assertTrue(fence.acceptState("process",20002,next,true))
        assertFalse(fence.acceptState("process",20001,pending,true))
        assertTrue(fence.acceptResult("process",20002,next))
    }

    @Test fun supersedingUnsentSwitchStillSendsBothServerRoundsButAcceptsOnlyLatestResults() {
        val fence=LatencyRequestFence("client")
        val first=fence.issue();val second=fence.issue()
        assertTrue("较新有效切换不能取消较早切换的服务端轮次",fence.canSend(first))
        assertTrue(fence.canSend(second))
        assertFalse(fence.isCurrent(first));assertTrue(fence.isCurrent(second))
        assertFalse(fence.acceptState("process",1,first,true))
        assertFalse(fence.acceptResult("process",1,first))
        assertTrue(fence.acceptState("process",2,second,true))
        assertTrue(fence.acceptResult("process",2,second))
        fence.cancel()
        assertFalse(fence.canSend(first));assertFalse(fence.canSend(second))
        val next=fence.issue();assertTrue(fence.canSend(next))
        assertFalse(fence.acceptState("process",3,first,true))
        assertTrue(fence.acceptState("process",4,next,true))
    }

    @Test fun backgroundBatchesStayVisibleAndNewBindingCanRestartBatchNumbers() {
        val fence=LatencyRequestFence("client")
        assertTrue(fence.acceptState("old",91,"background",true))
        fence.cancel()
        assertFalse(fence.acceptState("old",91,"background",true))
        assertFalse(fence.acceptResult("old",91,"background"))
        assertTrue(fence.acceptState("old",92,"next background",true))
        assertFalse(fence.acceptState("new",1,"new background",true))
        fence.resetBinding()
        assertTrue(fence.acceptState("new",1,"new background",true))
        assertFalse(fence.acceptResult("old",92,"next background"))
        assertTrue(fence.acceptResult("new",1,"new background"))
        val own=fence.issue();fence.resetBinding()
        assertFalse(fence.acceptState("new",2,own,true))
        val queued=fence.issue();fence.newConnection()
        assertTrue(fence.canSend(queued))
        assertTrue(fence.acceptState("newer",1,queued,true))
    }

    @Test fun successfulApplicationIsAcknowledgedBeforeOptionalTimeoutAndCancellation() = runBlocking {
        val applied=AtomicInteger();val warnings=AtomicInteger()
        applyThenMeasure(40,{},{applied.incrementAndGet()},{delay(200)},{warnings.incrementAndGet()})
        assertEquals(1,applied.get());assertEquals(1,warnings.get())
        val ready=CompletableDeferred<Unit>()
        val task=launch {applyThenMeasure(10000,{},{applied.incrementAndGet();ready.complete(Unit)},{awaitCancellation()},{warnings.incrementAndGet()})}
        ready.await();task.cancelAndJoin()
        assertEquals(2,applied.get());assertEquals(2,warnings.get())
        assertTrue(task.isCancelled)
    }

    @Test fun realApplicationFailuresAndTimeoutsRemainFailures() = runBlocking {
        var acknowledged=false
        try {
            applyThenMeasure(1000,{error("apply failed")},{acknowledged=true},{},{fail("measurement was never started")})
            fail("application must fail")
        } catch(e:IllegalStateException) {assertEquals("apply failed",e.message)}
        assertFalse(acknowledged)
        try {
            applyThenMeasure(40,{delay(200)},{acknowledged=true},{},{fail("application timeout is not optional")})
            fail("application timeout must fail")
        } catch(_:TimeoutCancellationException) {}
        assertFalse(acknowledged)
        applyThenMeasure(1000,{},{acknowledged=true},{},{fail("recovery should succeed")})
        assertTrue(acknowledged)
    }

    @Test fun ordinaryInitializationFailureIsReportedOnceAndDependentWaitersDoNotCrash() = runBlocking {
        val resources=RuntimeResources();val next=RuntimeResources()
        val failure=IllegalStateException("controlled initialization failure")
        val ready=CompletableDeferred<Unit>()
        val uncaught=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val handler=CoroutineExceptionHandler {_,error->uncaught.add(error)}
        val ran=AtomicInteger()
        val waiters=(1..3).map {resources.scope.launch(handler) {withRuntimeReady(ready) {ran.incrementAndGet()}}}
        try {
            val initialization=resources.initialize {throw failure}
            try {initialization.await();fail("initializer must report failure")}
            catch(e:IllegalStateException) {assertEquals(failure.javaClass,e.javaClass);assertEquals(failure.message,e.message);ready.completeExceptionally(e)}
            withTimeout(2000) {waiters.joinAll();next.initialize {}.await()}
            assertEquals(0,ran.get());assertTrue("readiness failure escaped into sibling launch: $uncaught",uncaught.isEmpty())
        } finally {resources.close({}, {}).join();next.close({}, {}).join()}
    }

    @Test fun successfulReadinessDoesNotHideAnOperationFailure() = runBlocking {
        val failure=IllegalArgumentException("operation failure")
        try {withRuntimeReady(CompletableDeferred(Unit)) {throw failure};fail("operation error must propagate")}
        catch(e:IllegalArgumentException) {assertSame(failure,e)}
    }

    @Test fun cancellingReadinessStillCancelsDependentWork() = runBlocking {
        val ready=CompletableDeferred<Unit>();var ran=false
        ready.cancel()
        try {withRuntimeReady(ready) {ran=true};fail("cancellation must propagate")}
        catch(_:CancellationException) {}
        assertFalse(ran)
    }

    @Test fun twoLiveIdleServicesCanBothFinishInitialization() = runBlocking {
        val first=RuntimeResources();val second=RuntimeResources()
        first.initialize {}.await()
        try {withTimeout(2000) {second.initialize {}.await()}}
        finally {first.close({}, {}).join();second.close({}, {}).join()}
    }

    @Test fun slowCloseDoesNotBlockCallerAndNextOwnerWaitsUntilSingleCleanupCompletes() = runBlocking {
        val first=RuntimeResources();val next=RuntimeResources()
        val closing=CountDownLatch(1);val release=CountDownLatch(1);val closes=AtomicInteger()
        first.initialize {}.await()
        val cleanup=first.close({}, {closes.incrementAndGet();closing.countDown();check(release.await(5,TimeUnit.SECONDS))})
        assertTrue(closing.await(2,TimeUnit.SECONDS))
        assertSame(cleanup,first.close({}, {fail("cleanup repeated")}))
        val nextAcquired=CompletableDeferred<Unit>()
        next.initialize {nextAcquired.complete(Unit)}
        delay(40);assertFalse(nextAcquired.isCompleted)
        release.countDown();withTimeout(2000){cleanup.join();nextAcquired.await()}
        assertEquals(1,closes.get())
        next.close({}, {}).join()
    }
    @Test fun cancelledQueuedInitializationCannotLetItsCloseOrSuccessorPassSlowPredecessor() = runBlocking {
        val first=RuntimeResources();val waiting=RuntimeResources();val last=RuntimeResources()
        first.initialize {}.await()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val closing=first.close({}, {entered.complete(Unit);release.await()})
        entered.await()
        var initialized=false
        val cancelledInitialization=waiting.initialize {initialized=true}
        val cancelledClose=waiting.close({}, {})
        val lastInitialization=last.initialize {}
        delay(40)
        assertFalse(cancelledInitialization.isCompleted);assertFalse(cancelledClose.isCompleted);assertFalse(lastInitialization.isCompleted)
        release.complete(Unit)
        withTimeout(2000) {closing.join();cancelledClose.join();lastInitialization.await()}
        assertTrue(cancelledInitialization.isCancelled);assertFalse(initialized)
        last.close({}, {}).join()
    }

    @Test fun closeCancelsInProgressInitializationAndCloseBeforeInitializationCannotFormCycle() = runBlocking {
        val first=RuntimeResources();val entered=CompletableDeferred<Unit>()
        first.initialize {entered.complete(Unit);awaitCancellation()}
        entered.await()
        withTimeout(2000) {first.close({}, {}).join()}
        val closed=RuntimeResources()
        val closing=closed.close({}, {})
        val initialization=closed.initialize {fail("closed resource cannot initialize")}
        withTimeout(2000) {closing.join();initialization.join()}
        assertTrue(initialization.isCancelled)
        val next=RuntimeResources()
        withTimeout(2000) {next.initialize {}.await();next.close({}, {}).join()}
    }

}
