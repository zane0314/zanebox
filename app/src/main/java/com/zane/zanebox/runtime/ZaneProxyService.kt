package com.zane.zanebox.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ZaneProxyService:Service() {
    private lateinit var runtime:ZaneRuntime
    override fun onCreate() { super.onCreate();runtime=ZaneRuntime(this,null);runtime.create() }
    override fun onBind(intent:Intent):IBinder=runtime.bind()
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=runtime.startCommand(intent)
    override fun onDestroy() { runtime.destroy();super.onDestroy() }
}
