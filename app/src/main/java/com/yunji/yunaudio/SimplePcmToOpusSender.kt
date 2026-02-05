package com.yunji.yunaudio

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * PCM 转 Opus 发送器 (简化版 - 无协程依赖)
 *
 * 依赖项:
 * - org.concentus:Concentus:1.17
 * - org.java-websocket:Java-WebSocket:1.5.3
 * - com.google.code.gson:gson:2.10.1
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

// ==================== Opus 编码器 ====================

class SimpleOpusEncoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitrate: Int = 64000
) {
    private val opusEncoder: OpusEncoder
    private val frameSizeInSamples: Int
    var timestampMicroseconds: Long = 0

    var onOutput: ((ByteArray, Long) -> Unit)? = null

    init {
        opusEncoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
        opusEncoder.bitrate = bitrate
        frameSizeInSamples = (sampleRate * 20 / 1000) // 20ms

        println("Opus 编码器初始化: ${sampleRate}Hz, ${channels}ch, ${bitrate}bps")
        println("每帧样本数: $frameSizeInSamples")
    }

    fun encode(pcmData: ShortArray) {
        val opusOutput = ByteArray(4096)
        val encodedBytes = opusEncoder.encode(
            pcmData, 0, frameSizeInSamples,
            opusOutput, 0, opusOutput.size
        )

        if (encodedBytes >= 10) {
            onOutput?.invoke(opusOutput.copyOf(encodedBytes), timestampMicroseconds)
        }

        timestampMicroseconds += 20000 // 20ms
    }

    fun getFrameSize(): Int = frameSizeInSamples
}

// ==================== WebSocket 客户端 ====================

class SimpleAudioWebSocket(
    serverUri: String,
    private val onHandshake: (HandshakeConfig) -> Unit
) : WebSocketClient(URI(serverUri)) {

    private val gson = Gson()

    override fun onOpen(handshakedata: ServerHandshake?) {
        println("WebSocket 连接成功")
    }

    override fun onMessage(message: String?) {
        message?.let {
            try {
                val wsMessage = gson.fromJson(it, WebSocketMessage::class.java)
                if (wsMessage.action == "handshake") {
                    wsMessage.data?.let { data ->
                        val config = gson.fromJson(gson.toJson(data), HandshakeConfig::class.java)
                        println("握手成功: Rate=${config.rate}, Ch=${config.channels}, Codec=${config.codec}")
                        onHandshake(config)
                    }
                }
            } catch (e: Exception) {
                println("解析消息失败: ${e.message}")
            }
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        println("WebSocket 断开: $reason")
    }

    override fun onError(ex: Exception?) {
        println("WebSocket 错误: ${ex?.message}")
    }

    fun sendBinary(data: ByteArray) {
        if (isOpen) send(data)
    }
}

// ==================== PCM 文件读取器 ====================

class SimplePcmReader(file: File) {
    private val pcmData: ShortArray
    val totalFrames: Int

    init {
        val fileBytes = file.readBytes()
        val byteBuffer = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)

        pcmData = ShortArray(fileBytes.size / 2)
        for (i in pcmData.indices) {
            pcmData[i] = byteBuffer.short
        }

        val samplesPerFrame = 960 // 20ms @ 48kHz
        totalFrames = (pcmData.size + samplesPerFrame - 1) / samplesPerFrame

        println("文件加载: ${file.name}, ${fileBytes.size / 1024.0} KB")
        println("样本数: ${pcmData.size}, 帧数: $totalFrames")
    }

    fun readFrame(frameIndex: Int, frameSize: Int): ShortArray? {
        val offset = frameIndex * frameSize
        if (offset >= pcmData.size) return null

        val size = minOf(frameSize, pcmData.size - offset)
        return pcmData.copyOfRange(offset, offset + size)
    }
}

// ==================== 主发送器 ====================

class SimplePcmSender(
    wsUrl: String,
    pcmFile: File
) {
    private val webSocket: SimpleAudioWebSocket
    private val reader: SimplePcmReader
    private var encoder: SimpleOpusEncoder? = null
    private var scheduler: ScheduledExecutorService? = null
    private var currentFrame = 0
    private var sentBytes = 0L
    private var isRunning = false

    init {
        reader = SimplePcmReader(pcmFile)

        webSocket = SimpleAudioWebSocket(wsUrl) { config ->
            if (config.codec == "opus") {
                println("准备就绪，可调用 start()")
            }
        }
    }

    fun connect() {
        println("连接 WebSocket...")
        webSocket.connect()
    }

    fun start() {
        if (isRunning) return

        encoder = SimpleOpusEncoder(48000, 1, 64000)
        encoder?.onOutput = { data, _ ->
            webSocket.sendBinary(data)
            sentBytes += data.size

            if (currentFrame % 100 == 0) {
                val progress = (currentFrame * 100.0 / reader.totalFrames).toInt()
                println("进度: $progress%, 已发送: ${sentBytes / 1024} KB")
            }
        }

        isRunning = true
        currentFrame = 0
        sentBytes = 0

        println("开始发送...")

        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            if (!isRunning || currentFrame >= reader.totalFrames) {
                stop()
                return@scheduleAtFixedRate
            }

            if (!webSocket.isOpen) {
                println("WebSocket 已断开")
                stop()
                return@scheduleAtFixedRate
            }

            val frameData = reader.readFrame(currentFrame, encoder!!.getFrameSize())
            if (frameData != null) {
                encoder?.encode(frameData)
                currentFrame++
            } else {
                stop()
            }
        }, 0, 20, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!isRunning) return

        isRunning = false
        scheduler?.shutdown()
        scheduler = null

        println("发送完成: $currentFrame 帧, ${sentBytes / 1024} KB")
    }

    fun disconnect() {
        stop()
        webSocket.close()
    }
}

// ==================== 主函数 ====================

/*fun main() {
    val wsUrl = "ws://127.0.0.1:38080/audio"
    val pcmFile = File("/origin.pcm")
    
    if (!pcmFile.exists()) {
        println("错误: 找不到文件 ${pcmFile.absolutePath}")
        return
    }
    
    val sender = SimplePcmSender(wsUrl, pcmFile)
    
    sender.connect()
    Thread.sleep(2000)  // 等待握手
    
    sender.start()
    
    println("按 Enter 键停止...")
    readLine()
    
    sender.disconnect()
    println("程序结束")
}*/
