package com.zane.zanebox.backup

import org.json.JSONObject
import org.json.JSONTokener

/** Guard recursion before JSONObject can consume attacker controlled nesting on the app stack. */
internal fun backupJson(text:String):JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size<=MAX_BACKUP) { "备份JSON超过64MiB" }
    com.zane.zanebox.json.requireJsonDepth(text)
    val tokener=JSONTokener(text);val value=tokener.nextValue()
    require(value is JSONObject && tokener.nextClean()=='\u0000') { "备份JSON必须是单个对象" }
    return value
}
internal fun backupJson(bytes:ByteArray):JSONObject = backupJson(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
