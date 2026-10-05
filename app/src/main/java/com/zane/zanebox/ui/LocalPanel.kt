package com.zane.zanebox.ui

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.ByteArrayInputStream
import java.net.URI

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LocalPanelDialog(vm:AppViewModel,onDismiss:()->Unit) {
    val url by vm.service.panelUrl.collectAsStateWithLifecycle()
    val runtime by vm.service.snapshot.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val webView=remember(context){WebView(context)}
    val origin=remember(url,runtime.state){runCatching{URI(url)}.getOrNull()?.takeIf{runtime.state==2 && it.host=="127.0.0.1" && it.scheme=="http" && it.port>0 && it.userInfo==null}}
    val currentStorageOrigin by rememberUpdatedState(origin?.let { "${it.scheme}://${it.host}:${it.port}" })
    fun allowed(value:String):Boolean {
        val target=runCatching{URI(value)}.getOrNull() ?: return false
        return origin!=null && target.scheme==origin.scheme && target.host=="127.0.0.1" && target.port==origin.port && target.userInfo==null
    }
    DisposableEffect(webView,lifecycle) {
        webView.settings.apply {
            javaScriptEnabled=true
            domStorageEnabled=true
            allowFileAccess=false
            allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically=false
            cacheMode=WebSettings.LOAD_NO_CACHE
        }
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME)webView.onResume()
            else if(event==Lifecycle.Event.ON_PAUSE)webView.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.tag=null
            webView.loadUrl("about:blank")
            currentStorageOrigin?.let { WebStorage.getInstance().deleteOrigin(it) }
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    DisposableEffect(webView,origin) {
        webView.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean = !allowed(request.url.toString())
            @Deprecated("Legacy WebView navigation callback")
            override fun shouldOverrideUrlLoading(view:WebView,value:String):Boolean = !allowed(value)
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                return if(allowed(request.url.toString()))null else WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
            }
        }
        onDispose { }
    }
    UiPage("本地 YACD", onDismiss) { padding ->
        if (origin == null) {
            Text(
                if(runtime.state==2)"正在准备本地面板；需要启用 Clash API 或流量统计。" else "请先连接代理后再打开本地面板。",
                Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )
        } else {
            AndroidView(
                factory = { webView },
                update = { view -> if (view.tag != url) { view.tag = url; view.loadUrl(url) } },
                modifier = Modifier.fillMaxSize().padding(padding).testTag("yacd_panel")
            )
        }
    }
}
