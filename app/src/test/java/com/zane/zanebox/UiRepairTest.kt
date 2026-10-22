package com.zane.zanebox

import com.zane.zanebox.ui.readImportBytes
import com.zane.zanebox.ui.receiveStoreRefreshes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class UiRepairTest {
    @Test fun fileFailuresExplainNullPermissionsAndReadsAndCloseTheStream() {
        fun failure(expected:String,open:()->InputStream?) {
            try { readImportBytes(open);fail("expected failure") }
            catch(e:IllegalStateException) { assertTrue(e.message.orEmpty().contains(expected)) }
            catch(e:IllegalArgumentException) { assertTrue(e.message.orEmpty().contains(expected)) }
        }
        failure("无法读取文件") { null }
        failure("权限") { throw SecurityException("revoked") }
        failure("无法读取文件") { throw java.io.FileNotFoundException("missing") }
        var closed=false
        failure("无法读取文件") { object:InputStream() {
            override fun read():Int=throw IOException("read failed")
            override fun close() { closed=true }
        } }
        assertTrue(closed)
        assertArrayEquals("fixture".toByteArray(),readImportBytes { ByteArrayInputStream("fixture".toByteArray()) })
    }

    @Test fun oversizedFilesStillFailAndCloseWithoutReturningPartialData() {
        var closed=false
        val stream=object:InputStream() {
            override fun read():Int=0
            override fun read(buffer:ByteArray,offset:Int,length:Int)=length
            override fun close() { closed=true }
        }
        try { readImportBytes {stream};fail("oversized input") }
        catch(e:IllegalArgumentException) { assertEquals("文件超过 64 MiB",e.message) }
        assertTrue(closed)
    }

    @Test fun burstRefreshesKeepTheFinalRevisionAfterASlowRead()=runBlocking {
        val requests=Channel<Unit>(Channel.CONFLATED)
        val reading=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var revision=1;val loaded=mutableListOf<Int>()
        val worker=launch {
            receiveStoreRefreshes(requests) {
                loaded.add(revision)
                if(loaded.size==1) {reading.complete(Unit);release.await()}
            }
        }
        requests.send(Unit);reading.await()
        repeat(100) { revision++;requests.trySend(Unit) }
        requests.close();release.complete(Unit)
        withTimeout(3000) {worker.join()}
        assertEquals(listOf(1,101),loaded)
    }
}
