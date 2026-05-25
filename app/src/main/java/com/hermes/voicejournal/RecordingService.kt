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
            while (isRunning) {
                if (!withinWorkHours()) {
                    updateNotification("업무 시간 대기 중 · 07:00~20:00")
                    delay(60_000)
                    continue
                }
                recordTimedChunk()
                if (isRunning) {
                    delay(1_000)
                }
            }
        } finally {
            stopMeetingRecorder()
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun recordTimedChunk() {
        val cfg = config ?: return
        val startedAtMs = System.currentTimeMillis()
        val chunkStopAtMs = minOf(startedAtMs + MAX_SEGMENT_DURATION_MS, workEndAt(startedAtMs))
        if (chunkStopAtMs <= startedAtMs) return

        val file = createSegmentFile()
        val recorder = createMeetingRecorder(file) ?: return
        meetingRecorder = recorder

        try {
            recorder.start()
            updateNotification("회의 녹음 중 · 1시간 단위 업로드")
            while (isRunning) {
                val nowMs = System.currentTimeMillis()
                if (!withinWorkHours(nowMs) || nowMs >= chunkStopAtMs) {
                    break
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

        updateNotification("전송 중 · 1시간 녹음 업로드")
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
                )
            )
            file.delete()
            currentSegmentIndex += 1
            if (isRunning) {
                updateNotification("다음 1시간 녹음 대기 중")
            }
            if (result != null && (result.savedPath != null || result.transcriptPath != null)) {
                updateNotification("전송 완료 · ${result.savedPath ?: "저장 경로 확인"}")
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
        private const val WORK_START_HOUR = 7
        private const val WORK_END_HOUR = 20
        private const val MAX_SEGMENT_DURATION_MS = 60 * 60 * 1000L
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
