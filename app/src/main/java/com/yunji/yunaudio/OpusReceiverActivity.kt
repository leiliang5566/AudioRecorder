package com.yunji.yunaudio

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
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
 * Opus 音频接收器 Activity
 * 完全模拟 audio-recorder_final.html 的 UI 和功能
 */
class OpusReceiverActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var tvWsStatus: TextView
    private lateinit var tvAudioConfig: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvDataSize: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSampleRate: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDownload: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    // 核心组件
    private var webSocket: WebSocketClient? = null
    private var opusDecoder: OpusDecoder? = null
    private var audioConfig: AudioConfig? = null
    private var isRecording = false

    // 数据和统计
    private val pcmBuffers = ConcurrentLinkedQueue<ShortArray>()
    private var totalBytes = 0L
    private var frameCount = 0
    private var startTime = 0L
    private var durationTimer: Timer? = null

    // WebSocket URL（可配置）
    private val wsUrl = "wss://192.168.10.18:38080/audio-out"

    companion object {
        private const val TAG = "OpusReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opus_receiver)

        initViews()
        setupListeners()
        connectWebSocket()
    }

    /**
     * 初始化视图
     */
    private fun initViews() {
        tvWsStatus = findViewById(R.id.tvWsStatus)
        tvAudioConfig = findViewById(R.id.tvAudioConfig)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        tvDataSize = findViewById(R.id.tvDataSize)
        tvDuration = findViewById(R.id.tvDuration)
        tvSampleRate = findViewById(R.id.tvSampleRate)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDownload = findViewById(R.id.btnDownload)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        // 初始状态
        btnStart.isEnabled = false
        btnStop.isEnabled = false
        btnDownload.isEnabled = false
    }

    /**
     * 设置监听器
     */
    private fun setupListeners() {
        btnStart.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecording() }
        btnDownload.setOnClickListener { downloadPCM() }
    }

    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket() {
        log("正在连接 WebSocket: $wsUrl")
        updateWsStatus("正在连接...", android.R.color.holo_orange_light)

        try {
            webSocket = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    runOnUiThread {
                        log("WebSocket 连接成功")
                        updateWsStatus("已连接", android.R.color.holo_green_light)
                    }
                }

                override fun onMessage(message: String?) {
                    message?.let { handleStringMessage(it) }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let { handleBinaryMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    runOnUiThread {
                        log("WebSocket 连接断开: $reason")
                        updateWsStatus("已断开", android.R.color.holo_red_light)
                        if (isRecording) {
                            stopRecording()
                        }
                    }
                }

                override fun onError(ex: Exception?) {
                    runOnUiThread {
                        log("WebSocket 错误: ${ex?.message}")
                        updateWsStatus("连接错误", android.R.color.holo_red_light)
                    }
                }
            }

            webSocket?.connect()

        } catch (e: Exception) {
            log("创建 WebSocket 失败: ${e.message}")
            updateWsStatus("创建失败", android.R.color.holo_red_light)
        }
    }

    /**
     * 处理文本消息
     */
    private fun handleStringMessage(message: String) {
        try {
            val gson = Gson()
            val wsMsg = gson.fromJson(message, WsMessage::class.java)

            when (wsMsg.action) {
                "handshake" -> {
                    wsMsg.data?.let { data ->
                        val config = gson.fromJson(gson.toJson(data), AudioConfig::class.java)
                        runOnUiThread { handleHandshake(config) }
                    }
                }

                "status" -> {
                    runOnUiThread { log("状态更新: ${wsMsg.data}") }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { log("解析消息失败: ${e.message}") }
        }
    }

    /**
     * 处理二进制消息
     */
    private fun handleBinaryMessage(buffer: ByteBuffer) {
        if (!isRecording) return

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        handleAudioData(bytes)
    }

    /**
     * 处理握手
     */
    private fun handleHandshake(config: AudioConfig) {
        audioConfig = config

        log("握手成功 - 采样率: ${config.rate}, 通道: ${config.channels}, 编码: ${config.codec}")

        tvAudioConfig.text = "${config.rate}Hz, ${config.channels}ch, ${config.codec}"
        tvRecordStatus.text = "就绪"
        btnStart.isEnabled = true

        // 初始化 Opus 解码器
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
            log("Opus 解码器已初始化")
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
            decodeOpusToPcm(opusData)
        } else {
            val pcmData = byteArrayToShortArray(opusData)
            pcmBuffers.add(pcmData)
            totalBytes += opusData.size.toLong()
            frameCount++
            runOnUiThread { updateStats() }
        }
    }

    /**
     * 解码 Opus 为 PCM
     */
    private fun decodeOpusToPcm(opusData: ByteArray) {
        val decoder = opusDecoder ?: return
        val config = audioConfig ?: return

        try {
            val numChannels = config.channels
            val bufferSizeInSamples = 5760
            val pcmOutput = ShortArray(bufferSizeInSamples * numChannels)

            val decodedSamples = decoder.decode(
                opusData, 0, opusData.size,
                pcmOutput, 0, bufferSizeInSamples,
                false
            )

            if (decodedSamples <= 0) return

            val totalSamples = decodedSamples * numChannels
            val decodedData = pcmOutput.copyOf(totalSamples)

            // 转换为 Float32
            val pcmFloat = FloatArray(totalSamples)
            for (i in decodedData.indices) {
                pcmFloat[i] = decodedData[i] / 32768.0f
            }

            // 多通道混音
            val finalPcmFloat = if (numChannels > 1) {
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

            // Float32 → Int16
            val pcmInt16 = ShortArray(finalPcmFloat.size)
            for (i in finalPcmFloat.indices) {
                val s = when {
                    finalPcmFloat[i] > 1.0f -> 1.0f
                    finalPcmFloat[i] < -1.0f -> -1.0f
                    else -> finalPcmFloat[i]
                }
                pcmInt16[i] = if (s < 0) {
                    (s * 32768.0f).toInt().toShort()
                } else {
                    (s * 32767.0f).toInt().toShort()
                }
            }

            pcmBuffers.add(pcmInt16)
            totalBytes += (pcmInt16.size * 2).toLong()
            frameCount++

            runOnUiThread { updateStats() }

        } catch (e: Exception) {
            runOnUiThread { log("解码失败: ${e.message}") }
        }
    }

    /**
     * 开始录制
     */
    private fun startRecording() {
        if (audioConfig == null) {
            log("错误: 未收到音频配置")
            return
        }

        isRecording = true
        pcmBuffers.clear()
        totalBytes = 0
        frameCount = 0
        startTime = System.currentTimeMillis()

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnDownload.isEnabled = false

        tvRecordStatus.text = "录制中..."
        log("开始录制音频")

        durationTimer = timer(period = 1000) {
            runOnUiThread { updateDuration() }
        }
    }

    /**
     * 停止录制
     */
    private fun stopRecording() {
        isRecording = false

        durationTimer?.cancel()
        durationTimer = null

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnDownload.isEnabled = pcmBuffers.isNotEmpty()

        tvRecordStatus.text = "已停止"
        log("录制停止 - 共收集 ${pcmBuffers.size} 个数据块，总大小: ${totalBytes / 1024.0} KB")
    }

    /**
     * 下载 PCM 文件
     */
    private fun downloadPCM() {
        if (pcmBuffers.isEmpty()) {
            log("错误: 没有可下载的数据")
            return
        }

        log("正在合并 PCM 数据...")

        try {
            // 合并所有缓冲区
            var totalLength = 0
            pcmBuffers.forEach { totalLength += it.size }

            val mergedPCM = ShortArray(totalLength)
            var offset = 0
            pcmBuffers.forEach { buffer ->
                System.arraycopy(buffer, 0, mergedPCM, offset, buffer.size)
                offset += buffer.size
            }

            // 转换为字节数组
            val byteArray = shortArrayToByteArray(mergedPCM)

            // 保存到文件
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val fileName = "audio_mono_$timestamp.pcm"

            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )

            file.writeBytes(byteArray)

            log("PCM 文件已保存: ${file.absolutePath}")
            log("大小: ${totalBytes / 1024.0} KB")
            log("音频参数: ${audioConfig?.rate}Hz, 1 通道 (单声道), Int16 PCM")
            log("播放命令: ffplay -f s16le -ar ${audioConfig?.rate} -ac 1 ${file.name}")

        } catch (e: Exception) {
            log("保存文件失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 更新统计信息
     */
    private fun updateStats() {
        tvDataSize.text = "${totalBytes / 1024.0} KB"
        audioConfig?.let {
            tvSampleRate.text = "${it.rate / 1000} kHz"
        }
    }

    /**
     * 更新录制时长
     */
    private fun updateDuration() {
        if (startTime > 0) {
            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            val minutes = elapsed / 60
            val seconds = elapsed % 60
            tvDuration.text = String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 更新 WebSocket 状态
     */
    private fun updateWsStatus(status: String, colorResId: Int) {
        tvWsStatus.text = status
        tvWsStatus.setTextColor(ContextCompat.getColor(this, colorResId))
    }

    /**
     * 日志输出
     */
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message\n"

        tvLog.append(logMessage)
        scrollLog.post {
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
        }

        // 限制日志长度
        if (tvLog.lineCount > 100) {
            val text = tvLog.text.toString()
            val lines = text.split("\n")
            tvLog.text = lines.takeLast(100).joinToString("\n")
        }
    }

    /**
     * ShortArray 转 ByteArray
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
     * ByteArray 转 ShortArray
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
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        webSocket?.close()
        opusDecoder = null
    }

    data class WsMessage(
        val action: String,
        val data: Any? = null
    )
}
