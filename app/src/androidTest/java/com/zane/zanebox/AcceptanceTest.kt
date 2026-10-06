package com.zane.zanebox

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.zane.zanebox.data.*
import com.zane.zanebox.backup.BackupManager
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.ui.AppViewModel
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Destructive only to the fresh emulator's independent ZaneBox database. */
@RunWith(AndroidJUnit4::class)
class AcceptanceTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val ins=InstrumentationRegistry.getInstrumentation()
 private val context get()=ins.targetContext
 private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText() }
 private fun waitFor(label:String,timeout:Long=20000,predicate:()->Boolean) { val end=SystemClock.elapsedRealtime()+timeout;while(!predicate()){ if(SystemClock.elapsedRealtime()>end) Assert.fail("超时 $label");Thread.sleep(100) } }
 private fun shot(name:String) { compose.waitForIdle();Thread.sleep(500);val b=ins.uiAutomation.takeScreenshot();File(context.getExternalFilesDir(null),"acceptance-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
 private fun probe(expected:String,url:String="http://203.0.113.9:19080/probe") {
  val nonce="p"+System.nanoTime();shell("am start -W -n com.zane.probe/.ProbeActivity --es url $url --es nonce $nonce")
  var result="";waitFor("不同 UID HTTP $expected",15000){result=shell("logcat -d -s ZaneProbe:I");result.contains("$nonce=")};Assert.assertTrue(result,result.contains("$nonce=$expected uid="))
 }
 @Test fun fullAcceptance() {
  val store=ZaneStore(context);store.replace(AppData(settings=mapOf("appLanguage" to "zh-CN")))
  var scenario=ActivityScenario.launch(MainActivity::class.java)
  compose.onNodeWithText("暂无节点，点右上角添加节点或订阅").performScrollTo().assertIsDisplayed();shot("empty")
  compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("导入文本").performClick()
  compose.onNodeWithTag("editor_field_0").performTextInput("{\"outbounds\":[{\"type\":\"socks\",\"tag\":\"香港 A\",\"server\":\"10.0.2.2\",\"server_port\":19081},{\"type\":\"socks\",\"tag\":\"日本 B\",\"server\":\"10.0.2.2\",\"server_port\":19082}]}")
  compose.onNodeWithTag("editor_save").performClick();waitFor("UI导入落盘"){store.snapshot().nodes.size==2}
  val imported=store.snapshot();val a=imported.nodes[0].id;val b=imported.nodes[1].id
  compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("node_$a"));compose.onNodeWithTag("node_$a").performClick();waitFor("默认节点选择"){store.snapshot().selectedNodeId==a}
  scenario.close()
  val app=context.applicationContext as Application;lateinit var vm:AppViewModel
  ins.runOnMainSync{vm=AppViewModel(app)}
  val g=store.snapshot().groups.single()
  ins.runOnMainSync{vm.saveGroup(g.copy(name="编辑后的订阅组",subscriptionUrl="http://10.0.2.2:19080/subscription"),true)}
  waitFor("订阅更新"){store.snapshot().groups.single().updatedAt>0 && store.snapshot().nodes.size==2}
  // Subscription changed protocol identity; get current IDs after replacement.
  val nodes=store.snapshot().nodes;val first=nodes[0].id;val second=nodes[1].id
  store.update{it.copy(nodes=it.nodes.map{n->n.copy(order=if(n.id==second)0 else 1)},settings=it.settings+mapOf("selectedNodeId" to first.toString(),"testUrl" to "http://10.0.2.2:19080/test","sniff" to "false","dnsRemote" to "tcp://10.0.2.2:19087","dnsDirect" to "tcp://10.0.2.2:19087"))}
  Assert.assertEquals(second,store.snapshot().nodes.sortedBy{it.order}.first().id)
  val backup=BackupManager(context);val original=store.snapshot();Assert.assertEquals(original,backup.`import`(backup.export(original)))
  try { store.replace(backup.`import`(byteArrayOf(1,2,3)));Assert.fail("坏备份被接受") }catch(_:Exception){}
  Assert.assertEquals(original,store.snapshot())
  File(context.getExternalFilesDir(null),"acceptance-config.json").writeText(ConfigBuilder.build(original))
  val client=ServiceClient(context);val events=java.util.concurrent.CopyOnWriteArrayList<String>();val observer=CoroutineScope(SupervisorJob()+Dispatchers.IO);observer.launch { client.events.collect { events.add(it) } };client.connect();Thread.sleep(1000)
  shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
  try {
   client.start();waitFor("VPN已连接",30000){if(client.snapshot.value.state==4)Assert.fail("VPN失败：${client.snapshot.value.error}");client.snapshot.value.state==2};probe("EXIT_A")
   val session=client.snapshot.value
   scenario=ActivityScenario.launch(MainActivity::class.java);scenario.recreate();Thread.sleep(500);shot("connected")
   Assert.assertEquals(session.generation,client.snapshot.value.generation);Assert.assertEquals(session.started,client.snapshot.value.started)
   client.testNodes(listOf(first,second));waitFor("节点HTTP测速",30000){client.testResults.value.size==2};Assert.assertTrue(client.testResults.value.values.all{it>=0});Assert.assertEquals(first,store.snapshot().selectedNodeId)
   client.testNodes(listOf(first,second));client.cancelTests();Thread.sleep(500);Assert.assertEquals(first,store.snapshot().selectedNodeId)
   fun apply(transform:(AppData)->AppData) { store.update(transform);File(context.getExternalFilesDir(null),"applied-config.json").writeText(ConfigBuilder.build(store.snapshot()));val generation=client.snapshot.value.generation;client.reload();waitFor("配置重载",30000){client.snapshot.value.state==2 && client.snapshot.value.generation>generation} }
   apply{it.copy(rules=listOf(RouteRule(900,"IP分流",ipCidrs="203.0.113.9/32",outbound="node:$second")))};probe("EXIT_B")
   apply{it.copy(rules=listOf(RouteRule(902,"域名分流",domains="probe.zanebox.test",outbound="node:$second")))};probe("EXIT_B","http://probe.zanebox.test:19080/probe")
   apply{it.copy(rules=listOf(RouteRule(902,"域名分流",domains="fakeip.zanebox.test",outbound="node:$second")),settings=it.settings+("enableDnsRouting" to "false"))};probe("EXIT_B","http://fakeip.zanebox.test:19080/probe")
   apply{it.copy(rules=listOf(RouteRule(901,"包名分流",packages="com.zane.probe",outbound="node:$second")),settings=it.settings+("enableDnsRouting" to "true"))};probe("EXIT_B")
   apply{it.copy(rules=emptyList(),settings=it.settings+mapOf("smart.ai.target" to "node:$second","smartRules.ai" to "IP-CIDR,203.0.113.9/32"))};probe("EXIT_B")
   apply{it.copy(settings=it.settings+mapOf("smart.ai.target" to "off","perAppEnabled" to "true","perAppMode" to "exclude","perAppPackages" to "com.zane.probe"))};probe("DIRECT","http://10.0.2.2:19080/probe")
   apply{it.copy(settings=it.settings+mapOf("perAppMode" to "include"))};probe("EXIT_A")
   val networkSession=client.snapshot.value
   shell("svc wifi disable");shell("svc data disable");Thread.sleep(1500)
   Assert.assertEquals(networkSession.started,client.snapshot.value.started)
   shell("svc wifi enable");shell("svc data enable");Thread.sleep(5000);probe("EXIT_A")
   Assert.assertEquals(networkSession.generation,client.snapshot.value.generation)
   shell("input keyevent 223");Thread.sleep(1000);probe("EXIT_A");shell("input keyevent 224");shell("wm dismiss-keyguard")
   Assert.assertEquals(networkSession.started,client.snapshot.value.started)
   val oldStarted=client.snapshot.value.started;val bgPid=shell("pidof com.zane.zanebox:bg").trim();Assert.assertTrue(bgPid.matches(Regex("[0-9]+")))
   shell("run-as com.zane.zanebox kill -9 $bgPid")
   waitFor("后台进程死亡后STICKY恢复",40000){client.snapshot.value.state==2 && client.snapshot.value.started>oldStarted};probe("EXIT_A")
   client.stop();waitFor("停止"){client.snapshot.value.state==0};client.start();waitFor("重连",30000){client.snapshot.value.state==2};probe("EXIT_A")
   client.refreshLogs();waitFor("服务日志"){client.logs.value.isNotEmpty()};Assert.assertFalse(client.logs.value.joinToString().contains("FATAL EXCEPTION"))
   scenario.close();store.update{it.copy(nodes=it.nodes.map{n->n.copy(name="长节点名称".repeat(50))},settings=it.settings+("fontScale" to "2.0"))}
   scenario=ActivityScenario.launch(MainActivity::class.java);compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("node_$first"));compose.onNodeWithTag("node_$first").assertIsDisplayed();shot("large-font");compose.onNodeWithTag("tab_1").performClick();shot("smart");compose.onNodeWithTag("main_back").performClick();compose.onNodeWithTag("tab_2").performClick();shot("settings")
   scenario.close();store.update{it.copy(settings=it.settings+("fontScale" to "1.0"))};scenario=ActivityScenario.launch(MainActivity::class.java)
   compose.onNodeWithTag("node_menu").performClick();compose.onNodeWithText("延迟升序").performClick();waitFor("延迟排序持久化"){store.snapshot().bool("sort_group_${g.id}")};Assert.assertEquals(first,store.snapshot().selectedNodeId)
   scenario.recreate();Assert.assertTrue(store.snapshot().bool("sort_group_${g.id}"));shot("sort-latency")
   compose.onNodeWithTag("group_${g.id}").performClick();compose.onNodeWithTag("group_menu_${g.id}").performClick();compose.onNodeWithText("订阅选项").performClick();compose.onNodeWithTag("subscription_options_save").performClick();waitFor("订阅选项落盘"){store.snapshot().groups.single().options.contains("autoUpdateDelay")}
   compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("network_tools"));compose.onNodeWithTag("network_tools").performClick();compose.onNodeWithTag("stun_tool").performClick();compose.onNodeWithTag("stun_server").performTextReplacement("127.0.0.1:invalid");compose.onNodeWithTag("stun_start").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("stun_result").fetchSemanticsNodes().isNotEmpty()};shot("stun-tools")
  } finally {shell("svc wifi enable");shell("svc data enable");shell("input keyevent 224");File(context.getExternalFilesDir(null),"runtime-last-snapshot.json").writeText(client.snapshot.value.json());File(context.getExternalFilesDir(null),"runtime-events.txt").writeText(events.joinToString("\n"));listOf("neko.log","tun-options.json").forEach { name->val f=File(context.cacheDir,name);if(f.exists())f.copyTo(File(context.getExternalFilesDir(null),name),overwrite=true) };File(context.getExternalFilesDir(null),"vpn-dumpsys.txt").writeText(shell("dumpsys connectivity"));runCatching { shot("last-state") };client.stop();Thread.sleep(1000);client.close();observer.cancel();scenario.close();vm.service.close();store.close()}
 }
}
