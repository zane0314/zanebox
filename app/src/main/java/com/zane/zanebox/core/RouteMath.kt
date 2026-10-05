package com.zane.zanebox.core

import java.math.BigInteger
import java.net.InetAddress

/** VpnService.Builder.excludeRoute exists only on API 33+; older systems need the complement as explicit routes. */
internal object RouteMath {
    private data class Prefix(val address:BigInteger,val length:Int,val bits:Int) {
        fun contains(other:Prefix)=bits==other.bits && length<=other.length && address.shiftRight(bits-length)==other.address.shiftRight(bits-length)
        fun halves():List<Prefix> = listOf(Prefix(address,length+1,bits),Prefix(address.setBit(bits-length-1),length+1,bits))
        override fun toString():String {
            val raw=address.toByteArray();val size=bits/8;val bytes=ByteArray(size)
            val copy=minOf(raw.size,size);System.arraycopy(raw,raw.size-copy,bytes,size-copy,copy)
            return InetAddress.getByAddress(bytes).hostAddress!!.substringBefore('%')+"/"+length
        }
    }
    private fun parse(value:String):Prefix {
        val parts=value.trim().split('/');require(parts.size==2) { "路由前缀无效" }
        val bytes=InetAddress.getByName(parts[0]).address;val bits=bytes.size*8;val length=parts[1].toInt();require(length in 0..bits) { "路由前缀长度无效" }
        val address=BigInteger(1,bytes).let { if(length==bits) it else it.shiftRight(bits-length).shiftLeft(bits-length) }
        return Prefix(address,length,bits)
    }
    fun subtract(include:List<String>,exclude:List<String>):List<String> {
        val excluded=exclude.map(::parse)
        fun remove(prefix:Prefix):List<Prefix> {
            val relevant=excluded.filter { it.bits==prefix.bits && (it.contains(prefix) || prefix.contains(it)) }
            if(relevant.isEmpty()) return listOf(prefix)
            if(relevant.any { it.contains(prefix) }) return emptyList()
            return prefix.halves().flatMap(::remove)
        }
        return include.map(::parse).flatMap(::remove).map { it.toString() }
    }
}
