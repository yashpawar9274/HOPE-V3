package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.license.LicenseManager
import com.google.firebase.FirebaseException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal object MemorySyncPolicy {
    fun turnJobId(ownerUid: String, turnId: String): String = "turn:$ownerUid:$turnId"

    fun shouldProcess(operation: String, cloudEnabled: Boolean): Boolean =
        cloudEnabled || operation == MemorySyncJob.OPERATION_CLEAR_ALL

    fun isRetryableHttpCode(httpCode: Int?): Boolean =
        httpCode == null || httpCode == 408 || httpCode == 429 || httpCode >= 500
}

internal object MemorySyncScheduler {
    private const val WORK_NAME_PREFIX = "hope_memory_sync_"
    private const val KEY_OWNER_UID = "owner_uid"

    fun enqueue(context: Context, ownerUid: String) {
        require(ownerUid.isNotBlank()) { "A memory sync owner is required." }
        val request = OneTimeWorkRequestBuilder<MemorySyncWorker>()
            .setInputData(Data.Builder().putString(KEY_OWNER_UID, ownerUid).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(ownerUid),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun workName(ownerUid: String): String = "$WORK_NAME_PREFIX$ownerUid"
    internal const val INPUT_OWNER_UID = KEY_OWNER_UID
}

class MemorySyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ownerUid = inputData.getString(MemorySyncScheduler.INPUT_OWNER_UID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val preferences = AppPreferences(applicationContext)
        val currentUid = LicenseManager.currentUser(applicationContext)?.uid
            ?: return Result.success()
        if (currentUid != ownerUid) return Result.success()

        val database = MemoryDatabase.getInstance(applicationContext)
        val dao = database.memorySyncDao()
        val cloudRepository = CloudMemoryRepository(applicationContext)
        val cloudEnabled = preferences.isCloudMemoryEnabled && preferences.cloudMemoryOwnerId == ownerUid
        val jobs = dao.pendingJobs(ownerUid, cloudEnabled, MAX_JOBS_PER_RUN)

        for (job in jobs) {
            if (dao.getJob(job.id) == null) continue
            val isCurrentCloudEnabled =
                preferences.isCloudMemoryEnabled && preferences.cloudMemoryOwnerId == ownerUid
            if (!MemorySyncPolicy.shouldProcess(job.operation, isCurrentCloudEnabled)) {
                return Result.success()
            }
            if (LicenseManager.currentUser(applicationContext)?.uid != ownerUid) {
                return Result.success()
            }
            dao.markAttempt(job.id)
            try {
                cloudRepository.processOutboxJob(job)
                dao.delete(job.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CloudMemoryException) {
                val retryable = MemorySyncPolicy.isRetryableHttpCode(error.httpCode)
                dao.markFailure(job.id, error.message.orEmpty().take(MAX_ERROR_LENGTH), !retryable)
                if (retryable) {
                    Log.w(TAG, "Cloud memory sync will retry (HTTP ${error.httpCode ?: "network"}).")
                    return Result.retry()
                }
                Log.e(TAG, "Cloud memory sync job was rejected and needs manual retry.", error)
            } catch (error: FirebaseException) {
                dao.markFailure(job.id, error.message ?: "Firebase authentication failed.", false)
                Log.w(TAG, "Cloud memory sync will retry after Firebase authentication recovers.", error)
                return Result.retry()
            } catch (error: Exception) {
                dao.markFailure(job.id, error.message ?: "Unexpected sync error.", true)
                Log.e(TAG, "Cloud memory sync failed unexpectedly.", error)
            }
        }

        val cloudEnabledNow =
            preferences.isCloudMemoryEnabled && preferences.cloudMemoryOwnerId == ownerUid
        if (dao.pendingJobs(ownerUid, cloudEnabledNow, 1).isNotEmpty()) {
            return try {
                MemorySyncScheduler.enqueue(applicationContext, ownerUid)
                Result.success()
            } catch (error: Exception) {
                Log.e(TAG, "Could not schedule the next local memory sync batch.", error)
                Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val MAX_JOBS_PER_RUN = 25
        private const val MAX_ERROR_LENGTH = 240
    }
}
