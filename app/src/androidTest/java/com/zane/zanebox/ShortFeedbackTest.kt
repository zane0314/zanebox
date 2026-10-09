package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShortFeedbackTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun visible(text:String)=compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun assertFeedbackDuration(vm:AppViewModel,text:String) {
        val expected=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("feedbackTimeoutMillis","1000")!!.toLong()
        val accessibility=compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        assertEquals(expected,accessibility.getRecommendedTimeoutMillis(1000,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong())
        var began=0L
        compose.runOnIdle { began=compose.mainClock.currentTime;vm.message.value=text }
        compose.waitUntil(5000){compose.mainClock.advanceTimeByFrame();visible(text)}
        compose.mainClock.advanceTimeBy(300);assertTrue(visible(text))
        compose.waitUntil(expected+650){compose.mainClock.advanceTimeByFrame();!visible(text)}
        val elapsed=compose.mainClock.currentTime-began
        println("FEEDBACK_DURATION text=$text expected=$expected elapsed=$elapsed")
        assertTrue("提示过早消失：$elapsed",elapsed>=expected-350)
        assertTrue("提示停留过久：$elapsed",elapsed<expected+650)
    }
    @Test fun feedbackLastsOneSecondAndNewMessageReplacesIt() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.onNodeWithTag("home_header").assertIsDisplayed()
        compose.mainClock.autoAdvance=false
        try {
            assertFeedbackDuration(vm,"驻留时间测试")
            compose.runOnIdle { vm.message.value="第一条提示" };compose.waitUntil(5000){compose.mainClock.advanceTimeByFrame();visible("第一条提示")}
            compose.runOnIdle { vm.message.value="第二条提示" };compose.waitUntil(1000){compose.mainClock.advanceTimeByFrame();visible("第二条提示")}
            compose.waitUntil(650){compose.mainClock.advanceTimeByFrame();!visible("第一条提示")}
        } finally {compose.mainClock.autoAdvance=true}
    }
    @Test fun settingsPageUsesTheSameFeedbackDuration() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.mainClock.autoAdvance=false
        try {assertFeedbackDuration(vm,"设置页驻留时间测试")}
        finally {compose.mainClock.autoAdvance=true}
    }
}
