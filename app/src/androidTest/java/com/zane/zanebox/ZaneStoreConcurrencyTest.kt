package com.zane.zanebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.ZaneStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.*

@RunWith(AndroidJUnit4::class)
class ZaneStoreConcurrencyTest {
    @Test fun coldConcurrentOpenPreservesAllTransactionsAndIds() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="concurrency-${java.util.UUID.randomUUID()}.db"
        val pool=Executors.newFixedThreadPool(12);val start=CountDownLatch(1)
        try {
            val tasks=(0 until 12).map { i -> pool.submit(Callable {
                start.await()
                ZaneStore(context,name).use { store -> store.update { it.copy(settings=it.settings+("transaction-$i" to i.toString())) };store.nextId() }
            }) }
            start.countDown();val ids=tasks.map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(12,ids.toSet().size)
            ZaneStore(context,name).use { store->assertEquals(12,store.snapshot().settings.size);assertEquals((0 until 12).map { it.toString() }.toSet(),store.snapshot().settings.values.toSet()) }
            File(context.getExternalFilesDir(null),"sqlite-concurrency-result.json").writeText("{\"concurrentOpen\":12,\"preservedTransactions\":12,\"uniqueIds\":12}")
        } finally { pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);context.deleteDatabase(name) }
    }
}
