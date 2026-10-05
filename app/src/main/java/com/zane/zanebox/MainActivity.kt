package com.zane.zanebox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import com.zane.zanebox.ui.ZaneApp

class MainActivity : ComponentActivity() {
    private lateinit var model:AppViewModel
    private val notifications=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        model = ViewModelProvider(this)[AppViewModel::class.java]
        setContent { ZaneApp(model) }
        if(savedInstanceState==null) importIntent(intent)
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val preferences=getSharedPreferences("permissions",MODE_PRIVATE)
            if(!preferences.getBoolean("notificationAsked",false)) { preferences.edit().putBoolean("notificationAsked",true).apply();notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);importIntent(intent) }
    // Any app can fire VIEW/SEND at us; nothing is written until the user confirms in ZaneApp.
    private fun importIntent(source:Intent?) {
        if(source?.action==Intent.ACTION_VIEW) {
            model.offerLink(source.dataString.orEmpty())
        }
        if(source?.action==Intent.ACTION_SEND) {
            source.getStringExtra(Intent.EXTRA_TEXT)?.let { text -> if(text.startsWith("sn://subscription") || text.startsWith("clash://install-config") || text.startsWith("zanebox://"))model.offerLink(text) else model.offerText(text) }
            @Suppress("DEPRECATION")
            source.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let(model::offerStream)
        }
    }
}
