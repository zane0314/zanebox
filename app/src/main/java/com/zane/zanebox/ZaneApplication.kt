package com.zane.zanebox

import android.app.Application

class ZaneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process=if(android.os.Build.VERSION.SDK_INT>=28)getProcessName() else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses?.firstOrNull { it.pid==android.os.Process.myPid() }?.processName
        mainProcess=process==packageName
        if(mainProcess) {
            com.zane.zanebox.subscription.SubscriptionScheduler.connectionCheck={ context -> com.zane.zanebox.runtime.ServiceClient.isConnected(context) }
            com.zane.zanebox.subscription.SubscriptionScheduler.initialize(this)
        }
    }
    companion object { @Volatile var mainProcess=false;private set }
}
