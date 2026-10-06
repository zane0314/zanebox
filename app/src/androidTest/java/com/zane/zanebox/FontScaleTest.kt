package com.zane.zanebox

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FontScaleTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fullPageAndChoiceDialogsUseTheAppFontScale() {
        var scale by mutableStateOf("1.0")
        var choice by mutableStateOf(false)
        compose.setContent {
            ZaneTheme(AppData(settings=mapOf("fontScale" to scale))) {
                if(choice)ChoiceDialog("字号测试","a",listOf("a" to "Choice item"),{},onChoose={})
                else UiPageList("字号测试",{}) {}
            }
        }
        fun height()=compose.onNodeWithText("字号测试").fetchSemanticsNode().boundsInRoot.height
        compose.waitForIdle();val page=height()
        compose.runOnIdle{scale="2.0"};compose.waitForIdle()
        // Android 14+ scales larger sp text nonlinearly; 2.0 is not a literal 2x pixel height.
        println("font-scale page=$page -> ${height()}")
        assertTrue("页面弹窗字号未放大：$page -> ${height()}",height()>page*1.4f)
        compose.runOnIdle{scale="1.0";choice=true};compose.waitForIdle();val alert=height()
        compose.runOnIdle{scale="2.0"};compose.waitForIdle()
        println("font-scale choice=$alert -> ${height()}")
        assertTrue("选择弹窗字号未放大：$alert -> ${height()}",height()>alert*1.4f)
    }
}
