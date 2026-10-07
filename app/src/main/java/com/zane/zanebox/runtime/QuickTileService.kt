package com.zane.zanebox.runtime

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.zane.zanebox.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickTileService:TileService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onStartListening() { qsTile?.apply { label="Links";state=if(getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("connected",false))Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;updateTile() } }
    override fun onClick() {
        val connected=getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("connected",false)
        scope.launch {
            try {
            val target=withContext(Dispatchers.IO) { RuntimeServiceTarget.serviceClass(this@QuickTileService,active=connected) }
            withContext(Dispatchers.Main.immediate) {
                if(!connected && target==ZaneVpnService::class.java && VpnService.prepare(this@QuickTileService)!=null) {
                    val intent=Intent(this@QuickTileService,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if(android.os.Build.VERSION.SDK_INT>=34)startActivityAndCollapse(android.app.PendingIntent.getActivity(this@QuickTileService,0,intent,android.app.PendingIntent.FLAG_IMMUTABLE)) else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
                    return@withContext
                }
                ContextCompat.startForegroundService(this@QuickTileService,Intent(this@QuickTileService,target).setAction(if(connected)"stop" else "start"))
            }
            } catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {android.util.Log.e("LinksTile","tile operation failed",e)}
        }
    }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
