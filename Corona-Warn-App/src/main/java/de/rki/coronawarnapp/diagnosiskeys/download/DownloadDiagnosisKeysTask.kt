package de.rki.coronawarnapp.diagnosiskeys.download

import de.rki.coronawarnapp.appconfig.AppConfigProvider
import de.rki.coronawarnapp.appconfig.ExposureDetectionConfig
import de.rki.coronawarnapp.diagnosiskeys.server.LocationCode
import de.rki.coronawarnapp.environment.EnvironmentSetup
import de.rki.coronawarnapp.nearby.ENFClient
import de.rki.coronawarnapp.nearby.InternalExposureNotificationClient
import de.rki.coronawarnapp.nearby.modules.detectiontracker.TrackedExposureDetection
import de.rki.coronawarnapp.risk.RollbackItem
import de.rki.coronawarnapp.storage.LocalData
import de.rki.coronawarnapp.task.Task
import de.rki.coronawarnapp.task.TaskCancellationException
import de.rki.coronawarnapp.task.TaskFactory
import de.rki.coronawarnapp.task.TaskFactory.Config.CollisionBehavior
import de.rki.coronawarnapp.util.TimeStamper
import de.rki.coronawarnapp.util.ui.toLazyString
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import org.joda.time.Duration
import org.joda.time.Instant
import timber.log.Timber
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

