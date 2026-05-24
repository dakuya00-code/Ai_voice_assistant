package com.hermes.voicejournal

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
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
    private lateinit var statusText: TextView
    private lateinit var configSummaryText: TextView
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
            statusText.text = "정지 요청됨"
            toast("음성 감지를 정지했습니다.")
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
        serverUrlInput.setText(current.serverUrl)
        uploadPathInput.setText(current.uploadPath)
        silenceTimeoutInput.setText(current.silenceTimeoutSeconds.toString())
        sessionLabelInput.setText(current.sessionLabel)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (firstRun) getString(R.string.first_run_title) else getString(R.string.settings_title))
            .setMessage(if (firstRun) "처음 한 번만 설정하면 됩니다. 이후에는 메뉴에서 수정할 수 있어요." else null)
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

    private fun beginVoiceMonitoring() {
        val cfg = Prefs.load(this)
        RecordingService.start(this)
        statusText.text = "음성 감지 대기 중 · 무음 ${cfg.silenceTimeoutSeconds}초 후 종료"
        toast("음성 감지를 시작했습니다.")
    }

    private fun refreshConfigSummary() {
        val config = Prefs.load(this)
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
