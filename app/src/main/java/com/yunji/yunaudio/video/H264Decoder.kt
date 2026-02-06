package com.yunji.yunaudio.video
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 解码器
 * 用于解码从 WebSocket 接收的 H.264 裸流数据
 */
class H264Decoder(
    private val width: Int = 1080,
    private val height: Int = 1920,
    private val outputSurface: Surface? = null
) {
    private var decoder: MediaCodec? = null
    private var isRunning = false
    
    // 统计信息
    private var decodedFrameCount = 0
    private var lastFrameTime = 0L
    
    // 回调接口
    var onFrameDecoded: ((frameCount: Int, latency: Long) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null
    
    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val TIMEOUT_US = 10000L // 10ms
    }
    
    /**
     * 初始化解码器
     */
    fun initialize(): Boolean {
        return try {
            // 创建解码器
            decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            
            // 配置解码器
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 实时优先级
                
                // 低延迟设置
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE)
            }
            
            // 如果提供了 Surface，用于直接渲染
            decoder?.configure(format, outputSurface, null, 0)
            decoder?.start()
            
            isRunning = true
            decodedFrameCount = 0
            
            Log.d(TAG, "✅ 解码器初始化成功: ${width}x${height}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解码器初始化失败", e)
            onError?.invoke("解码器初始化失败: ${e.message}")
            false
        }
    }
    
    /**
     * 解码 H.264 数据
     * @param data H.264 裸流数据（Annex-B 格式）
     * @param timestamp 时间戳（微秒）
     * @param isKeyFrame 是否为关键帧
     */
    fun decode(data: ByteArray, timestamp: Long = System.nanoTime() / 1000, isKeyFrame: Boolean = false) {
        if (!isRunning || decoder == null) {
            Log.w(TAG, "解码器未运行")
            return
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // 1. 获取输入缓冲区
            val inputBufferIndex = decoder!!.dequeueInputBuffer(TIMEOUT_US)
            
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder!!.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                
                // 2. 提交数据到解码器
                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                decoder!!.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    data.size,
                    timestamp,
                    flags
                )
                
                Log.v(TAG, "📥 提交解码数据: ${data.size} bytes, keyFrame=$isKeyFrame")
            } else {
                Log.w(TAG, "无可用输入缓冲区: $inputBufferIndex")
            }
            
            // 3. 获取解码后的输出
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            
            while (outputBufferIndex >= 0) {
                // 如果使用 Surface，解码器会自动渲染到 Surface
                // 只需要释放缓冲区
                decoder!!.releaseOutputBuffer(outputBufferIndex, outputSurface != null)
                
                decodedFrameCount++
                
                // 计算延迟
                val latency = System.currentTimeMillis() - startTime
                
                // 回调通知
                onFrameDecoded?.invoke(decodedFrameCount, latency)
                
                if (decodedFrameCount % 30 == 0) {
                    Log.d(TAG, "✅ 已解码帧数: $decodedFrameCount, 延迟: ${latency}ms")
                }
                
                outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 0)
            }
            
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder!!.outputFormat
                    Log.d(TAG, "📐 输出格式变化: $newFormat")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 正常情况，没有可用输出
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解码错误", e)
            onError?.invoke("解码错误: ${e.message}")
        }
    }
    
    /**
     * 批量解码数据（用于处理缓存的数据包）
     */
    fun decodeMultiple(dataPackets: List<ByteArray>) {
        dataPackets.forEachIndexed { index, data ->
            // 第一帧通常是关键帧
            val isKeyFrame = index == 0
            decode(data, System.nanoTime() / 1000, isKeyFrame)
        }
    }
    
    /**
     * 获取解码统计信息
     */
    fun getStats(): DecoderStats {
        return DecoderStats(
            decodedFrames = decodedFrameCount,
            isRunning = isRunning
        )
    }
    
    /**
     * 停止解码器
     */
    fun stop() {
        try {
            isRunning = false
            decoder?.stop()
            Log.d(TAG, "⏹️ 解码器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止解码器失败", e)
        }
    }
    
    /**
     * 释放解码器资源
     */
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
    
    /**
     * 刷新解码器（清空缓冲区）
     */
    fun flush() {
        try {
            decoder?.flush()
            Log.d(TAG, "🔄 解码器已刷新")
        } catch (e: Exception) {
            Log.e(TAG, "刷新解码器失败", e)
        }
    }
    
    /**
     * 解码器统计信息
     */
    data class DecoderStats(
        val decodedFrames: Int,
        val isRunning: Boolean
    )
}
