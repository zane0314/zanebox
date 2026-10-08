package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import java.util.Locale

internal data class InstalledApp(val name:String,val packageName:String,val system:Boolean=false,val launchable:Boolean=true)

internal fun appPackages(text:String):Set<String> = text.split(Regex("[\\s,;]+" )).filter{it.isNotBlank()}.toSet()

internal fun filterVpnPackages(data:AppData,packages:Iterable<String>):Set<String> {
    if(!data.bool("perAppEnabled"))return packages.toSet()
    val selected=appPackages(data.setting("perAppPackages"))
    val include=data.setting("perAppMode","exclude")=="include"
    return packages.filter{(it in selected)==include}.toSet()
}

internal fun effectiveSmartPackages(data:AppData,key:String,installed:Set<String>?=null):Set<String> {
    val packages=filterVpnPackages(data,appPackages(data.setting("smartCustom.$key.packages")))
    return if(installed==null)packages else packages.intersect(installed)
}

internal fun commonAppPackages(apps:List<InstalledApp>,self:String,bypass:Boolean=false):Set<String> = apps.filter {
    it.packageName!=self && (CommonProxyApps.matches(it.packageName,it.name) || it.packageName in CommonProxyApps.companions)!=bypass
}.map{it.packageName}.toSet()

internal fun visibleApps(apps:List<InstalledApp>,selected:Set<String>,query:String,showSystem:Boolean,scope:AppData?=null):List<InstalledApp> {
    val allowed=scope?.let{filterVpnPackages(it,apps.map{app->app.packageName})}
    return apps.filter{(showSystem || it.launchable && !it.system) && (query.isBlank() || it.name.contains(query,true) || it.packageName.contains(query,true)) && (allowed==null || it.packageName in allowed)}
        .sortedWith(compareByDescending<InstalledApp>{it.packageName in selected}.thenBy{it.name.lowercase(Locale.ROOT)}.thenBy{it.packageName})
}

internal data class AppPaste(val accepted:Set<String>,val skipped:Int)
internal fun parseAppPaste(text:String,installed:Set<String>,scope:AppData?=null):AppPaste {
    val entries=appPackages(text)
    val valid=entries.filter{it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) && it in installed}.toSet()
    val accepted=if(scope==null)valid else filterVpnPackages(scope,valid)
    return AppPaste(accepted,entries.size-accepted.size)
}
