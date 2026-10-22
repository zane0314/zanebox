package com.zane.zanebox.subscription

import java.math.BigDecimal

/** Go time.ParseDuration units/grammar; keep the user's duration instead of rounding or clamping. */
internal fun hysteriaHopInterval(raw:String):String {
    val value=if(raw.matches(Regex("[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+"))) "${raw}s" else raw
    val units=mapOf("ns" to 1L,"us" to 1000L,"µs" to 1000L,"μs" to 1000L,"ms" to 1000000L,"s" to 1000000000L,"m" to 60000000000L,"h" to 3600000000000L)
    val part=Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
    var offset=if(value.startsWith('+'))1 else 0;var nanos=BigDecimal.ZERO
    while(offset<value.length) {
        val match=part.matchAt(value,offset)
        require(match!=null) { "跳跃间隔格式无效" }
        nanos+=match.groupValues[1].toBigDecimal().multiply(BigDecimal.valueOf(units.getValue(match.groupValues[2]))).setScale(0,java.math.RoundingMode.DOWN)
        offset=match.range.last+1
    }
    require(nanos<=BigDecimal.valueOf(Long.MAX_VALUE)) { "跳跃间隔超出范围" }
    require(nanos>=BigDecimal.valueOf(5000000000L)) { "跳跃间隔不能小于5秒" }
    return value
}
