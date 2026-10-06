package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportDestinationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun seed():AppViewModel {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle { vm.edit { AppData(groups=listOf(Group(801,"已有组")),settings=mapOf("appLanguage" to "zh-CN","browseGroupId" to "801")) } }
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.groups.singleOrNull()?.id==801L}
        return vm
    }
    private fun offer(vm:AppViewModel,text:String) {
        compose.runOnIdle { vm.importText(text) }
        compose.waitUntil(10000){!vm.busy.value && vm.pendingImport.value!=null}
        compose.onNodeWithTag("import_confirm").assertIsDisplayed()
    }
    @Test fun nodesRequireConfirmationAndSupportExistingNewCancelAndInvalidDestination() {
        val vm=seed()
        offer(vm,"socks://10.0.2.2:19081#cancel")
        assertTrue(vm.data.value.nodes.isEmpty())
        compose.onNodeWithTag("import_cancel").performClick()
        assertTrue(vm.data.value.nodes.isEmpty())
        offer(vm,"socks://10.0.2.2:19081#existing")
        compose.onNodeWithTag("import_group_801").performClick()
        compose.onNodeWithTag("import_confirm").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.pendingImport.value==null}
        assertEquals(801L,vm.data.value.nodes.single().groupId)
        offer(vm,"socks://10.0.2.2:19082#new")
        compose.onNodeWithTag("import_new_group").performClick()
        compose.onNodeWithTag("import_group_name").performTextReplacement("")
        compose.onNodeWithTag("import_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("import_group_name").performTextReplacement("新的节点组")
        compose.onNodeWithTag("import_confirm").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.pendingImport.value==null}
        assertEquals("新的节点组",vm.data.value.groups.single{it.id==vm.data.value.nodes.single{it.name=="new"}.groupId}.name)
        offer(vm,"socks://10.0.2.2:19081#invalid")
        compose.onNodeWithTag("import_group_801").performClick()
        compose.runOnIdle { vm.deleteGroup(801) }
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.groups.none{it.id==801L}}
        compose.onNodeWithTag("import_confirm").assertIsNotEnabled()
        compose.runOnIdle { vm.confirmImport("ignored",801) }
        compose.waitUntil(10000){!vm.busy.value && !vm.importing.value}
        assertNotNull(vm.pendingImport.value)
        assertEquals(1,vm.data.value.nodes.size)
        assertTrue(vm.data.value.groups.none{it.id==801L})
        compose.onNodeWithTag("import_cancel").performClick()
    }
    @Test fun failedSubscriptionKeepsConfirmationAndReusesTheGroupOnRetry() {
        val vm=seed()
        offer(vm,"http://127.0.0.1:1/subscription")
        compose.onNodeWithTag("import_group_name").performTextReplacement("下载失败的订阅")
        compose.runOnIdle {
            vm.confirmImport("下载失败的订阅",-1)
            vm.confirmImport("重复点击",-1)
            vm.cancelImport()
        }
        compose.waitUntil(20000){!vm.busy.value && !vm.importing.value}
        assertNotNull(vm.pendingImport.value)
        val created=vm.data.value.groups.single{it.subscriptionUrl.isNotBlank()}.id
        compose.onNodeWithTag("import_group_name").performTextReplacement("重试改名")
        compose.onNodeWithTag("import_confirm").performClick()
        compose.waitUntil(20000){!vm.busy.value && !vm.importing.value}
        assertNotNull(vm.pendingImport.value)
        assertEquals(created,vm.data.value.groups.single{it.subscriptionUrl.isNotBlank()}.id)
        assertEquals("重试改名",vm.data.value.groups.single{it.subscriptionUrl.isNotBlank()}.name)
        assertEquals(2,vm.data.value.groups.size)
        compose.onNodeWithTag("import_cancel").performClick()
        assertNull(vm.pendingImport.value)
    }
    @Test fun subscriptionCreatesAnEditableNewGroup() {
        val vm=seed()
        offer(vm,"http://10.0.2.2:19080/subscription")
        compose.onNodeWithTag("import_group_801").assertDoesNotExist()
        compose.onNodeWithTag("import_group_name").performTextReplacement("改名的订阅")
        compose.onNodeWithTag("import_confirm").performClick()
        compose.waitUntil(20000){!vm.busy.value && vm.data.value.groups.any{it.name=="改名的订阅" && it.updatedAt>0}}
        assertEquals(2,vm.data.value.groups.size)
        assertEquals(2,vm.data.value.nodes.size)
    }
}
