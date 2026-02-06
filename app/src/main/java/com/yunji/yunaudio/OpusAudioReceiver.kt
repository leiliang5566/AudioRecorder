package com.yunji.yunaudio

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.github.jaredmdobson.concentus.OpusDecoder
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.timer

/**
 * Opus 音频接收器 - Kotlin 实现
 *
 * 功能：
 * 1. 通过 WebSocket 接收 Opus 音频数据
 * 2. 解码为 PCM 格式（使用 Concentus 库）
 * 3. 支持多通道混音为单声道
 * 4. 保存为 PCM 文件
 *
 * 依赖：
 * - implementation("io.github.jaredmdobson:concentus:1.0.2")
 * - implementation("org.java-websocket:Java-WebSocket:1.5.3")
 * - implementation("com.google.code.gson:gson:2.10.1")
 */

// ==================== 数据类 ====================

/**
 * 握手配置
 */
data class AudioConfig(
    val rate: Int,
    val channels: Int,
    val codec: String,
    @SerializedName("codec_ext")
    val codecExt: String? = null
)

/**
 * WebSocket 消息
 */
data class WsMessage(
    val action: String,
    val data: Any? = null
)

/**
 * 录制统计信息
 */
data class RecordingStats(
    var totalBytes: Long = 0,
    var frameCount: Int = 0,
    var startTime: Long = 0,
    var durationSeconds: Int = 0
)

// ==================== Opus 音频接收器 ====================

/**
 * Opus 音频接收器
 * 完全模拟 audio-recorder_final.html 的功能
 */
