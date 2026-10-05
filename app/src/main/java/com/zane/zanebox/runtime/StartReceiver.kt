package com.zane.zanebox.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.zane.zanebox.data.ZaneStore

class StartReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED) || !context.getSharedPreferences("runtime",Context.MODE_PRIVATE).getBoolean("connected",false))return
        val target=RuntimeServiceTarget.serviceClass(context)
        ZaneStore(context).use { store -> if(store.snapshot().bool("autoStart",false)&&(target==ZaneProxyService::class.java || VpnService.prepare(context)==null)) ContextCompat.startForegroundService(context,Intent(context,target).setAction("start")) }
    }
}
