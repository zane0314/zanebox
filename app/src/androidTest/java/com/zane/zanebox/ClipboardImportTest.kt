package com.zane.zanebox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClipboardImportTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun systemClipboardUsesTheSubscriptionAndNodeConfirmationFlow() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle {vm.edit {AppData(groups=listOf(Group(801,"已有组")),settings=mapOf("appLanguage" to "zh-CN","browseGroupId" to "801"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.groups.size==1}
        val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        for(text in listOf("http://10.0.2.2:19080/subscription","socks://10.0.2.2:19081#clipboard")) {
            compose.runOnIdle {clipboard.setPrimaryClip(ClipData.newPlainText("test",text));assertEquals(text,clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString())}
            compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("从剪贴板导入").performClick()
            compose.waitUntil(10000){vm.pendingImport.value!=null}
            compose.onNodeWithTag("import_confirm").assertIsDisplayed()
            if(text.startsWith("http"))compose.onNodeWithTag("import_group_name").assertIsDisplayed() else compose.onNodeWithTag("import_group_801").assertIsDisplayed()
            assertTrue(vm.data.value.nodes.isEmpty());compose.onNodeWithTag("import_cancel").performClick()
        }
    }
}
