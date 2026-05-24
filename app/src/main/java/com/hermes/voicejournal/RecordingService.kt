package com.hermes.voicejournal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private var recorder: MediaRecorder? = null
    private var isRunning = false
    private var currentSessionId: String = ""
    private var currentChunkIndex = 0
    private var currentRecordingFile: File? = null
    private var currentChunkStartedAtMs: Long = 0L
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
        currentChunkIndex = 0
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("녹음 준비 중"))

        activeJob = serviceScope.launch {
            runLoop()
        }
    }

    private fun handleStop() {
        isRunning = false
        activeJob?.cancel()
        activeJob = null
        stopRecorderSafely()
        stopForeground(true)
        stopSelf()
    }

    private suspend fun runLoop() {
        val cfg = config ?: return
        while (isRunning) {
            val file = createChunkFile()
            currentRecordingFile = file
            currentChunkStartedAtMs = System.currentTimeMillis()

            startRecorder(file)
            updateNotification("기록 중 · 청크 ${currentChunkIndex + 1}")

            delay(cfg.chunkMinutes * 60_000L)

            stopRecorderSafely()
            val durationSeconds = ((System.currentTimeMillis() - currentChunkStartedAtMs) / 1000L).coerceAtLeast(1L)
            updateNotification("전송 중 · 청크 ${currentChunkIndex + 1}")

            val uploadResult = uploadClient.uploadChunk(
                serverUrl = cfg.serverUrl,
                uploadPath = cfg.uploadPath,
                sessionId = currentSessionId,
                chunkIndex = currentChunkIndex,
                durationSeconds = durationSeconds,
                startedAtIso = isoNow(currentChunkStartedAtMs),
                file = file,
            )

            if (uploadResult.isSuccess) {
                file.delete()
                currentChunkIndex += 1
                if (isRunning) {
                    updateNotification("다음 청크 준비 중")
                }
            } else {
                updateNotification("업로드 실패 · 파일 보관 중")
            }
        }
    }

    private fun startRecorder(file: File) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        this.recorder = recorder
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setAudioSamplingRate(44_100)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
    }

    private fun stopRecorderSafely() {
        val current = recorder ?: return
        runCatching { current.stop() }
        runCatching { current.reset() }
        runCatching { current.release() }
        recorder = null
    }

    private fun createChunkFile(): File {
        val baseDir = File(cacheDir, "voice-journal/$currentSessionId").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(baseDir, "chunk_${currentChunkIndex.toString().padStart(4, '0')}_$stamp.m4a")
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
        stopRecorderSafely()
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
