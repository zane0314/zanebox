package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeGroupsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun compactUniformTabsOpenMenusWithoutChangingSelectionAtBothFontSizes() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle {vm.edit{AppData(nodes=listOf(Node(1,1,"主节点","""{"type":"socks","server":"127.0.0.1","server_port":9}"""),Node(2,2,"其他节点","""{"type":"socks","server":"127.0.0.1","server_port":10}""")),groups=listOf(Group(1,"订阅 A","https://example.test/a"),Group(2,"本地 B")),settings=mapOf("selectedNodeId" to "1","browseGroupId" to "0","appLanguage" to "zh-CN"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==2}
        for(scale in listOf("1.0","2.0")) {
            compose.runOnIdle{vm.setting("fontScale",scale)};compose.waitUntil(10000){vm.data.value.setting("fontScale")==scale}
            compose.onNodeWithTag("group_summary").assertDoesNotExist();compose.onNodeWithTag("group_menu_1").assertDoesNotExist()
            val all=compose.onNodeWithTag("group_all").fetchSemanticsNode().layoutInfo.coordinates.size
            for(id in listOf(1,2))assertEquals(all.height,compose.onNodeWithTag("group_$id").fetchSemanticsNode().layoutInfo.coordinates.size.height)
            val selected=compose.onNodeWithText("已选：主节点").getUnclippedBoundsInRoot()
            val tab=compose.onNodeWithTag("group_all").getUnclippedBoundsInRoot()
            assertTrue("顶部留白过大：$selected / $tab",(tab.top-selected.bottom).value<=24f)
            compose.onNodeWithTag("group_2").performTouchInput{longClick()}
            compose.onNodeWithTag("home_group_menu").assertIsDisplayed();assertEquals(0L,vm.data.value.browseGroupId)
            assertTrue(vm.data.value.groups.all{it.enabled})
            compose.onNodeWithTag("home_group_menu").performScrollToNode(hasText("编辑"));compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithTag("group_name").assertTextContains("本地 B");compose.onNodeWithTag("page_back").performClick()
            compose.onNodeWithTag("group_2").performTouchInput{longClick()}
            compose.onNodeWithTag("home_group_menu").performScrollToNode(hasText("前置代理"));compose.onNodeWithText("前置代理").performClick()
            compose.runOnIdle {vm.edit{d->d.copy(groups=d.groups.map{if(it.id==2L)it.copy(updatedAt=123456,userInfo="已更新",options="""{"autoUpdateDelay":60}""")else it})}}
            compose.waitUntil(10000){!vm.busy.value && vm.data.value.groups.first{it.id==2L}.updatedAt==123456L}
            compose.onNode(hasText("主节点") and hasAnyAncestor(isDialog())).performClick();compose.waitUntil(10000){vm.data.value.groups.first{it.id==2L}.frontProxy==1L}
            assertEquals(0L,vm.data.value.groups.first{it.id==1L}.frontProxy)
            assertEquals(123456L,vm.data.value.groups.first{it.id==2L}.updatedAt)
            assertEquals("已更新",vm.data.value.groups.first{it.id==2L}.userInfo)
            assertEquals("""{"autoUpdateDelay":60}""",vm.data.value.groups.first{it.id==2L}.options)
            val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();val bitmap=ins.uiAutomation.takeScreenshot()!!
            java.io.File(ins.targetContext.getExternalFilesDir(null),"home-groups-$scale.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @Test fun smartTargetsShowOneProxyNoRegionsAndTheAllSubscriptionScope() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle{vm.edit{AppData(settings=mapOf("appLanguage" to "zh-CN","smart.youtube.target" to "region:jp"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("smart.youtube.target")=="region:jp"}
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithText("自动选择范围").assertIsDisplayed()
        compose.onNodeWithText("全部启用订阅组 · 0 个可用节点").assertIsDisplayed()
        compose.onNodeWithTag("smart_target_youtube").performClick()
        compose.onAllNodes(hasText("代理") and hasAnyAncestor(isDialog())).assertCountEquals(1);compose.onNode(hasText("代理") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        for(old in listOf("普通主节点","关闭 · 使用普通主节点","香港","美国","韩国","日本","新加坡","台湾"))compose.onNodeWithText(old).assertDoesNotExist()
        compose.onNodeWithText("自动选择").assertIsDisplayed();compose.onNodeWithText("直连").assertIsDisplayed();compose.onNode(hasText("代理") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="proxy"}
    }
}
