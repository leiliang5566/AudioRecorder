package com.yunji.yunaudio

import OpusCodec
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.*

class MainActivity : AppCompatActivity() {
    private val TAG = "AudioRecorder"

    // UI 控件
    private lateinit var tvWsStatus: TextView
    private lateinit var tvAudioConfig: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvDataSize: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSampleRate: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDownload: Button
    private lateinit var lvLogs: ListView

    // WebSocket
    private var webSocket: WebSocket? = null
    private val client = OkHttpClientBuilder.createUnsafeClient()

    // 音频配置
    private var audioConfig: AudioConfig? = null

    // PCM 缓冲区
    private val pcmBuffers = mutableListOf<ByteArray>()
    private var totalBytes = 0L
    private var recordingStartTime = 0L

    // 日志
    private val logMessages = mutableListOf<String>()
    private lateinit var logAdapter: ArrayAdapter<String>

    // 协程
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var durationJob: Job? = null

    // Opus 解码器
    private var opusDecoder: OpusMediaCodecDecoder? = null
    //private var opusToPcmDecoder = OpusToPcmDecoderNative()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()

        scope.launch {
            connectWebSocket()
        }
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
        lvLogs = findViewById(R.id.lvLogs)

        // 初始化日志
        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logMessages)
        lvLogs.adapter = logAdapter

        // 按钮事件
        btnStart.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecording() }
        btnDownload.setOnClickListener { downloadPCM() }

        // 初始状态
        btnStart.isEnabled = false
        btnStop.isEnabled = false
        btnDownload.isEnabled = false
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        } else {
            addLog("已有录音权限")
        }
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add("[$timestamp] $message")
            if (logMessages.size > 100) {
                logMessages.removeAt(0)
            }
            logAdapter.notifyDataSetChanged()
            lvLogs.smoothScrollToPosition(logMessages.size - 1)
        }
    }

    private suspend fun connectWebSocket() {
        withContext(Dispatchers.Main) {
            addLog("正在连接 WebSocket...")
            tvWsStatus.text = "正在连接..."
            tvWsStatus.setTextColor(0xFFFF9800.toInt())
        }

        val request = Request.Builder()
            .url("wss://127.0.0.1:38080/audio-out")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch(Dispatchers.Main) {
                    addLog("WebSocket 连接成功")
                    tvWsStatus.text = "已连接"
                    tvWsStatus.setTextColor(0xFF4CAF50.toInt())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(Dispatchers.Main) {
                    try {
                        val json = JSONObject(text)
                        val action = json.getString("action")

                        when (action) {
                            "handshake" -> {
                                val data = json.getJSONObject("data")
                                handleHandshake(data)
                            }
                            "status" -> {
                                val status = json.getString("data")
                                addLog("状态更新: $status")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("解析消息失败: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (btnStop.isEnabled) {
                    scope.launch(Dispatchers.IO) {
                        handleAudioData(bytes.toByteArray())
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(Dispatchers.Main) {
                    addLog("WebSocket 已断开")
                    tvWsStatus.text = "已断开"
                    tvWsStatus.setTextColor(0xFFF44336.toInt())
                    btnStart.isEnabled = false
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch(Dispatchers.Main) {
                    addLog("WebSocket 错误: ${t.message}")
                    tvWsStatus.text = "连接错误"
                    tvWsStatus.setTextColor(0xFFF44336.toInt())
                }
            }
        })
    }

    private fun handleHandshake(data: JSONObject) {
        val rate = data.getInt("rate")
        val channels = data.getInt("channels")
        val codec = data.optString("codec", "pcm")

        audioConfig = AudioConfig(rate, channels, codec)

        addLog("握手成功 - 采样率: $rate, 通道: $channels, 编码: $codec")
        tvAudioConfig.text = "${rate}Hz, ${channels}ch, $codec"
        tvRecordStatus.text = "就绪"
        tvSampleRate.text = "${rate / 1000}kHz"
        btnStart.isEnabled = true

        if (codec == "opus") {
            try {
                opusDecoder = OpusMediaCodecDecoder()
                lifecycleScope.launch {
                    opusDecoder?.initialize()
                }
                startCollecting()
                addLog("Opus 解码器已初始化")
            } catch (e: Exception) {
                addLog("初始化 Opus 解码器失败: ${e.message}")
            }
        }
    }

    private fun startCollecting() {
        lifecycleScope.launch {
            while (isActive) {
                // 尝试获取解码后的数据
                val pcmData = opusDecoder?.getDecodedData()
                if (pcmData != null) {
                    if (pcmData != null) {
                        // 处理 PCM 数据（播放等）
                        val monoPcm = pcmData

                        synchronized(pcmBuffers) {
                            pcmBuffers.add(monoPcm!!)
                            totalBytes += monoPcm.size
                        }

                        withContext(Dispatchers.Main) {
                            updateStats()
                        }
                    }
                } else {
                    delay(10) // 没有数据时稍等
                }
            }
        }
    }

    private suspend fun handleAudioData(data: ByteArray) {
        val config = audioConfig ?: return

//        opusDecoder!!.decode(data)

        opusDecoder?.decode(data)

    }

    private fun convertToMono(data: ByteArray, channels: Int): ByteArray {
        val samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val numFrames = samples.capacity() / channels
        val monoData = ShortArray(numFrames)

        for (i in 0 until numFrames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += samples.get(i * channels + ch)
            }
            monoData[i] = (sum / channels).toShort()
        }

        val buffer = ByteBuffer.allocate(monoData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(monoData)
        return buffer.array()
    }

    private fun updateStats() {
        tvDataSize.text = String.format("%.2f KB", totalBytes / 1024.0)

        if (recordingStartTime > 0) {
            val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
            val minutes = elapsed / 60
            val seconds = elapsed % 60
            tvDuration.text = String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun startRecording() {
        if (audioConfig == null) {
            addLog("错误: 未收到音频配置")
            return
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnDownload.isEnabled = false
        tvRecordStatus.text = "录制中..."

        synchronized(pcmBuffers) {
            pcmBuffers.clear()
            totalBytes = 0
        }

        recordingStartTime = System.currentTimeMillis()
        addLog("开始录制音频")

        durationJob = scope.launch {
            while (isActive) {
                updateStats()
                delay(1000)
            }
        }
    }

    private fun stopRecording() {
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnDownload.isEnabled = pcmBuffers.isNotEmpty()
        tvRecordStatus.text = "已停止"

        durationJob?.cancel()
        recordingStartTime = 0

        addLog("录制停止 - 共收集 ${pcmBuffers.size} 个数据块，总大小: ${String.format("%.2f KB", totalBytes / 1024.0)}")
    }

    private fun downloadPCM() {
        if (pcmBuffers.isEmpty()) {
            addLog("错误: 没有可下载的数据")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                addLog("正在合并 PCM 数据...")

                val config = audioConfig ?: return@launch

                var totalLength = 0
                synchronized(pcmBuffers) {
                    pcmBuffers.forEach { totalLength += it.size }
                }

                val mergedData = ByteArray(totalLength){
                    0
                }
                var offset = 0
                synchronized(pcmBuffers) {
                    pcmBuffers.forEach { buffer ->
                        System.arraycopy(buffer, 0, mergedData, offset, buffer.size)
                        offset += buffer.size
                    }
                }

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName = "audio_mono_$timestamp.pcm"

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)

                FileOutputStream(file).use { fos ->
                    fos.write(mergedData)
                }

                withContext(Dispatchers.Main) {
                    addLog("PCM 文件已保存: $fileName")
                    addLog("保存位置: ${file.absolutePath}")
                    addLog("文件大小: ${String.format("%.2f KB", totalBytes / 1024.0)}")
                    addLog("音频参数: ${config.rate}Hz, 1 通道 (单声道), Int16 PCM")

                    Toast.makeText(
                        this@MainActivity,
                        "文件已保存到下载目录\n$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("保存文件失败: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "保存失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity destroyed")
        scope.cancel()
    }
}