class OpusAudioReceiver(
    private val wsUrl: String,
    private val outputDirectory: File = File(".")
) {

    private var webSocket: WebSocketClient? = null
    private var opusDecoder: OpusDecoder? = null
    private var audioConfig: AudioConfig? = null
    private var isRecording = false

    // PCM 数据缓冲区
    private val pcmBuffers = ConcurrentLinkedQueue<ShortArray>()
    private val stats = RecordingStats()

    // 时长计时器
    private var durationTimer: Timer? = null

    // 日志回调
    var onLog: ((String) -> Unit)? = null

    // 状态回调
    var onStatusChange: ((String, String?) -> Unit)? = null

    companion object {
        private const val TAG = "OpusAudioReceiver"
    }

    /**
     * 连接到 WebSocket 服务器
     */
    fun connect() {
        log("正在连接 WebSocket: $wsUrl")
        updateStatus("正在连接...")

        try {
            webSocket = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    log("WebSocket 连接成功")
                    updateStatus("已连接")
                }

                override fun onMessage(message: String?) {
                    message?.let { handleStringMessage(it) }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let { handleBinaryMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    log("WebSocket 连接断开: $reason")
                    updateStatus("已断开")
                    if (isRecording) {
                        stopRecording()
                    }
                }

                override fun onError(ex: Exception?) {
                    log("WebSocket 错误: ${ex?.message}")
                    updateStatus("连接错误")
                }
            }

            webSocket?.connect()

        } catch (e: Exception) {
            log("创建 WebSocket 失败: ${e.message}")
            updateStatus("创建失败")
        }
    }

    /**
     * 处理文本消息（握手、状态等）
     */
    private fun handleStringMessage(message: String) {
        try {
            val gson = Gson()
            val wsMsg = gson.fromJson(message, WsMessage::class.java)

            when (wsMsg.action) {
                "handshake" -> {
                    wsMsg.data?.let { data ->
                        val config = gson.fromJson(gson.toJson(data), AudioConfig::class.java)
                        handleHandshake(config)
                    }
                }

                "status" -> {
                    log("状态更新: ${wsMsg.data}")
                }
            }
        } catch (e: Exception) {
            log("解析消息失败: ${e.message}")
        }
    }

    /**
     * 处理二进制消息（音频数据）
     */
    private fun handleBinaryMessage(buffer: ByteBuffer) {
        if (!isRecording) return

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        handleAudioData(bytes)
    }

    /**
     * 处理握手配置
     */
    private fun handleHandshake(config: AudioConfig) {
        audioConfig = config

        log("握手成功 - 采样率: ${config.rate}, 通道: ${config.channels}, 编码: ${config.codec}")
        updateStatus("就绪", "${config.rate}Hz, ${config.channels}ch, ${config.codec}")

        // 如果是 Opus 编码，初始化解码器
        if (config.codec == "opus") {
            initOpusDecoder(config)
        }
    }

    /**
     * 初始化 Opus 解码器
     */
    private fun initOpusDecoder(config: AudioConfig) {
        try {
            opusDecoder = OpusDecoder(config.rate, config.channels)
            log("Opus 解码器已初始化 (Concentus)")
            log("配置: ${config.rate}Hz, ${config.channels}ch")
        } catch (e: Exception) {
            log("初始化解码器失败: ${e.message}")
            opusDecoder = null
        }
    }

    /**
     * 处理音频数据
     */
    private fun handleAudioData(opusData: ByteArray) {
        val config = audioConfig ?: return

        if (config.codec == "opus" && opusDecoder != null) {
            // Opus 编码数据，需要解码
            decodeOpusToPcm(opusData)
        } else {
            // 假设是 PCM Int16 数据
            val pcmData = byteArrayToShortArray(opusData)
            pcmBuffers.add(pcmData)
            stats.totalBytes += opusData.size.toLong()
            stats.frameCount++
        }
    }

    /**
     * 解码 Opus 为 PCM（完全模拟 HTML 逻辑）
     */
    private fun decodeOpusToPcm(opusData: ByteArray) {
        val decoder = opusDecoder ?: return
        val config = audioConfig ?: return

        try {
            val numChannels = config.channels
            val sampleRate = config.rate

            // 使用足够大的缓冲区
            val bufferSizeInSamples = 5760  // 120ms @ 48kHz
            val pcmOutput = ShortArray(bufferSizeInSamples * numChannels)

            // ========== 步骤 1: Concentus 解码 ==========
            val decodedSamples = decoder.decode(
                opusData, 0, opusData.size,
                pcmOutput, 0, bufferSizeInSamples,
                false
            )

            if (decodedSamples <= 0) {
                log("解码失败: 返回 $decodedSamples 样本")
                return
            }

            val totalSamples = decodedSamples * numChannels
            val decodedData = pcmOutput.copyOf(totalSamples)

            // ========== 步骤 2: 转换为 Float32（模拟 HTML）==========
            val pcmFloat = FloatArray(totalSamples)
            for (i in decodedData.indices) {
                pcmFloat[i] = decodedData[i] / 32768.0f
            }

            // ========== 步骤 3: 多通道混音为单声道（模拟 HTML）==========
            val finalPcmFloat = if (numChannels > 1) {
                log("将 $numChannels 通道混音为单通道")

                val monoFloat = FloatArray(decodedSamples)

                for (i in 0 until decodedSamples) {
                    var sum = 0f
                    for (ch in 0 until numChannels) {
                        sum += pcmFloat[i * numChannels + ch]
                    }
                    monoFloat[i] = sum / numChannels
                }

                monoFloat
            } else {
                pcmFloat
            }

            // ========== 步骤 4: Float32 → Int16（模拟 HTML）==========
            val pcmInt16 = ShortArray(finalPcmFloat.size)

            for (i in finalPcmFloat.indices) {
                // 限幅到 [-1, 1]
                val s = when {
                    finalPcmFloat[i] > 1.0f -> 1.0f
                    finalPcmFloat[i] < -1.0f -> -1.0f
                    else -> finalPcmFloat[i]
                }

                // 转换为 Int16（与 HTML 完全一致）
                pcmInt16[i] = if (s < 0) {
                    (s * 32768.0f).toInt().toShort()
                } else {
                    (s * 32767.0f).toInt().toShort()
                }
            }

            // ========== 步骤 5: 存储 PCM 数据 ==========
            pcmBuffers.add(pcmInt16)
            stats.totalBytes += (pcmInt16.size * 2).toLong()
            stats.frameCount++

        } catch (e: Exception) {
            log("解码失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 开始录制
     */
    fun startRecording() {
        if (audioConfig == null) {
            log("错误: 未收到音频配置")
            return
        }

        isRecording = true
        pcmBuffers.clear()
        stats.totalBytes = 0
        stats.frameCount = 0
        stats.startTime = System.currentTimeMillis()

        updateStatus("录制中...")
        log("开始录制音频")

        // 启动时长计时器
        durationTimer = timer(period = 1000) {
            updateDuration()
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        isRecording = false

        durationTimer?.cancel()
        durationTimer = null

        updateStatus("已停止")
        log("录制停止 - 共收集 ${pcmBuffers.size} 个数据块，总大小: ${stats.totalBytes / 1024.0} KB")
    }

    /**
     * 保存 PCM 文件
     */
    fun savePcmFile(): File? {
        if (pcmBuffers.isEmpty()) {
            log("错误: 没有可保存的数据")
            return null
        }

        log("正在合并 PCM 数据...")

        try {
            // 合并所有 PCM 缓冲区
            var totalLength = 0
            pcmBuffers.forEach { buffer -> totalLength += buffer.size }

            val mergedPCM = ShortArray(totalLength)
            var offset = 0
            pcmBuffers.forEach { buffer ->
                System.arraycopy(buffer, 0, mergedPCM, offset, buffer.size)
                offset += buffer.size
            }

            // 转换为字节数组（小端序）
            val byteArray = shortArrayToByteArray(mergedPCM)

            // 生成文件名
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val fileName = "audio_mono_${timestamp}.pcm"
            val file = File(outputDirectory, fileName)

            // 写入文件
            file.writeBytes(byteArray)

            log("PCM 文件已保存: ${file.absolutePath} (${stats.totalBytes / 1024.0} KB)")
            log("音频参数: ${audioConfig?.rate}Hz, 1 通道 (单声道), Int16 PCM")
            log("播放命令: ffplay -f s16le -ar ${audioConfig?.rate} -ac 1 ${file.name}")

            return file

        } catch (e: Exception) {
            log("保存文件失败: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        stopRecording()
        webSocket?.close()
        opusDecoder = null
    }

    /**
     * 获取录制时长（秒）
     */
    fun getDurationSeconds(): Int = stats.durationSeconds

    /**
     * 获取录制统计信息
     */
    fun getStats(): RecordingStats = stats.copy()

    /**
     * 更新录制时长
     */
    private fun updateDuration() {
        if (stats.startTime > 0) {
            stats.durationSeconds = ((System.currentTimeMillis() - stats.startTime) / 1000).toInt()
        }
    }

    /**
     * ShortArray 转 ByteArray（小端序）
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val value = shorts[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * ByteArray 转 ShortArray（小端序）
     */
    private fun byteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() shl 8
            shorts[i] = (high or low).toShort()
        }
        return shorts
    }

    /**
     * 日志输出
     */
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        println(logMessage)
        onLog?.invoke(logMessage)
    }

    /**
     * 状态更新
     */
    private fun updateStatus(status: String, extra: String? = null) {
        onStatusChange?.invoke(status, extra)
    }
}

// ==================== 主函数示例 ====================

fun main() {
    val wsUrl = "wss://192.168.10.18:38080/audio-out"
    val outputDir = File("./recordings")

    // 确保输出目录存在
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    // 创建接收器
    val receiver = OpusAudioReceiver(wsUrl, outputDir)

    // 设置日志回调
    receiver.onLog = { message ->
        println(message)
    }

    // 设置状态回调
    receiver.onStatusChange = { status, extra ->
        println("状态: $status ${extra ?: ""}")
    }

    // 连接
    receiver.connect()

    // 等待握手完成
    Thread.sleep(3000)

    // 开始录制
    println("\n按 Enter 开始录制...")
    readLine()
    receiver.startRecording()

    // 等待用户停止
    println("按 Enter 停止录制...")
    readLine()
    receiver.stopRecording()

    // 保存文件
    val file = receiver.savePcmFile()
    if (file != null) {
        println("\n文件已保存: ${file.absolutePath}")
    }

    // 断开连接
    receiver.disconnect()

    println("\n程序结束")
}
