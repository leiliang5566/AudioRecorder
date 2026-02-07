package com.yunji.yunaudio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class OpusMediaCodecDecoder(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2
) {
    private var decoder: MediaCodec? = null
    private var isInitialized = false
    private var presentationTimeUs = 0L

    // 输出数据队列
    private val outputQueue = ConcurrentLinkedQueue<ByteArray>()

    // 解码线程控制
    @Volatile
    private var isRunning = false
    private var decoderThread: Thread? = null

    companion object {
        private const val TAG = "OpusMediaCodecDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val TIMEOUT_US = 10000L
    }

    /**
     * 初始化解码器
     */
    fun initialize(): Boolean {
        return try {
            val bitRate = 102000
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

            val csd0bytes = byteArrayOf(
                0x4f, 0x70, 0x75, 0x73,  // "Opus"
                0x48, 0x65, 0x61, 0x64,  // "Head"
                0x01,  // Version
                0x02,  // Channel Count
                0x02.toByte(), 0x00,  // Pre skip (修正为 0x02, 0x00)
                0x80.toByte(), 0xbb.toByte(), 0x00, 0x00,  // Input Sample Rate
                0x00, 0x00,  // Output Gain
                0x00  // Mapping Family
            )
            val csd1bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val csd2bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0bytes))
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1bytes))
            mediaFormat.setByteBuffer("csd-2", ByteBuffer.wrap(csd2bytes))

            decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder?.configure(mediaFormat, null, null, 0)
            decoder?.start()

            isInitialized = true
            presentationTimeUs = 0L

            // 启动输出处理线程
            startOutputThread()

            Log.d(TAG, "Decoder initialized: $sampleRate Hz, $channelCount channels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize decoder", e)
            false
        }
    }

    /**
     * 启动输出处理线程
     */
    private fun startOutputThread() {
        isRunning = true
        decoderThread = Thread {
            while (isRunning) {
                try {
                    processOutput()
                    Thread.sleep(5) // 避免CPU占用过高
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in output thread", e)
                }
            }
        }.apply {
            name = "OpusDecoderOutputThread"
            start()
        }
    }

    /**
     * 处理输出数据
     */
    private fun processOutput() {
        val codec = decoder ?: return

        try {
            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

            when {
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (bufferInfo.size > 0) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer?.position(bufferInfo.offset)
                        outputBuffer?.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer?.get(pcmData)

                        // 放入输出队列
                        outputQueue.offer(pcmData)
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Output format: ${codec.outputFormat}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing output", e)
        }
    }

    /**
     * 解码单个 Opus 数据包（异步）
     * @param opusData 编码的 Opus 数据
     * @return 立即返回 true/false，实际解码数据通过 getDecodedData() 获取
     */
    fun decode(opusData: ByteArray): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "Decoder not initialized")
            return false
        }

        val codec = decoder ?: return false

        return try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(opusData)

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    opusData.size,
                    presentationTimeUs,
                    0
                )

                presentationTimeUs += 20000
                true
            } else {
                Log.w(TAG, "No input buffer available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during decode", e)
            false
        }
    }

    /**
     * 获取解码后的 PCM 数据
     * @return 解码后的数据，如果队列为空返回 null
     */
    fun getDecodedData(): ByteArray? {
        return outputQueue.poll()
    }

    /**
     * 获取所有可用的解码数据并合并
     */
    fun getAllDecodedData(): ByteArray? {
        val dataList = mutableListOf<ByteArray>()

        while (true) {
            val data = outputQueue.poll() ?: break
            dataList.add(data)
        }

        if (dataList.isEmpty()) return null

        val totalSize = dataList.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0

        for (data in dataList) {
            System.arraycopy(data, 0, result, offset, data.size)
            offset += data.size
        }

        return result
    }

    /**
     * 刷新解码器
     */
    fun flush() {
        try {
            decoder?.flush()
            outputQueue.clear()
            presentationTimeUs = 0L
            Log.d(TAG, "Decoder flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing decoder", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            isRunning = false
            decoderThread?.interrupt()
            decoderThread?.join(1000)

            decoder?.stop()
            decoder?.release()
            decoder = null
            outputQueue.clear()
            isInitialized = false

            Log.d(TAG, "Decoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }
    }
}