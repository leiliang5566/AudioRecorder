package com.yunji.yunaudio.video
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebSocket 客户端
 * 用于发送 H.264 数据到服务器
 */
class WebSocketClient(
    private val serverUrl: String
) {
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var isConnected = false
    
    // 统计信息
    private var packetCount = 0L
    private var totalBytesSent = 0L
    private var connectionStartTime = 0L
    
    // 回调接口
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null
    var onMessageReceived: ((data: ByteArray) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null
    var onPacketSent: ((packetCount: Long, totalBytes: Long) -> Unit)? = null
    
    companion object {
        private const val TAG = "WebSocketClient"
        private const val PING_INTERVAL = 30L // 秒
        private const val CONNECT_TIMEOUT = 10L // 秒
        private const val READ_TIMEOUT = 0L // 无限制（实时流）
        private const val WRITE_TIMEOUT = 10L // 秒
    }

    object OkHttpClientBuilder {
        fun createUnsafeClient(): OkHttpClient {
            return try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                val sslSocketFactory = sslContext.socketFactory

                val hostnameVerifier = HostnameVerifier { _, _ -> true }

                OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                    .hostnameVerifier(hostnameVerifier)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()

            } catch (e: Exception) {
                throw RuntimeException("Failed to create unsafe OkHttpClient", e)
            }
        }
    }
    
    /**
     * 连接到 WebSocket 服务器
     */
    fun connect() {
        if (isConnected) {
            Log.w(TAG, "已经连接，无需重复连接")
            return
        }
        
        try {
            // 创建 OkHttpClient
            okHttpClient = OkHttpClientBuilder.createUnsafeClient()
            
            // 创建 WebSocket 请求
            val request = Request.Builder()
                .url(serverUrl)
                .build()
            
            // 连接 WebSocket
            webSocket = okHttpClient!!.newWebSocket(request, object : WebSocketListener() {
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    connectionStartTime = System.currentTimeMillis()
                    packetCount = 0
                    totalBytesSent = 0
                    
                    Log.d(TAG, "🟢 WebSocket 已连接: $serverUrl")
                    onConnected?.invoke()
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "📥 收到文本消息: $text")
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "📥 收到二进制消息: ${bytes.size} bytes")
                    onMessageReceived?.invoke(bytes.toByteArray())
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🟡 WebSocket 正在关闭: $code - $reason")
                    webSocket.close(1000, null)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    Log.d(TAG, "🔴 WebSocket 已关闭: $code - $reason")
                    onDisconnected?.invoke(reason)
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    val errorMsg = "WebSocket 连接失败: ${t.message}"
                    Log.e(TAG, "❌ $errorMsg", t)
                    onError?.invoke(errorMsg)
                    onDisconnected?.invoke(errorMsg)
                }
            })
            
            Log.d(TAG, "🟡 正在连接 WebSocket: $serverUrl")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebSocket 连接异常", e)
            onError?.invoke("连接异常: ${e.message}")
        }
    }
    
    /**
     * 断开 WebSocket 连接
     */
    fun disconnect() {
        try {
            webSocket?.close(1000, "客户端主动断开")
            webSocket = null
            isConnected = false
            
            okHttpClient?.dispatcher?.executorService?.shutdown()
            okHttpClient = null
            
            Log.d(TAG, "🔌 WebSocket 已断开")
            
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败", e)
        }
    }
    
    /**
     * 发送二进制数据（H.264 数据）
     * @param data 要发送的数据
     * @return 是否发送成功
     */
    fun sendBinary(data: ByteArray): Boolean {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "WebSocket 未连接，无法发送数据")
            return false
        }
        
        return try {
            val byteString = data.toByteString()
            val success = webSocket!!.send(byteString)
            
            if (success) {
                packetCount++
                totalBytesSent += data.size
                
                onPacketSent?.invoke(packetCount, totalBytesSent)
                
                if (packetCount % 100 == 0L) {
                    Log.d(TAG, "📤 已发送: $packetCount 包, ${formatBytes(totalBytesSent)}")
                }
            } else {
                Log.w(TAG, "发送数据失败（队列已满或连接关闭）")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送数据异常", e)
            onError?.invoke("发送失败: ${e.message}")
            false
        }
    }
    
    /**
     * 发送文本消息
     */
    fun sendText(text: String): Boolean {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "WebSocket 未连接，无法发送文本")
            return false
        }
        
        return try {
            webSocket!!.send(text)
        } catch (e: Exception) {
            Log.e(TAG, "发送文本失败", e)
            false
        }
    }
    
    /**
     * 批量发送数据包
     */
    fun sendBatch(dataPackets: List<ByteArray>): Int {
        var successCount = 0
        
        dataPackets.forEach { data ->
            if (sendBinary(data)) {
                successCount++
            }
        }
        
        Log.d(TAG, "📦 批量发送: ${successCount}/${dataPackets.size} 成功")
        return successCount
    }
    
    /**
     * 获取连接统计信息
     */
    fun getStats(): WebSocketStats {
        val connectionTime = if (isConnected) {
            System.currentTimeMillis() - connectionStartTime
        } else {
            0L
        }
        
        return WebSocketStats(
            isConnected = isConnected,
            packetCount = packetCount,
            totalBytesSent = totalBytesSent,
            connectionTimeMs = connectionTime
        )
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * 格式化字节数
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes == 0L) return "0 B"
        val k = 1024
        val sizes = arrayOf("B", "KB", "MB", "GB")
        val i = (Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
        return String.format("%.2f %s", bytes / Math.pow(k.toDouble(), i.toDouble()), sizes[i])
    }
    
    /**
     * WebSocket 统计信息
     */
    data class WebSocketStats(
        val isConnected: Boolean,
        val packetCount: Long,
        val totalBytesSent: Long,
        val connectionTimeMs: Long
    ) {
        fun getConnectionTimeFormatted(): String {
            val seconds = connectionTimeMs / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            
            return when {
                hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
                else -> String.format("%02d:%02d", minutes, seconds % 60)
            }
        }
    }
}
