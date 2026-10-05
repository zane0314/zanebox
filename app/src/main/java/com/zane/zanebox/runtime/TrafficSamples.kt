package com.zane.zanebox.runtime

import org.json.JSONObject
import java.util.Locale

internal data class TrafficSample(val id:String,val app:String,val domain:String,val tx:Long,val rx:Long)
internal object TrafficSamples {
    fun domain(value:String):String {
        var host=value.trim().substringAfter("://")
        host=host.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        host=if(host.startsWith('['))host.substringBefore(']')+"]" else if(host.count { it==':' }==1)host.substringBefore(':') else host
        return host.trim().lowercase(Locale.ROOT).take(253)
    }
    fun parse(text:String):List<TrafficSample> {
        require(text.toByteArray(Charsets.UTF_8).size<=256*1024) { "连接统计超过256KiB" }
        val rows=JSONObject(text).optJSONArray("connections") ?: return emptyList()
        val seen=HashSet<String>()
        return (0 until minOf(rows.length(),512)).mapNotNull { i ->
            val row=rows.getJSONObject(i);val id=row.optString("id")
            if(id.isBlank() || !seen.add(id))return@mapNotNull null
            val meta=row.optJSONObject("metadata") ?: JSONObject()
            TrafficSample(id,meta.optString("processPath").substringAfterLast('/').ifBlank { meta.optString("process","未知应用") }.take(253),domain(meta.optString("host").ifBlank { meta.optString("destinationIP","未知域名") }),row.optLong("upload").coerceAtLeast(0),row.optLong("download").coerceAtLeast(0))
        }
    }
}
