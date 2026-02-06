package com.yunji.yunaudio.video

import android.content.res.AssetFileDescriptor
import android.media.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yunji.yunaudio.R
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * 从 Assets 目录读取视频文件并本地解码显示
 * 调试版本：详细日志
 */
class AssetsVideoStreamActivity : AppCompatActivity() {

    private lateinit var surfaceViewDecoded: SurfaceView
    private lateinit var btnConnect: Button
    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button
    private lateinit var tvStats: TextView
    private lateinit var tvStatus: TextView

    private var mediaExtractor: MediaExtractor? = null
    private var streamManager: H264StreamManager? = null

    private var decodeSurface: Surface? = null
    private var isStreaming = false

    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null

    private var processedFrames = 0
    private var sentBytes = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoDurationUs = 0L
    private var keyFrameCount = 0

    companion object {
        private const val TAG = "AssetsVideoStream"
        private const val SERVER_URL = "wss://127.0.0.1:11935/ws"
        private const val ASSET_VIDEO_PATH = "2024Q1_CG.mp4"
        private const val OUTPUT_WIDTH = 1080
        private const val OUTPUT_HEIGHT = 1920

        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        initViews()
        setupSurface()
        loadVideoFromAssets()
    }

    private fun initViews() {
        surfaceViewDecoded = findViewById(R.id.surfaceView)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStream = findViewById(R.id.btnStartDecode)
        btnStopStream = findViewById(R.id.btnStopDecode)
        tvStats = findViewById(R.id.tvStats)
        tvStatus = findViewById(R.id.tvStatus)

        btnConnect.setOnClickListener {
            if (streamManager?.getStats()?.isConnected == true) {
                disconnect()
            } else {
                connect()
            }
        }

        btnStartStream.setOnClickListener {
            startStreaming()
        }

        btnStopStream.setOnClickListener {
            stopStreaming()
        }

        btnStartStream.isEnabled = false
        btnStopStream.isEnabled = false
    }

