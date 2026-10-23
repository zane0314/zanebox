package com.zane.zanebox.runtime

import org.junit.Assert.*
import org.junit.Test

class ChainLatencyTest {
    private val probe=ChainProbe("front",listOf("a","b"),"block")
    @Test fun selectsStrictLowestAndBlocksAllFailures() {
        val choices=mutableListOf<String>()
        assertEquals("b",chooseChainProbe(probe,{if(it=="a")90 else 20},{_,v->choices.add(v);true},{true}))
        assertEquals("block",chooseChainProbe(probe,{-1},{_,v->choices.add(v);true},{true}))
        assertEquals(listOf("b","block"),choices)
    }
    @Test fun oldRoundCannotSelectAfterItsContextChanges() {
        var active=true;var selections=0
        runCatching {chooseChainProbe(probe,{active=false;10},{_,_->selections++;true},{active})}.onSuccess {fail("过期轮被提交")}
        assertEquals(0,selections)
    }
    @Test fun tiesUseCandidateOrder() {
        assertEquals("a",chooseChainProbe(probe,{20},{_,_->true},{true}))
    }
    @Test fun managedParentTracksDynamicDependenciesThroughSelectors() {
        val config="""{"outbounds":[{"type":"selector","tag":"chain-1-front","default":"chain-1-unavailable","outbounds":["chain-1-front-2","chain-1-unavailable"]},{"type":"socks","tag":"node-1"},{"type":"selector","tag":"group-9","outbounds":["node-1"]},{"type":"urltest","tag":"merge-10","managed_test":true,"outbounds":["group-9","node-3"]},{"type":"urltest","tag":"merge-11","outbounds":["node-3"]}],"route":{"final":"group-9"}}"""
        assertEquals(listOf(ChainParentProbe("merge-10",listOf("group-9","node-3"),setOf(1L))),chainParentProbes(config))
        assertEquals(setOf(1L),routedChainNodes(config))
    }

}
