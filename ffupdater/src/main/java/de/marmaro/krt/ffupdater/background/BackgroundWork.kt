package de.marmaro.krt.ffupdater.background

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.ExistingWorkPolicy.KEEP
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.marmaro.krt.ffupdater.FFUpdater.Companion.LOG_TAG
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.background.PeriodicWorkMethodResult.Companion.neverRetry
import de.marmaro.krt.ffupdater.background.PeriodicWorkMethodResult.Companion.retryRegularTimeSlot
import de.marmaro.krt.ffupdater.background.PeriodicWorkMethodResult.Companion.retrySoon
import de.marmaro.krt.ffupdater.background.PeriodicWorkMethodResult.Companion.success
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.device.InstalledAppsCache
import de.marmaro.krt.ffupdater.device.PowerSaveModeReceiver
import de.marmaro.krt.ffupdater.device.PowerUtil
import de.marmaro.krt.ffupdater.network.NetworkUtil
import de.marmaro.krt.ffupdater.network.NetworkUtil.isNetworkMetered
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.FileDownloader
import de.marmaro.krt.ffupdater.notification.NotificationBuilder.showErrorNotification
import de.marmaro.krt.ffupdater.notification.NotificationBuilder.showNetworkErrorNotification
import de.marmaro.krt.ffupdater.notification.NotificationRemover
import de.marmaro.krt.ffupdater.settings.BackgroundSettings
import de.marmaro.krt.ffupdater.settings.DataStoreHelper
import de.marmaro.krt.ffupdater.settings.ForegroundSettings
import de.marmaro.krt.ffupdater.utils.WorkManagerTiming.calcBackoffTime
import de.marmaro.krt.ffupdater.utils.WorkManagerTiming.getRetriesForTotalBackoffTime
import java.time.Duration
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS

/**
 * BackgroundJob will be regularly called by the AndroidX WorkManager to:
 * - check for app updates
 * - download them
 * - install them
 *
 * Depending on the device and the settings from the user not all steps will be executed.
 */
@Keep
class BackgroundWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context.applicationContext, workerParams) {

