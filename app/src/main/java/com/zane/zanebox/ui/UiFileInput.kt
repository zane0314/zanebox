package com.zane.zanebox.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal fun readImportBytes(open:()->InputStream?):ByteArray = try {
    val input=open() ?: error("无法读取文件，请重新选择")
    input.use {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=it.read(buffer)
            if(count<0)break
            require(out.size()+count<=64*1024*1024) { "文件超过 64 MiB" }
            out.write(buffer,0,count)
        }
        out.toByteArray()
    }
} catch(e:SecurityException) {
    throw IllegalArgumentException("没有文件读取权限，请重新选择文件",e)
} catch(e:IOException) {
    throw IllegalArgumentException("无法读取文件，请确认文件存在且可访问",e)
}
