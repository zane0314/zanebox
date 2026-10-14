package com.zane.zanebox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal val skinLauncherAliases=linkedMapOf(
    "prism" to "launcher.PrismAlias", "linear" to "launcher.LinearAlias",
    "claude" to "launcher.ClaudeAlias", "supabase" to "launcher.SupabaseAlias",
    "miro" to "launcher.MiroAlias",
)
// Keep old components installed until upgraded launchers have observed their disabled state.
private val retiredLauncherAliases=listOf("launcher.PixelAlias","launcher.NodeNAlias","launcher.ChannelGateAlias","launcher.ConfluenceAlias","launcher.LaunchPathAlias")
internal fun launcherAliasForSkin(value:String)=skinLauncherAliases.getValue(resolvedUiSkin(value))
private fun launcherComponents(context:Context)=(skinLauncherAliases.values+retiredLauncherAliases).map { ComponentName(context.packageName,"${context.packageName}.$it") }

internal fun enabledLauncherSkin(context:Context):String = skinLauncherAliases.entries.firstOrNull { (skin,alias) ->
    val state=context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName,"${context.packageName}.$alias"))
    state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && skin=="prism")
}?.key ?: "prism"

private fun setLauncherStates(pm:PackageManager,states:Map<ComponentName,Int>) {
    if(android.os.Build.VERSION.SDK_INT>=33)pm.setComponentEnabledSettings(states.map { (name,state) -> PackageManager.ComponentEnabledSetting(name,state,PackageManager.DONT_KILL_APP) })
    else states.entries.sortedByDescending { (name,state) -> state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && name.className.endsWith(".PrismAlias")) }
        .forEach { (name,state) -> pm.setComponentEnabledSetting(name,state,PackageManager.DONT_KILL_APP) }
}

/** A failed database save restores the exact previous launcher state. Startup reconciles process death. */
internal fun withLauncherSkin(context:Context,value:String,persist:()->Unit) {
    val pm=context.packageManager
    val selected="${context.packageName}.${launcherAliasForSkin(value)}"
    val before=launcherComponents(context).associateWith(pm::getComponentEnabledSetting)
    val next=before.mapValues { (name,_) -> if(name.className==selected)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
    try {
        if(before!=next)setLauncherStates(pm,next)
        persist()
    } catch(e:Exception) {
        runCatching {setLauncherStates(pm,before)}.exceptionOrNull()?.let(e::addSuppressed)
        throw e
    }
}

fun applyLauncherIcon(context:Context,value:String)=withLauncherSkin(context,value) {}
