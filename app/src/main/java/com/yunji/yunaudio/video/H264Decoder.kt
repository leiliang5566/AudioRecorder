package com.yunji.yunaudio.video
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 解码器 - 支持后台运行版本
 * 可以在没有 Surface 时继续解码（不渲染）
 */
class H264Decoder(
    private val width: Int = 1080,
    private val height: Int = 1920,
    private var outputSurface: Surface? = null  // 改为 var，允许后续更新
) {

    private var decoder: MediaCodec? = null
    private var isRunning = false

    private var decodedFrameCount = 0

    var onFrameDecoded: ((frameCount: Int, latency: Long) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_US = 10000L
    }

    /**
     * 初始化解码器
     */
    fun initialize(): Boolean {
        return try {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                // 如果没有 Surface，使用 YUV 颜色格式
                if (outputSurface == null) {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                } else {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                }

                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE)
            }

            // 配置时可以没有 Surface
            decoder?.configure(format, outputSurface, null, 0)
            decoder?.start()

            isRunning = true
            decodedFrameCount = 0

            val surfaceStatus = if (outputSurface != null) "有 Surface" else "无 Surface（后台模式）"
            Log.d(TAG, "✅ 解码器初始化成功: ${width}x${height}, $surfaceStatus")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ 解码器初始化失败", e)
            onError?.invoke("解码器初始化失败: ${e.message}")
            false
        }
    }

    /**
     * 更新 Surface（从后台返回前台时）
     * 注意：MediaCodec 不支持动态更换 Surface，需要重新创建解码器
     */
    fun updateSurface(surface: Surface?) {
        if (surface == outputSurface) {
            Log.d(TAG, "Surface 未变化")
            return
        }

        Log.d(TAG, "🔄 Surface 变化，需要重新创建解码器")
        outputSurface = surface

        // 重新初始化
        val wasRunning = isRunning
        stop()
        if (wasRunning) {
            initialize()
        }
    }

    /**
     * 解码 H.264 数据
     */
    fun decode(data: ByteArray, timestamp: Long = System.nanoTime() / 1000, isKeyFrame: Boolean = false) {
        if (!isRunning || decoder == null) {
            Log.w(TAG, "解码器未运行")
            return
        }

        try {
            val startTime = System.currentTimeMillis()

            // 1. 提交输入数据
            val inputBufferIndex = decoder!!.dequeueInputBuffer(TIMEOUT_US)

            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder!!.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)

                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                decoder!!.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    data.size,
                    timestamp,
                    flags
                )

                if (decodedFrameCount < 5 || decodedFrameCount % 100 == 0) {
                    Log.v(TAG, "📥 提交解码数据: ${data.size} bytes, keyFrame=$isKeyFrame")
                }
            } else {
                Log.w(TAG, "无可用输入缓冲区: $inputBufferIndex")
            }

            // 2. 获取输出数据
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            while (outputBufferIndex >= 0) {
                // 如果有 Surface，渲染到 Surface
                // 如果没有 Surface，只是释放缓冲区（不渲染，但解码完成）
                val render = outputSurface != null
                decoder!!.releaseOutputBuffer(outputBufferIndex, render)

                decodedFrameCount++

                val latency = System.currentTimeMillis() - startTime
                onFrameDecoded?.invoke(decodedFrameCount, latency)

                if (decodedFrameCount % 30 == 0) {
                    val mode = if (outputSurface != null) "渲染" else "后台"
                    Log.d(TAG, "✅ 已解码: $decodedFrameCount 帧 ($mode), 延迟: ${latency}ms")
                }

                outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 0)
            }

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder!!.outputFormat
                    Log.d(TAG, "📐 输出格式变化: $newFormat")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 正常，没有输出
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 解码错误", e)
            onError?.invoke("解码错误: ${e.message}")
        }
    }

    fun getStats(): DecoderStats {
        return DecoderStats(
            decodedFrames = decodedFrameCount,
            isRunning = isRunning,
            hasSurface = outputSurface != null
        )
    }

    fun stop() {
        try {
            isRunning = false
            decoder?.stop()
            Log.d(TAG, "⏹️ 解码器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止解码器失败", e)
        }
    }

    fun release() {
        try {
            stop()
            decoder?.release()
            decoder = null
            Log.d(TAG, "🗑️ 解码器已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放解码器失败", e)
        }
    }

    fun flush() {
        try {
            decoder?.flush()
            Log.d(TAG, "🔄 解码器已刷新")
        } catch (e: Exception) {
            Log.e(TAG, "刷新解码器失败", e)
        }
    }

    data class DecoderStats(
        val decodedFrames: Int,
        val isRunning: Boolean,
        val hasSurface: Boolean
    )
}
