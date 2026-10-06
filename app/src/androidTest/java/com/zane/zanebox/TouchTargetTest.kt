package com.zane.zanebox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.zane.zanebox.ui.UiSwitch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TouchTargetTest {
    @get:Rule val compose=createComposeRule()
    @Test fun smallVisualTargetsExpandAndLargeTargetsAcceptEdgeTaps() {
        var small=0;var large=0;var enabled=false
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(32.dp)) {
                Box(Modifier.size(100.dp).testTag("small_parent"),contentAlignment=Alignment.Center) {
                    Box(Modifier.size(24.dp).clickable{small++}.testTag("small"))
                }
                Box(Modifier.size(100.dp).clickable{large++}.testTag("large"))
                UiSwitch(enabled,{enabled=it},Modifier.testTag("native_switch"))
            }
        } }
        compose.onNodeWithTag("small").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
        compose.onNodeWithTag("small_parent").performTouchInput { click(Offset(center.x+20.dp.toPx(),center.y)) }
        compose.onNodeWithTag("large").performTouchInput { click(Offset(width-4.dp.toPx(),height-4.dp.toPx())) }
        compose.onNodeWithTag("native_switch").assertHeightIsEqualTo(36.dp).performTouchInput { click(Offset(center.x,center.y-20.dp.toPx())) }
        compose.runOnIdle { assertEquals(1,small);assertEquals(1,large);assertTrue(enabled) }
    }
}
