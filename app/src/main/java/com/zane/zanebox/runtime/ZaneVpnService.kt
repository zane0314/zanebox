package com.zane.zanebox.runtime

import android.content.Intent
import android.net.VpnService
import android.os.IBinder

class ZaneVpnService:VpnService() {
    private lateinit var runtime:ZaneRuntime
    override fun onCreate() { super.onCreate();runtime=ZaneRuntime(this,this);runtime.create() }
    override fun onBind(intent:Intent):IBinder? = if(intent.action==SERVICE_INTERFACE)super.onBind(intent) else runtime.bind()
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=runtime.startCommand(intent)
    override fun onRevoke()=runtime.revoke()
    override fun onDestroy() { runtime.destroy();super.onDestroy() }
}
