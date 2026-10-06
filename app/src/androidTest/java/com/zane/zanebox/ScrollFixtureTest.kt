package com.zane.zanebox

import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import org.junit.Test

/** Same data for shell-driven frame measurements; no test synchronization during scrolling. */
class ScrollFixtureTest {
    @Test fun seedHomeList() {
        val args=InstrumentationRegistry.getArguments()
        val count=args.getString("nodeCount","200").toInt()
        val groupCount=args.getString("groupCount","1").toInt()
        require(count>0 && groupCount>0 && count%groupCount==0) { "nodeCount must be a positive multiple of groupCount" }
        val nodesPerGroup=count/groupCount
        val multiGroup=groupCount>1
        val groups=(1..groupCount).map { id->
            Group(id.toLong(),if(multiGroup)"滚动验收分组 $id" else "滚动验收",order=if(multiGroup)id else 0)
        }
        val store=ZaneStore(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            store.replace(AppData(nodes=groups.flatMapIndexed { groupIndex,group->
                (1..nodesPerGroup).map { localIndex->
                    val id=groupIndex*nodesPerGroup+localIndex
                    Node(id.toLong(),group.id,if(multiGroup)"${group.name} · 节点 $localIndex" else "滚动验收节点 $id","""{"type":"socks","server":"10.0.2.2","server_port":19081}""",ping=60+id%80,status=1,order=localIndex)
                }
            },groups=groups,settings=mapOf("appLanguage" to "zh-CN","fontScale" to "1.0","showBottomBar" to args.getString("showBottomBar","true"),"browseGroupId" to "1","selectedNodeId" to "1","serviceMode" to "proxy","statsEnabled" to "false")))
        } finally {store.close()}
    }
}
