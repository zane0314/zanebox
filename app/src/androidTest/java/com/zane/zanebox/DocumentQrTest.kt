package com.zane.zanebox

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import org.hamcrest.Matchers.allOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.zane.zanebox.data.*
import com.zane.zanebox.backup.BackupManager
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Mocks system picker/scanner results, while exercising real URI I/O, QR pixels and app callbacks. */
@RunWith(AndroidJUnit4::class)
class DocumentQrTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,predicate:()->Boolean) { val until=android.os.SystemClock.elapsedRealtime()+15000;while(!predicate()) { if(android.os.SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) } }
    @Test fun documentCallbacksQrPixelsAndScannerResultWork() {
        val store=ZaneStore(context)
        val link="socks://10.0.2.2:19081#qr-fixture"
        store.replace(AppData(nodes=listOf(Node(301,301,"qr-fixture","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19081}",shareLink=link)),groups=listOf(Group(301,"二维码")),settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","selectedNodeId" to "301","selectedGroupId" to "301")))
        val folder=File(context.cacheDir,"exports").apply { mkdirs() }
        val qr=File(folder,"qa-qr-${java.util.UUID.randomUUID()}.png")
        val zip=File(folder,"qa-backup-${java.util.UUID.randomUUID()}.zip")
        val text=File(folder,"qa-group-${java.util.UUID.randomUUID()}.txt")
        fun uri(file:File)=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        Intents.init()
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT),hasType("image/png"))).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(uri(qr))))
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED,null))
            intending(hasAction("com.google.zxing.client.android.SCAN")).respondWith(ActivityResult(Activity.RESULT_OK,Intent().putExtra("SCAN_RESULT","socks://10.0.2.2:19082#scan-fixture").putExtra("SCAN_RESULT_FORMAT","QR_CODE")))
            scenario=ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("node_301"))
            compose.onNodeWithTag("node_menu_301").performClick();compose.onNode(hasText("二维码") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("节点分享二维码").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存图片").performClick();waitFor("PNG写入") { qr.length()>100 }
            val bitmap=android.graphics.BitmapFactory.decodeFile(qr.absolutePath);assertNotNull(bitmap)
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
            assertEquals(link,decoded);bitmap.recycle()
            compose.onNodeWithText("分享二维码").performClick();waitFor("分享Intent") { Intents.getIntents().any { it.action==Intent.ACTION_CHOOSER } }
            val chooser=Intents.getIntents().first { it.action==Intent.ACTION_CHOOSER }
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("image/png",send.type);assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("扫码导入").performClick();waitFor("扫码结果导入") { store.snapshot().nodes.any { it.name=="scan-fixture" } }
            intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT),hasType("application/zip"))).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(uri(zip))))
            compose.onNodeWithTag("tab_2").performClick()
            compose.onNodeWithTag("network_tools").performClick();compose.onNodeWithTag("tools_backup_tab").performClick();compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("backup_export"));compose.onNodeWithTag("backup_export").performScrollTo().performClick();waitFor("ZIP写入") { zip.length()>100 }
            val saved=BackupManager(context).`import`(zip.readBytes());assertEquals(2,saved.nodes.size)
            intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT),hasType("text/plain"))).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(uri(text))))
            compose.onNodeWithTag("page_back").performClick()
            compose.onNodeWithTag("settings_groups").performClick();compose.onNodeWithTag("manage_group_menu_301").performClick();compose.onNodeWithText("导出节点").performClick()
            waitFor("分组文本写入") { text.length()>0 }
            assertEquals("qr-fixture",com.zane.zanebox.subscription.SubscriptionParser.parse(text.readText()).single().name)
            File(context.getExternalFilesDir(null),"group-export-result.txt").writeBytes(text.readBytes())
            File(context.getExternalFilesDir(null),"document-qr-result.json").writeText("{\"qrPngDecode\":true,\"shareUriGrant\":true,\"scannerCallback\":true,\"documentZipWrite\":true,\"groupDocumentScope\":true,\"scope\":\"mock picker/scanner results; real Android URI I/O\"}")
        } finally { scenario?.close();Intents.release();store.close() }
    }
}
