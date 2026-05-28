package com.hermes.voicejournal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class TextUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val config = Prefs.load(applicationContext)
        val client = UploadClient()
        return runCatching {
            TextUploadQueue.uploadPending(applicationContext, config, client)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "hourly-text-upload"

        fun scheduleHourly(context: Context) {
            val delayMs = computeDelayToNextHourMs()
            val request = PeriodicWorkRequestBuilder<TextUploadWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private fun computeDelayToNextHourMs(): Long {
            val now = ZonedDateTime.now()
            val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            return Duration.between(now, nextHour).toMillis().coerceAtLeast(5_000L)
        }
    }
}
