package com.zane.zanebox.runtime

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.zane.zanebox.MainActivity

class QuickTileService:TileService() {
    override fun onStartListening() { qsTile?.apply { label="zanebox";state=if(getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("connected",false))Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;updateTile() } }
    override fun onClick() {
        val connected=getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("connected",false)
        val target=RuntimeServiceTarget.serviceClass(this,active=connected)
        if(!connected && target==ZaneVpnService::class.java && VpnService.prepare(this)!=null) {
            val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(android.os.Build.VERSION.SDK_INT>=34)startActivityAndCollapse(android.app.PendingIntent.getActivity(this,0,intent,android.app.PendingIntent.FLAG_IMMUTABLE)) else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            return
        }
        ContextCompat.startForegroundService(this,Intent(this,target).setAction(if(connected)"stop" else "start"))
    }
}
