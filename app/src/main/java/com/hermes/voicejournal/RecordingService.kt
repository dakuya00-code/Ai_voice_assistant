package com.hermes.voicejournal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadClient = UploadClient()

    private var activeJob: Job? = null
    private var isRunning = false
    private var currentSessionId: String = ""
    private var currentSegmentIndex = 0
    private var monitorRecorder: AudioRecord? = null
    private var config: RecordingConfig? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStart() {
        if (isRunning) return
        config = Prefs.load(this)
        currentSessionId = buildSessionId(config?.sessionLabel ?: "workday")
        currentSegmentIndex = 0
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("음성 대기 중 · 말하면 자동 녹음"))

        activeJob = serviceScope.launch {
            runLoop()
        }
    }

    private fun handleStop() {
        isRunning = false
        stopMonitorRecorder()
        stopMeetingRecorder()
        updateNotification("정지 중")
    }

    private fun withinWorkHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= WORK_START_HOUR && hour < WORK_END_HOUR
    }

    private fun workEndAt(nowMs: Long = System.currentTimeMillis()): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, WORK_END_HOUR)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private suspend fun runLoop() {
        try {
            while (isRunning && activeJob?.isActive == true) {
                if (!withinWorkHours()) {
                    updateNotification("업무 시간 대기 중 · 07:00~20:00")
                    delay(60_000)
                    continue
                }

                val detector = VoiceActivityDetector()
                listenForSpeech(detector)
            }
        } finally {
            stopMonitorRecorder()
            stopMeetingRecorder()
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun listenForSpeech(detector: VoiceActivityDetector) {
        if (!isRunning) return

        val recorder = createMonitorRecorder() ?: run {
            updateNotification("마이크 준비 실패 · 잠시 후 재시도")
            delay(2_000)
            return
        }

        monitorRecorder = recorder
        val buffer = ShortArray(MONITOR_BUFFER_SAMPLES)

        try {
            runCatching { recorder.startRecording() }
                .getOrElse {
                    updateNotification("음성 감지 시작 실패 · 재시도 중")
                    return
                }

            updateNotification("음성 대기 중 · 말하면 자동 녹음")

            while (isRunning && withinWorkHours()) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val amplitude = averageAmplitude(buffer, read)
                val nowMs = System.currentTimeMillis()
                when (detector.observe(amplitude, nowMs)) {
                    is VoiceActivityEvent.StartRecording -> {
                        stopMonitorRecorder()
                        recordSpeechSegment(detector)
                        return
                    }
                    else -> Unit
                }
            }
        } finally {
            stopMonitorRecorder()
        }
    }

    private suspend fun recordSpeechSegment(detector: VoiceActivityDetector) {
        if (!isRunning) {
            detector.reset()
            return
        }

        val cfg = config ?: Prefs.load(this).also { config = it }
        val startedAtMs = System.currentTimeMillis()
        val file = createSegmentFile()
        writeSegmentMeta(file, currentSessionId, currentSegmentIndex, startedAtMs, 0L)

        val recorder = createSegmentAudioRecorder() ?: run {
            cleanupSegmentArtifacts(file)
            detector.reset()
            updateNotification("녹음기 준비 실패 · 대기 중")
            return
        }

        val chunkStopAtMs = minOf(startedAtMs + MAX_SEGMENT_DURATION_MS, workEndAt(startedAtMs))
        val pcmBuffer = ShortArray(SEGMENT_BUFFER_SAMPLES)
        val pcmOut = java.io.ByteArrayOutputStream()

        try {
            runCatching { recorder.startRecording() }
                .getOrElse {
                    runCatching { recorder.release() }
                    cleanupSegmentArtifacts(file)
                    detector.reset()
                    updateNotification("녹음 시작 실패 · 대기 중")
                    return
                }

            updateNotification("음성 녹음 중 · 침묵 시 자동 종료")

            while (isRunning) {
                val nowMs = System.currentTimeMillis()
                if (!withinWorkHours(nowMs) || nowMs >= chunkStopAtMs) break

                val read = recorder.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val amplitude = averageAmplitude(pcmBuffer, read)
                when (detector.observe(amplitude, nowMs)) {
                    is VoiceActivityEvent.StopRecording -> break
                    else -> Unit
                }

                for (i in 0 until read) {
                    val s = pcmBuffer[i].toInt()
                    pcmOut.write(s and 0xFF)
                    pcmOut.write((s shr 8) and 0xFF)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val pcmBytes = pcmOut.toByteArray()
        writeWavFile(file, pcmBytes, SEGMENT_SAMPLE_RATE, 1, 16)

        val durationSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(1L)
        writeSegmentMeta(file, currentSessionId, currentSegmentIndex, startedAtMs, durationSeconds)

        if (!file.exists() || file.length() < MIN_VALID_FILE_BYTES) {
            cleanupSegmentArtifacts(file)
            detector.reset()
            updateNotification("짧은 녹음은 건너뜀 · 대기 중")
            return
        }

        val analysisText = runCatching {
            VoskTranscriber.transcribeFile(this, file)
        }.getOrNull()?.trim().orEmpty()

        if (analysisText.isNotBlank()) {
            runCatching {
                uploadClient.uploadAnalysisText(
                    serverUrl = cfg.serverUrl,
                    sessionId = currentSessionId,
                    sourceFile = file.name,
                    analyzedText = analysisText,
                )
            }
        }

        updateNotification("전송 중 · 음성 세그먼트 업로드")
        val uploadResult = uploadClient.uploadChunk(
            serverUrl = cfg.serverUrl,
            uploadPath = cfg.uploadPath,
            sessionId = currentSessionId,
            chunkIndex = currentSegmentIndex,
            durationSeconds = durationSeconds,
            startedAtIso = isoNow(startedAtMs),
            file = file,
        )

        if (uploadResult.isSuccess) {
            val result = uploadResult.getOrNull()
            UploadHistoryStore.append(
                this,
                UploadedFileEntry(
                    sessionId = currentSessionId,
                    fileName = file.name,
                    chunkIndex = currentSegmentIndex,
                    durationSeconds = durationSeconds,
                    startedAtIso = isoNow(startedAtMs),
                    uploadedAtIso = isoNow(System.currentTimeMillis()),
                    payloadType = "audio",
                )
            )
            cleanupSegmentArtifacts(file)
            currentSegmentIndex += 1
            updateNotification("전송 완료 · ${result?.savedPath ?: "서버 저장 완료"}")
        } else {
            val reason = uploadResult.exceptionOrNull()?.message?.take(80)
            updateNotification(if (reason.isNullOrBlank()) "업로드 실패 · 파일 보관 중" else "업로드 실패 · $reason")
        }

        detector.reset()
    }

    private fun createMonitorRecorder(): AudioRecord? {
        val sampleRate = MONITOR_SAMPLE_RATE
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null
        val bufferSize = minBufferSize.coerceAtLeast(MONITOR_BUFFER_SAMPLES * 2)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun createSegmentAudioRecorder(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SEGMENT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null
        val bufferSize = minBufferSize.coerceAtLeast(SEGMENT_BUFFER_SAMPLES * 2)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SEGMENT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun writeWavFile(file: File, pcmBytes: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = pcmBytes.size
        val chunkSize = 36 + dataSize

        file.outputStream().use { out ->
            fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun i(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }
            fun s(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }

            w("RIFF")
            i(chunkSize)
            w("WAVE")
            w("fmt ")
            i(16)
            s(1)
            s(channels)
            i(sampleRate)
            i(byteRate)
            s(channels * bitsPerSample / 8)
            s(bitsPerSample)
            w("data")
            i(dataSize)
            out.write(pcmBytes)
        }
    }

    private fun averageAmplitude(samples: ShortArray, read: Int): Int {
        if (read <= 0) return 0
        var sum = 0L
        for (i in 0 until read) {
            sum += kotlin.math.abs(samples[i].toInt())
        }
        return (sum / read).toInt()
    }

    private fun stopMonitorRecorder() {
        val recorder = monitorRecorder ?: return
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        monitorRecorder = null
    }

    private fun stopMeetingRecorder() {
        val recorder = meetingRecorder ?: return
        runCatching { recorder.stop() }
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
        meetingRecorder = null
    }

    private fun createSegmentFile(): File {
        val baseDir = File(cacheDir, "voice-journal/$currentSessionId").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(baseDir, "meeting_${currentSegmentIndex.toString().padStart(4, '0')}_$stamp.wav")
    }

    private fun writeSegmentMeta(
        file: File,
        sessionId: String,
        chunkIndex: Int,
        startedAtMs: Long,
        durationSeconds: Long,
    ) {
        val meta = JSONObject().apply {
            put("session_id", sessionId)
            put("chunk_index", chunkIndex)
            put("duration_seconds", durationSeconds)
            put("started_at", isoNow(startedAtMs))
        }
        File(file.parentFile, "${file.nameWithoutExtension}.json").writeText(meta.toString())
    }

    private fun cleanupSegmentArtifacts(file: File) {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.nameWithoutExtension}.json").delete() }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            pendingFlags()
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            pendingFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notebook_badge)
            .setContentTitle("Ai Voice Assistant")
            .setContentText(content)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "정지", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = NotificationManagerCompat.from(this)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ai Voice Assistant Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun buildSessionId(label: String): String {
        val safeLabel = label.lowercase(Locale.US).replace("[^a-z0-9]+".toRegex(), "-").trim('-')
        return listOfNotNull(safeLabel.ifBlank { null }, UUID.randomUUID().toString().take(8)).joinToString("-")
    }

    private fun isoNow(ms: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))
    }

    override fun onDestroy() {
        stopMonitorRecorder()
        stopMeetingRecorder()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val WORK_START_HOUR = 7
        private const val WORK_END_HOUR = 20
        private const val MAX_SEGMENT_DURATION_MS = 5 * 60 * 1000L
        private const val MIN_VALID_FILE_BYTES = 2_048L
        private const val MONITOR_SAMPLE_RATE = 16_000
        private const val MONITOR_BUFFER_SAMPLES = 1_024
        private const val SEGMENT_SAMPLE_RATE = 16_000
        private const val SEGMENT_BUFFER_SAMPLES = 2_048
        const val ACTION_START = "com.hermes.voicejournal.action.START"
        const val ACTION_STOP = "com.hermes.voicejournal.action.STOP"
        const val CHANNEL_ID = "voice_journal_recording"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
