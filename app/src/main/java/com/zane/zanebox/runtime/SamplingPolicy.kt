package com.zane.zanebox.runtime

import org.json.JSONArray
import org.json.JSONObject

/** The native tracker counts the route's chosen tag, including service/group targets, once. */
internal object SamplingPolicy {
    fun pauseCore(deviceIdle:Boolean,subscriptionBusy:Boolean,wireGuard:Boolean)=deviceIdle && !subscriptionBusy && !wireGuard
    fun interval(interactive:Boolean,observed:Boolean)=if(interactive && observed)1000L else 10000L
    fun trackedTags(root:JSONObject):List<String> {
        val route=root.optJSONObject("route")
        val tags=linkedSetOf(route?.optString("final").orEmpty())
        fun visit(value:Any?) {
            when(value) {
                is JSONObject -> {value.optString("outbound").takeIf {it.isNotBlank()}?.let{tags.add(it)};value.keys().forEach{visit(value.opt(it))}}
                is JSONArray -> (0 until value.length()).forEach{visit(value.opt(it))}
            }
        }
        visit(route?.optJSONArray("rules"))
        if(tags.first().isBlank()) {
            val out=root.optJSONArray("outbounds")
            out?.optJSONObject(0)?.optString("tag")?.let{tags.add(it)}
        }
        return tags.filter {it.isNotBlank()}
    }
}
