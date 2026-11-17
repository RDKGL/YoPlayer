package com.yoopen.player.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yoopen.player.data.preferences.UserPreferencesRepository
import com.yoopen.player.di.AppScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily background check that posts a notification once per newly found release.
 * Scheduled to trigger every day at 12:00 PM (noon) local phone time.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val updateManager: AppUpdateManager,
    private val preferences: UserPreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val update = try {
            updateManager.findUpdate()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Timber.w(error, "Background update check failed")
            return Result.retry()
        }
        val available = update as? UpdateStatus.Available ?: return Result.success()
        if (available.requiresReinstall) return Result.success()

        val versionName = available.release.versionName
        if (preferences.getLastNotifiedUpdateVersion() == versionName) return Result.success()
        if (UpdateNotifications.showUpdateAvailable(applicationContext, versionName)) {
            preferences.setLastNotifiedUpdateVersion(versionName)
        }
        return Result.success()
    }
}

/** Keeps the periodic update check in step with the user's auto-check preference. */
@Singleton
class UpdateCheckScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val updateManager: AppUpdateManager,
    @AppScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            updateManager.autoCheckEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    val initialDelayMs = calculateDelayUntil12Noon()
                    val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()

                    workManager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
                } else {
                    workManager.cancelUniqueWork(WORK_NAME)
                }
            }
        }
    }

    companion object {
        const val WORK_NAME = "app_update_check"

        /** Calculates milliseconds until the next 12:00:00 (midday) local phone time. */
        fun calculateDelayUntil12Noon(nowMillis: Long = System.currentTimeMillis()): Long {
            val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
