package com.zane.zanebox

import com.zane.zanebox.data.AppData
import com.zane.zanebox.runtime.SwitchTestGate
import com.zane.zanebox.runtime.isEffectiveNodeSwitch
import com.zane.zanebox.runtime.parseSelectRequest
import com.zane.zanebox.runtime.selectPayload
import org.junit.Assert.*
import org.junit.Test

class NodeSwitchTestGateTest {
    private fun data(selected:Long,auto:Boolean=false)=AppData(settings=mapOf("selectedNodeId" to selected.toString(),"homeAutoSelect" to auto.toString()))

    @Test fun sameNodeIsNotASwitchButReturningIs() {
        assertFalse(isEffectiveNodeSwitch(data(1),1))
        assertTrue(isEffectiveNodeSwitch(data(1),2))
        // A → B → A: each step is judged against the node selected just before it.
        assertTrue(isEffectiveNodeSwitch(data(2),1))
    }

    @Test fun leavingAutoSelectCountsEvenForTheStoredNode() {
        assertTrue(isEffectiveNodeSwitch(data(1,auto=true),1))
    }

    @Test fun selectPayloadStaysCompatibleWithPlainIds() {
        assertEquals(7L to "",parseSelectRequest("7").let { it.id to it.token })
        val token="3f2b6a52-0000-4000-8000-000000000001"
        val parsed=parseSelectRequest(selectPayload(9,token))
        assertEquals(9L,parsed.id);assertEquals(token,parsed.token)
    }

    @Test fun everySuccessfulSwitchStartsExactlyOneTest() {
        val gate=SwitchTestGate()
        val a1=gate.issue();val b=gate.issue();val a2=gate.issue()
        // A → B → A in quick succession: each successful switch gets its own round, in event order.
        assertTrue(gate.accept(a1))
        assertTrue(gate.accept(b))
        assertTrue(gate.accept(a2))
        // A replayed event cannot trigger a second test for the same switch.
        assertFalse(gate.accept(a2))
        assertFalse("automatic selections carry no token",gate.accept(""))
        assertFalse("unknown token",gate.accept("not-issued"))
    }

    @Test fun queuedSuccessfulSwitchesAreNeverDropped() {
        val gate=SwitchTestGate()
        val tokens=List(100){gate.issue()}
        tokens.forEach { assertTrue("every successful queued switch must be tested",gate.accept(it)) }
        tokens.forEach { assertFalse(gate.accept(it)) }
    }

    @Test fun releasedAndClearedTokensStartNothing() {
        val gate=SwitchTestGate()
        val failedSend=gate.issue();val lost=gate.issue()
        // A failed send releases its own token only; later switches are unaffected.
        assertTrue(gate.accept(failedSend))
        val later=gate.issue()
        gate.clear()
        assertFalse("binding gone: no receipt can follow",gate.accept(lost))
        assertFalse(gate.accept(later))
        assertTrue(gate.accept(gate.issue()))
    }
}
