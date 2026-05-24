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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uploadClient = UploadClient()

    private var activeJob: Job? = null
    private var isRunning = false
    private var currentSessionId: String = ""
    private var currentSegmentIndex = 0
    private var monitorRecorder: AudioRecord? = null
    private var meetingRecorder: MediaRecorder? = null
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
        startForeground(NOTIFICATION_ID, buildNotification("음성 감지 대기 중"))

        activeJob = serviceScope.launch {
            runLoop()
        }
    }

    private fun handleStop() {
        isRunning = false
        runCatching { monitorRecorder?.stop() }
        runCatching { meetingRecorder?.stop() }
        updateNotification("정지 중")
    }

    private suspend fun runLoop() {
        val cfg = config ?: return
        val detector = VoiceActivityDetector(
            startThreshold = 1_500,
            requiredStartSamples = 2,
            silenceTimeoutMs = cfg.silenceTimeoutSeconds * 1_000L,
            minRecordingDurationMs = 3_000,
            stopThreshold = 900,
        )

        try {
            while (isRunning) {
                updateNotification("음성 감지 대기 중")
                val detected = monitorForVoice(detector)
                if (!isRunning) break
                if (!detected) {
                    delay(500)
                    continue
                }
                recordMeetingSegment(detector)
            }
        } finally {
            stopMonitorRecorder()
            stopMeetingRecorder()
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun monitorForVoice(detector: VoiceActivityDetector): Boolean {
        val recorder = createMonitorRecorder() ?: return false
        monitorRecorder = recorder
        val buffer = ShortArray(2048)

        return try {
            recorder.startRecording()
            while (isRunning) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val amplitude = averageAmplitude(buffer, read)
                when (detector.observe(amplitude, System.currentTimeMillis())) {
                    VoiceActivityEvent.StartRecording -> return true
                    else -> Unit
                }
            }
            false
        } catch (_: IllegalStateException) {
            false
        } finally {
            stopMonitorRecorder()
        }
    }

    private suspend fun recordMeetingSegment(detector: VoiceActivityDetector) {
        val cfg = config ?: return
        val file = createSegmentFile()
        val recorder = createMeetingRecorder(file) ?: run {
            detector.reset()
            return
        }
        meetingRecorder = recorder
        val startedAtMs = System.currentTimeMillis()

        try {
            recorder.start()
            updateNotification("회의 녹음 중 · 무음 ${cfg.silenceTimeoutSeconds}초 후 종료")
            while (isRunning) {
                val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                when (detector.observe(amplitude, System.currentTimeMillis())) {
                    VoiceActivityEvent.StopRecording -> break
                    else -> Unit
                }
                delay(500)
            }
        } finally {
            stopMeetingRecorder()
        }

        if (!file.exists()) return
        val durationSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(1L)
        if (durationSeconds < 3) {
            file.delete()
            return
        }

        updateNotification("전송 중 · 회의 녹음 업로드")
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
            UploadHistoryStore.append(
                this,
                UploadedFileEntry(
                    sessionId = currentSessionId,
                    fileName = file.name,
                    chunkIndex = currentSegmentIndex,
                    durationSeconds = durationSeconds,
                    startedAtIso = isoNow(startedAtMs),
                    uploadedAtIso = isoNow(System.currentTimeMillis()),
                )
            )
            file.delete()
            currentSegmentIndex += 1
            if (isRunning) {
                updateNotification("음성 감지 대기 중")
            }
        } else {
            updateNotification("업로드 실패 · 파일 보관 중")
        }
    }

    private fun createMonitorRecorder(): AudioRecord? {
        val sampleRate = 16_000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null
        val bufferSize = minBufferSize.coerceAtLeast(4_096)
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

    private fun createMeetingRecorder(file: File): MediaRecorder? {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder
        } catch (_: Exception) {
            runCatching { recorder.release() }
            null
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
        return File(baseDir, "meeting_${currentSegmentIndex.toString().padStart(4, '0')}_$stamp.m4a")
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
