package com.zane.zanebox.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Only the configuration phase can fail an application; optional measurements never undo its receipt. */
internal suspend fun applyThenMeasure(
    timeout:Long,apply:suspend ()->Unit,onApplied:()->Unit,measure:suspend ()->Unit,onMeasurementFailure:(Exception)->Unit
) {
    var applied=false
    try {
        withTimeout(timeout) {apply();onApplied();applied=true;measure()}
    } catch(e:CancellationException) {
        if(applied)onMeasurementFailure(e)
        if(!applied || e !is TimeoutCancellationException)throw e
    } catch(e:Exception) {
        if(!applied)throw e
        onMeasurementFailure(e)
    }
}
