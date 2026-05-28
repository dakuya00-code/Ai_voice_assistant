package com.hermes.voicejournal

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        private const val UPDATE_URL = "https://github.com/dakuya00-code/Ai_voice_assistant/releases"
    }

    private val uploadClient = UploadClient()

    private lateinit var statusText: TextView
    private lateinit var configSummaryText: TextView
    private lateinit var recordingUsageText: TextView
    private var pendingStartAfterSetup = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
        val notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        if (!audioGranted || !notifGranted) {
            toast("권한이 필요합니다. 마이크/알림 권한을 허용해 주세요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        configSummaryText = findViewById(R.id.configSummaryText)
        recordingUsageText = findViewById(R.id.recordingUsageText)

        val startButton: MaterialButton = findViewById(R.id.startButton)
        val stopButton: MaterialButton = findViewById(R.id.stopButton)
        val manualUploadButton: MaterialButton = findViewById(R.id.manualUploadButton)

        startButton.setOnClickListener {
            if (!Prefs.isSetupComplete(this)) {
                pendingStartAfterSetup = true
                hapticSuccess(startButton)
                showSettingsDialog(firstRun = true)
                return@setOnClickListener
            }
            hapticSuccess(startButton)
            beginVoiceMonitoring()
        }

        manualUploadButton.setOnClickListener {
            hapticSuccess(manualUploadButton)
            startManualUpload()
        }

        stopButton.setOnClickListener {
            hapticStop(stopButton)
            RecordingService.stop(this)
            statusText.text = "감지 종료 요청됨"
            toast("음성 감지를 종료했습니다. 진행 중인 조각이 있으면 업로드 후 멈춥니다.")
        }

        ensurePermissions()
        ensureVoskModelReady()
        refreshConfigSummary()

        if (!Prefs.isSetupComplete(this)) {
            window.decorView.post {
                showSettingsDialog(firstRun = true)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                showSettingsDialog(firstRun = false)
                true
            }
            R.id.menu_saved_files -> {
                showSavedFilesDialog()
                true
            }
            R.id.menu_check_update -> {
                showUpdateDialog()
                true
            }
            R.id.menu_install_guide -> {
                showInstallGuideDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog(firstRun: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_recording_settings, null, false)
        val serverUrlLayout = view.findViewById<TextInputLayout>(R.id.serverUrlLayout)
        val uploadPathLayout = view.findViewById<TextInputLayout>(R.id.uploadPathLayout)
        val sessionLabelLayout = view.findViewById<TextInputLayout>(R.id.sessionLabelLayout)
        val serverUrlInput = view.findViewById<TextInputEditText>(R.id.serverUrlInput)
        val uploadPathInput = view.findViewById<TextInputEditText>(R.id.uploadPathInput)
        val sessionLabelInput = view.findViewById<TextInputEditText>(R.id.sessionLabelInput)

        val current = Prefs.load(this)
        serverUrlInput.setText(current.serverUrl.ifBlank { "https://3394dc7db4303708-187-77-115-121.serveousercontent.com" })
        uploadPathInput.setText(current.uploadPath.ifBlank { "/api/upload" })
        sessionLabelInput.setText(current.sessionLabel.ifBlank { "workday" })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (firstRun) getString(R.string.first_run_title) else getString(R.string.settings_title))
            .setMessage(if (firstRun) "처음 실행할 때는 서버 주소만 확인하고 바로 저장하시면 됩니다. 녹음은 07:00~20:00에만 동작하고, 음성이 감지되면 자동으로 세그먼트를 업로드합니다." else null)
            .setView(view)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                serverUrlLayout.error = null
                uploadPathLayout.error = null
                sessionLabelLayout.error = null

                val cfg = RecordingConfig(
                    serverUrl = serverUrlInput.text?.toString().orEmpty().trim(),
                    uploadPath = uploadPathInput.text?.toString().orEmpty().trim().ifBlank { "/api/upload" },
                    sessionLabel = sessionLabelInput.text?.toString().orEmpty().trim().ifBlank { "workday" },
                )

                if (cfg.serverUrl.isBlank()) {
                    serverUrlLayout.error = "서버 URL을 입력해 주세요."
                    return@setOnClickListener
                }

                Prefs.save(this, cfg)
                Prefs.setSetupComplete(this, true)
                refreshConfigSummary()
                statusText.text = "설정 저장됨 · VAD 자동 녹음 / 07:00~20:00"
                toast("설정을 저장했습니다.")
                dialog.dismiss()

                if (pendingStartAfterSetup) {
                    pendingStartAfterSetup = false
                    beginVoiceMonitoring()
                }
            }
        }

        dialog.show()
    }

    private fun showSavedFilesDialog() {
        val entries = UploadHistoryStore.readAll(this).takeLast(20).asReversed()
        val pendingFiles = listLocalRecordingFiles().takeLast(20).asReversed()
        val pendingTextFiles = listLocalAnalysisTextFiles().takeLast(20).asReversed()
        val message = buildString {
            appendLine("로컬에 남아 있는 음성파일")
            if (pendingFiles.isEmpty()) {
                appendLine("- 없음")
            } else {
                pendingFiles.forEachIndexed { index, file ->
                    appendLine("${index + 1}. ${file.name}")
                }
            }
            appendLine()
            appendLine("로컬에 남아 있는 분석 텍스트")
            if (pendingTextFiles.isEmpty()) {
                appendLine("- 없음")
            } else {
                pendingTextFiles.forEachIndexed { index, file ->
                    appendLine("${index + 1}. ${file.name}")
                }
            }
            appendLine()
            appendLine("업로드 히스토리")
            if (entries.isEmpty()) {
                appendLine("- 아직 업로드된 파일이 없습니다.")
            } else {
                entries.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. [${entry.payloadType}] ${entry.fileName}")
                    appendLine("   세션: ${entry.sessionId}")
                    appendLine("   길이: ${entry.durationSeconds}초")
                    appendLine("   시작: ${entry.startedAtIso}")
                    appendLine("   업로드: ${entry.uploadedAtIso}")
                    appendLine()
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_files_title)
            .setMessage(message)
            .setNeutralButton("로그 삭제") { _, _ ->
                UploadHistoryStore.clear(this)
                toast("업로드 히스토리를 삭제했습니다.")
                refreshConfigSummary()
            }
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun startManualUpload() {
        val pendingFiles = listLocalRecordingFiles().sortedBy { it.lastModified() }
        if (pendingFiles.isEmpty()) {
            toast("수동 업로드할 음성파일이 없습니다.")
            return
        }

        statusText.text = "수동 업로드 준비 중 · ${pendingFiles.size}개"
        lifecycleScope.launch {
            val config = Prefs.load(this@MainActivity)
            var successCount = 0
            var failureCount = 0
            val failedNames = mutableListOf<String>()
            val failureReasons = mutableListOf<String>()

            for (file in pendingFiles) {
                val meta = readPendingUploadMeta(file)
                val result = uploadClient.uploadChunk(
                    serverUrl = config.serverUrl,
                    uploadPath = config.uploadPath,
                    sessionId = meta.sessionId,
                    chunkIndex = meta.chunkIndex,
                    durationSeconds = meta.durationSeconds,
                    startedAtIso = meta.startedAtIso,
                    file = file,
                )

                if (result.isSuccess) {
                    val uploadInfo = result.getOrNull()
                    UploadHistoryStore.append(
                        this@MainActivity,
                        UploadedFileEntry(
                            sessionId = meta.sessionId,
                            fileName = file.name,
                            chunkIndex = meta.chunkIndex,
                            durationSeconds = meta.durationSeconds,
                            startedAtIso = meta.startedAtIso,
                            uploadedAtIso = java.time.Instant.now().toString(),
                            payloadType = "audio",
                        )
                    )
                    runCatching { file.delete() }
                    runCatching { File(file.parentFile, "${file.nameWithoutExtension}.json").delete() }
                    successCount += 1
                    uploadInfo?.let {
                        logUploadDestination(file.name, it.savedPath, it.transcriptPath, it.audioDeleted, it.audioDeleteError)
                    }
                } else {
                    failureCount += 1
                    failedNames += file.name
                    val reason = result.exceptionOrNull()?.message?.take(80) ?: "unknown"
                    failureReasons += "${file.name}: ${reason}"
                }
            }

            val textUploadCount = uploadPendingTextAnalyses(config)

            refreshConfigSummary()
            statusText.text = if (failureCount == 0) {
                "수동 업로드 완료 · 음성 ${successCount}개 / 텍스트 ${textUploadCount}개"
            } else {
                "수동 업로드 일부 실패 · 음성 성공 ${successCount}개 / 실패 ${failureCount}개 / 텍스트 ${textUploadCount}개"
            }
            toast(
                if (failureCount == 0) {
                    "수동 업로드를 완료했습니다."
                } else {
                    "수동 업로드 실패 파일: ${failedNames.joinToString(", ")}"
                }
            )
            if (failureReasons.isNotEmpty()) {
                statusText.text = statusText.text.toString() + "\n" + failureReasons.joinToString(" | ")
            }
        }
    }

    private data class PendingUploadMeta(
        val sessionId: String,
        val chunkIndex: Int,
        val durationSeconds: Long,
        val startedAtIso: String,
    )

    private fun readPendingUploadMeta(file: File): PendingUploadMeta {
        val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (metaFile.exists()) {
            runCatching {
                val json = JSONObject(metaFile.readText())
                return PendingUploadMeta(
                    sessionId = json.optString("session_id").ifBlank { file.parentFile?.name ?: "manual" },
                    chunkIndex = json.optInt("chunk_index", parseChunkIndex(file.name)),
                    durationSeconds = json.optLong("duration_seconds", 1L).coerceAtLeast(1L),
                    startedAtIso = json.optString("started_at").ifBlank { metaTimestamp(file) },
                )
            }
        }

        return PendingUploadMeta(
            sessionId = file.parentFile?.name ?: "manual",
            chunkIndex = parseChunkIndex(file.name),
            durationSeconds = 1L,
            startedAtIso = metaTimestamp(file),
        )
    }

    private fun parseChunkIndex(name: String): Int {
        val match = Regex("meeting_(\\d+)_").find(name)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun metaTimestamp(file: File): String {
        return java.time.Instant.ofEpochMilli(file.lastModified()).toString()
    }

    private fun showUpdateDialog() {
        val currentVersion = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrElse { "unknown" }
        val message = buildString {
            appendLine("현재 설치 버전: v$currentVersion")
            appendLine()
            appendLine("업데이트가 필요하면 아래 버튼으로 GitHub Releases 페이지를 열어 최신 APK를 받으세요.")
            appendLine("설치 후에는 기존 설정이 유지되고, 앱에서 바로 다시 시작할 수 있습니다.")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton("업데이트 페이지 열기") { _, _ ->
                openUrl(UPDATE_URL)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("업데이트 페이지를 열 수 없습니다.")
        }
    }

    private fun showInstallGuideDialog() {
        val message = buildString {
            appendLine("설치/세팅 안내")
            appendLine()
            appendLine("1. 앱 설치 후 실행")
            appendLine("2. 첫 실행 설정에서 VPS 주소 입력")
            appendLine("3. 업로드 경로는 /api/upload 유지")
            appendLine("4. 녹음은 07:00~20:00 사이에만 동작")
            appendLine("5. 음성이 감지되면 자동으로 세그먼트를 업로드")
            appendLine("6. 시작 버튼 = VAD 자동 녹음/업로드 시작")
            appendLine("7. 중지 버튼 = 진행 중인 조각을 마무리한 뒤 완전히 종료")

            appendLine("9. 삼성 배터리 최적화에서 제외하면 안정적")
            appendLine("10. 업데이트는 오른쪽 위 메뉴의 '업데이트 확인'에서 확인")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_guide_title)
            .setMessage(message)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun listLocalRecordingFiles(): List<java.io.File> {
        val root = java.io.File(cacheDir, "voice-journal")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && (it.extension.equals("m4a", ignoreCase = true) || it.extension.equals("wav", ignoreCase = true)) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private fun listLocalAnalysisTextFiles(): List<java.io.File> {
        val root = java.io.File(cacheDir, "voice-journal")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private suspend fun uploadPendingTextAnalyses(config: RecordingConfig): Int {
        val textFiles = listLocalAnalysisTextFiles().sortedBy { it.lastModified() }
        var uploaded = 0
        for (file in textFiles) {
            val sessionId = file.parentFile?.name ?: config.sessionLabel
            val text = runCatching { file.readText() }.getOrDefault("").trim()
            if (text.isBlank()) continue

            val result = uploadClient.uploadAnalysisText(
                serverUrl = config.serverUrl,
                sessionId = sessionId,
                sourceFile = file.name,
                analyzedText = text,
            )
            if (result.isSuccess) {
                UploadHistoryStore.append(
                    this,
                    UploadedFileEntry(
                        sessionId = sessionId,
                        fileName = file.name,
                        chunkIndex = 0,
                        durationSeconds = 0,
                        startedAtIso = java.time.Instant.ofEpochMilli(file.lastModified()).toString(),
                        uploadedAtIso = java.time.Instant.now().toString(),
                        payloadType = "text",
                    )
                )
                runCatching { file.delete() }
                uploaded += 1
            }
        }
        return uploaded
    }

    private fun beginVoiceMonitoring() {
        RecordingService.start(this)
        statusText.text = "VAD 자동 녹음/업로드 시작 · 07:00~20:00에만 동작"
        toast("녹음을 시작했습니다. 음성이 감지되면 자동으로 업로드하고, 07:00~20:00에만 동작합니다.")
    }

    private fun refreshConfigSummary() {
        val config = Prefs.load(this)
        val modelStatus = if (VoskTranscriber.hasModel(this)) "준비됨" else "없음(filesDir/vosk-model)"
        val usage = "시작: 서비스를 켜면 07:00~20:00 동안 음성 감지 기반으로 녹음/업로드합니다.\n중지: 진행 중인 음성 조각을 마무리한 뒤 완전히 종료합니다."
        recordingUsageText.text = usage
        val summary = buildString {
            appendLine("현재 설정")
            appendLine("- 서버 URL: ${config.serverUrl}")
            appendLine("- 업로드 경로: ${config.uploadPath}")
            appendLine("- 세션 이름: ${config.sessionLabel}")
            appendLine("- 녹음 시간: 07:00~20:00")
            appendLine("- 녹음 방식: VAD 자동 세그먼트")
            appendLine("- 전사 키: 서버의 Gemini API 키")
            appendLine("- 모바일 Vosk 모델: ${modelStatus}")
            appendLine()
            appendLine(if (Prefs.isSetupComplete(this@MainActivity)) "설정 완료 · 메뉴에서 다시 수정할 수 있습니다." else "초기 설정이 필요합니다. 오른쪽 위 메뉴에서도 다시 열 수 있습니다.")
        }
        configSummaryText.text = summary
        if (statusText.text.isNullOrBlank() || statusText.text.toString() == "대기 중") {
            statusText.text = if (Prefs.isSetupComplete(this)) "VAD 대기 중" else "초기 설정 필요"
        }
    }

    private fun ensurePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureVoskModelReady() {
        lifecycleScope.launch {
            if (VoskTranscriber.hasModel(this@MainActivity)) return@launch
            statusText.text = "모바일 분석 준비 중 · Vosk 모델 설치"
            val result = withContext(Dispatchers.IO) {
                VoskModelInstaller.ensureInstalled(this@MainActivity)
            }
            result.onSuccess { installedFrom ->
                when (installedFrom) {
                    "installed_from_assets" -> toast("Vosk 모델 설치 완료(앱 내장)")
                    "installed_from_download" -> toast("Vosk 모델 다운로드 설치 완료")
                }
            }.onFailure {
                statusText.text = "모바일 분석 준비 실패 · Vosk 모델 설치 오류"
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Vosk 모델 필요")
                    .setMessage("Vosk 모델 설치에 실패했습니다. 네트워크 연결 상태를 확인하고 앱을 다시 실행해 주세요.")
                    .setPositiveButton("확인", null)
                    .show()
            }
            refreshConfigSummary()
        }
    }

    private fun hapticSuccess(view: android.view.View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        pulse(view, pressedScale = 0.97f, bounceScale = 1.0f, shortDuration = 70L, bounceDuration = 120L)
        vibrate(18)
    }

    private fun hapticStop(view: android.view.View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        pulse(view, pressedScale = 0.94f, bounceScale = 1.0f, shortDuration = 80L, bounceDuration = 140L)
        vibrate(28)
    }

    private fun pulse(view: android.view.View, pressedScale: Float, bounceScale: Float, shortDuration: Long, bounceDuration: Long) {
        view.animate()
            .scaleX(pressedScale)
            .scaleY(pressedScale)
            .setDuration(shortDuration)
            .withEndAction {
                view.animate()
                    .scaleX(bounceScale)
                    .scaleY(bounceScale)
                    .setDuration(bounceDuration)
                    .start()
            }
            .start()
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService<VibratorManager>()
            val vibrator = manager?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService<Vibrator>()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun logUploadDestination(
        fileName: String,
        savedPath: String?,
        transcriptPath: String?,
        audioDeleted: Boolean,
        audioDeleteError: String?,
    ) {
        val details = buildString {
            appendLine("서버 저장 확인: $fileName")
            appendLine("- saved_path: ${savedPath ?: "(없음)"}")
            appendLine("- transcript_path: ${transcriptPath ?: "(없음)"}")
            appendLine("- audio_deleted: $audioDeleted")
            appendLine("- audio_delete_error: ${audioDeleteError ?: "(없음)"}")
        }
        statusText.text = "전송 완료 · 서버 저장 위치 확인"
        configSummaryText.text = configSummaryText.text.toString() + "\n\n" + details
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