class DownloadDiagnosisKeysTask @Inject constructor(
    private val enfClient: ENFClient,
    private val environmentSetup: EnvironmentSetup,
    private val appConfigProvider: AppConfigProvider,
    private val keyPackageSyncTool: KeyPackageSyncTool,
    private val timeStamper: TimeStamper
) : Task<DownloadDiagnosisKeysTask.Progress, Task.Result> {

    private val internalProgress = ConflatedBroadcastChannel<Progress>()
    override val progress: Flow<Progress> = internalProgress.asFlow()

    private var isCanceled = false

    @Suppress("LongMethod")
    override suspend fun run(arguments: Task.Arguments): Task.Result {
        val rollbackItems = mutableListOf<RollbackItem>()
        try {
            Timber.d("Running with arguments=%s", arguments)
            arguments as Arguments

            /**
             * Handles the case when the ENClient got disabled but the Task is still scheduled
             * in a background job. Also it acts as a failure catch in case the orchestration code did
             * not check in before.
             */

            if (!InternalExposureNotificationClient.asyncIsEnabled()) {
                Timber.tag(TAG).w("EN is not enabled, skipping RetrieveDiagnosisKeys")
                return object : Task.Result {}
            }

            throwIfCancelled()
            val currentDate = Date(timeStamper.nowUTC.millis)
            Timber.tag(TAG).d("Using $currentDate as current date in task.")

            throwIfCancelled()

            // RETRIEVE RISK SCORE PARAMETERS
            val exposureConfig: ExposureDetectionConfig = appConfigProvider.getAppConfig()

            internalProgress.send(Progress.ApiSubmissionStarted)
            internalProgress.send(Progress.KeyFilesDownloadStarted)

            val requestedCountries = arguments.requestedCountries
            val keySyncResult = getAvailableKeyFiles(requestedCountries)
            throwIfCancelled()

            val trackedExposureDetections = enfClient.latestTrackedExposureDetection().first()
            val now = timeStamper.nowUTC

            if (exposureConfig.maxExposureDetectionsPerUTCDay == 0) {
                Timber.tag(TAG).w("Exposure detections are disabled! maxExposureDetectionsPerUTCDay=0")
                return object : Task.Result {}
            }

            if (wasLastDetectionPerformedRecently(now, exposureConfig, trackedExposureDetections)) {
                // At most one detection every 6h
                Timber.tag(TAG).i("task aborted, because detection was performed recently")
                return object : Task.Result {}
            }

            if (hasRecentDetectionAndNoNewFiles(now, keySyncResult, trackedExposureDetections)) {
                Timber.tag(TAG).i("task aborted, last check was within 24h, and there are no new files")
                return object : Task.Result {}
            }

            val availableKeyFiles = keySyncResult.availableKeys.map { it.path }
            val totalFileSize = availableKeyFiles.fold(0L, { acc, file -> file.length() + acc })

            internalProgress.send(
                Progress.KeyFilesDownloadFinished(
                    availableKeyFiles.size,
                    totalFileSize
                )
            )
            val token = retrieveToken(rollbackItems)
            Timber.tag(TAG).d("Attempting submission to ENF with token $token")

            val isSubmissionSuccessful = enfClient.provideDiagnosisKeys(
                keyFiles = availableKeyFiles,
                configuration = exposureConfig.exposureDetectionConfiguration,
                token = token
            )
            Timber.tag(TAG).d("Diagnosis Keys provided (success=%s, token=%s)", isSubmissionSuccessful, token)

            // EXPOSUREAPP-3878 write timestamp immediately after submission,
            // so that progress observers can rely on a clean app state
            if (isSubmissionSuccessful) {
                saveTimestamp(currentDate, rollbackItems)
            }

            internalProgress.send(Progress.ApiSubmissionFinished)

            return object : Task.Result {}
        } catch (error: Exception) {
            Timber.tag(TAG).e(error)

            rollback(rollbackItems)

            throw error
        } finally {
            Timber.i("Finished (isCanceled=$isCanceled).")
            internalProgress.close()
        }
    }

    private fun wasLastDetectionPerformedRecently(
        now: Instant,
        exposureConfig: ExposureDetectionConfig,
        trackedDetections: Collection<TrackedExposureDetection>
    ): Boolean {
        val lastDetection = trackedDetections.maxByOrNull { it.startedAt }
        val nextDetectionAt = lastDetection?.startedAt?.plus(exposureConfig.minTimeBetweenDetections)

        return (nextDetectionAt != null && now.isBefore(nextDetectionAt)).also {
            if (it) Timber.tag(TAG).w("Aborting. Last detection is recent: %s (now=%s)", lastDetection, now)
        }
    }

    private fun hasRecentDetectionAndNoNewFiles(
        now: Instant,
        keySyncResult: KeyPackageSyncTool.Result,
        trackedDetections: Collection<TrackedExposureDetection>
    ): Boolean {
        // One forced detection every 24h, ignoring the sync results
        val lastSuccessfulDetection = trackedDetections.filter { it.isSuccessful }.maxByOrNull { it.startedAt }
        val nextForcedDetectionAt = lastSuccessfulDetection?.startedAt?.plus(Duration.standardDays(1))

        val hasRecentDetection = nextForcedDetectionAt != null && now.isBefore(nextForcedDetectionAt)

        return (hasRecentDetection && keySyncResult.newKeys.isEmpty()).also {
            if (it) Timber.tag(TAG).w("Aborting. Last detection is recent (<24h) and no new keyfiles.")
        }
    }

    private fun saveTimestamp(
        currentDate: Date,
        rollbackItems: MutableList<RollbackItem>
    ) {
        val lastFetchDateForRollback = LocalData.lastTimeDiagnosisKeysFromServerFetch()
        rollbackItems.add {
            LocalData.lastTimeDiagnosisKeysFromServerFetch(lastFetchDateForRollback)
        }
        Timber.tag(TAG).d("dateUpdate(currentDate=%s)", currentDate)
        LocalData.lastTimeDiagnosisKeysFromServerFetch(currentDate)
    }

    private fun retrieveToken(rollbackItems: MutableList<RollbackItem>): String {
        val googleAPITokenForRollback = LocalData.googleApiToken()
        rollbackItems.add {
            LocalData.googleApiToken(googleAPITokenForRollback)
        }
        return UUID.randomUUID().toString().also {
            Timber.tag(TAG).d("Generating and storing new token: $it")
            LocalData.googleApiToken(it)
        }
    }

    private fun rollback(rollbackItems: MutableList<RollbackItem>) {
        try {
            Timber.tag(TAG).d("Initiate Rollback")
            for (rollbackItem: RollbackItem in rollbackItems) rollbackItem.invoke()
        } catch (rollbackException: Exception) {
            Timber.tag(TAG).e(rollbackException, "Rollback failed.")
        }
    }

    private suspend fun getAvailableKeyFiles(requestedCountries: List<String>?): KeyPackageSyncTool.Result {
        val wantedLocations = if (environmentSetup.useEuropeKeyPackageFiles) {
            listOf("EUR")
        } else {
            requestedCountries ?: appConfigProvider.getAppConfig().supportedCountries
        }.map { LocationCode(it) }

        return keyPackageSyncTool.syncKeyFiles(wantedLocations)
    }

    private fun throwIfCancelled() {
        if (isCanceled) throw TaskCancellationException()
    }

    override suspend fun cancel() {
        Timber.w("cancel() called.")
        isCanceled = true
    }

    sealed class Progress : Task.Progress {
        object ApiSubmissionStarted : Progress()
        object ApiSubmissionFinished : Progress()

        object KeyFilesDownloadStarted : Progress()
        data class KeyFilesDownloadFinished(val keyCount: Int, val fileSize: Long) : Progress()

        override val primaryMessage = this::class.java.simpleName.toLazyString()
    }

    class Arguments(
        val requestedCountries: List<String>? = null
    ) : Task.Arguments

    data class Config(
        override val executionTimeout: Duration = Duration.standardMinutes(8), // TODO unit-test that not > 9 min

        override val collisionBehavior: CollisionBehavior = CollisionBehavior.SKIP_IF_SIBLING_RUNNING

    ) : TaskFactory.Config

    class Factory @Inject constructor(
        private val taskByDagger: Provider<DownloadDiagnosisKeysTask>,
        private val appConfigProvider: AppConfigProvider
    ) : TaskFactory<Progress, Task.Result> {

        override suspend fun createConfig(): TaskFactory.Config = Config(
            executionTimeout = appConfigProvider.getAppConfig().overallDownloadTimeout
        )

        override val taskProvider: () -> Task<Progress, Task.Result> = {
            taskByDagger.get()
        }
    }

    companion object {
        private val TAG: String? = DownloadDiagnosisKeysTask::class.simpleName
    }
}
