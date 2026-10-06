package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test

class DragOrderTest {
    @Test fun preservesHiddenSlotsFieldsAndOtherScopes() {
        assertEquals(listOf(3L,2L,1L,4L),reorderVisibleIds(listOf(1,2,3,4).map{it.toLong()},listOf(3L,1L)))
        for(ids in listOf(listOf(1L,1L),listOf(99L)))try {reorderVisibleIds(listOf(1L,2L),ids);fail("invalid IDs accepted")}catch(_:IllegalArgumentException){}
        val d=AppData(nodes=listOf(Node(1,1,"a","{}",tx=17),Node(2,1,"b","{}",order=1),Node(3,2,"c","{}")),groups=listOf(Group(1,"one",frontProxy=3),Group(2,"two",order=1)),rules=listOf(RouteRule(1,"front",prioritize=true),RouteRule(2,"back",order=5),RouteRule(3,"front2",order=1,prioritize=true)),settings=mapOf("selectedNodeId" to "1","perAppPackages" to "android"))
        val n=d.reorderNodes(listOf(2L,1L));assertEquals(listOf(2L,1L),n.nodes.filter{it.groupId==1L}.sortedBy{it.order}.map{it.id});assertEquals(d.nodes.last(),n.nodes.last());assertEquals(17,n.nodes.first().tx);assertEquals(d.settings["perAppPackages"],n.settings["perAppPackages"]);assertEquals(1L,n.selectedNodeId)
        val g=d.reorderGroups(listOf(2L,1L));assertEquals(3L,g.groups.first().frontProxy);assertEquals("one",g.groups.first().name)
        val r=d.reorderRules(true,listOf(3L,1L));assertEquals(d.rules[1],r.rules[1]);assertTrue(r.rules.filter{it.id!=2L}.all{it.prioritize})
        try {d.reorderRules(true,listOf(2L));fail("cross-section move accepted")}catch(_:IllegalArgumentException){}
    }
}
