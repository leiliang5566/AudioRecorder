package com.yunji.yunaudio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpusMediaCodecDecoder(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val outputMono: Boolean = true,
    private val jitterBufferMs: Int = 60 // 抖动缓冲区大小（毫秒）
) {
    @Volatile
    private var decoder: MediaCodec? = null

    private val isInitialized = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 使用优先队列，按时间戳排序
    private val inputQueue = PriorityBlockingQueue<OpusFrame>(100) { a, b ->
        a.pts.compareTo(b.pts)
    }

    // 抖动缓冲区 - 先积累一定数量再开始输出
    private val jitterBuffer = mutableListOf<ByteArray>()
    private var isBuffering = true
    private val minBufferFrames = (jitterBufferMs / 20).coerceAtLeast(3) // 至少 3 帧

    // 输出队列
    private val outputQueue = ArrayDeque<ByteArray>(100)

    // 时间戳管理
    private val currentPts = AtomicLong(0)
    private val frameDurationUs = 20000L

    // Pre-skip
    private var skipSamples = 312
    private var totalSkipped = 0

    // 统计
    private var inputCount = 0
    private var outputCount = 0
    private var droppedCount = 0
    private var underrunCount = 0

    private var processingJob: Job? = null

    companion object {
        private const val TAG = "OpusMediaCodecDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_OPUS
    }

    data class OpusFrame(
        val data: ByteArray,
        val pts: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isReleased.get() || isInitialized.get()) {
            return@withContext isInitialized.get()
        }

        try {
            Log.d(TAG, "========== 初始化解码器 ==========")
            Log.d(TAG, "参数: $sampleRate Hz, $channelCount ch, Jitter Buffer: ${minBufferFrames}帧")

            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // OpusHead
            val csd0 = createOpusHeader(sampleRate, channelCount, 312)
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))

            val emptyCsd = ByteArray(8)
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(emptyCsd))
            mediaFormat.setByteBuffer("csd-2", ByteBuffer.wrap(emptyCsd))

            val newDecoder = MediaCodec.createDecoderByType(MIME_TYPE)
            Log.d(TAG, "解码器: ${newDecoder.name}")

            newDecoder.configure(mediaFormat, null, null, 0)
            newDecoder.start()

            decoder = newDecoder
            isInitialized.set(true)
            currentPts.set(0)
            totalSkipped = 0
            isBuffering = true

            jitterBuffer.clear()
            inputQueue.clear()
            outputQueue.clear()

            startProcessing()

            Log.d(TAG, "✅ 初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化失败", e)
            e.printStackTrace()
            cleanup()
            false
        }
    }

    private fun createOpusHeader(sampleRate: Int, channels: Int, preSkip: Int): ByteArray {
        return ByteBuffer.allocate(19).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(0x01.toByte())
            put(channels.toByte())
            putShort(preSkip.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0x00.toByte())
        }.array()
    }

    private fun startProcessing() {
        processingJob = processingScope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "处理协程启动")

            while (isActive && !isReleased.get() && isInitialized.get()) {
                try {
                    // 1. 处理输入（主动拉取）
                    processInputBatch()

                    // 2. 处理输出（主动拉取）
                    processOutputBatch()

                    // 3. 管理抖动缓冲区
                    manageJitterBuffer()

                    // 4. 短暂休眠
                    delay(1) // 1ms

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "处理错误", e)
                    delay(10)
                }
            }

            Log.d(TAG, "处理协程结束")
        }
    }

    /**
     * 批量处理输入
     */
    private fun processInputBatch() {
        val codec = decoder ?: return

        // 一次最多处理 5 帧输入
        var processed = 0
        while (processed < 5 && inputQueue.isNotEmpty()) {
            val frame = inputQueue.poll() ?: break

            try {
                val inputIndex = codec.dequeueInputBuffer(0) // 非阻塞
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(frame.data)

                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        frame.data.size,
                        frame.pts,
                        0
                    )

                    inputCount++
                    processed++
                } else {
                    // 没有可用缓冲区，放回队列
                    inputQueue.offer(frame)
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "送入输入失败", e)
                droppedCount++
                break
            }
        }
    }

    /**
     * 批量处理输出
     */
    private fun processOutputBatch() {
        val codec = decoder ?: return

        // 主动拉取，尽可能多地获取输出
        var retrieved = 0
        while (retrieved < 10) { // 一次最多取 10 帧
            val bufferInfo = MediaCodec.BufferInfo()
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0) // 非阻塞

            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)

                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(pcmData)

                        codec.releaseOutputBuffer(outputIndex, false)

                        // 处理数据
                        processPcmData(pcmData)

                        outputCount++
                        retrieved++
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "📋 格式: ${codec.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }
                else -> {
                    break
                }
            }
        }
    }

    /**
     * 处理 PCM 数据
     */
    private fun processPcmData(pcmData: ByteArray) {
        // 1. 处理 Pre-skip
        val afterSkip = handlePreSkip(pcmData) ?: return

        // 2. 混音
        val afterMix = if (outputMono && channelCount > 1) {
            mixToMono(afterSkip, channelCount)
        } else {
            afterSkip
        }

        // 3. 平滑处理
        val smoothed = smoothAudio(afterMix)

        // 4. 归一化
        val normalized = normalizeAudio(smoothed)

        // 5. 放入抖动缓冲区
        synchronized(jitterBuffer) {
            jitterBuffer.add(normalized)
        }
    }

    /**
     * 管理抖动缓冲区
     */
    private fun manageJitterBuffer() {
        synchronized(jitterBuffer) {
            if (isBuffering) {
                // 缓冲阶段：积累足够的帧
                if (jitterBuffer.size >= minBufferFrames) {
                    isBuffering = false
                    Log.d(TAG, "✅ 缓冲完成，开始输出 (${jitterBuffer.size} 帧)")
                }
            } else {
                // 正常输出阶段
                if (jitterBuffer.isEmpty()) {
                    // 缓冲区耗尽，重新开始缓冲
                    isBuffering = true
                    underrunCount++
                    Log.w(TAG, "⚠️ 缓冲区下溢，重新缓冲 (第 $underrunCount 次)")
                } else {
                    // 将数据从 jitterBuffer 移到 outputQueue
                    while (jitterBuffer.isNotEmpty()) {
                        outputQueue.addLast(jitterBuffer.removeAt(0))
                    }
                }
            }
        }
    }

    private fun handlePreSkip(pcmData: ByteArray): ByteArray? {
        if (totalSkipped >= skipSamples) {
            return pcmData
        }

        val samplesInFrame = pcmData.size / (2 * channelCount)
        val samplesToSkip = minOf(skipSamples - totalSkipped, samplesInFrame)
        val bytesToSkip = samplesToSkip * 2 * channelCount

        totalSkipped += samplesToSkip

        if (bytesToSkip >= pcmData.size) {
            return null
        }

        return pcmData.copyOfRange(bytesToSkip, pcmData.size)
    }

    /**
     * 音频平滑（减少抖动）
     */
    private var lastSample: Short = 0

    private fun smoothAudio(pcmData: ByteArray): ByteArray {
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        // 简单的平滑：与前一个样本加权平均
        if (samples.isNotEmpty()) {
            val alpha = 0.05f // 平滑系数（越小越平滑，但可能损失细节）

            for (i in samples.indices) {
                val current = samples[i]
                val smoothed = (lastSample * alpha + current * (1 - alpha)).toInt()
                samples[i] = smoothed.coerceIn(-32768, 32767).toShort()
                lastSample = samples[i]
            }
        }

        val result = ByteArray(samples.size * 2)
        ByteBuffer.wrap(result)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)

        return result
    }

    private fun normalizeAudio(pcmData: ByteArray): ByteArray {
        val samples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        // 找最大振幅
        var maxAmplitude = 0
        for (sample in samples) {
            val amplitude = Math.abs(sample.toInt())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }

        // 轻微增益（如果音量太小）
        if (maxAmplitude in 1..8000) {
            val gain = 8000f / maxAmplitude
            val limitedGain = gain.coerceAtMost(1.3f) // 最多增益 1.3 倍

            for (i in samples.indices) {
                val amplified = (samples[i] * limitedGain).toInt()
                samples[i] = amplified.coerceIn(-32768, 32767).toShort()
            }
        }

        val result = ByteArray(samples.size * 2)
        ByteBuffer.wrap(result)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)

        return result
    }

    private fun mixToMono(stereoData: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return stereoData

        val stereoSamples = ShortArray(stereoData.size / 2)
        ByteBuffer.wrap(stereoData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(stereoSamples)

        val monoSampleCount = stereoSamples.size / channels
        val monoSamples = ShortArray(monoSampleCount)

        for (i in 0 until monoSampleCount) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += stereoSamples[i * channels + ch]
            }
            monoSamples[i] = (sum / channels).toShort()
        }

        val monoData = ByteArray(monoSamples.size * 2)
        ByteBuffer.wrap(monoData).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(monoSamples)

        return monoData
    }

    suspend fun decode(opusData: ByteArray) {
        if (!isInitialized.get() || isReleased.get()) {
            return
        }

        val pts = currentPts.getAndAdd(frameDurationUs)
        inputQueue.offer(OpusFrame(opusData, pts))
    }

    suspend fun getDecodedData(): ByteArray? {
        return synchronized(outputQueue) {
            if (outputQueue.isNotEmpty()) {
                outputQueue.removeFirst()
            } else {
                null
            }
        }
    }

    fun getStats(): String {
        val bufferStatus = synchronized(jitterBuffer) {
            if (isBuffering) "缓冲中(${jitterBuffer.size}/$minBufferFrames)"
            else "输出中(${jitterBuffer.size})"
        }

        return "输入:$inputCount | 输出:$outputCount | 队列:${outputQueue.size} | $bufferStatus | 下溢:$underrunCount"
    }

    private fun cleanup() {
        try {
            decoder?.release()
        } catch (e: Exception) { }
        decoder = null
        isInitialized.set(false)
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        try {
            decoder?.flush()
            inputQueue.clear()
            synchronized(jitterBuffer) {
                jitterBuffer.clear()
            }
            synchronized(outputQueue) {
                outputQueue.clear()
            }
            currentPts.set(0)
            totalSkipped = 0
            isBuffering = true
            lastSample = 0
            Log.d(TAG, "已刷新")
        } catch (e: Exception) {
            Log.e(TAG, "刷新失败", e)
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        if (isReleased.get()) return@withContext

        Log.d(TAG, "========== 释放 ==========")
        Log.d(TAG, "最终统计: ${getStats()}")

        isReleased.set(true)
        isInitialized.set(false)

        processingJob?.cancel()
        processingJob?.join()

        cleanup()
        processingScope.cancel()

        Log.d(TAG, "✅ 已释放")
    }

    fun isReady(): Boolean = isInitialized.get() && !isReleased.get()
}