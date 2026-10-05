package com.zane.zanebox.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object NodeIdentity {
    /** Object key order and display tags never define a subscription node's identity. */
    fun key(outbound:String):String {
        val value=JSONObject(outbound);value.remove("tag");value.remove("name")
        fun canonical(v:Any?):String = when(v) {
            null,JSONObject.NULL -> "null"
            is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(v.get(it)) }
            is JSONArray -> (0 until v.length()).joinToString(",","[","]") { canonical(v.get(it)) }
            is String -> JSONObject.quote(v)
            is Number -> v.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: v.toString()
            else -> v.toString()
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
