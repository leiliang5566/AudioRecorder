package com.yunji.yunaudio

import android.util.Log

class OpusDecoder(
    private val sampleRate: Int,
    private val channels: Int
) {
    private val TAG = "OpusDecoder"
    private var decoderHandle: Long = 0

    init {
        try {
            System.loadLibrary("opus-jni")
            decoderHandle = nativeInit(sampleRate, channels)

            if (decoderHandle == 0L) {
                throw RuntimeException("Failed to initialize Opus decoder")
            }

            Log.d(TAG, "Opus decoder initialized: $sampleRate Hz, $channels channels")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            throw RuntimeException("Native Opus library not found", e)
        }
    }

    fun decode(opusData: ByteArray): ByteArray {
        if (decoderHandle == 0L) {
            Log.e(TAG, "Decoder not initialized")
            return ByteArray(0)
        }

        return try {
            nativeDecode(decoderHandle, opusData) ?: ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            ByteArray(0)
        }
    }

    fun release() {
        if (decoderHandle != 0L) {
            nativeRelease(decoderHandle)
            decoderHandle = 0
            Log.d(TAG, "Opus decoder released")
        }
    }

    // Native methods
    private external fun nativeInit(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(handle: Long, data: ByteArray): ByteArray?
    private external fun nativeRelease(handle: Long)
}