    private fun setupSurface() {
        surfaceViewDecoded.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                decodeSurface = holder.surface
                Log.d(TAG, "✅ Surface 已创建")

                // 如果是第一次创建，初始化管理器
                if (streamManager == null) {
                    initializeStreamManager()
                } else {
                    // Surface 重新创建（从后台返回），重新设置 Surface
                    Log.d(TAG, "🔄 Surface 重新创建，更新解码器")
                    // TODO: 如果需要，可以重新配置解码器的 Surface
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface 尺寸: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "⚠️ Surface 被销毁（进入后台）")
                decodeSurface = null

                // 关键：不要在这里 release()
                // Surface 销毁不代表应用结束，可能只是进入后台
                // 只有在 onDestroy 时才真正释放资源
            }
        })
    }

    private fun loadVideoFromAssets() {
        try {
            mediaExtractor?.release()

            val afd: AssetFileDescriptor = assets.openFd(ASSET_VIDEO_PATH)

            mediaExtractor = MediaExtractor().apply {
                setDataSource(
                    afd.fileDescriptor,
                    afd.startOffset,
                    afd.length
                )
            }

            afd.close()

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until mediaExtractor!!.trackCount) {
                val format = mediaExtractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)

                Log.d(TAG, "轨道 $i: $mime")

                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex >= 0 && videoFormat != null) {
                mediaExtractor!!.selectTrack(videoTrackIndex)

                videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

                if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
                }

                // 打印完整的 MediaFormat 信息
                Log.d(TAG, "MediaFormat 完整信息:")
                Log.d(TAG, videoFormat.toString())

                extractSPSPPS(videoFormat)

                val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    30
                }

                val durationSec = videoDurationUs / 1_000_000

                tvStatus.text = "✅ 视频已加载\n" +
                        "📹 ${videoWidth}x${videoHeight}\n" +
                        "🎬 $mime\n" +
                        "⏱️ ${durationSec}秒\n" +
                        "🎞️ ${frameRate}fps\n" +
                        "🔑 SPS: ${spsData?.size ?: 0}B\n" +
                        "🔑 PPS: ${ppsData?.size ?: 0}B"

                btnStartStream.isEnabled = true

            } else {
                tvStatus.text = "❌ 未找到视频轨道"
            }

        } catch (e: Exception) {
            Log.e(TAG, "加载失败", e)
            tvStatus.text = "❌ 加载失败: ${e.message}"
        }
    }

    private fun extractSPSPPS(format: MediaFormat) {
        try {
            if (format.containsKey("csd-0")) {
                val csd0 = format.getByteBuffer("csd-0")!!
                csd0.rewind()  // 重置位置
                spsData = ByteArray(csd0.remaining())
                csd0.get(spsData)
                Log.d(TAG, "✅ 提取 SPS: ${spsData!!.size} bytes")
                logHex("SPS", spsData!!)
            } else {
                Log.e(TAG, "❌ MediaFormat 中没有 csd-0")
            }

            if (format.containsKey("csd-1")) {
                val csd1 = format.getByteBuffer("csd-1")!!
                csd1.rewind()  // 重置位置
                ppsData = ByteArray(csd1.remaining())
                csd1.get(ppsData)
                Log.d(TAG, "✅ 提取 PPS: ${ppsData!!.size} bytes")
                logHex("PPS", ppsData!!)
            } else {
                Log.e(TAG, "❌ MediaFormat 中没有 csd-1")
            }

        } catch (e: Exception) {
            Log.e(TAG, "提取 SPS/PPS 失败", e)
        }
    }

    /**
     * 关键修改：直接使用 MediaExtractor 读取的原始数据
     * 不进行 AVCC 转换，因为 MediaExtractor 可能已经输出了 Annex-B 格式
     */
    private fun convertToAnnexB(rawData: ByteArray, isKeyFrame: Boolean): ByteArray {
        // 先检查数据格式
        logHex("原始数据", rawData, 32)

        // 检查是否已经是 Annex-B 格式
        if (rawData.size >= 4 &&
            rawData[0] == 0x00.toByte() &&
            rawData[1] == 0x00.toByte() &&
            (rawData[2] == 0x00.toByte() || rawData[2] == 0x01.toByte()) &&
            rawData[3] == 0x01.toByte()) {

            Log.d(TAG, "✅ 数据已经是 Annex-B 格式")

            // 如果是关键帧且没有 SPS/PPS，添加它们
            if (isKeyFrame && spsData != null && ppsData != null) {
                // 检查是否已经包含 SPS/PPS
                val hasSPS = rawData.containsNAL(7)
                val hasPPS = rawData.containsNAL(8)

                if (!hasSPS || !hasPPS) {
                    Log.d(TAG, "🔑 关键帧缺少 SPS/PPS，添加")
                    val result = mutableListOf<Byte>()
                    result.addAll(START_CODE.toList())
                    result.addAll(spsData!!.toList())
                    result.addAll(START_CODE.toList())
                    result.addAll(ppsData!!.toList())
                    result.addAll(rawData.toList())
                    return result.toByteArray()
                }
            }

            return rawData
        }

        // 否则尝试 AVCC 转换
        Log.d(TAG, "⚙️ 尝试 AVCC 转 Annex-B")
        return convertAVCCToAnnexB(rawData, isKeyFrame)
    }

    private fun convertAVCCToAnnexB(avccData: ByteArray, isKeyFrame: Boolean): ByteArray {
        val result = mutableListOf<Byte>()

        if (isKeyFrame && spsData != null && ppsData != null) {
            result.addAll(START_CODE.toList())
            result.addAll(spsData!!.toList())
            result.addAll(START_CODE.toList())
            result.addAll(ppsData!!.toList())
            Log.d(TAG, "🔑 关键帧: 添加 SPS/PPS")
        }

        var offset = 0
        var nalCount = 0

        while (offset + 4 <= avccData.size) {
            // 读取 NAL 长度（大端序）
            val b0 = avccData[offset].toInt() and 0xFF
            val b1 = avccData[offset + 1].toInt() and 0xFF
            val b2 = avccData[offset + 2].toInt() and 0xFF
            val b3 = avccData[offset + 3].toInt() and 0xFF

            val nalLength = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

            Log.v(TAG, "offset=$offset, nalLength=$nalLength, 剩余=${avccData.size - offset - 4}")

            if (nalLength <= 0 || nalLength > avccData.size - offset - 4) {
                Log.w(TAG, "⚠️ NAL 长度异常: $nalLength at offset $offset")
                break
            }

            offset += 4

            val nalUnit = avccData.copyOfRange(offset, offset + nalLength)
            val nalType = nalUnit[0].toInt() and 0x1F

            Log.v(TAG, "NAL 类型: $nalType, 长度: $nalLength")

            if (nalType == 7 || nalType == 8) {
                Log.d(TAG, "跳过 NAL 类型 $nalType")
                offset += nalLength
                continue
            }

            result.addAll(START_CODE.toList())
            result.addAll(nalUnit.toList())
            nalCount++

            offset += nalLength
        }

        Log.d(TAG, "转换完成: 找到 $nalCount 个 NAL 单元")

        return result.toByteArray()
    }

    // 检查数据中是否包含指定类型的 NAL 单元
    private fun ByteArray.containsNAL(nalType: Int): Boolean {
        for (i in 0 until this.size - 4) {
            if (this[i] == 0x00.toByte() &&
                this[i+1] == 0x00.toByte() &&
                this[i+2] == 0x00.toByte() &&
                this[i+3] == 0x01.toByte()) {

                if (i + 4 < this.size) {
                    val type = this[i + 4].toInt() and 0x1F
                    if (type == nalType) return true
                }
            }
        }
        return false
    }

    private fun initializeStreamManager() {
        decodeSurface?.let { surface ->
            streamManager = H264StreamManager(
                serverUrl = SERVER_URL,
                width = OUTPUT_WIDTH,
                height = OUTPUT_HEIGHT,
                outputSurface = surface
            )

            streamManager?.onStatusChanged = { status ->
                runOnUiThread {
                    when (status) {
                        H264StreamManager.StreamStatus.CONNECTED -> {
                            updateStatus("🟢 WebSocket 已连接")
                            btnConnect.text = "断开连接"
                        }
                        H264StreamManager.StreamStatus.DISCONNECTED -> {
                            updateStatus("🔴 WebSocket 未连接")
                            btnConnect.text = "连接服务器"
                        }
                        else -> {}
                    }
                }
            }

            streamManager?.onStatsUpdated = { stats ->
                runOnUiThread {
                    updateStats(stats)
                }
            }

            if (streamManager?.initialize() == true) {
                Log.d(TAG, "✅ StreamManager 初始化成功")
            }
        }
    }

    private fun connect() {
        streamManager?.connect()
    }

    private fun disconnect() {
        streamManager?.disconnect()
    }

    private fun startStreaming() {
        if (isStreaming || mediaExtractor == null) return

        isStreaming = true
        processedFrames = 0
        sentBytes = 0
        keyFrameCount = 0

        btnStartStream.isEnabled = false
        btnStopStream.isEnabled = true

        // 启动前台服务，保持后台运行
        KeepAliveService.start(this)

        startVideoProcessing()

        updateStatus("🎬 播放中...")
    }

    private fun stopStreaming() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null

        // 停止前台服务
        KeepAliveService.stop(this)

        btnStartStream.isEnabled = true
        btnStopStream.isEnabled = false
        updateStatus("⏸️ 已停止")
    }

    private fun startVideoProcessing() {
        streamJob = scope.launch(Dispatchers.IO) {
            val extractor = mediaExtractor ?: return@launch

            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val maxBufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(maxBufferSize)

            var lastFrameTimeUs = 0L
            var frameInterval = 33333L

            Log.d(TAG, "📹 开始视频处理")

            while (isStreaming) {
                try {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)

                    if (sampleSize < 0) {
                        Log.d(TAG, "🔄 重新开始")
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        lastFrameTimeUs = 0
                        continue
                    }

                    val currentTimeUs = extractor.sampleTime
                    if (lastFrameTimeUs > 0) {
                        frameInterval = currentTimeUs - lastFrameTimeUs
                    }
                    lastFrameTimeUs = currentTimeUs

                    // 读取原始数据
                    val rawData = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(rawData, 0, sampleSize)

                    val flags = extractor.sampleFlags
                    val isKeyFrame = (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

                    if (processedFrames < 5 || isKeyFrame) {
                        Log.d(TAG, "帧 #$processedFrames: size=$sampleSize, keyFrame=$isKeyFrame, flags=$flags")
                    }

                    if (isKeyFrame) {
                        keyFrameCount++
                        Log.d(TAG, "🔑 关键帧 #$keyFrameCount")
                    }

                    // 转换格式
                    val annexBData = convertToAnnexB(rawData, isKeyFrame)

                    if (annexBData.isEmpty()) {
                        Log.w(TAG, "⚠️ 转换后数据为空")
                        extractor.advance()
                        continue
                    }

                    // 关键修改：只在 Surface 可用时解码
                    if (decodeSurface != null) {
                        // 前台，正常解码显示
                        streamManager?.decodeImmediately(annexBData, isKeyFrame)
                    } else {
                        // 后台，跳过解码（节省资源）
                        if (processedFrames % 100 == 0) {
                            Log.v(TAG, "⏭️ 后台运行，跳过解码")
                        }
                    }

                    // 无论前台后台，都发送到 WebSocket
                    if (streamManager?.getStats()?.isConnected == true) {
                        streamManager?.sendH264Data(annexBData)
                    }

                    processedFrames++
                    sentBytes += annexBData.size

                    extractor.advance()

                    val delayMs = (frameInterval / 1000).coerceIn(10, 100)
                    delay(delayMs)

                } catch (e: Exception) {
                    Log.e(TAG, "处理错误", e)
                    break
                }
            }
        }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
    }

    private fun updateStats(stats: H264StreamManager.StreamStats) {
        val statsText = """
            📹 ${videoWidth}x${videoHeight}
            ├─ 已处理: $processedFrames 帧
            ├─ 关键帧: $keyFrameCount
            
            📊 解码统计
            ├─ 解码帧数: ${stats.decodedFrames}
            ├─ 平均延迟: ${stats.averageLatencyMs} ms
            └─ 队列: ${stats.queueSize}
        """.trimIndent()

        tvStats.text = statsText
    }

    private fun logHex(name: String, data: ByteArray, limit: Int = 16) {
        val hex = data.take(limit).joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "$name (${data.size}B): $hex${if (data.size > limit) "..." else ""}")
    }

    private fun release() {
        stopStreaming()

        // 确保服务停止
        try {
            KeepAliveService.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败", e)
        }

        mediaExtractor?.release()
        mediaExtractor = null
        streamManager?.release()
        streamManager = null
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    override fun onPause() {
        super.onPause()
        // 不要在 onPause 中停止，允许后台继续运行
        Log.d(TAG, "Activity onPause - 保持运行")
    }

    override fun onStop() {
        super.onStop()
        // 应用完全不可见时也保持运行
        Log.d(TAG, "Activity onStop - 保持运行")
    }
}