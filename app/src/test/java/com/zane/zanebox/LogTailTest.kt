package com.zane.zanebox

import com.zane.zanebox.runtime.LogTail
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTailTest {
    @Test fun reads500LinesAndHandlesUtf8ByteCut() {
        val file = File.createTempFile("links-log-tail-", ".log")
        try {
            file.writeText(buildString {
                repeat(700) { append("行").append(it).append("-😀-payload\n") }
            }, StandardCharsets.UTF_8)

            val lines = LogTail.read(file)
            assertEquals(500, lines.size)
            assertEquals("行699-😀-payload", lines.last())

            val bounded = LogTail.read(file, maxBytes = 96)
            assertTrue(bounded.isNotEmpty())
            assertTrue(bounded.size <= 500)
            assertEquals("行699-😀-payload", bounded.last())
            assertTrue(bounded.all { !it.contains('\uFFFD') })
            assertTrue(bounded.first().startsWith("行"))
        } finally {
            file.delete()
        }
    }
}
