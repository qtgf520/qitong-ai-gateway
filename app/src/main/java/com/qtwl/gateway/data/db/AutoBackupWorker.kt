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
import androidx.work.WorkInfo
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Calendar

/**
 * WorkManager 定时自动备份 Worker
 * 每24小时执行一次，自动备份到 QiTongGateway/backups/
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
         * 调度定时备份 — 宽松约束+防重复
         */
        fun schedule(context: Context, hour: Int = 3, minute: Int = 0) {
            val workManager = WorkManager.getInstance(context)
            // ★ 检查是否已在调度中，避免重复 REPLACE 重置计时器
            try {
                val existing = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                if (existing.isNotEmpty() && existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                    Log.i(TAG, "定时备份已在调度中，跳过重复注册")
                    return
                }
            } catch (_: Exception) { }

            // ★ 放宽约束：不要求网络+不要求电量
            val constraints = Constraints.Builder()
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                24, TimeUnit.HOURS,
                30, TimeUnit.MINUTES  // 弹性窗口30分钟
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(hour, minute), TimeUnit.MILLISECONDS)
                .addTag("qitong_auto_backup")
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // ★ KEEP 不 REPLACE，避免重置计时器
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

        /**
         * 调度一次性测试备份（10秒后执行）
         */
        fun scheduleTest(context: Context) {
            val testRequest = androidx.work.OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInitialDelay(10, java.util.concurrent.TimeUnit.SECONDS)
                .addTag("qitong_backup_test")
                .build()
            WorkManager.getInstance(context).enqueue(testRequest)
            Log.i(TAG, "测试备份已调度（10秒后执行）")
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