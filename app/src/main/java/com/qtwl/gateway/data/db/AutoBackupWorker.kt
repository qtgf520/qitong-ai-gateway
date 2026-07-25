package com.qtwl.gateway.data.db

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Calendar

/**
 * WorkManager 定时自动备份 Worker
 * 每24小时执行一次，自动备份到 Downloads/QiTongGateway/
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "定时备份开始...")
            val db = com.qtwl.gateway.data.db.AppDatabase.getInstance(applicationContext)
            val manager = BackupManager(db)

            val dir = manager.getBackupDir()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(dir, "auto_backup_$timestamp.qtbk")

            val result = manager.exportToFile(file)
            if (result.isSuccess) {
                Log.i(TAG, "定时备份成功: ${file.absolutePath}")
                // 清理旧备份（保留最近7份）
                manager.cleanupOldBackups(7)
                Result.success()
            } else {
                Log.e(TAG, "定时备份失败: ${result.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "定时备份异常", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val WORK_NAME = "auto_backup"

        /**
         * 调度定时备份
         * @param hour 每日几点执行（0-23）
         * @param minute 每日几分执行（0-59）
         */
        fun schedule(context: Context, hour: Int = 3, minute: Int = 0) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                24, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(hour, minute), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            Log.i(TAG, "定时备份已调度: 每日 ${hour}:${minute.toString().padStart(2, '0')}")
        }

        /**
         * 取消定时备份
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "定时备份已取消")
        }

        private fun calculateInitialDelay(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            if (target.before(now)) target.add(Calendar.DAY_OF_MONTH, 1)
            return target.timeInMillis - now.timeInMillis
        }
    }
}