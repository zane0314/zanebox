package com.zane.zanebox

import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import org.junit.Test

/** Same data for shell-driven frame measurements; no test synchronization during scrolling. */
class ScrollFixtureTest {
    @Test fun seedHomeList() {
        val args=InstrumentationRegistry.getArguments()
        val count=args.getString("nodeCount","200").toInt()
        val store=ZaneStore(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            store.replace(AppData(nodes=(1..count).map {Node(it.toLong(),1,"滚动验收节点 $it","""{"type":"socks","server":"10.0.2.2","server_port":19081}""",ping=60+it%80,status=1,order=it)},groups=listOf(Group(1,"滚动验收")),settings=mapOf("appLanguage" to "zh-CN","fontScale" to "1.0","showBottomBar" to args.getString("showBottomBar","true"),"browseGroupId" to "1","selectedNodeId" to "1","serviceMode" to "proxy","statsEnabled" to "false")))
        } finally {store.close()}
    }
}
