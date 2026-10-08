package com.zane.zanebox.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun batteryOptimizationState(context: Context): Boolean? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) null
    else (context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false)

internal fun openBatteryOptimizationSettings(context: Context, ignored: Boolean) {
    val packageUri = android.net.Uri.parse("package:${context.packageName}")
    val intents = if (ignored) {
        listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    } else {
        listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
    }
    var failure: Exception? = null
    for (intent in intents) {
        try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (exception: Exception) {
            failure = exception
        }
    }
    throw failure ?: IllegalStateException("没有可用的系统设置入口")
}

/** System battery-optimization state; the system dialog is opened only after an explicit tap. */
@Composable
internal fun BatteryOptimizationRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ignored by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf("") }

    fun refresh() {
        ignored = batteryOptimizationState(context)
    }

    LaunchedEffect(context) { refresh() }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val unsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
    val subtitle = when {
        unsupported -> "当前系统不支持电池优化豁免"
        ignored == true -> "已豁免 · 点击打开应用设置"
        ignored == false -> "未豁免 · 点击请求系统授权"
        else -> "正在检查系统状态…"
    }
    UiCard(modifier.testTag("battery_optimization_card")) {
        UiRow(
            "电池优化豁免",
            subtitle,
            Icons.Outlined.BatteryChargingFull,
            onClick = if (unsupported || ignored == null) null else {
                {
                    error = ""
                    runCatching { openBatteryOptimizationSettings(context, ignored == true) }
                        .onFailure { error = "无法打开系统设置，请在系统设置中搜索“电池优化”" }
                }
            },
            modifier = Modifier.testTag("battery_optimization"),
            chevron = !unsupported,
            summaryLines = 2,
        )
        if (error.isNotBlank()) {
            Text(
                error,
                Modifier.padding(start = 54.dp, end = 16.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
    }
}
