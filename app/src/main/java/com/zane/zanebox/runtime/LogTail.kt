package com.zane.zanebox.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val LOG_TAIL_MAX_LINES = 500
private const val LOG_TAIL_MAX_BYTES = 256 * 1024

/** Reads only the final bounded byte window, dropping incomplete UTF-8 and partial first lines. */
internal object LogTail {
    fun read(file: File, maxBytes: Int = LOG_TAIL_MAX_BYTES): List<String> {
        require(maxBytes in 1..LOG_TAIL_MAX_BYTES) { "maxBytes must be between 1 and $LOG_TAIL_MAX_BYTES" }
        if (!file.isFile) return emptyList()

        val (text, startsAtLineBoundary) = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - maxBytes.toLong()).coerceAtLeast(0L)
            val bytes = ByteArray((length - start).toInt())
            val previous = if (start == 0L) -1 else {
                input.seek(start - 1)
                input.read()
            }
            input.seek(start)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(bytes, 0, count))
                .toString()
            decoded to (start == 0L || previous == '\n'.code || previous == '\r'.code)
        }

        val lines = text.lineSequence().map { it.removeSuffix("\r") }
        val normalized = (if (startsAtLineBoundary) lines else lines.drop(1)).toList()
        val withoutTrailingSentinel = if (normalized.lastOrNull() == "") normalized.dropLast(1) else normalized
        return withoutTrailingSentinel.takeLast(LOG_TAIL_MAX_LINES)
    }
}
