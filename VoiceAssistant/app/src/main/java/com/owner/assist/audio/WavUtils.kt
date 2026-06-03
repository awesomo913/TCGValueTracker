package com.owner.assist.audio

import java.io.OutputStream

/** Writes a standard 44-byte PCM WAV header. Call before writing raw PCM bytes. */
fun writeWavHeader(
    out: OutputStream,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int,
    dataBytes: Int,
) {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    fun i32(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte(),
    )
    fun i16(n: Int) = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())

    out.write("RIFF".toByteArray())
    out.write(i32(36 + dataBytes))
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    out.write(i32(16))
    out.write(i16(1))          // PCM = 1
    out.write(i16(channels))
    out.write(i32(sampleRate))
    out.write(i32(byteRate))
    out.write(i16(blockAlign))
    out.write(i16(bitsPerSample))
    out.write("data".toByteArray())
    out.write(i32(dataBytes))
}
