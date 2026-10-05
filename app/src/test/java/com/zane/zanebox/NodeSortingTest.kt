package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test

class NodeSortingTest {
    private val group=Group(7,"g",options="{\"nodeSortOrder\":2}")
    private val legacy="legacy.preference.anybox_nodes.sort_group_7"
    @Test fun explicitFalseOverridesLegacyAndBean() {
        val d=AppData(settings=mapOf("sort_group_7" to "false",legacy to "{\"type\":\"boolean\",\"value\":true}"))
        assertEquals(NodeSortMode.DEFAULT,nodeSortMode(d,group))
        assertEquals(NodeSortMode.DEFAULT,nodeSortMode(d.copy(settings=mapOf(legacy to "{\"type\":\"boolean\",\"value\":false}")),group))
    }
    @Test fun typedBooleanAndBeanFallbacksRemainDistinct() {
        assertEquals(NodeSortMode.LATENCY,nodeSortMode(AppData(settings=mapOf(legacy to "{\"type\":\"boolean\",\"value\":true}")),group.copy(options="{\"nodeSortOrder\":1}")))
        assertEquals(NodeSortMode.NAME,nodeSortMode(AppData(),group.copy(options="{\"nodeSortOrder\":1}")))
        assertEquals(NodeSortMode.LATENCY,nodeSortMode(AppData(),group))
        assertEquals(NodeSortMode.NAME,nodeSortMode(AppData(settings=mapOf(legacy to "{\"type\":\"boolean\",\"value\":\"false\"}")),group.copy(options="{\"nodeSortOrder\":1}")))
    }
    @Test fun validLatencyFirstAndStableUserOrder() {
        val nodes=listOf(Node(1,7,"a","{}",ping=-1,order=0),Node(2,7,"b","{}",ping=30,order=3),Node(3,7,"c","{}",ping=30,order=1),Node(4,7,"d","{}",ping=Int.MAX_VALUE,order=2),Node(5,7,"e","{}",ping=0,order=4))
        assertEquals(listOf(3L,2L,4L,1L,5L),nodes.sortedWith(nodeComparator(NodeSortMode.LATENCY)).map{it.id})
    }
    @Test fun naturalNamesMatchDigitRunsAndLeadingZerosWithoutOverflow() {
        val names=listOf("节点10","节点002","节点2","节点02","节点1000000000000000000000000","节点20")
        assertEquals(listOf("节点2","节点02","节点002","节点10","节点20","节点1000000000000000000000000"),names.sortedWith(Comparator(::naturalNodeName)))
        assertEquals(0,naturalNodeName("Alpha2","alpha2"))
    }
}
