package org.cryptgregresearch.dontree.agent

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import java.nio.ByteOrder

data class PcmChunk(val startMs: Long, val samples: FloatArray)

/** Decodes in bounded 30-second chunks. Playback is never blocked by inference. */
class AudioDecoder(private val context: Context) {
    fun chunks(uri: String) = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("No supported audio track in this file")
            extractor.selectTrack(track)
            var format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false; var outputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var phase = 0L; var sum = 0.0; var averaged = 0
            var pcm = FloatArray(16000 * 30); var used = 0; var emitted = 0L
            var lastOutput = System.nanoTime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "Audio decoder stalled" }
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)!!
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) { decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoder PCM format" }
                        floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        check(sampleRate >= 16000 && channels in 1..8) { "Analysis requires audio sampled at 16 kHz or above" }
                    }
                    else -> if (outputIndex >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(outputIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val frameBytes = channels * if (floatPcm) 4 else 2
                            while (buffer.remaining() >= frameBytes) {
                                var mono = 0.0
                                repeat(channels) { mono += if (floatPcm) buffer.float.toDouble() else buffer.short.toDouble() / 32768 }
                                sum += mono / channels; averaged++; phase += 16000
                                if (phase >= sampleRate) {
                                    pcm[used++] = (sum / averaged).toFloat().coerceIn(-1f, 1f)
                                    phase -= sampleRate; sum = 0.0; averaged = 0
                                    if (used == pcm.size) {
                                        emit(PcmChunk(emitted * 1000 / 16000, pcm)); emitted += used
                                        pcm = FloatArray(16000 * 30); used = 0
                                    }
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastOutput = System.nanoTime()
                        } finally { decoder.releaseOutputBuffer(outputIndex, false) }
                    }
                }
            }
            if (used > 0) emit(PcmChunk(emitted * 1000 / 16000, pcm.copyOf(used)))
        } finally { codec?.release(); extractor.release() }
    }.flowOn(Dispatchers.IO).buffer(0)
}
