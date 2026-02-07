package com.yunji.yunaudio

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.yunji.yunaudio.MainActivity.OkHttpClientBuilder
import io.github.jaredmdobson.concentus.OpusDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
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
    private var webSocket: WebSocket? = null
    private var opusDecoder: OpusDecoder? = null
    private var audioConfig: AudioConfig? = null
    private var isRecording = false

    // 数据和统计
    private val pcmBuffers = ConcurrentLinkedQueue<ByteArray>()
    private var totalBytes = 0L
    private var frameCount = 0
    private var startTime = 0L
    private var durationTimer: Timer? = null
    private val client = OkHttpClientBuilder.createUnsafeClient()

    // WebSocket URL（可配置）
    private val wsUrl = "wss://127.0.0.1:38080/audio-out"

    companion object {
        private const val TAG = "OpusReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opus_receiver)

        initViews()
        setupListeners()
        lifecycleScope.launch {
            connectWebSocket()
        }

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
    private suspend fun connectWebSocket() {
        withContext(Dispatchers.Main) {
            log("正在连接 WebSocket...")
            tvWsStatus.text = "正在连接..."
            tvWsStatus.setTextColor(0xFFFF9800.toInt())
        }

        val request = Request.Builder()
            .url("wss://127.0.0.1:38080/audio-out")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lifecycleScope.launch(Dispatchers.Main) {
                    log("WebSocket 连接成功")
                    updateWsStatus("已连接", android.R.color.holo_green_light)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        val json = JSONObject(text)
                        val action = json.getString("action")

                        when (action) {
                            "handshake" -> {
                                handleStringMessage(text)
                            }
                            "status" -> {
                                val status = json.getString("data")
                                log("状态更新: $status")
                            }
                        }
                    } catch (e: Exception) {
                        log("解析消息失败: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (btnStop.isEnabled) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val arr = bytes.toByteArray();
                        Log.d("message",arr.size.toString())
                       val buffer =  ByteBuffer.allocateDirect(arr.size);
                        buffer.put(arr);
                        buffer.flip()
                        handleBinaryMessage(buffer)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    log("WebSocket 已断开")
                    tvWsStatus.text = "已断开"
                    tvWsStatus.setTextColor(0xFFF44336.toInt())
                    btnStart.isEnabled = false
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lifecycleScope.launch(Dispatchers.Main) {
                    log("WebSocket 错误: ${t.message}")
                    tvWsStatus.text = "连接错误"
                    tvWsStatus.setTextColor(0xFFF44336.toInt())
                }
            }
        })
    }

    /**
     * 连接 WebSocket
     */
   /* private fun connectWebSocket() {
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
    }*/

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
            opusDecoder = OpusDecoder(48000, 2)
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
            //val pcmData
            pcmBuffers.add(opusData)
            totalBytes += opusData.size.toLong()
            frameCount++
            runOnUiThread { updateStats() }
        }
    }

    // 1. 在类成员变量中添加复用缓冲区，避免频繁创建数组
    private var pcmDecodeBuffer = ByteArray(5760 * 2*2) // 最大支持 120ms 双声道
    /**
     * 原地重置 ByteArray 所有元素为 0（修改原数组，无额外内存开销）
     * @param byteArray 待重置的 ByteArray（可为 null）
     * @return true：重置成功；false：数组为 null/空，无需重置
     */
    fun resetByteArrayToZero(byteArray: ByteArray?): Boolean {
        // 处理 null 或空数组
        if (byteArray == null || byteArray.isEmpty()) {
            return false
        }
        // 遍历数组，逐个赋值为 0
        for (i in byteArray.indices) {
            byteArray[i] = 0.toByte()
        }
        return true
    }
    /**
     * 解码 Opus 为 PCM（优化版）
     */
    private fun decodeOpusToPcm(opusData: ByteArray) {
        val decoder = opusDecoder ?: return
        val config = audioConfig ?: return
        resetByteArrayToZero(pcmDecodeBuffer)
        try {
            val numChannels = config.channels
            // Opus 每一帧每个通道最大采样数为 5760 (120ms @ 48kHz)
            val maxSamplesPerChannel = 5760

            // 如果频道数变化，动态调整缓冲区（虽然通常不会变）
            if (pcmDecodeBuffer.size < maxSamplesPerChannel * numChannels) {
                pcmDecodeBuffer = ByteArray(maxSamplesPerChannel * numChannels)
            }

            // Concentus 解码：返回的是每个通道的采样数
            val decodedSamplesPerChannel = decoder.decode(
                opusData, 0, opusData.size,
                pcmDecodeBuffer, 0, maxSamplesPerChannel,
                false
            )
            Log.d(TAG, "decodeOpusToPcm,valid data size = ${countTrailingZeros(pcmDecodeBuffer)}")
            if (decodedSamplesPerChannel <= 0) return

            // 计算总样本数
            val totalSamples = decodedSamplesPerChannel * numChannels

            // 处理混音：如果是多通道，直接在 Short 级别转为单声道
            val finalPcm: ByteArray = pcmDecodeBuffer.copyOf(3840)
/*            if (numChannels > 1) {
                val monoShorts = ShortArray(decodedSamplesPerChannel)
                for (i in 0 until decodedSamplesPerChannel) {
                    // 直接对 Short 进行平均值计算，防止溢出使用 Long 暂存
                    var sum = 0L
                    for (ch in 0 until numChannels) {
                        sum += pcmDecodeBuffer[i * numChannels + ch]
                    }
                    monoShorts[i] = (sum / numChannels).toShort()
                }
                monoShorts
            } else {
                // 如果已经是单声道，拷贝一份有效数据
                pcmDecodeBuffer.copyOf(totalSamples)
            }*/

            // 存储 PCM 数据
            pcmBuffers.add(finalPcm)
            totalBytes += (finalPcm.size * 2).toLong()
            frameCount++

            runOnUiThread { updateStats() }

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { log("解码异常: ${e.message}") }
        }
    }

    /**
     * 计算 ByteArray 尾部连续 0 字节的数量（高效版）
     * @param byteArray 待计算的 ByteArray（可为 null）
     * @return 尾部连续 0 的个数：null/空数组返回 0，全 0 数组返回数组长度，否则返回尾部连续 0 的数量
     */
    fun countTrailingZeros(byteArray: ByteArray?): Int {
        // 1. 处理 null 或空数组
        if (byteArray == null || byteArray.isEmpty()) {
            return 0
        }

        var count = 0
        // 2. 从数组最后一位开始反向遍历
        for (i in byteArray.indices.reversed()) {
            if (byteArray[i] == 0.toByte()) {
                count++ // 是 0 字节，计数+1
            } else {
                break // 遇到非 0 字节，终止遍历
            }
        }
        return count
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

            val mergedPCM = ByteArray(totalLength)
            var offset = 0
            pcmBuffers.forEach { buffer ->
                System.arraycopy(buffer, 0, mergedPCM, offset, buffer.size)
                offset += buffer.size
            }

            // 转换为字节数组
            //val byteArray = shortArrayToByteArray(mergedPCM)

            // 保存到文件
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val fileName = "audio_mono_$timestamp.pcm"

            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )

            file.writeBytes(mergedPCM)

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

        webSocket?.close(1000, "Activity destroyed")
        opusDecoder = null
    }

    data class WsMessage(
        val action: String,
        val data: Any? = null
    )
}
