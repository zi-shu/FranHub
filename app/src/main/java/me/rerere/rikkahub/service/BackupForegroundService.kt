package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BACKUP_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.backup.BackupPhase
import me.rerere.rikkahub.data.backup.BackupProgress
import me.rerere.rikkahub.data.backup.BackupTaskKind
import me.rerere.rikkahub.data.backup.BackupTaskRunner
import me.rerere.rikkahub.data.backup.BackupTaskState
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.android.ext.android.inject

private const val TAG = "BackupFgs"

/**
 * 备份与恢复期间把进程留在前台，并持有 CPU 唤醒锁。
 *
 * 作业本身由 [BackupTaskRunner] 跑在应用作用域里。这里只做三件事：
 * 进前台（防 Android 12+ 冻结缓存进程）、持有 PARTIAL_WAKE_LOCK（防锁屏后 CPU 休眠）、
 * 按进度刷新通知。任务结束后自停，并留一条完成或失败通知。
 */
class BackupForegroundService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.BACKUP_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.BACKUP_STOP"
        private const val EXTRA_KIND = "backup_task_kind"

        const val NOTIFICATION_ID = 2005

        fun start(context: Context, kind: BackupTaskKind) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_KIND, kind.name)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "Unable to start backup foreground service", it)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to stop backup foreground service", it)
            }
        }
    }

    private val runner: BackupTaskRunner by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false
    private var kind: BackupTaskKind = BackupTaskKind.WORKSPACE_EXPORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        observer = scope.launch {
            runner.state.collect { state -> onState(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopService()
            else -> {
                kind = intent?.getStringExtra(EXTRA_KIND)
                    ?.let { name -> runCatching { BackupTaskKind.valueOf(name) }.getOrNull() }
                    ?: kind
                // 必须在这里同步进前台，晚于 5 秒系统会抛 ForegroundServiceDidNotStartInTimeException
                enterForeground(buildProgressNotification(runner.state.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        releaseWakeLock()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 14 起 dataSync 前台服务有每日时长上限。作业仍在应用作用域里跑，
        // 只是不再受前台保护，这里记录后放它继续。
        Log.w(TAG, "Backup foreground service timed out (type=$fgsType)")
    }

    private fun onState(state: BackupTaskState) {
        when (state) {
            is BackupTaskState.Running -> {
                kind = state.kind
                enterForeground(buildProgressNotification(state))
            }

            is BackupTaskState.Completed -> finish(buildResultNotification(state))
            is BackupTaskState.Failed -> finish(buildResultNotification(state))
            BackupTaskState.Idle -> stopService()
        }
    }

    private fun enterForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            stopService()
        }
    }

    /** 作业结束：撤掉前台通知，再补一条可点掉的完成通知。 */
    private fun finish(notification: Notification) {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.e(TAG, "Failed to post result notification", it) }
        stopService()
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildProgressNotification(state: BackupTaskState): Notification {
        val progress = (state as? BackupTaskState.Running)?.progress ?: BackupProgress()
        val builder = baseNotification()
            .setContentTitle(getString(R.string.app_name))
            .setContentText(progressText(progress))
            .setOngoing(true)
            .setSilent(true)
        val fraction = progress.fraction
        if (progress.phase == BackupPhase.WRITING && fraction >= 0f) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun progressText(progress: BackupProgress): String = when (progress.phase) {
        BackupPhase.SCANNING -> getString(R.string.backup_notification_scanning)
        BackupPhase.WRITING -> {
            val head = getString(
                when (kind) {
                    BackupTaskKind.WORKSPACE_EXPORT -> R.string.backup_notification_exporting_workspace
                    BackupTaskKind.CHAT_EXPORT -> R.string.backup_notification_exporting_chat
                    else -> R.string.backup_notification_restoring
                }
            )
            if (progress.totalBytes > 0) {
                val percent = (progress.fraction * 100).toInt()
                "$head · $percent% · ${progress.doneBytes.fileSizeToString()} / ${progress.totalBytes.fileSizeToString()}"
            } else {
                "$head · ${progress.doneBytes.fileSizeToString()}"
            }
        }

        else -> getString(
            when (kind) {
                BackupTaskKind.WORKSPACE_EXPORT, BackupTaskKind.CHAT_EXPORT ->
                    R.string.backup_notification_exporting_workspace

                else -> R.string.backup_notification_restoring
            }
        )
    }

    private fun buildResultNotification(state: BackupTaskState): Notification {
        val isExport = when (state) {
            is BackupTaskState.Completed -> state.kind.isExport
            is BackupTaskState.Failed -> state.kind.isExport
            else -> true
        }
        val text = when (state) {
            is BackupTaskState.Failed -> getString(
                if (isExport) R.string.backup_notification_failed else R.string.backup_notification_restore_failed,
                state.message,
            )

            else -> getString(
                if (isExport) R.string.backup_notification_done else R.string.backup_notification_restore_done
            )
        }
        return baseNotification()
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
    }

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, BACKUP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 备份期间保持 CPU 运行。不加超时：由 [releaseWakeLock] 在服务销毁与任务结束时释放，
     * 进程被杀时系统也会自动释放。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "franhub:backup").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.e(TAG, "Failed to acquire wake lock", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.e(TAG, "Failed to release wake lock", it) }
        wakeLock = null
    }
}