    /**
     * Execute the logic for update checking, downloading and installation.
     *
     * If an common exception (like CancellationException, GithubRateLimitExceededException or
     * NetworkException) occurs, then retry it later.
     * If after 24 retries (five days) the background check still fails, then show a notification and
     * retry in the next regular execution interval.
     *
     * If an uncommon exception (anything else) occurs, then show a notification and retry in the next
     * regular execution interval.
     *
     * I can't return Result.error() because even if an unknown exception occurs, I want that BackgroundJob
     * is still regularly executed.
     * But Result.error() will remove BackgroundJob from the WorkManager job schedule.
     *
     * Getting cancelled after 10 minutes.
     */
    @MainThread
    override suspend fun doWork(): Result {
        try {
            Log.i(LOG_TAG, "BackgroundWork: Execute background job.")
            val result = internalDoWork()
            Log.i(LOG_TAG, "BackgroundWork: Finish.")
            return result
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Log.w(LOG_TAG, "BackgroundWork: Job failed. Restart in ${calcBackoffTime(runAttemptCount)}.", e)
                return Result.retry()
            }

            val backgroundException = BackgroundException(e)
            Log.e(LOG_TAG, "BackgroundWorker: Job failed.", backgroundException)
            when (e) {
                is NetworkException -> showNetworkErrorNotification(applicationContext, backgroundException)
                else -> showErrorNotification(applicationContext, backgroundException)
            }
            return Result.success() // BackgroundJob should not be removed from WorkManager schedule
        }
    }

    private fun storeBackgroundJobExecutionTime() {
        DataStoreHelper.lastBackgroundCheck2 = System.currentTimeMillis()
    }

    private fun areRunRequirementsMet(): PeriodicWorkMethodResult {
        if (PowerUtil.isBatteryLow()) {
            return retryRegularTimeSlot("BackgroundJob: Skip because battery is low.")
        }
        if (NetworkUtil.isDataSaverEnabled(applicationContext)) {
            return retrySoon("BackgroundJob: Skip due to data saver.")
        }
        if (!BackgroundSettings.isUpdateCheckOnMeteredAllowed && isNetworkMetered(applicationContext)) {
            return retrySoon("BackgroundJob: Skip because network is metered.")
        }
        if (BackgroundSettings.isUpdateCheckOnlyAllowedWhenDeviceIsIdle && PowerUtil.isDeviceInteractive()) {
            return retrySoon("BackgroundJob: Skip because device is not idle.")
        }
        if (PowerSaveModeReceiver.isPowerSaveModeEnabledForShortTime()) {
            return retryRegularTimeSlot("BackgroundJob: Skip because power save mode was enabled recently")
        }
        return success()
    }

    @Suppress("IfThenToElvis")
    @MainThread
    private suspend fun internalDoWork(): Result {
        storeBackgroundJobExecutionTime()
        areRunRequirementsMet().onFailure { return it }

        NotificationRemover.removeDownloadErrorNotification(applicationContext)
        NotificationRemover.removeAppStatusNotifications(applicationContext)

        checkUpdateCheckAllowed().onFailure { return@internalDoWork it }
        val outdatedApps = findOutdatedApps().sortedBy { it.installationChronology }

        // enqueue all work requests for update check
        val workManager = WorkManager.getInstance(applicationContext)
        var lastWorkRequest: WorkContinuation? = null
        for (app in outdatedApps) {
            lastWorkRequest = if (lastWorkRequest == null) {
                workManager.beginUniqueWork(DOWNLOADER_INSTALLER_KEY, KEEP, AppUpdater.createWorkRequest(app))
            } else {
                lastWorkRequest.then(AppUpdater.createWorkRequest(app))
            }
        }
        lastWorkRequest?.enqueue()

        return Result.success()
    }

    private fun checkUpdateCheckAllowed(): PeriodicWorkMethodResult {
        return when {
            !BackgroundSettings.isUpdateCheckEnabled -> neverRetry("BackgroundWork: Background should be disabled - disable it now.")

            FileDownloader.areDownloadsCurrentlyRunning() -> retrySoon("BackgroundWork: Retry background job because other downloads are running.")

            !BackgroundSettings.isUpdateCheckOnMeteredAllowed && isNetworkMetered(applicationContext) -> retrySoon("BackgroundWork: No unmetered network available for update check.")

            else -> success()
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private suspend fun findOutdatedApps(): List<App> {
        InstalledAppsCache.updateCache(applicationContext)
        val appsToCheck = InstalledAppsCache
                .getInstalledAppsWithCorrectFingerprint(applicationContext)
                .filter { it !in BackgroundSettings.excludedAppsFromUpdateCheck }
                .filter { it !in ForegroundSettings.hiddenApps }
                .filter { DeviceAbiExtractor.supportsOneOf(it.findImpl().supportedAbis) }
                .map { it.findImpl() }
                .filter { !it.wasInstalledByOtherApp(applicationContext) }
                .filter { FileDownloader.isUrlAvailable(it.hostnameForInternetCheck) }
            .map { it.findStatusOrUseRecentCache(applicationContext) }

        val outdatedApps = appsToCheck
                .filter { it.isUpdateAvailable }
                .map { it.app }

        // delete old cached APK files
        appsToCheck.forEach {
            it.app
                    .findImpl()
                    .deleteFileCacheExceptLatest(applicationContext, it.latestVersion)
        }

        Log.d(LOG_TAG, "BackgroundWork: [${outdatedApps.joinToString(",")}] are outdated.")
        return outdatedApps
    }

    companion object {
        private const val CHECK_FOR_UPDATES_KEY = "update_checker"
        private const val DOWNLOADER_INSTALLER_KEY = "ffupdater_downloader_and_installer"
        private val MAX_RETRIES = getRetriesForTotalBackoffTime(Duration.ofHours(8))

        fun start(context: Context) {
            Log.i(LOG_TAG, "BackgroundWork: Start BackgroundWork")
            internalStart(context.applicationContext, UPDATE)
        }

        fun forceRestart(context: Context) {
            Log.i(LOG_TAG, "BackgroundWork: Force restart BackgroundWork")
            internalStart(context.applicationContext, CANCEL_AND_REENQUEUE)
        }

        private fun internalStart(
            context: Context,
            policy: ExistingPeriodicWorkPolicy,
            initialDelay: Duration = Duration.ZERO,
        ) {
            val instance = WorkManager.getInstance(context.applicationContext)
            if (!BackgroundSettings.isUpdateCheckEnabled) {
                instance.cancelUniqueWork(CHECK_FOR_UPDATES_KEY)
                return
            }

            val minutes = BackgroundSettings.updateCheckInterval.toMinutes()
            val workRequest = PeriodicWorkRequest
                    .Builder(BackgroundWork::class.java, minutes, MINUTES)
                    .setInitialDelay(initialDelay.seconds, SECONDS)
                    .build()
            instance.enqueueUniquePeriodicWork(CHECK_FOR_UPDATES_KEY, policy, workRequest)
        }

        fun isBackgroundUpdateCheckReliableExecuted(): Boolean {
            if (!BackgroundSettings.isUpdateCheckEnabled) {
                return true
            }
            val lastExecutionTime = DataStoreHelper.lastBackgroundCheck2
            // background job was not yet executed -> skip check
            if (lastExecutionTime == 0L) {
                return true
            }

            val intervalSettings = BackgroundSettings.updateCheckInterval
            val maxRetryInterval = Duration.ofHours(5)
            val interval = if (intervalSettings > maxRetryInterval) intervalSettings else maxRetryInterval
            val intervalWithErrorMargin = interval + Duration.ofHours(24)

            val timeSinceExecution = Duration.ofMillis(System.currentTimeMillis() - lastExecutionTime)
            return timeSinceExecution < intervalWithErrorMargin
        }
    }
}