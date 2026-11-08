package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

enum class RecorderState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPED
}

class AudioRecorderManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var amplitudeJob: Job? = null
    private var pcmJob: Job? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null

    private var currentOutputFile: File? = null
    private var startTimeMs = 0L
    private var totalPausedDurationMs = 0L
    private var pauseStartTimeMs = 0L

    private val _recorderState = MutableStateFlow(RecorderState.IDLE)
    val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

    private val _currentDurationMs = MutableStateFlow(0L)
    val currentDurationMs: StateFlow<Long> = _currentDurationMs.asStateFlow()

    private val _currentAmplitude = MutableStateFlow(0f)
    val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

    private val _waveformAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val waveformAmplitudes: StateFlow<List<Float>> = _waveformAmplitudes.asStateFlow()

    var gainMultiplier: Float = 1.0f

    fun startRecording(format: String = "M4A"): File {
        stopRecording()

        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val extension = when (format.uppercase()) {
            "WAV" -> "wav"
            "MP3" -> "mp3"
            else -> "m4a"
        }
        val file = File(recordingsDir, "voxflow_${System.currentTimeMillis()}.$extension")
        currentOutputFile = file

        startTimeMs = System.currentTimeMillis()
        totalPausedDurationMs = 0L
        _waveformAmplitudes.value = emptyList()

        if (format.uppercase() == "WAV") {
            startPcmWavRecording(file)
        } else {
            startMediaRecorder(file)
        }

        _recorderState.value = RecorderState.RECORDING
        startPollingDurationAndAmplitude()
        return file
    }

    private fun startMediaRecorder(file: File) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192000) // High-fidelity 192 kbps
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start MediaRecorder", e)
            recorder.release()
            throw e
        }
    }

    private fun startPcmWavRecording(file: File) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            audioRecord = record
            record.startRecording()

            pcmJob = scope.launch {
                val tempPcmFile = File(file.parentFile, "${file.nameWithoutExtension}.pcm")
                val buffer = ShortArray(bufferSize / 2)
                FileOutputStream(tempPcmFile).use { fos ->
                    while (isActive && _recorderState.value != RecorderState.STOPPED) {
                        if (_recorderState.value == RecorderState.RECORDING) {
                            val read = record.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                var maxAmp = 0
                                val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until read) {
                                    val sample = (buffer[i] * gainMultiplier).toInt().coerceIn(-32768, 32767).toShort()
                                    byteBuffer.putShort(sample)
                                    val absSample = abs(sample.toInt())
                                    if (absSample > maxAmp) maxAmp = absSample
                                }
                                fos.write(byteBuffer.array())
                                val normalizedAmp = (maxAmp / 32767f).coerceIn(0f, 1f)
                                _currentAmplitude.value = normalizedAmp
                            }
                        } else {
                            delay(50)
                        }
                    }
                }
                // Convert raw PCM to proper WAV header file
                rawPcmToWav(tempPcmFile, file, sampleRate, 1, 16)
                tempPcmFile.delete()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start AudioRecord for WAV", e)
            throw e
        }
    }

    private fun rawPcmToWav(rawFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val rawData = rawFile.readBytes()
        val totalAudioLen = rawData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        FileOutputStream(wavFile).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte() // RIFF/WAVE header
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xffL).toByte()
            header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
            header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
            header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte() // 'fmt ' chunk
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // 4 bytes: size of 'fmt ' chunk
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // format = 1 (PCM)
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xffL).toByte()
            header[29] = ((byteRate shr 8) and 0xffL).toByte()
            header[30] = ((byteRate shr 16) and 0xffL).toByte()
            header[31] = ((byteRate shr 24) and 0xffL).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte() // block align
            header[33] = 0
            header[34] = bitsPerSample.toByte() // bits per sample
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xffL).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()
            out.write(header, 0, 44)
            out.write(rawData)
        }
    }

    private fun startPollingDurationAndAmplitude() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isActive && _recorderState.value != RecorderState.STOPPED) {
                if (_recorderState.value == RecorderState.RECORDING) {
                    val elapsed = System.currentTimeMillis() - startTimeMs - totalPausedDurationMs
                    _currentDurationMs.value = elapsed.coerceAtLeast(0L)

                    if (mediaRecorder != null) {
                        val maxAmp = try {
                            mediaRecorder?.maxAmplitude ?: 0
                        } catch (e: Exception) {
                            0
                        }
                        val normalized = ((maxAmp / 32767f) * gainMultiplier).coerceIn(0.05f, 1.0f)
                        _currentAmplitude.value = normalized

                        val currentList = _waveformAmplitudes.value.toMutableList()
                        currentList.add(normalized)
                        if (currentList.size > 80) currentList.removeAt(0)
                        _waveformAmplitudes.value = currentList
                    } else if (audioRecord != null) {
                        val currentList = _waveformAmplitudes.value.toMutableList()
                        currentList.add(_currentAmplitude.value)
                        if (currentList.size > 80) currentList.removeAt(0)
                        _waveformAmplitudes.value = currentList
                    }
                }
                delay(80)
            }
        }
    }

    fun pauseRecording() {
        if (_recorderState.value != RecorderState.RECORDING) return
        pauseStartTimeMs = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error pausing MediaRecorder", e)
        }
        _recorderState.value = RecorderState.PAUSED
    }

    fun resumeRecording() {
        if (_recorderState.value != RecorderState.PAUSED) return
        val pausedDuration = System.currentTimeMillis() - pauseStartTimeMs
        totalPausedDurationMs += pausedDuration
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error resuming MediaRecorder", e)
        }
        _recorderState.value = RecorderState.RECORDING
    }

    fun stopRecording(): File? {
        val file = currentOutputFile
        _recorderState.value = RecorderState.STOPPED
        amplitudeJob?.cancel()
        amplitudeJob = null

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }

        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        _recorderState.value = RecorderState.IDLE
        _currentAmplitude.value = 0f
        return file
    }
}
