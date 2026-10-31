package com.alfa.launcher

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Tells the linked NoCap server "this phone is still here" every 15 minutes (battery level and
 * app version). A 401/403 means the server unlinked the phone, so the link is forgotten.
 */
class HeartbeatWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val NAME = "nocap-heartbeat"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(NAME)
        }

        fun battery(ctx: Context): Int =
            ctx.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
    }

    override fun doWork(): Result {
        val link = NoCapLink.get(applicationContext) ?: run { cancel(applicationContext); return Result.success() }
        return try {
            NoCapApi.heartbeat(link, battery(applicationContext), Updater.currentName(applicationContext))
            Result.success()
        } catch (e: NoCapApi.Unlinked) {
            NoCapLink.clear(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // offline / server down: the next 15-minute run tries again
            Result.success()
        }
    }
}
