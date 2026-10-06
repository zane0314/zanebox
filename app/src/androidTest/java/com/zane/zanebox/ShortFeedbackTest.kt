package com.zane.zanebox

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShortFeedbackTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun feedbackLastsTwoSecondsAndNewMessageReplacesIt() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fun visible(text:String)=compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        compose.runOnIdle { vm.message.value="重启已完成-110" }
        compose.waitUntil(5000){visible("重启已完成-110")}
        val began=SystemClock.elapsedRealtime()
        Thread.sleep(600);assertTrue(visible("重启已完成-110"))
        compose.waitUntil(3500){!visible("重启已完成-110")}
        val elapsed=SystemClock.elapsedRealtime()-began
        assertTrue("提示过早消失：$elapsed",elapsed>=1200);assertTrue("提示停留过久：$elapsed",elapsed<2800)
        compose.runOnIdle { vm.message.value="第一条-110" };compose.waitUntil(5000){visible("第一条-110")}
        compose.runOnIdle { vm.message.value="第二条-110" };compose.waitUntil(1000){visible("第二条-110")};assertFalse(visible("第一条-110"))
    }
}
