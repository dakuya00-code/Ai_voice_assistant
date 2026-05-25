package com.hermes.voicejournal

class VoiceActivityDetector(
    private val startThreshold: Int = 800,
    private val requiredStartSamples: Int = 3,
    private val silenceTimeoutMs: Long = 2_500,
    private val minRecordingDurationMs: Long = 1_200,
    private val stopThreshold: Int = 500,
) {
    private enum class Mode { Listening, Recording }

    private var mode: Mode = Mode.Listening
    private var voiceStreak = 0
    private var recordingStartedAtMs: Long = 0L
    private var silenceStartedAtMs: Long? = null

    fun observe(amplitude: Int, nowMs: Long): VoiceActivityEvent {
        return when (mode) {
            Mode.Listening -> observeListening(amplitude, nowMs)
            Mode.Recording -> observeRecording(amplitude, nowMs)
        }
    }

    fun reset() {
        mode = Mode.Listening
        voiceStreak = 0
        recordingStartedAtMs = 0L
        silenceStartedAtMs = null
    }

    private fun observeListening(amplitude: Int, nowMs: Long): VoiceActivityEvent {
        if (amplitude >= startThreshold) {
            voiceStreak += 1
            if (voiceStreak >= requiredStartSamples) {
                mode = Mode.Recording
                recordingStartedAtMs = nowMs
                silenceStartedAtMs = null
                voiceStreak = 0
                return VoiceActivityEvent.StartRecording
            }
        } else {
            voiceStreak = 0
        }
        return VoiceActivityEvent.None
    }

    private fun observeRecording(amplitude: Int, nowMs: Long): VoiceActivityEvent {
        if (amplitude >= stopThreshold) {
            silenceStartedAtMs = null
            return VoiceActivityEvent.None
        }

        if (silenceStartedAtMs == null) {
            silenceStartedAtMs = nowMs
            return VoiceActivityEvent.None
        }

        val recordingDuration = nowMs - recordingStartedAtMs
        val silenceDuration = nowMs - silenceStartedAtMs!!
        if (recordingDuration >= minRecordingDurationMs && silenceDuration >= silenceTimeoutMs) {
            reset()
            return VoiceActivityEvent.StopRecording
        }
        return VoiceActivityEvent.None
    }
}

sealed class VoiceActivityEvent {
    object None : VoiceActivityEvent()
    object StartRecording : VoiceActivityEvent()
    object StopRecording : VoiceActivityEvent()
}
