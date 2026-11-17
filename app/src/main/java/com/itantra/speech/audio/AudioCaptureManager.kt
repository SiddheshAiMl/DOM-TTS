package com.itantra.speech.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Arrays

/**
 * Captures 16 kHz, 16-bit Mono PCM audio locally.
 * Transient memory buffers are securely zeroed out immediately after processing.
 */
class AudioCaptureManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_SAMPLES = 480 // 30 ms chunk at 16 kHz
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _rmsEnergy = MutableStateFlow(0f)
    val rmsEnergy: StateFlow<Float> = _rmsEnergy.asStateFlow()

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startCapture(onChunkCaptured: (ShortArray) -> Unit) {
        if (!hasRecordPermission() || _isRecording.value) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_SAMPLES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioRecord?.startRecording()
        _isRecording.value = true

        recordingJob = scope.launch {
            val chunk = ShortArray(CHUNK_SIZE_SAMPLES)
            while (isActive && _isRecording.value) {
                val readSamples = audioRecord?.read(chunk, 0, CHUNK_SIZE_SAMPLES) ?: -1
                if (readSamples > 0) {
                    // Calculate RMS energy level
                    var sum = 0.0
                    for (i in 0 until readSamples) {
                        sum += chunk[i] * chunk[i]
                    }
                    val rms = kotlin.math.sqrt(sum / readSamples).toFloat()
                    _rmsEnergy.value = (rms / 32768f).coerceIn(0f, 1f)

                    onChunkCaptured(chunk.copyOf(readSamples))

                    // Privacy Hygiene: Zero out temporary chunk immediately after passing to callback
                    Arrays.fill(chunk, 0.toShort())
                }
            }
        }
    }

    fun stopCapture() {
        _isRecording.value = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        _rmsEnergy.value = 0f
    }
}
