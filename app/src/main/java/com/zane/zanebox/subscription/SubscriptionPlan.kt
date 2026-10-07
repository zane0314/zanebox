package com.zane.zanebox.subscription

import com.zane.zanebox.data.Group
import org.json.JSONObject

object SubscriptionPlan {
    fun dueAt(group:Group):Long {
        val options=SubscriptionOptions.parse(group.options)
        val legacy=JSONObject(group.options).optLong("subscriptionLastUpdated")*1000L
        val last=group.updatedAt.takeIf { it>0 } ?: legacy
        return if(last<=0) 0 else last + options.autoUpdateDelay*60000L
    }
    fun nextAt(group:Group):Long {
        val runtime=JSONObject(group.options).optJSONObject("subscriptionRuntime")
        val retry=runtime?.optLong("nextAttempt") ?: 0L
        return if(runtime?.optString("state") in setOf("applying","apply-error") && retry>0)retry else maxOf(dueAt(group),retry)
    }
    fun shouldUpdate(group:Group,now:Long,connected:Boolean):Boolean {
        val options=SubscriptionOptions.parse(group.options)
        return group.enabled && group.subscriptionUrl.isNotBlank() && options.autoUpdate && now>=dueAt(group) && (!options.updateWhenConnectedOnly || connected)
    }
    fun scheduled(group:Group):Boolean = group.enabled && group.subscriptionUrl.isNotBlank() && SubscriptionOptions.parse(group.options).autoUpdate
}
