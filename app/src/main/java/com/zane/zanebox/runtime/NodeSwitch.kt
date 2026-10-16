package com.zane.zanebox.runtime

import com.zane.zanebox.data.AppData
import java.util.UUID

/** Re-picking the stored node is not a switch, but leaving auto-select always is. */
internal fun isEffectiveNodeSwitch(before:AppData,id:Long):Boolean = before.bool("homeAutoSelect") || before.selectedNodeId!=id

/** [token] is blank for runtime-internal selections (kernel sync), which must never start a latency test. */
internal class SelectRequest(val id:Long,val token:String)
internal fun selectPayload(id:Long,token:String):String = if(token.isBlank())id.toString() else "$id|$token"
internal fun parseSelectRequest(payload:String):SelectRequest = SelectRequest(payload.substringBefore('|').toLong(),payload.substringAfter('|',""))

/**
 * Every user switch owns one token that the runtime always answers (success, no-op or failure) and that [accept] consumes once,
 * so a replayed receipt starts nothing. Tokens are never evicted: dropping an unanswered one would lose a valid switch's test.
 * [clear] only runs when the binding is gone and no receipt can arrive any more.
 */
internal class SwitchTestGate {
    private val pending=HashSet<String>()
    @Synchronized fun issue():String = UUID.randomUUID().toString().also { pending.add(it) }
    @Synchronized fun accept(token:String):Boolean = token.isNotBlank() && pending.remove(token)
    @Synchronized fun clear() = pending.clear()
}
