package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.SHELL_NOTIFICATION_CHANNEL_ID

private const val TAG = "ShellFgs"

/**
 * Shell 命令执行期间把进程留在前台。
 *
 * 屏幕关闭后系统会把后台进程按停（进程状态变 t），proot 的 ptrace 跟踪随之卡住，
 * 命令既不结束也不退出。前台进程不在冻结候选名单里，可以避开这一层。
 * CPU 不休眠由 WorkspaceTools 一侧的 PARTIAL_WAKE_LOCK 负责，两者各管一半。
 */
class ShellForegroundService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.SHELL_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.SHELL_STOP"
        private const val EXTRA_LABEL = "shell_label"

        const val NOTIFICATION_ID = 2006

        fun start(context: Context, label: String) {
            val intent = Intent(context, ShellForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "Unable to start shell foreground service", it)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShellForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to stop shell foreground service", it)
            }
        }
    }

    private var label: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        // 必须同步进前台, 晚于 5 秒系统会抛 ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pending = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, SHELL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.notification_shell_running))
            .setContentText(label)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
    }
}
