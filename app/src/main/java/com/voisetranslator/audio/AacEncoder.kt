package com.voisetranslator.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class AudioEncodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Re-packs the WAV produced by the TTS engine into an AAC/MP4 (`.m4a`) file.
 *
 * WhatsApp shows an `.m4a` as an inline audio attachment with a player; a raw `.wav` is
 * usually offered as a plain document instead, so the conversion is what makes the message
 * playable without leaving the chat. Uses only platform codecs — no ffmpeg, no extra deps.
 */
object AacEncoder {

    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L
    private const val PCM_CHUNK_BYTES = 8192

    suspend fun wavToM4a(source: File, target: File): File = withContext(Dispatchers.IO) {
        val info = try {
            WavFile.read(source)
        } catch (e: Exception) {
            throw AudioEncodingException(e.message ?: "Не удалось прочитать WAV", e)
        }

        if (info.bitsPerSample != 16) {
            throw AudioEncodingException("Ожидался 16-битный WAV, получено ${info.bitsPerSample} бит")
        }

        target.parentFile?.mkdirs()
        target.delete()

        val format = MediaFormat.createAudioFormat(MIME, info.sampleRate, info.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (info.channels > 1) 96_000 else 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES * 2)
        }

        val codec = try {
            MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        } catch (e: Exception) {
            throw AudioEncodingException("Кодировщик AAC недоступен на этом устройстве", e)
        }

        val muxer = try {
            MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            codec.release()
            throw AudioEncodingException("Не удалось создать файл ${target.name}", e)
        }

        try {
            encode(codec, muxer, source, info)
        } catch (e: AudioEncodingException) {
            throw e
        } catch (e: Exception) {
            throw AudioEncodingException("Не удалось перекодировать звук: ${e.message}", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }

        if (!target.exists() || target.length() == 0L) {
            throw AudioEncodingException("Перекодирование дало пустой файл")
        }
        target
    }

    private fun encode(codec: MediaCodec, muxer: MediaMuxer, source: File, info: WavInfo) {
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = info.channels * 2
        val pcm = ByteArray(PCM_CHUNK_BYTES)

        var trackIndex = -1
        var muxerStarted = false
        var bytesRead = 0L
        var inputDone = false
        var outputDone = false

        RandomAccessFile(source, "r").use { raf ->
            raf.seek(info.dataOffset)

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val remaining = info.dataSize - bytesRead
                        val wanted = minOf(remaining, PCM_CHUNK_BYTES.toLong()).toInt()
                        val read = if (wanted > 0) raf.read(pcm, 0, wanted) else -1

                        // Presentation time is derived from the byte offset, so it stays exact
                        // even when the last chunk is short.
                        val presentationTimeUs =
                            bytesRead * 1_000_000L / (info.sampleRate.toLong() * bytesPerFrame)

                        if (read <= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val input = codec.getInputBuffer(inputIndex)
                                ?: throw AudioEncodingException("Кодировщик не выдал входной буфер")
                            input.clear()
                            input.put(pcm, 0, read)
                            codec.queueInputBuffer(inputIndex, 0, read, presentationTimeUs, 0)
                            bytesRead += read
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw AudioEncodingException("Формат кодировщика изменился дважды")
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    else -> {
                        if (outputIndex < 0) continue

                        val output = codec.getOutputBuffer(outputIndex)
                            ?: throw AudioEncodingException("Кодировщик не выдал выходной буфер")

                        // The codec-config buffer carries the AAC header; the muxer writes its
                        // own from the track format, so it must not be muxed as a sample.
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0) {
                            if (!muxerStarted) throw AudioEncodingException("Кодировщик выдал данные до начала записи")
                            writeSample(muxer, trackIndex, output, bufferInfo)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        if (muxerStarted) runCatching { muxer.stop() }
    }

    private fun writeSample(
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }
}
