package com.zane.zanebox.runtime

import android.content.Context
import com.zane.zanebox.data.ZaneStore

internal object RuntimeServiceTarget {
    fun serviceClass(context:Context,active:Boolean=false):Class<*> {
        val mode=if(active)context.getSharedPreferences("runtime",Context.MODE_PRIVATE).getString("serviceMode","vpn")
        else ZaneStore(context).use { it.snapshot().setting("serviceMode","vpn") }
        return if(mode=="proxy")ZaneProxyService::class.java else ZaneVpnService::class.java
    }
}
