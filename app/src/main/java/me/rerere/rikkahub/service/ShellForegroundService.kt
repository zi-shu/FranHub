package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.SHELL_NOTIFICATION_CHANNEL_ID
import java.util.Collections

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
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.SHELL_ACQUIRE"
        private const val EXTRA_TOKEN = "shell_token"
        private const val EXTRA_LABEL = "shell_label"

        /** 最后一个令牌释放后等一小段时间再停，留给同时在路上的启动请求。 */
        private const val STOP_GRACE_MS = 1_000L

        const val NOTIFICATION_ID = 2006

        /**
         * 正在执行的命令令牌，一条命令一个。
         *
         * 多条命令并行时用计数而不是布尔量：先结束的命令不能把服务停掉，
         * 否则另一条命令排队中的 startForegroundService 会变成
         * ForegroundServiceDidNotStartInTimeException。
         */
        private val tokens = Collections.synchronizedSet(mutableSetOf<String>())
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: ShellForegroundService? = null

        fun acquire(context: Context, token: String, label: String) {
            tokens.add(token)
            val intent = Intent(context, ShellForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_LABEL, label)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                tokens.remove(token)
                Log.e(TAG, "Unable to start shell foreground service", it)
            }
        }

        fun release(context: Context, token: String) {
            tokens.remove(token)
            val current = instance ?: return
            // 走进程内调用，不用 startService 发 STOP：应用在后台时 startService 会被拒绝，
            // 服务永远停不掉，通知一直挂着。
            mainHandler.post {
                if (instance === current) current.onTokenReleased()
            }
        }
    }

    private var label: String = ""
    private var stopScheduled = false

    private val stopRunnable = Runnable {
        stopScheduled = false
        if (tokens.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_ACQUIRE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        intent.getStringExtra(EXTRA_TOKEN)?.let { tokens.add(it) }
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        cancelScheduledStop()
        // 必须同步进前台, 晚于 5 秒系统会抛 ForegroundServiceDidNotStartInTimeException
        enterForeground()
        if (tokens.isEmpty()) {
            scheduleStop()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        tokens.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopRunnable)
        stopScheduled = false
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun onTokenReleased() {
        if (tokens.isEmpty()) {
            scheduleStop()
        } else {
            enterForeground()
        }
    }

    private fun scheduleStop() {
        if (stopScheduled) return
        stopScheduled = true
        mainHandler.postDelayed(stopRunnable, STOP_GRACE_MS)
    }

    private fun cancelScheduledStop() {
        mainHandler.removeCallbacks(stopRunnable)
        stopScheduled = false
    }

    private fun enterForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 进前台失败时立刻停掉自己，系统的前台服务等待随之取消。
            // 不处理会在 5 秒后变成 ForegroundServiceDidNotStartInTimeException。
            Log.e(TAG, "Failed to enter foreground", e)
            tokens.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
