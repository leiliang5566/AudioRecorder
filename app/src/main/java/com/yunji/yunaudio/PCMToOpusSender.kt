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

/**
 * PCM 转 Opus 并通过 WebSocket 发送
 *
 * 使用 Concentus Opus 编码库
 * 依赖项:
 * - implementation("org.concentus:Concentus:1.17")
 * - implementation("org.java-websocket:Java-WebSocket:1.5.3")
 * - implementation("com.google.code.gson:gson:2.10.1")
 * - implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
 */

// ==================== 数据类 ====================

/**
 * WebSocket 握手配置
 */
data class HandshakeConfig(
    val rate: Int,
    val channels: Int,
    val codec: String,
    val bitrate: Int? = null,
    @SerializedName("codec_ext")
    val codecExt: String? = null
)

/**
 * WebSocket 消息
 */
data class WebSocketMessage(
    val action: String,
    val data: Any? = null
)

/**
 * PCM 文件参数
 */
data class PcmFileConfig(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bitDepth: Int = 16
)

// ==================== PCM 转 Opus 编码器 ====================

/**
 * PCM 到 Opus 编码器
 * 完全模拟浏览器端的 AudioEncoder 逻辑
 */
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

    // 输出回调
    var onOutput: ((ByteArray, Long) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    init {
        try {
            // 创建 Opus 编码器
            opusEncoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
            opusEncoder.bitrate = bitrate

            // 计算每帧的样本数
            // 20ms @ 48kHz = 960 samples
            frameSizeInSamples = (sampleRate * frameDurationMs / 1000)
            frameSizeInBytes = frameSizeInSamples * channels * 2 // 16-bit = 2 bytes per sample

            println("Opus 编码器已初始化")
            println("配置: ${sampleRate}Hz, ${channels}ch, ${bitrate}bps")
            println("每帧: ${frameSizeInSamples} samples, ${frameSizeInBytes} bytes")

        } catch (e: Exception) {
            println("初始化 Opus 编码器失败: ${e.message}")
            throw e
        }
    }

    /**
     * 编码 PCM 数据
     * @param pcmData Int16 格式的 PCM 数据
     */
    fun encode(pcmData: ShortArray) {
        try {
            // Opus 编码输出缓冲区（最大 4KB 应该足够）
            val opusOutput = ByteArray(4096)

            // 编码
            val encodedBytes = opusEncoder.encode(
                pcmData,
                0,
                frameSizeInSamples,
                opusOutput,
                0,
                opusOutput.size
            )

            // 过滤小于 10 字节的数据包（与浏览器端逻辑一致）
            if (encodedBytes >= 10) {
                val encodedData = opusOutput.copyOf(encodedBytes)
                onOutput?.invoke(encodedData, timestampMicroseconds)
            }

            // 增加时间戳
            timestampMicroseconds += (frameDurationMs * 1000).toLong()

        } catch (e: Exception) {
            onError?.invoke(e)
            println("编码错误: ${e.message}")
        }
    }

    /**
     * 获取帧大小（样本数）
     */
    fun getFrameSize(): Int = frameSizeInSamples

    /**
     * 关闭编码器
     */
    fun close() {
        println("编码器已关闭")
    }
}

// ==================== WebSocket 客户端 ====================

/**
 * 音频 WebSocket 客户端
 * 模拟浏览器端的 WebSocket 连接逻辑
 */
