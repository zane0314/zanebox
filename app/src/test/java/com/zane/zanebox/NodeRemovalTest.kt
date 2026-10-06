package com.zane.zanebox

import com.zane.zanebox.data.Node
import com.zane.zanebox.subscription.nodeRemovalIds
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeRemovalTest {
    @Test fun removalIncludesRecursiveChainsAndKeepsUnrelatedNodes() {
        fun node(id:Long)=Node(id,1,"节点 $id","""{"type":"socks","server":"192.0.2.1","server_port":1080}""")
        fun chain(id:Long,vararg hops:Long)=Node(id,2,"链 $id","""{"type":"chain","node_ids":[${hops.joinToString(",") }]}""")
        val nodes=listOf(chain(5,4,3),chain(4,1,3),chain(6,2,3),node(1),node(2),node(3))
        assertEquals(setOf(1L,4L,5L),nodeRemovalIds(nodes,setOf(1)))
        assertEquals(listOf(6L,2L,3L),nodes.filter{it.id !in nodeRemovalIds(nodes,setOf(1))}.map{it.id})
        assertEquals(emptySet<Long>(),nodeRemovalIds(nodes,emptySet()))
        assertEquals(setOf(4L,5L),nodeRemovalIds(nodes,setOf(4)))
    }
}
