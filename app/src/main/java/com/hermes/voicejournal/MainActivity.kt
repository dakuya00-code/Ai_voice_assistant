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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    companion object {
        private const val UPDATE_URL = "https://github.com/dakuya00-code/Ai_voice_assistant/releases"
    }

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

        stopButton.setOnClickListener {
            hapticStop(stopButton)
            RecordingService.stop(this)
            statusText.text = "감지 종료 요청됨"
            toast("음성 감지를 종료했습니다. 진행 중인 조각이 있으면 업로드 후 멈춥니다.")
        }

        ensurePermissions()
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
        val silenceTimeoutLayout = view.findViewById<TextInputLayout>(R.id.silenceTimeoutLayout)
        val sessionLabelLayout = view.findViewById<TextInputLayout>(R.id.sessionLabelLayout)
        val serverUrlInput = view.findViewById<TextInputEditText>(R.id.serverUrlInput)
        val uploadPathInput = view.findViewById<TextInputEditText>(R.id.uploadPathInput)
        val silenceTimeoutInput = view.findViewById<TextInputEditText>(R.id.silenceTimeoutInput)
        val sessionLabelInput = view.findViewById<TextInputEditText>(R.id.sessionLabelInput)

        val current = Prefs.load(this)
        serverUrlInput.setText(current.serverUrl.ifBlank { "http://187.77.115.121:8799" })
        uploadPathInput.setText(current.uploadPath.ifBlank { "/api/upload" })
        silenceTimeoutInput.setText(current.silenceTimeoutSeconds.takeIf { it > 0 }?.toString() ?: "15")
        sessionLabelInput.setText(current.sessionLabel.ifBlank { "workday" })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (firstRun) getString(R.string.first_run_title) else getString(R.string.settings_title))
            .setMessage(if (firstRun) "처음 실행할 때는 기본값이 미리 들어가 있으니, 서버 주소만 확인하고 바로 저장하시면 됩니다." else null)
            .setView(view)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                serverUrlLayout.error = null
                uploadPathLayout.error = null
                silenceTimeoutLayout.error = null
                sessionLabelLayout.error = null

                val cfg = RecordingConfig(
                    serverUrl = serverUrlInput.text?.toString().orEmpty().trim(),
                    uploadPath = uploadPathInput.text?.toString().orEmpty().trim().ifBlank { "/api/upload" },
                    silenceTimeoutSeconds = silenceTimeoutInput.text?.toString().orEmpty().trim().toIntOrNull()?.coerceIn(5, 120) ?: 15,
                    sessionLabel = sessionLabelInput.text?.toString().orEmpty().trim().ifBlank { "workday" },
                )

                if (cfg.serverUrl.isBlank()) {
                    serverUrlLayout.error = "서버 URL을 입력해 주세요."
                    return@setOnClickListener
                }

                Prefs.save(this, cfg)
                Prefs.setSetupComplete(this, true)
                refreshConfigSummary()
                statusText.text = "설정 저장됨 · 무음 ${cfg.silenceTimeoutSeconds}초 후 종료"
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
            appendLine("업로드 히스토리")
            if (entries.isEmpty()) {
                appendLine("- 아직 업로드된 파일이 없습니다.")
            } else {
                entries.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. ${entry.fileName}")
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
            .setPositiveButton("닫기", null)
            .show()
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

    private fun showInstallGuideDialog() {
        val message = buildString {
            appendLine("설치/세팅 안내")
            appendLine()
            appendLine("1. 앱 설치 후 실행")
            appendLine("2. 첫 실행 설정에서 VPS 주소 입력")
            appendLine("3. 업로드 경로는 /api/upload 유지")
            appendLine("4. 무음 종료 시간은 15초 권장")
            appendLine("5. 시작 버튼 = 음성감지 시작")
            appendLine("6. 중지 버튼 = 감지 완전 종료(진행 중 조각 업로드 후 종료)")
            appendLine("7. 권한(마이크/알림) 허용")
            appendLine("8. 삼성 배터리 최적화에서 제외하면 안정적")
            appendLine("9. 업데이트는 오른쪽 위 메뉴의 '업데이트 확인'에서 확인")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_guide_title)
            .setMessage(message)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("업데이트 페이지를 열 수 없습니다.")
        }
    }

    private fun listLocalRecordingFiles(): List<java.io.File> {
        val root = java.io.File(cacheDir, "voice-journal")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private fun beginVoiceMonitoring() {
        val cfg = Prefs.load(this)
        RecordingService.start(this)
        statusText.text = "음성감지 대기 중 · 한 번 켜면 계속 감지합니다 · 사람이 말하면 자동 녹음 · 무음 ${cfg.silenceTimeoutSeconds}초 후 업로드"
        toast("음성감지를 시작했습니다. 사람 목소리를 감지하면 자동으로 녹음합니다.")
    }

    private fun refreshConfigSummary() {
        val config = Prefs.load(this)
        val usage = "시작: 음성감지를 켭니다. 한 번 켜면 사용자가 중지할 때까지 계속 감지합니다. 사람이 말하면 자동으로 녹음하고, 무음 ${config.silenceTimeoutSeconds}초가 지속되면 그 조각을 업로드한 뒤 다시 대기합니다.\n중지: 음성감지를 완전히 종료합니다. 진행 중인 조각이 있으면 마무리 후 업로드하고 멈춥니다."
        recordingUsageText.text = usage
        val summary = buildString {
            appendLine("현재 설정")
            appendLine("- 서버 URL: ${config.serverUrl}")
            appendLine("- 업로드 경로: ${config.uploadPath}")
            appendLine("- 무음 종료 시간: ${config.silenceTimeoutSeconds}초")
            appendLine("- 세션 이름: ${config.sessionLabel}")
            appendLine()
            appendLine(if (Prefs.isSetupComplete(this@MainActivity)) "설정 완료 · 메뉴에서 다시 수정할 수 있습니다." else "초기 설정이 필요합니다. 오른쪽 위 메뉴에서도 다시 열 수 있습니다.")
        }
        configSummaryText.text = summary
        if (statusText.text.isNullOrBlank() || statusText.text.toString() == "대기 중") {
            statusText.text = if (Prefs.isSetupComplete(this)) "음성 감지 대기 중" else "초기 설정 필요"
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
