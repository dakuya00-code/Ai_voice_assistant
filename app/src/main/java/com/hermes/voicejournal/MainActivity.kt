package com.hermes.voicejournal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var serverUrlInput: EditText
    private lateinit var uploadPathInput: EditText
    private lateinit var chunkMinutesInput: EditText
    private lateinit var sessionLabelInput: EditText
    private lateinit var statusText: TextView

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

        serverUrlInput = findViewById(R.id.serverUrlInput)
        uploadPathInput = findViewById(R.id.uploadPathInput)
        chunkMinutesInput = findViewById(R.id.chunkMinutesInput)
        sessionLabelInput = findViewById(R.id.sessionLabelInput)
        statusText = findViewById(R.id.statusText)

        val startButton: MaterialButton = findViewById(R.id.startButton)
        val stopButton: MaterialButton = findViewById(R.id.stopButton)

        val config = Prefs.load(this)
        serverUrlInput.setText(config.serverUrl)
        uploadPathInput.setText(config.uploadPath)
        chunkMinutesInput.setText(config.chunkMinutes.toString())
        sessionLabelInput.setText(config.sessionLabel)

        ensurePermissions()

        startButton.setOnClickListener {
            val cfg = readConfig()
            Prefs.save(this, cfg)
            RecordingService.start(this)
            statusText.text = "녹음 시작됨 · ${cfg.chunkMinutes}분마다 업로드"
            toast("녹음을 시작했습니다.")
        }

        stopButton.setOnClickListener {
            RecordingService.stop(this)
            statusText.text = "정지 요청됨"
            toast("녹음을 정지했습니다.")
        }
    }

    private fun readConfig(): RecordingConfig {
        return RecordingConfig(
            serverUrl = serverUrlInput.text?.toString().orEmpty().trim(),
            uploadPath = uploadPathInput.text?.toString().orEmpty().trim().ifBlank { "/api/upload" },
            chunkMinutes = chunkMinutesInput.text?.toString().orEmpty().trim().toIntOrNull()?.coerceAtLeast(1) ?: 60,
            sessionLabel = sessionLabelInput.text?.toString().orEmpty().trim().ifBlank { "workday" },
        )
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
