package com.andrew.proxyapp.manager

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.RuleSetManager
import java.util.concurrent.TimeUnit

class RuleSetUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val update = RuleSetManager.updateAll(applicationContext)
        return update.fold(
            onSuccess = {
                ConfigStore.get(applicationContext).updateSettings {
                    settings -> settings.lastRuleSetCheck = System.currentTimeMillis()
                }
                Result.success()
            },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
        )
    }

    companion object {
        private const val UNIQUE_NAME = "weekly-rule-set-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RuleSetUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
