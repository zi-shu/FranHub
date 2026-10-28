package me.rerere.rikkahub.data.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.BackupForegroundService
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BackupTaskRunner"

/** 备份与恢复作业的种类。界面按它点亮对应的那一行。 */
enum class BackupTaskKind {
    CHAT_EXPORT,
    CHAT_IMPORT,
    WORKSPACE_EXPORT,
    WORKSPACE_IMPORT,
    WORKSPACE_RESTORE,
    CHATBOX_IMPORT,
    CHERRY_IMPORT;

    /** 是否写文件（导出类任务才显示字节进度）。 */
    val isExport: Boolean get() = this == CHAT_EXPORT || this == WORKSPACE_EXPORT
}

enum class BackupPhase {
    /** 准备中：还没开始读文件。 */
    PREPARING,

    /** 统计体积。 */
    SCANNING,

    /** 打包中。 */
    WRITING,

    /** 收尾：写入压缩包尾部、关闭文件。 */
    FINISHING,
}

/**
 * 一次作业的进度。
 *
 * [totalBytes] 小于 0 表示总量未知，界面显示不定进度。
 */
data class BackupProgress(
    val phase: BackupPhase = BackupPhase.PREPARING,
    val doneBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val entries: Int = 0,
    val current: String = "",
) {
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else -1f
}

/** 备份与恢复作业的状态。作业跑在应用作用域，界面销毁不影响它。 */
sealed interface BackupTaskState {
    data object Idle : BackupTaskState

    data class Running(
        val kind: BackupTaskKind,
        val progress: BackupProgress = BackupProgress(),
    ) : BackupTaskState

    data class Failed(
        val kind: BackupTaskKind,
        val message: String,
    ) : BackupTaskState

    /** [restartRequired] 为真时提示用户重启，工作区换目录与聊天库恢复都在启动时生效。 */
    data class Completed(
        val kind: BackupTaskKind,
        val restartRequired: Boolean,
    ) : BackupTaskState
}

/** 已读入缓存的工作区备份包，等用户在弹窗里选完再恢复。 */
class PendingWorkspaceRestore(
    val file: java.io.File,
    val manifest: WorkspaceBackupManifest,
)

/**
 * 备份与恢复作业的执行者。
 *
 * 作业跑在 [AppScope] 上，不跟随页面 ViewModel 销毁，配合 [BackupForegroundService]
 * 让打包在锁屏或切到后台后仍能跑完。界面只观察 [state]。
 */
class BackupTaskRunner(
    private val context: Context,
    private val appScope: AppScope,
) {
    private val _state = MutableStateFlow<BackupTaskState>(BackupTaskState.Idle)
    val state: StateFlow<BackupTaskState> = _state.asStateFlow()

    // 待恢复包也放在这里：导入读完清单后即使用户离开页面，选择结果仍在
    private val _pendingWorkspaceRestore = MutableStateFlow<PendingWorkspaceRestore?>(null)
    val pendingWorkspaceRestore: StateFlow<PendingWorkspaceRestore?> = _pendingWorkspaceRestore.asStateFlow()

    private var job: Job? = null

    fun setPendingWorkspaceRestore(pending: PendingWorkspaceRestore) {
        _pendingWorkspaceRestore.value?.file?.delete()
        _pendingWorkspaceRestore.value = pending
    }

    /** 用户确认恢复：取出待恢复包并清空槽位，文件交给作业处理，不删除。 */
    fun takePendingWorkspaceRestore(): PendingWorkspaceRestore? {
        val pending = _pendingWorkspaceRestore.value
        _pendingWorkspaceRestore.value = null
        return pending
    }

    /** 用户放弃恢复：清空槽位并删除缓存里的包。 */
    fun clearPendingWorkspaceRestore() {
        _pendingWorkspaceRestore.value?.file?.delete()
        _pendingWorkspaceRestore.value = null
    }

    /** 进度回调的最小间隔，避免每个 64K 文件块都刷一次界面与通知。 */
    private val progressIntervalMs = 200L
    private val progressStepBytes = 1L * 1024 * 1024

    fun isRunning(): Boolean = _state.value is BackupTaskState.Running

    /**
     * 启动一次作业。[block] 返回是否需要重启，参数是进度回调。
     *
     * 已有作业在跑时返回 false，不覆盖。
     */
    fun start(
        kind: BackupTaskKind,
        block: suspend (report: (BackupProgress) -> Unit) -> Boolean,
    ): Boolean {
        if (isRunning()) {
            Log.w(TAG, "start $kind rejected: ${_state.value}")
            return false
        }
        _state.value = BackupTaskState.Running(kind, BackupProgress(phase = BackupPhase.PREPARING))
        BackupForegroundService.start(context, kind)
        job = appScope.launch(Dispatchers.IO) {
            val lastReportedAt = AtomicLong(0L)
            val lastReportedBytes = AtomicLong(0L)
            val report: (BackupProgress) -> Unit = { progress ->
                val now = System.currentTimeMillis()
                val bigStep = progress.phase != BackupPhase.WRITING ||
                    progress.doneBytes - lastReportedBytes.get() >= progressStepBytes
                val afterInterval = now - lastReportedAt.get() >= progressIntervalMs
                // 换阶段、跨过 1 MB、或距上次超过 200 ms 才更新一次
                if (bigStep || afterInterval) {
                    lastReportedAt.set(now)
                    lastReportedBytes.set(progress.doneBytes)
                    _state.value = BackupTaskState.Running(kind, progress)
                }
            }
            val result = try {
                val restartRequired = block(report)
                BackupTaskState.Completed(kind, restartRequired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "task $kind failed", e)
                BackupTaskState.Failed(kind, describe(e))
            } finally {
                BackupForegroundService.stop(context)
            }
            _state.value = result
        }
        return true
    }

    /** 界面把结果提示出来以后调用，回到 Idle。作业进行中不清理。 */
    fun acknowledge() {
        if (_state.value !is BackupTaskState.Running) {
            _state.value = BackupTaskState.Idle
        }
    }

    /** 异常转成一句能看懂的话，空消息时退回类名。 */
    private fun describe(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return message ?: e.javaClass.simpleName
    }
}
