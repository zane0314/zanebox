package com.zane.zanebox.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.zane.zanebox.data.ZaneStore
import kotlinx.coroutines.launch

class StartReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED) || !context.getSharedPreferences("runtime",Context.MODE_PRIVATE).getBoolean("connected",false))return
        val pending=goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val target=RuntimeServiceTarget.serviceClass(context)
                ZaneStore(context,autoLoad=false).use { store -> if(store.readSetting("autoStart","false").toBoolean() && (target==ZaneProxyService::class.java || VpnService.prepare(context)==null)) ContextCompat.startForegroundService(context,Intent(context,target).setAction("start")) }
            } catch(e:Exception) {android.util.Log.e("LinksStartup","automatic start failed",e)} finally { pending?.finish() }
        }
    }
}
