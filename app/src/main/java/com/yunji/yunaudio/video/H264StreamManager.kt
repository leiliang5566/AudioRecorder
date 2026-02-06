package com.yunji.yunaudio.video
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * H.264 流处理管理器
 * 整合解码器和 WebSocket 客户端
 */
class H264StreamManager(
    private val serverUrl: String,
    private val width: Int = 1080,
    private val height: Int = 1920,
    private val outputSurface: Surface? = null
) {
    private val decoder = H264Decoder(width, height, outputSurface)
    private val webSocketClient = WebSocketClient(serverUrl)
    
    // 数据缓冲队列（用于解码）
    private val decodeQueue = ConcurrentLinkedQueue<ByteArray>()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var decodeJob: Job? = null
    
    // 状态
    private var isRunning = false
    
    // 统计信息
    private var totalLatency = 0L
    private var latencyCount = 0
    
    // 回调接口
    var onStatusChanged: ((status: StreamStatus) -> Unit)? = null
    var onStatsUpdated: ((stats: StreamStats) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null
    
    companion object {
        private const val TAG = "H264StreamManager"
        private const val DECODE_INTERVAL_MS = 5L // 解码间隔
    }
    
    /**
     * 初始化
     */
    fun initialize(): Boolean {
        Log.d(TAG, "🚀 初始化流处理管理器")
        
        // 初始化解码器
        if (!decoder.initialize()) {
            onError?.invoke("解码器初始化失败")
            return false
        }
        
        // 设置解码器回调
        decoder.onFrameDecoded = { frameCount, latency ->
            totalLatency += latency
            latencyCount++
            updateStats()
        }
        
        decoder.onError = { error ->
            onError?.invoke("解码错误: $error")
        }
        
        // 设置 WebSocket 回调
        webSocketClient.onConnected = {
            Log.d(TAG, "✅ WebSocket 已连接")
            onStatusChanged?.invoke(StreamStatus.CONNECTED)
        }
        
        webSocketClient.onDisconnected = { reason ->
            Log.d(TAG, "❌ WebSocket 已断开: $reason")
            onStatusChanged?.invoke(StreamStatus.DISCONNECTED)
        }
        
        webSocketClient.onMessageReceived = { data ->
            // 收到服务器回传的数据，加入解码队列
            addToDecodeQueue(data)
        }
        
        webSocketClient.onError = { error ->
            onError?.invoke("WebSocket 错误: $error")
        }
        
        webSocketClient.onPacketSent = { packetCount, totalBytes ->
            updateStats()
        }
        
        Log.d(TAG, "✅ 管理器初始化成功")
        return true
    }
    
    /**
     * 连接到服务器
     */
    fun connect() {
        webSocketClient.connect()
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        webSocketClient.disconnect()
    }
    
    /**
     * 启动解码处理循环
     */
    fun startDecoding() {
        if (isRunning) {
            Log.w(TAG, "解码已经在运行")
            return
        }
        
        isRunning = true
        
        decodeJob = scope.launch {
            Log.d(TAG, "🎬 启动解码循环")
            
            while (isRunning) {
                // 从队列中取出数据解码
                val data = decodeQueue.poll()
                
                if (data != null) {
                    decoder.decode(data, System.nanoTime() / 1000, false)
                } else {
                    // 队列为空，短暂延迟
                    delay(DECODE_INTERVAL_MS)
                }
            }
            
            Log.d(TAG, "⏹️ 解码循环已停止")
        }
    }
    
    /**
     * 停止解码处理
     */
    fun stopDecoding() {
        isRunning = false
        decodeJob?.cancel()
        decodeJob = null
        decodeQueue.clear()
        
        Log.d(TAG, "⏹️ 解码已停止")
    }
    
    /**
     * 发送 H.264 数据到服务器
     * @param data H.264 裸流数据
     * @return 是否发送成功
     */
    fun sendH264Data(data: ByteArray): Boolean {
        return webSocketClient.sendBinary(data)
    }
    
    /**
     * 批量发送 H.264 数据
     */
    fun sendH264Batch(dataPackets: List<ByteArray>): Int {
        return webSocketClient.sendBatch(dataPackets)
    }
    
    /**
     * 添加数据到解码队列
     */
    fun addToDecodeQueue(data: ByteArray) {
        decodeQueue.offer(data)
        
        if (decodeQueue.size % 100 == 0) {
            Log.d(TAG, "📦 解码队列大小: ${decodeQueue.size}")
        }
    }
    
    /**
     * 直接解码数据（不通过队列）
     */
    fun decodeImmediately(data: ByteArray, isKeyFrame: Boolean = false) {
        decoder.decode(data, System.nanoTime() / 1000, isKeyFrame)
    }
    
    /**
     * 更新统计信息
     */
    private fun updateStats() {
        val decoderStats = decoder.getStats()
        val wsStats = webSocketClient.getStats()
        
        val avgLatency = if (latencyCount > 0) {
            totalLatency / latencyCount
        } else {
            0L
        }
        
        val stats = StreamStats(
            decodedFrames = decoderStats.decodedFrames,
            sentPackets = wsStats.packetCount,
            totalBytesSent = wsStats.totalBytesSent,
            averageLatencyMs = avgLatency,
            queueSize = decodeQueue.size,
            connectionTime = wsStats.getConnectionTimeFormatted(),
            isConnected = wsStats.isConnected
        )
        
        onStatsUpdated?.invoke(stats)
    }
    
    /**
     * 获取当前统计信息
     */
    fun getStats(): StreamStats {
        val decoderStats = decoder.getStats()
        val wsStats = webSocketClient.getStats()
        
        val avgLatency = if (latencyCount > 0) {
            totalLatency / latencyCount
        } else {
            0L
        }
        
        return StreamStats(
            decodedFrames = decoderStats.decodedFrames,
            sentPackets = wsStats.packetCount,
            totalBytesSent = wsStats.totalBytesSent,
            averageLatencyMs = avgLatency,
            queueSize = decodeQueue.size,
            connectionTime = wsStats.getConnectionTimeFormatted(),
            isConnected = wsStats.isConnected
        )
    }
    
    /**
     * 刷新解码器缓冲区
     */
    fun flush() {
        decoder.flush()
        decodeQueue.clear()
        Log.d(TAG, "🔄 缓冲区已刷新")
    }
    
    /**
     * 释放所有资源
     */
    fun release() {
        Log.d(TAG, "🗑️ 释放资源")
        
        stopDecoding()
        disconnect()
        
        decoder.release()
        
        scope.cancel()
        
        decodeQueue.clear()
        
        Log.d(TAG, "✅ 资源已释放")
    }
    
    /**
     * 流状态
     */
    enum class StreamStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    /**
     * 流统计信息
     */
    data class StreamStats(
        val decodedFrames: Int,
        val sentPackets: Long,
        val totalBytesSent: Long,
        val averageLatencyMs: Long,
        val queueSize: Int,
        val connectionTime: String,
        val isConnected: Boolean
    ) {
        fun formatBytesSent(): String {
            if (totalBytesSent == 0L) return "0 B"
            val k = 1024
            val sizes = arrayOf("B", "KB", "MB", "GB")
            val i = (Math.log(totalBytesSent.toDouble()) / Math.log(k.toDouble())).toInt()
            return String.format("%.2f %s", totalBytesSent / Math.pow(k.toDouble(), i.toDouble()), sizes[i])
        }
    }
}
