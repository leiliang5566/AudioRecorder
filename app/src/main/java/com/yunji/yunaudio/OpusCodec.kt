import android.util.Log
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus

/**
 * Opus 音频编解码器封装类
 * 用于在 PCM 和 Opus 格式之间进行转换
 */
class OpusCodec(
    private val sampleRate: Constants.SampleRate = Constants.SampleRate._48000(),
    private val channels: Constants.Channels = Constants.Channels.stereo(),
    private val application: Constants.Application = Constants.Application.audio(),
    private val frameSize: Constants.FrameSize = Constants.FrameSize._120()
) {

    private val codec: Opus = Opus()
    private var isInitialized = false

    companion object {
        private const val TAG = "OpusCodec"
    }

    /**
     * 初始化编解码器
     * @param complexity 编码器复杂度 (0-10)，默认为10
     * @param bitrate 编码器比特率，默认为最大值
     * @return 初始化是否成功
     */
    fun initialize(
        complexity: Int = 10,
        bitrate: Constants.Bitrate = Constants.Bitrate.max()
    ): Boolean {
        return try {
            // 初始化编码器和解码器
            codec.encoderInit(sampleRate, channels, application)
            codec.decoderInit(sampleRate, channels)

            // 配置编码器参数（可选）
            val complexityConfig = Constants.Complexity.instance(complexity)
            codec.encoderSetComplexity(complexityConfig)
            codec.encoderSetBitrate(bitrate)

            isInitialized = true
            Log.d(TAG, "Opus codec initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Opus codec", e)
            false
        }
    }

    /**
     * 将 PCM 音频帧编码为 Opus 格式
     * @param pcmFrame PCM 音频数据（字节数组）
     * @return 编码后的 Opus 数据（ByteArray），失败返回 null
     */
    fun encodePcmToOpus(pcmFrame: ByteArray): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "Codec not initialized")
            return null
        }

        return try {
            val encoded = codec.encode(pcmFrame, frameSize)
            if (encoded != null) {
                val byteArray = encoded
                Log.d(TAG, "Encoded PCM frame (${pcmFrame.size} bytes) to Opus (${byteArray.size} bytes)")
                byteArray
            } else {
                Log.w(TAG, "Encoding returned null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding PCM to Opus", e)
            null
        }
    }

    /**
     * 将 PCM 音频帧编码为 Opus 格式（Short 数组版本）
     * @param pcmFrame PCM 音频数据（short 数组）
     * @return 编码后的 Opus 数据（ByteArray），失败返回 null
     */
    fun encodePcmToOpus(pcmFrame: ShortArray): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "Codec not initialized")
            return null
        }

        return try {
            val encoded = codec.encode(pcmFrame, frameSize)
            if (encoded != null) {
                val byteArray = shortArrayToByteArray(encoded)
                Log.d(TAG, "Encoded PCM frame (${pcmFrame.size} shorts) to Opus (${byteArray.size} bytes)")
                byteArray
            } else {
                Log.w(TAG, "Encoding returned null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding PCM to Opus", e)
            null
        }
    }

    /**
     * 将 Opus 格式解码为 PCM 音频帧
     * @param opusFrame Opus 编码数据（ByteArray）
     * @return 解码后的 PCM 数据（字节数组），失败返回 null
     */
    fun decodeOpusToPcm(opusFrame: ByteArray): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "Codec not initialized")
            return null
        }

        return try {
            // 将 ByteArray 转换为 ShortArray 后解码
            val opusShorts = byteArrayToShortArray(opusFrame)
            val decoded = codec.decode(opusShorts, frameSize)
            if (decoded != null) {
                Log.d(TAG, "Decoded Opus frame (${opusFrame.size} bytes) to PCM (${decoded.size} bytes)")
            } else {
                Log.w(TAG, "Decoding returned null")
            }
            shortArrayToByteArray(decoded!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding Opus to PCM", e)
            null
        }
    }

    /**
     * 批量编码 PCM 数据流
     * @param pcmData 完整的 PCM 数据（字节数组）
     * @param frameSizeBytes 每帧的大小（字节数）
     * @return 编码后的 Opus 数据列表（ByteArray列表）
     */
    fun encodeStream(pcmData: ByteArray, frameSizeBytes: Int): List<ByteArray> {
        val encodedFrames = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + frameSizeBytes <= pcmData.size) {
            val frame = pcmData.copyOfRange(offset, offset + frameSizeBytes)
            val encoded = encodePcmToOpus(frame)
            if (encoded != null) {
                encodedFrames.add(encoded)
            }
            offset += frameSizeBytes
        }

        Log.d(TAG, "Encoded ${encodedFrames.size} frames from stream")
        return encodedFrames
    }

    /**
     * 批量编码 PCM 数据流（Short数组版本）
     * @param pcmData 完整的 PCM 数据（Short数组）
     * @param frameSizeShorts 每帧的大小（short数量）
     * @return 编码后的 Opus 数据列表（ByteArray列表）
     */
    fun encodeStream(pcmData: ShortArray, frameSizeShorts: Int): List<ByteArray> {
        val encodedFrames = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + frameSizeShorts <= pcmData.size) {
            val frame = pcmData.copyOfRange(offset, offset + frameSizeShorts)
            val encoded = encodePcmToOpus(frame)
            if (encoded != null) {
                encodedFrames.add(encoded)
            }
            offset += frameSizeShorts
        }

        Log.d(TAG, "Encoded ${encodedFrames.size} frames from stream")
        return encodedFrames
    }

    /**
     * 批量解码 Opus 数据流
     * @param opusFrames Opus 数据帧列表（ByteArray列表）
     * @return 解码后的 PCM 数据（字节数组）
     */
    fun decodeStream(opusFrames: List<ByteArray>): ByteArray {
        val decodedFrames = mutableListOf<ByteArray>()

        opusFrames.forEach { frame ->
            val decoded = decodeOpusToPcm(frame)
            if (decoded != null) {
                decodedFrames.add(decoded)
            }
        }

        // 合并所有解码后的帧
        val totalSize = decodedFrames.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0

        decodedFrames.forEach { frame ->
            System.arraycopy(frame, 0, result, offset, frame.size)
            offset += frame.size
        }

        Log.d(TAG, "Decoded ${opusFrames.size} frames to ${result.size} bytes")
        return result
    }

    /**
     * ShortArray 转 ByteArray（小端序）
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * ByteArray 转 ShortArray（小端序）
     */
    private fun byteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return shorts
    }

    /**
     * 获取当前配置信息
     */
    fun getConfig(): String {
        return "SampleRate: $sampleRate, Channels: $channels, FrameSize: $frameSize"
    }

    /**
     * 释放资源
     * 应在不再需要编解码器时调用
     */
    fun release() {
        if (isInitialized) {
            try {
                codec.encoderRelease()
                codec.decoderRelease()
                isInitialized = false
                Log.d(TAG, "Opus codec released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing codec", e)
            }
        }
    }

    /**
     * 检查编解码器是否已初始化
     */
    fun isReady(): Boolean = isInitialized
}