package com.zane.zanebox

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.ZaneStore
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SmartRuleCommitInstrumentedTest {
    @Test fun actualViewModelRejectsOldHttpResponseAfterSourceChange() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val handler=Thread {
            server.accept().use { socket ->
                val reader=socket.getInputStream().bufferedReader()
                while(!reader.readLine().isNullOrEmpty()) {}
                entered.countDown()
                check(release.await(15,TimeUnit.SECONDS))
                val body="DOMAIN-SUFFIX,old.invalid".toByteArray()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()+body)
            }
        }.apply { isDaemon=true;start() }
        val holder=ViewModelStore()
        val store=ZaneStore(context)
        lateinit var vm:AppViewModel
        fun waitFor(predicate:()->Boolean) {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            while(!predicate() && System.nanoTime()<until)Thread.sleep(30)
            assertTrue("ViewModel operation did not finish",predicate())
        }
        try {
            store.replace(AppData(settings=mapOf("smartUrl.ai" to "http://127.0.0.1:${server.localPort}/rules.txt","smartRules.ai" to "old")))
            instrumentation.runOnMainSync { vm=AppViewModel(context.applicationContext as Application);holder.put("probe",vm) }
            waitFor { !vm.busy.value }
            instrumentation.runOnMainSync { vm.updateSmart("ai") }
            assertTrue("Controlled HTTP request was not reached",entered.await(15,TimeUnit.SECONDS))
            store.update { it.copy(settings=it.settings+mapOf("smartUrl.ai" to "https://new.invalid/rules.txt","smartRules.ai" to "new rules")) }
            release.countDown()
            waitFor { !vm.busy.value }
            store.reload()
            assertEquals("https://new.invalid/rules.txt",store.snapshot().setting("smartUrl.ai"))
            assertEquals("new rules",store.snapshot().setting("smartRules.ai"))
            assertTrue(vm.message.value.contains("已变更"))
            File(context.getExternalFilesDir(null),"smart-rule-race-result.json").writeText("{\"actualViewModel\":true,\"actualHttp\":true,\"sqliteNewSourceAndRulesPreserved\":true}")
        } finally {
            release.countDown();server.close();handler.join(3000)
            instrumentation.runOnMainSync { holder.clear() }
            store.close()
        }
    }
}