class AudioWebSocketClient(
    serverUri: String,
    private val onHandshake: (HandshakeConfig) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (Exception) -> Unit
) : WebSocketClient(URI(serverUri)) {

    private val gson = Gson()

    override fun onOpen(handshakedata: ServerHandshake?) {
        println("Mic WS 连接成功")
        onConnected()
    }

    override fun onMessage(message: String?) {
        message?.let {
            try {
                val wsMessage = gson.fromJson(it, WebSocketMessage::class.java)

                when (wsMessage.action) {
                    "handshake" -> {
                        wsMessage.data?.let { data ->
                            val config = gson.fromJson(
                                gson.toJson(data),
                                HandshakeConfig::class.java
                            )
                            println("Mic 握手成功")
                            println("配置: Rate: ${config.rate} | Ch: ${config.channels} | Codec: ${config.codec}")
                            onHandshake(config)
                        }
                    }

                    "status" -> {
                        wsMessage.data?.let { status ->
                            onStatus(status.toString())
                        }
                    }
                }

            } catch (e: Exception) {
                println("解析 Mic 消息失败: ${e.message}")
            }
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        println("Mic WS 连接断开: $reason")
        onDisconnected()
    }

    override fun onError(ex: Exception?) {
        println("Mic WS 错误: ${ex?.message}")
        ex?.let { onError(it) }
    }

    /**
     * 发送二进制数据
     */
    fun sendBinary(data: ByteArray) {
        if (isOpen) {
            send(data)
        }
    }
}

// ==================== PCM 文件读取器 ====================

/**
 * PCM 文件读取器
 * 模拟浏览器端的 MediaStreamTrackProcessor
 */
class PcmFileReader(
    private val file: File,
    private val config: PcmFileConfig = PcmFileConfig()
) {
    private val pcmData: ShortArray
    val totalFrames: Int
    val durationSeconds: Double

    init {
        // 读取 PCM 文件
        val fileBytes = file.readBytes()

        // 转换为 ShortArray (Int16)
        val byteBuffer = ByteBuffer.wrap(fileBytes)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

        pcmData = ShortArray(fileBytes.size / 2)
        for (i in pcmData.indices) {
            pcmData[i] = byteBuffer.short
        }

        // 计算总帧数和时长
        val samplesPerFrame = (config.sampleRate * 20 / 1000) // 20ms
        totalFrames = (pcmData.size / config.channels + samplesPerFrame - 1) / samplesPerFrame
        durationSeconds = pcmData.size.toDouble() / config.channels / config.sampleRate

        println("文件加载完成: ${file.name}")
        println("大小: ${fileBytes.size / 1024.0} KB, 样本数: ${pcmData.size}")
        println("预计时长: ${"%.2f".format(durationSeconds)} 秒")
        println("总帧数: $totalFrames")
    }

    /**
     * 读取指定帧的数据
     * @param frameIndex 帧索引
     * @param frameSizeInSamples 每帧的样本数
     * @return PCM 数据，如果到达文件末尾返回 null
     */
    fun readFrame(frameIndex: Int, frameSizeInSamples: Int): ShortArray? {
        val offset = frameIndex * frameSizeInSamples * config.channels

        if (offset >= pcmData.size) {
            return null // 文件结束
        }

        val frameSize = minOf(frameSizeInSamples * config.channels, pcmData.size - offset)
        return pcmData.copyOfRange(offset, offset + frameSize)
    }

    fun getTotalSamples(): Int = pcmData.size
}

// ==================== 主发送器 ====================

/**
 * PCM 到 Opus 发送器
 * 完整实现浏览器端的录制发送逻辑
 */
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

    /**
     * 连接 WebSocket
     */
    fun connect() {
        println("正在连接 Mic WS: $wsUrl")

        webSocket = AudioWebSocketClient(
            serverUri = wsUrl,
            onHandshake = { config ->
                handleHandshake(config)
            },
            onStatus = { status ->
                handleStatus(status)
            },
            onConnected = {
                println("已连接，等待握手...")
            },
            onDisconnected = {
                println("已断开")
                stopSending()
            },
            onError = { error ->
                println("WebSocket 错误: ${error.message}")
            }
        )

        webSocket?.connect()
    }

    /**
     * 处理握手
     */
    private fun handleHandshake(config: HandshakeConfig) {
        handshakeConfig = config

        // 如果是 Opus 编码
        if (config.codec == "opus") {
            println("握手成功，准备就绪")
            println("可以调用 startSending() 开始发送")
        } else {
            println("不支持的编码格式: ${config.codec}")
        }
    }

    /**
     * 处理状态
     */
    private fun handleStatus(status: String) {
        when (status) {
            "waiting" -> println("已连接 WS，等待服务端就绪...")
            "disconnected" -> println("服务端断开连接")
        }
    }

    /**
     * 开始发送
     */
    fun startSending() {
        val config = handshakeConfig
        if (config == null) {
            println("错误：缺少握手配置")
            return
        }

        if (config.codec != "opus") {
            println("错误：不支持的编码格式")
            return
        }

        try {
            println("使用 Opus 编码 (Concentus)")

            // 初始化编码器
            encoder = PcmToOpusEncoder(
                sampleRate = 48000,
                channels = 1,
                bitrate = 64000,
                frameDurationMs = 20
            )

            // 设置输出回调
            encoder?.onOutput = { data, timestamp ->
                handleEncodedOutput(data, timestamp)
            }

            encoder?.onError = { error ->
                println("编码器错误: ${error.message}")
            }

            // 加载 PCM 文件
            fileReader = PcmFileReader(pcmFile)

            // 开始发送
            isRecording = true
            sentBytes = 0
            frameCount = 0

            println("开始录制...")
            startReadingAudio()

        } catch (e: Exception) {
            println("启动录制失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 处理编码输出
     */
    private fun handleEncodedOutput(data: ByteArray, timestamp: Long) {
        // 通过 WebSocket 发送
        webSocket?.sendBinary(data)

        sentBytes += data.size
        frameCount++

        // 每 100 帧打印一次统计
        if (frameCount % 100 == 0) {
            val progress = fileReader?.let { reader ->
                val currentSample = frameCount * encoder!!.getFrameSize()
                (currentSample.toDouble() / reader.getTotalSamples() * 100).toInt()
            } ?: 0

            println("已发送 $frameCount 帧, ${sentBytes / 1024.0} KB, 进度: $progress%")
        }
    }

    /**
     * 开始读取音频（模拟实时流）
     */
    private fun startReadingAudio() {
        val enc = encoder ?: return
        val reader = fileReader ?: return

        val frameSizeInSamples = enc.getFrameSize()

        coroutineScope.launch {
            var currentFrame = 0

            while (isRecording && currentFrame < reader.totalFrames) {
                // 检查 WebSocket 状态
                if (webSocket?.isOpen != true) {
                    println("WebSocket 已断开，停止发送")
                    stopSending()
                    break
                }

                // 读取一帧数据
                val frameData = reader.readFrame(currentFrame, frameSizeInSamples)

                if (frameData == null) {
                    // 文件结束
                    println("文件读取完成")
                    stopSending()
                    break
                }

                // 编码
                enc.encode(frameData)

                currentFrame++

                // 模拟实时发送：每 20ms 发送一帧
                delay(20)
            }

            if (isRecording) {
                stopSending()
            }
        }
    }

    /**
     * 停止发送
     */
    fun stopSending() {
        if (!isRecording) return

        isRecording = false

        println("停止录制")
        println("共发送 $frameCount 帧, ${sentBytes / 1024.0} KB")

        encoder?.close()
        encoder = null
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        stopSending()
        webSocket?.close()
        coroutineScope.cancel()
    }
}

// ==================== 主函数示例 ====================

/*fun main() {
    // WebSocket URL
    val wsUrl = "ws://localhost:8080/audio"  // 根据实际情况修改
    
    // PCM 文件路径
    val pcmFile = File("origin.pcm")  // 48kHz, 1ch, 16bit
    
    if (!pcmFile.exists()) {
        println("错误: 找不到 PCM 文件: ${pcmFile.absolutePath}")
        return
    }
    
    // 创建发送器
    val sender = PcmToOpusSender(wsUrl, pcmFile)
    
    // 连接
    sender.connect()
    
    // 等待握手完成（实际使用中应该在握手回调中调用）
    Thread.sleep(2000)
    
    // 开始发送
    sender.startSending()
    
    // 等待发送完成
    println("按 Enter 键停止...")
    readLine()
    
    // 断开连接
    sender.disconnect()
    
    println("程序结束")
}*/
