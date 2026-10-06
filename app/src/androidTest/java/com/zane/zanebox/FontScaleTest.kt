package com.zane.zanebox

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FontScaleTest {
    @get:Rule val compose=createComposeRule()
    @Test fun appFontScaleIsIgnoredButSystemFontScaleStillScalesDialogs() {
        var appScale by mutableStateOf("1.0")
        var systemScale by mutableStateOf(1f)
        var choice by mutableStateOf(false)
        compose.setContent {
            val base=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density,systemScale)) {
            ZaneTheme(AppData(settings=mapOf("fontScale" to appScale))) {
                if(choice)ChoiceDialog("字号测试","a",listOf("a" to "Choice item"),{},onChoose={})
                else UiPageList("字号测试",{}) {}
            }
            }
        }
        fun height()=compose.onNodeWithText("字号测试").fetchSemanticsNode().boundsInRoot.height
        compose.waitForIdle();val page=height()
        compose.runOnIdle{appScale="2.0"};compose.waitForIdle()
        assertEquals("旧应用字号设置不应改变页面大小",page,height(),1f)
        compose.runOnIdle{appScale="1.0";choice=true};compose.waitForIdle();val alert=height()
        compose.runOnIdle{appScale="2.0"};compose.waitForIdle()
        assertEquals("旧应用字号设置不应改变选择弹窗大小",alert,height(),1f)
        compose.runOnIdle{appScale="1.0";systemScale=2f};compose.waitForIdle();val systemAlert=height()
        assertTrue("系统字号应放大选择弹窗：$alert -> $systemAlert",systemAlert>alert*1.4f)
        compose.runOnIdle{systemScale=1f};compose.waitForIdle()
        assertEquals("恢复系统字号后选择弹窗大小应还原",alert,height(),1f)
        compose.runOnIdle{systemScale=2f;choice=false};compose.waitForIdle();val systemPage=height()
        assertTrue("系统字号应放大页面：$page -> $systemPage",systemPage>page*1.4f)
        compose.runOnIdle{systemScale=1f};compose.waitForIdle()
        assertEquals("恢复系统字号后页面大小应还原",page,height(),1f)
    }
}
