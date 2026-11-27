package com.network.messenger.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.VoiceMode
import com.network.messenger.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class EncodedVoiceChunk(
    val streamId: String,
    val seq: Int,
    val isFinal: Boolean,
    val codec: String,
    val sampleRate: Int,
    val bitrateBps: Int,
    val bytes: ByteArray
)

/**
 * Push-to-Talk voice engine supporting Opus 16 kbps (via Android MediaCodec on API 29+)
 * with graceful fallback to AAC-LC 16 kbps / compact µ-law PCM on devices lacking an Opus encoder.
 * Handles Android AudioFocus (loss, transient loss, ducking) and live-stream vs clip modes.
 */
@Singleton
class AudioEngine @Inject constructor(
    private val context: Context,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) {
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var recordJob: Job? = null
    private var activeTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                logRepository.warn(LogCategory.VOICE, "Audio focus lost — stopping voice playback")
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                logRepository.warn(LogCategory.VOICE, "Audio focus transiently lost — pausing playback")
                runCatching { activeTrack?.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logRepository.info(LogCategory.VOICE, "Audio focus ducked (25% volume)")
                runCatching { activeTrack?.setVolume(0.25f) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching {
                    activeTrack?.setVolume(1.0f)
                    if (activeTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                        activeTrack?.play()
                    }
                }
            }
        }
    }

    fun selectBestCodec(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_OPUS)) {
            return CODEC_OPUS
        }
        if (hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_AAC)) {
            return CODEC_AAC
        }
        return CODEC_ULAW
    }

    @SuppressLint("MissingPermission")
    fun startPttRecording(
        mode: VoiceMode,
        onChunkReady: suspend (EncodedVoiceChunk) -> Unit
    ) {
        if (_isRecording.value) return
        if (!PermissionHelper.hasMicrophonePermission(context)) {
            logRepository.error(LogCategory.PERMISSION, "Cannot start PTT: RECORD_AUDIO permission denied")
            return
        }

        val codecName = selectBestCodec()
        val streamId = UUID.randomUUID().toString().substring(0, 8)
        _isRecording.value = true

        logRepository.info(
            LogCategory.VOICE,
            "PTT recording started: stream=$streamId mode=${mode.name} codec=$codecName (${BITRATE_BPS / 1000} kbps)"
        )

        recordJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: Exception) {
                logRepository.error(LogCategory.VOICE, "AudioRecord init failed: ${e.message}")
                _isRecording.value = false
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                logRepository.error(LogCategory.VOICE, "AudioRecord not initialized (mic busy or unavailable)")
                runCatching { recorder.release() }
                _isRecording.value = false
                return@launch
            }

            val pcmBuffer = ByteArray(FRAME_BYTES) // 200ms @ 16kHz 16-bit mono = 6400 bytes
            val clipAccumulator = ByteArrayOutputStream()
            var seq = 0

            try {
                recorder.startRecording()
                while (isActive && _isRecording.value) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        val slice = pcmBuffer.copyOf(read)
                        if (mode == VoiceMode.LIVE_STREAM) {
                            val (actualCodec, encoded) = encodePcm(slice, codecName)
                            if (encoded.isNotEmpty()) {
                                seq++
                                onChunkReady(
                                    EncodedVoiceChunk(
                                        streamId = streamId,
                                        seq = seq,
                                        isFinal = false,
                                        codec = actualCodec,
                                        sampleRate = SAMPLE_RATE,
                                        bitrateBps = BITRATE_BPS,
                                        bytes = encoded
                                    )
                                )
                            }
                        } else {
                            clipAccumulator.write(slice)
                            if (clipAccumulator.size() >= MAX_CLIP_PCM_BYTES) {
                                logRepository.warn(LogCategory.VOICE, "Voice clip reached max duration; finalizing")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logRepository.error(LogCategory.VOICE, "PTT capture error: ${e.message}")
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                _isRecording.value = false

                if (mode == VoiceMode.VOICE_CLIP) {
                    val rawPcm = clipAccumulator.toByteArray()
                    if (rawPcm.isNotEmpty()) {
                        val (actualCodec, encoded) = encodePcm(rawPcm, codecName)
                        logRepository.info(
                            LogCategory.VOICE,
                            "PTT clip encoded: stream=$streamId codec=$actualCodec raw=${rawPcm.size}B encoded=${encoded.size}B"
                        )
                        onChunkReady(
                            EncodedVoiceChunk(
                                streamId = streamId,
                                seq = 1,
                                isFinal = true,
                                codec = actualCodec,
                                sampleRate = SAMPLE_RATE,
                                bitrateBps = BITRATE_BPS,
                                bytes = encoded
                            )
                        )
                    } else {
                        logRepository.warn(LogCategory.VOICE, "PTT released immediately (empty clip ignored)")
                    }
                } else {
                    logRepository.info(LogCategory.VOICE, "PTT live stream ended: stream=$streamId chunks=$seq")
                }
            }
        }
    }

    fun stopPttRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
    }

    fun playIncomingVoice(
        senderId: String,
        streamId: String,
        codec: String,
        sampleRate: Int,
        encodedBytes: ByteArray
    ) {
        scope.launch(Dispatchers.IO) {
            if (!requestAudioFocus()) {
                logRepository.warn(
                    LogCategory.VOICE,
                    "Skipped voice playback stream=$streamId from ${CryptoManager.shortId(senderId)}: audio focus denied"
                )
                return@launch
            }

            val pcm = decodeToPcm(encodedBytes, codec, sampleRate)
            if (pcm.isEmpty()) {
                logRepository.warn(LogCategory.VOICE, "Voice decode produced 0 bytes (codec=$codec)")
                abandonAudioFocus()
                return@launch
            }

            _isPlaying.value = true
            logRepository.info(
                LogCategory.VOICE,
                "Playing incoming voice: stream=$streamId from=${CryptoManager.shortId(senderId)} codec=$codec size=${encodedBytes.size}B"
            )

            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(pcm.size)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                activeTrack = track
                track.write(pcm, 0, pcm.size)
                track.play()
                val durationMs = ((pcm.size / 2L) * 1000L) / sampleRate.coerceAtLeast(8000)
                kotlinx.coroutines.delay(durationMs + 80L)
                runCatching {
                    track.stop()
                    track.release()
                }
                if (activeTrack === track) {
                    activeTrack = null
                }
            } catch (e: Exception) {
                logRepository.error(LogCategory.VOICE, "Voice playback error: ${e.message}")
            } finally {
                _isPlaying.value = false
                abandonAudioFocus()
            }
        }
    }

    fun stopPlayback() {
        runCatching {
            activeTrack?.stop()
            activeTrack?.release()
        }
        activeTrack = null
        _isPlaying.value = false
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun hasEncoderForMime(mime: String): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun encodePcm(pcm: ByteArray, preferredCodec: String): Pair<String, ByteArray> {
        if (preferredCodec == CODEC_OPUS || preferredCodec == CODEC_AAC) {
            val mime = if (preferredCodec == CODEC_OPUS) {
                MediaFormat.MIMETYPE_AUDIO_OPUS
            } else {
                MediaFormat.MIMETYPE_AUDIO_AAC
            }
            val encoded = runMediaCodecEncode(pcm, mime)
            if (encoded != null && encoded.isNotEmpty()) {
                return preferredCodec to encoded
            }
        }
        // Compact 8-bit µ-law fallback (2:1 compression) when hardware/software MediaCodec is absent
        return CODEC_ULAW to encodeMuLaw(pcm)
    }

    private fun decodeToPcm(encoded: ByteArray, codec: String, sampleRate: Int): ByteArray {
        return when (codec) {
            CODEC_OPUS -> runMediaCodecDecode(encoded, MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate)
                ?: decodeMuLaw(encoded)
            CODEC_AAC -> runMediaCodecDecode(encoded, MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate)
                ?: decodeMuLaw(encoded)
            else -> decodeMuLaw(encoded)
        }
    }

    private fun runMediaCodecEncode(pcm: ByteArray, mime: String): ByteArray? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createEncoderByType(mime)
            val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val outStream = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputOffset = 0
            var eosSignaled = false

            while (true) {
                if (!eosSignaled) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (inputOffset < pcm.size) {
                            val chunk = minOf(inBuf.remaining(), pcm.size - inputOffset)
                            inBuf.put(pcm, inputOffset, chunk)
                            val ptsUs = (inputOffset / 2L) * 1_000_000L / SAMPLE_RATE
                            inputOffset += chunk
                            codec.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSignaled = true
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val packet = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(packet)
                        // Length-prefix each encoded frame for clean decoding
                        val lenHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(packet.size).array()
                        outStream.write(lenHeader)
                        outStream.write(packet)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && eosSignaled) {
                    break
                }
            }
            outStream.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
        }
    }

    private fun runMediaCodecDecode(framedBytes: ByteArray, mime: String, sampleRate: Int): ByteArray? {
        var codec: MediaCodec? = null
        return try {
            val frames = unpackLengthPrefixedFrames(framedBytes)
            if (frames.isEmpty()) return null

            codec = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createAudioFormat(mime, sampleRate, 1)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var frameIdx = 0
            var eosSignaled = false

            while (true) {
                if (!eosSignaled) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (frameIdx < frames.size) {
                            val data = frames[frameIdx]
                            inBuf.put(data, 0, minOf(data.size, inBuf.remaining()))
                            codec.queueInputBuffer(inIdx, 0, data.size, frameIdx * 20_000L, 0)
                            frameIdx++
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSignaled = true
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && eosSignaled) {
                    break
                }
            }
            pcmOut.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
        }
    }

    private fun unpackLengthPrefixedFrames(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 4) {
            val len = buf.int
            if (len <= 0 || len > buf.remaining()) break
            val frame = ByteArray(len)
            buf.get(frame)
            result.add(frame)
        }
        return result
    }

    private fun encodeMuLaw(pcm16: ByteArray): ByteArray {
        val sampleCount = pcm16.size / 2
        val out = ByteArray(sampleCount)
        val buf = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            out[i] = linearToMuLaw(buf.short.toInt())
        }
        return out
    }

    private fun decodeMuLaw(ulaw: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(ulaw.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (b in ulaw) {
            out.putShort(muLawToLinear(b))
        }
        return out.array()
    }

    private fun linearToMuLaw(sampleInput: Int): Byte {
        var sample = sampleInput
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > 32635) sample = 32635
        sample += 132
        var exponent = 7
        var expMask = 0x4000
        while (exponent > 0 && (sample and expMask) == 0) {
            exponent--
            expMask = expMask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun muLawToLinear(ulawByte: Byte): Short {
        val ulaw = ulawByte.toInt().inv() and 0xFF
        val sign = ulaw and 0x80
        val exponent = (ulaw shr 4) and 0x07
        val mantissa = ulaw and 0x0F
        var sample = ((mantissa shl 3) + 132) shl exponent
        sample -= 132
        return (if (sign != 0) -sample else sample).toShort()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BITRATE_BPS = 16_000
        private const val FRAME_BYTES = 6_400 // 200ms @ 16kHz 16-bit mono
        private const val MAX_CLIP_PCM_BYTES = SAMPLE_RATE * 2 * 12 // 12 seconds max clip
        const val CODEC_OPUS = "opus-16k"
        const val CODEC_AAC = "aac-16k"
        const val CODEC_ULAW = "ulaw-8k"
    }
}
