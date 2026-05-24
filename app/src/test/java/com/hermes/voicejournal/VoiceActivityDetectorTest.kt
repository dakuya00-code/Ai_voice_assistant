package com.hermes.voicejournal

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceActivityDetectorTest {
    @Test
    fun voice_activity_starts_recording_after_two_loud_samples() {
        val detector = VoiceActivityDetector(
            startThreshold = 100,
            requiredStartSamples = 2,
            silenceTimeoutMs = 3_000,
            minRecordingDurationMs = 1_000,
            stopThreshold = 60,
        )

        assertEquals(VoiceActivityEvent.None, detector.observe(20, 0))
        assertEquals(VoiceActivityEvent.None, detector.observe(120, 100))
        assertEquals(VoiceActivityEvent.StartRecording, detector.observe(130, 200))
    }

    @Test
    fun voice_activity_stops_after_silence_timeout_during_recording() {
        val detector = VoiceActivityDetector(
            startThreshold = 100,
            requiredStartSamples = 2,
            silenceTimeoutMs = 2_000,
            minRecordingDurationMs = 1_000,
            stopThreshold = 60,
        )

        detector.observe(20, 0)
        detector.observe(120, 100)
        assertEquals(VoiceActivityEvent.StartRecording, detector.observe(140, 200))

        assertEquals(VoiceActivityEvent.None, detector.observe(10, 300))
        assertEquals(VoiceActivityEvent.None, detector.observe(10, 1_000))
        assertEquals(VoiceActivityEvent.None, detector.observe(10, 2_100))
        assertEquals(VoiceActivityEvent.StopRecording, detector.observe(10, 2_300))
    }
}
