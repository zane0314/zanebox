package com.zane.zanebox.ui

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

internal suspend fun receiveStoreRefreshes(requests:ReceiveChannel<Unit>,reload:suspend ()->Unit) {
    for(request in requests) {
        reload()
        // The conflated channel keeps the final request even during a slow reload or a busy batch.
        delay(250)
    }
}
