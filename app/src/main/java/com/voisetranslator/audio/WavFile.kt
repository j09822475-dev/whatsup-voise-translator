package com.voisetranslator.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where the PCM payload of a WAV file lives, and how to interpret it. */
data class WavInfo(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond <= 0) 0 else dataSize * 1000 / bytesPerSecond
        }
}

/**
 * Minimal RIFF/WAVE header reader.
 *
 * `TextToSpeech.synthesizeToFile` writes a canonical 16-bit PCM WAV, but some engines slip an
 * extra `LIST` chunk in before `data`, so the chunks are walked rather than assumed.
 */
object WavFile {

    fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        val riff = ByteArray(12)
        raf.readFully(riff)
        require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF") { "Это не WAV-файл" }
        require(String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") { "Это не WAV-файл" }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        while (raf.filePointer + 8 <= raf.length()) {
            val header = ByteArray(8)
            raf.readFully(header)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val bodyStart = raf.filePointer

            when (id) {
                "fmt " -> {
                    if (size < 16L) throw IOException("Повреждённый заголовок WAV")
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = buffer.short.toInt()
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int // byte rate, derivable
                    buffer.short // block align, derivable
                    bitsPerSample = buffer.short.toInt()
                    if (audioFormat != PCM_FORMAT) {
                        throw IOException("Синтезатор вернул WAV в неподдерживаемом формате ($audioFormat)")
                    }
                }

                "data" -> {
                    // The declared size can overrun the file when the header was written first.
                    val available = raf.length() - bodyStart
                    return@use WavInfo(
                        channels = channels,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        dataOffset = bodyStart,
                        dataSize = minOf(size, available),
                    ).also { require(it.channels > 0 && it.sampleRate > 0) { "Повреждённый заголовок WAV" } }
                }
            }

            // Chunks are word-aligned: an odd size is followed by a pad byte.
            raf.seek(bodyStart + size + (size and 1L))
        }

        throw IOException("В WAV-файле нет звуковых данных")
    }

    private const val PCM_FORMAT = 1
}
