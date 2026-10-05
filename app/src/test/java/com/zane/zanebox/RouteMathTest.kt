package com.zane.zanebox

import com.zane.zanebox.core.RouteMath
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress

class RouteMathTest {
    private val lan4=listOf("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","169.254.0.0/16")
    private fun covers(routes:List<String>,address:String):Boolean {
        val target=BigInteger(1,InetAddress.getByName(address).address)
        return routes.any { route -> val (host,length)=route.split('/');val bytes=InetAddress.getByName(host).address
            if(bytes.size!=InetAddress.getByName(address).address.size) false else { val bits=bytes.size*8;BigInteger(1,bytes).shiftRight(bits-length.toInt())==target.shiftRight(bits-length.toInt()) } }
    }
    @Test fun complementExcludesLanAndKeepsPublicRanges() {
        val routes=RouteMath.subtract(listOf("0.0.0.0/0"),lan4)
        listOf("8.8.8.8","1.1.1.1","198.18.0.1","172.15.255.255","172.32.0.1","192.169.0.1","11.0.0.1").forEach { assertTrue(it,covers(routes,it)) }
        listOf("10.1.2.3","172.19.0.2","192.168.1.1","169.254.1.1").forEach { assertFalse(it,covers(routes,it)) }
        val total=routes.sumOf { 1L shl (32-it.substringAfter('/').toInt()) }
        assertEquals((1L shl 32)-(1L shl 24)-(1L shl 20)-(1L shl 16)-(1L shl 16),total)
        assertTrue(routes.size<120)
    }
    @Test fun ipv6ComplementAndNoOverlap() {
        val routes=RouteMath.subtract(listOf("::/0"),listOf("fc00::/7","fe80::/10"))
        assertTrue(covers(routes,"2001:4860::8888"));assertFalse(covers(routes,"fd00::1"));assertFalse(covers(routes,"fe80::1"))
        assertEquals(listOf("8.8.8.0/24"),RouteMath.subtract(listOf("8.8.8.0/24"),lan4))
        assertTrue(RouteMath.subtract(listOf("10.0.0.0/16"),lan4).isEmpty())
    }
}
