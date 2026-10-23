package com.zane.zanebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.config.*
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
    @Test fun derivedSyncDoesNotConsumeAnAlreadyReservedNodeId() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="derived-reservation-${java.util.UUID.randomUUID()}.db"
        val source=Node(10,1,"源A","""{"type":"socks","server":"a.example","server_port":1080}""")
        val spare=source.copy(id=11,groupId=2,name="可加入源组")
        try {
            ZaneStore(context,name).use { store->
                store.replace(AppData(nodes=listOf(source,spare),groups=listOf(Group(1,"源组"),Group(2,"其他组"))))
                store.update { it.createDerivedChainGroup(1,"C",ChainTargets(),allocateId=store::nextId) }
                val reserved=store.nextId()
                val changed=store.update { data->data.copy(nodes=data.nodes.map { if(it.id==spare.id)it.copy(groupId=1)else it }) }
                val group=changed.groups.single { derivedGroupSourceId(it)>0 }
                val generated=changed.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==spare.id }
                assertNotEquals(reserved,generated.id)
                assertTrue(generated.id>reserved)
                val pending=source.copy(id=reserved,groupId=2,name="先保留ID后保存")
                val saved=store.update { it.copy(nodes=it.nodes+pending) }
                assertEquals(pending,saved.nodes.single { it.id==reserved })
                assertEquals(generated,saved.nodes.single { it.id==generated.id })
                assertEquals(source,saved.nodes.single { it.id==source.id })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun importingHighIdsRaisesSequenceBeforeGeneratingMissingMembers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="derived-import-floor-${java.util.UUID.randomUUID()}.db"
        val source=Node(2,1,"源A","""{"type":"socks","server":"a.example","server_port":1080}""")
        val second=source.copy(id=3,name="源B")
        val original=Group(1,"原分组")
        val derived=Group(4,"派生C",options="""{"chainSourceGroupId":1}""")
        try {
            ZaneStore(context,name).use { store->
                val result=store.replace(AppData(nodes=listOf(source,second),groups=listOf(original,derived)))
                assertEquals(source,result.nodes.single { it.id==source.id })
                assertEquals(second,result.nodes.single { it.id==second.id })
                assertEquals(original,result.groups.single { it.id==original.id })
                val generated=result.nodes.filter { it.groupId==derived.id }
                assertEquals(2,generated.size)
                assertTrue(generated.all { it.id>4 })
                assertEquals(2,generated.map { it.id }.toSet().size)
                val reserved=store.nextId()
                assertTrue(reserved>generated.maxOf { it.id })
                assertEquals(result,store.update { it })
                assertEquals(reserved+1,store.nextId())
            }
        } finally { context.deleteDatabase(name) }
    }

}
