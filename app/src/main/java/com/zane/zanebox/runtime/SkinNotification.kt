package com.zane.zanebox.runtime

import com.zane.zanebox.R
import com.zane.zanebox.ui.resolvedUiSkin

internal val skinNotificationIcons=linkedMapOf(
    "prism" to R.drawable.links_status_prism,
    "linear" to R.drawable.links_status_linear,
    "claude" to R.drawable.links_status_claude,
    "supabase" to R.drawable.links_status_supabase,
    "miro" to R.drawable.links_status_miro,
)
internal fun skinNotificationIcon(skin:String)=skinNotificationIcons.getValue(resolvedUiSkin(skin))
