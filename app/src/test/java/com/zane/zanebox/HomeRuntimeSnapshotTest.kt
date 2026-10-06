package com.zane.zanebox

import com.zane.zanebox.runtime.RuntimeSnapshot
import com.zane.zanebox.ui.homeRuntimeSnapshots
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeRuntimeSnapshotTest {
    @Test fun trafficSamplesDoNotRefreshHomeButConnectionReloadAndErrorsDo() = runBlocking {
        val connected=RuntimeSnapshot(state=2,generation=1)
        val values=listOf(RuntimeSnapshot(),connected,connected.copy(txRate=100,rxTotal=1000),connected.copy(txRate=200,rxTotal=2000),connected.copy(generation=2),connected.copy(generation=2,error="fixture-error"),connected.copy(generation=2,error="fixture-error",pendingManual=true),RuntimeSnapshot(generation=3))
        val rendered=homeRuntimeSnapshots(values.asFlow()).toList()
        assertEquals(listOf(values[0],values[1],values[4],values[5],values[6],values[7]),rendered)
    }
}
