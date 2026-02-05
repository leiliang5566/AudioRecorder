package com.yunji.yunaudio

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * PCM 转 Opus 并通过 WebSocket 发送
 */

// ==================== 数据类 ====================

data class HandshakeConfig(
    val rate: Int,
    val channels: Int,
    val codec: String,
    val bitrate: Int? = null,
    @SerializedName("codec_ext")
    val codecExt: String? = null
)

data class WebSocketMessage(
    val action: String,
    val data: Any? = null
)

data class PcmFileConfig(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bitDepth: Int = 16
)

// ==================== PCM 转 Opus 编码器 ====================

class PcmToOpusEncoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitrate: Int = 64000,
    private val frameDurationMs: Int = 20
) {
    private val opusEncoder: OpusEncoder
    private val frameSizeInSamples: Int
    private val frameSizeInBytes: Int
    private var timestampMicroseconds: Long = 0

    var onOutput: ((ByteArray, Long) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    init {
        opusEncoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
        opusEncoder.bitrate = bitrate
        frameSizeInSamples = (sampleRate * frameDurationMs / 1000)
        frameSizeInBytes = frameSizeInSamples * channels * 2

        println("Opus 编码器初始化: ${sampleRate}Hz, ${channels}ch")
    }

    fun encode(pcmData: ShortArray) {
        try {
            val opusOutput = ByteArray(4096)
            val encodedBytes = opusEncoder.encode(
                pcmData, 0, frameSizeInSamples,
                opusOutput, 0, opusOutput.size
            )

            if (encodedBytes >= 10) {
                onOutput?.invoke(opusOutput.copyOf(encodedBytes), timestampMicroseconds)
            }
            timestampMicroseconds += (frameDurationMs * 1000).toLong()
        } catch (e: Exception) {
            onError?.invoke(e)
        }
    }

    fun getFrameSize(): Int = frameSizeInSamples
    fun close() {}
}

// ==================== WebSocket 客户端 ====================

class AudioWebSocketClient(
    serverUri: String,
    private val onHandshake: (HandshakeConfig) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (Exception) -> Unit
) : WebSocketClient(URI(serverUri)) {

    private val gson = Gson()

    init {
        if (serverUri.startsWith("wss")) {
            setupUnsafeSsl()
        }
    }

    /**
     * 配置不安全的 SSL 以忽略域名匹配和证书校验
     */
    private fun setupUnsafeSsl() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 设置 SocketFactory
            this.setSocketFactory(sslContext.socketFactory)

            // 注意：Java-WebSocket 在某些版本中可能还需要处理 HostnameVerifier
            // 这里我们主要通过自定义 SocketFactory 处理
        } catch (e: Exception) {
            println("配置不安全 SSL 失败: ${e.message}")
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        println("WS 连接成功")
        onConnected()
    }

    override fun onMessage(message: String?) {
        message?.let {
            try {
                val wsMessage = gson.fromJson(it, WebSocketMessage::class.java)
                if (wsMessage.action == "handshake") {
                    wsMessage.data?.let { data ->
                        val config = gson.fromJson(gson.toJson(data), HandshakeConfig::class.java)
                        onHandshake(config)
                    }
                } else if (wsMessage.action == "status") {
                    onStatus(wsMessage.data.toString())
                }
            } catch (e: Exception) {
                println("解析消息失败: ${e.message}")
            }
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onDisconnected()
    }

    override fun onError(ex: Exception?) {
        ex?.let { onError(it) }
    }

    fun sendBinary(data: ByteArray) {
        if (isOpen) send(data)
    }
}

// ==================== PCM 文件读取器 ====================

class PcmFileReader(
    private val file: File,
    private val config: PcmFileConfig = PcmFileConfig()
) {
    private val pcmData: ShortArray
    val totalFrames: Int

    init {
        val fileBytes = file.readBytes()
        val byteBuffer = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcmData = ShortArray(fileBytes.size / 2)
        for (i in pcmData.indices) {
            pcmData[i] = byteBuffer.short
        }
        val samplesPerFrame = (config.sampleRate * 20 / 1000)
        totalFrames = (pcmData.size / config.channels + samplesPerFrame - 1) / samplesPerFrame
    }

    fun readFrame(frameIndex: Int, frameSizeInSamples: Int): ShortArray? {
        val offset = frameIndex * frameSizeInSamples * config.channels
        if (offset >= pcmData.size) return null
        val frameSize = minOf(frameSizeInSamples * config.channels, pcmData.size - offset)
        return pcmData.copyOfRange(offset, offset + frameSize)
    }

    fun getTotalSamples(): Int = pcmData.size
}

// ==================== 主发送器 ====================

class PcmToOpusSender(
    private val wsUrl: String,
    private val pcmFile: File
) {
    private var webSocket: AudioWebSocketClient? = null
    private var encoder: PcmToOpusEncoder? = null
    private var fileReader: PcmFileReader? = null
    private var handshakeConfig: HandshakeConfig? = null

    private var isRecording = false
    private var sentBytes = 0L
    private var frameCount = 0

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun connect() {
        webSocket = AudioWebSocketClient(
            serverUri = wsUrl,
            onHandshake = { config -> handleHandshake(config) },
            onStatus = { status -> println("状态: $status") },
            onConnected = { println("已连接，等待握手...") },
            onDisconnected = { stopSending() },
            onError = { error -> println("WS 错误: ${error.message}") }
        )
        webSocket?.connect()
    }

    private fun handleHandshake(config: HandshakeConfig) {
        handshakeConfig = config
        if (config.codec == "opus") {
            println("握手成功，准备发送")
        }
    }

    fun startSending() {
        val config = handshakeConfig ?: return
        if (config.codec != "opus") return

        encoder = PcmToOpusEncoder(48000, 1, 64000, 20)
        encoder?.onOutput = { data, timestamp -> handleEncodedOutput(data, timestamp) }
        fileReader = PcmFileReader(pcmFile)

        isRecording = true
        sentBytes = 0
        frameCount = 0

        startReadingAudio()
    }

    private fun handleEncodedOutput(data: ByteArray, timestamp: Long) {
        webSocket?.sendBinary(data)
        sentBytes += data.size
        frameCount++
        if (frameCount % 100 == 0) {
            println("已发送 $frameCount 帧, ${sentBytes / 1024} KB")
        }
    }

    private fun startReadingAudio() {
        val enc = encoder ?: return
        val reader = fileReader ?: return
        val frameSize = enc.getFrameSize()
        
        coroutineScope.launch {
            var currentFrame = 0
            while (isRecording && currentFrame < reader.totalFrames) {
                if (webSocket?.isOpen != true) break
                val frameData = reader.readFrame(currentFrame, frameSize) ?: break
                enc.encode(frameData)
                currentFrame++
                delay(20)
            }
            stopSending()
        }
    }

    fun stopSending() {
        isRecording = false
        encoder?.close()
    }

    fun disconnect() {
        stopSending()
        webSocket?.close()
        coroutineScope.cancel()
    }
}
