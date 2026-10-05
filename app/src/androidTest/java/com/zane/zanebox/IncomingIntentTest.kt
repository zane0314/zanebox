package com.zane.zanebox

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.backup.BackupManager
import com.zane.zanebox.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncomingIntentTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun waitFor(label:String,predicate:()->Boolean) { val until=android.os.SystemClock.elapsedRealtime()+20000;while(!predicate()) { if(android.os.SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) } }
    private fun dialogShown()=compose.onAllNodesWithTag("import_confirm").fetchSemanticsNodes().isNotEmpty()
    private fun awaitDialog(label:String) { compose.waitUntil(20000) { dialogShown() };assertTrue(label,dialogShown()) }
    @Test fun externalIntentsRequireConfirmationBeforeWriting() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val store=ZaneStore(context);store.replace(AppData())
        val view=Intent(Intent.ACTION_VIEW,Uri.parse("socks://10.0.2.2:19081#deep-fixture")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull("原生链接未注册",context.packageManager.resolveActivity(view,0))
        val zip=File(File(context.cacheDir,"exports").apply { mkdirs() },"incoming-${java.util.UUID.randomUUID()}.zip")
        val scenario=ActivityScenario.launch<MainActivity>(view)
        try {
            awaitDialog("ACTION_VIEW确认框");Thread.sleep(500);assertEquals("确认前不得写入",0,store.snapshot().nodes.size)
            compose.onNodeWithTag("import_confirm").performClick()
            waitFor("ACTION_VIEW节点导入") { store.snapshot().nodes.size==1 };assertEquals("deep-fixture",store.snapshot().nodes.single().name)
            val original=store.snapshot().selectedNodeId
            scenario.recreate();Thread.sleep(600);assertFalse("重建后不得重复弹出",dialogShown());assertEquals(1,store.snapshot().nodes.size)
            context.startActivity(Intent(Intent.ACTION_SEND).setClass(context,MainActivity::class.java).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"socks://10.0.2.2:19082#share-fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitDialog("ACTION_SEND确认框");compose.onNodeWithTag("import_confirm").performClick()
            waitFor("ACTION_SEND节点导入") { store.snapshot().nodes.size==2 };assertEquals(original,store.snapshot().selectedNodeId)
            val hostile=AppData(nodes=listOf(Node(900,900,"hostile","{\"type\":\"socks\",\"server\":\"192.0.2.1\",\"server_port\":1}")),groups=listOf(Group(900,"hostile")))
            zip.writeBytes(BackupManager(context).export(hostile))
            val stream=FileProvider.getUriForFile(context,"${context.packageName}.files",zip)
            context.startActivity(Intent(Intent.ACTION_SEND).setClass(context,MainActivity::class.java).setType("application/zip").putExtra(Intent.EXTRA_STREAM,stream).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            awaitDialog("分享ZIP确认框");compose.onNodeWithTag("import_cancel").performClick()
            Thread.sleep(800);assertEquals("取消后数据不得被替换",listOf("deep-fixture","share-fixture"),store.snapshot().nodes.map { it.name }.sorted())
            val url=Uri.encode("http://10.0.2.2:19080/subscription")
            val subscription=Intent(Intent.ACTION_VIEW,Uri.parse("sn://subscription?url=$url&name=deep-subscription")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertNotNull(context.packageManager.resolveActivity(subscription,0));context.startActivity(subscription)
            awaitDialog("订阅链接确认框");Thread.sleep(300);assertTrue(store.snapshot().groups.none { it.name=="deep-subscription" })
            compose.onNodeWithTag("import_confirm").performClick()
            waitFor("订阅链接下载/导入") { store.snapshot().groups.any { it.name=="deep-subscription" && it.updatedAt>0 } }
            assertEquals(4,store.snapshot().nodes.size);assertEquals(original,store.snapshot().selectedNodeId)
            File(context.getExternalFilesDir(null),"incoming-intent-result.json").writeText("{\"nodeViewConfirm\":true,\"shareSendConfirm\":true,\"zipCancelKeepsData\":true,\"subscriptionViewConfirm\":true,\"activityRecreateNoDuplicate\":true}")
        } finally {
            zip.delete()
            // singleTask changes the Intent used by ActivityScenario's matching monitor.
            ins.runOnMainSync {
                val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STARTED,androidx.test.runner.lifecycle.Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            ins.waitForIdleSync();store.close()
        }
    }
}
