import android.util.Log
import com.auralis.opuscodec.Opus

/**
 * Opus 音频编解码器封装类
 * 用于在 PCM 和 Opus 格式之间进行转换
 */
class OpusCodec(
) {

    private val codec: Opus = Opus()
    private var isInitialized = false

    companion object {
        private const val TAG = "OpusCodec"
    }

    init {
        codec.decoderInit(48000, 2)
    }

    fun decodeOpus(data: ByteArray): ByteArray {
        return codec.decode(data, 480, 1)
    }
}