package com.yunji.yunaudio

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SenderActivity : AppCompatActivity() {

    private lateinit var tvWsStatus: TextView
    private lateinit var tvAudioConfig: TextView
    private lateinit var tvSendStatus: TextView
    private lateinit var tvSentSize: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnStartSend: Button
    private lateinit var btnStop: Button
    private lateinit var lvLogs: ListView

    private val logMessages = mutableListOf<String>()
    private lateinit var logAdapter: ArrayAdapter<String>

    private var sender: PcmToOpusSender? = null
    private val wsUrl = "wss://127.0.0.1:38080/audio" // 修改为你的服务端地址

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        initViews()
        setupSender()
    }

    private fun initViews() {
        tvWsStatus = findViewById(R.id.tvWsStatus)
        tvAudioConfig = findViewById(R.id.tvAudioConfig)
        tvSendStatus = findViewById(R.id.tvSendStatus)
        tvSentSize = findViewById(R.id.tvSentSize)
        tvProgress = findViewById(R.id.tvProgress)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartSend = findViewById(R.id.btnStartSend)
        btnStop = findViewById(R.id.btnStop)
        lvLogs = findViewById(R.id.lvLogs)

        logAdapter =
            object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, logMessages) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(0xFF00FF00.toInt())
                    view.textSize = 12f
                    return view
                }
            }
        lvLogs.adapter = logAdapter

        btnConnect.setOnClickListener { connect() }
        btnStartSend.setOnClickListener { startSending() }
        btnStop.setOnClickListener { stop() }

        btnStartSend.isEnabled = false
        btnStop.isEnabled = false
    }

    private fun setupSender() {
        // 使用 getExternalFilesDir 避免权限问题，对应文件名为 origin.pcm
        val targetDir = getExternalFilesDir(null)
        val pcmFile = File(targetDir, "origin.pcm")

        if (!pcmFile.exists()) {
            addLog("文件不存在，正在从 Assets 拷贝: ${pcmFile.name}")
            val success = copyAssetToFile("origin.pcm", pcmFile)
            if (success) {
                addLog("文件拷贝成功: ${pcmFile.absolutePath}")
            } else {
                addLog("错误: 无法从 Assets 拷贝文件")
            }
        } else {
            addLog("找到待发送文件: ${pcmFile.name} (${pcmFile.length() / 1024} KB)")
        }

        sender = PcmToOpusSender(wsUrl, pcmFile)
    }

    private fun copyAssetToFile(assetName: String, outFile: File): Boolean {
        return try {
            assets.open(assetName).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun connect() {
        addLog("正在连接 WebSocket...")
        tvWsStatus.text = "连接中..."

        sender?.connect()

        // 模拟握手成功后的状态切换
        btnConnect.isEnabled = false
        btnStartSend.isEnabled = true
        tvWsStatus.text = "已连接"
        tvWsStatus.setTextColor(0xFF4CAF50.toInt())
    }

    private fun startSending() {
        addLog("开始发送音频流...")
        tvSendStatus.text = "发送中"
        btnStartSend.isEnabled = false
        btnStop.isEnabled = true

        sender?.startSending()

        startProgressUpdate()
    }

    private fun stop() {
        sender?.stopSending()
        tvSendStatus.text = "已停止"
        btnStartSend.isEnabled = true
        btnStop.isEnabled = false
        addLog("发送已手动停止")
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add("[$timestamp] $message")
            if (logMessages.size > 100) logMessages.removeAt(0)
            logAdapter.notifyDataSetChanged()
            lvLogs.smoothScrollToPosition(logMessages.size - 1)
        }
    }

    private fun startProgressUpdate() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sender?.disconnect()
    }
}