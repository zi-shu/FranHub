package me.rerere.workspace

import java.io.File
import java.util.UUID

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** AI 写入该目录时免强制审批 */
    val writable: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        unavailableReason(context)?.let { reason ->
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = reason,
            )
        }
        val pidFile = File(context.linuxDir, "${EXEC_DIR.trimStart('/')}/${UUID.randomUUID()}.pid")
        pidFile.parentFile?.mkdirs()
        // 用 Rootfs 自带的 timeout 包住命令: 到点由它结束命令, proot 才能正常清理子孙并退出
        val useTimeout = hasTimeoutCommand(context.linuxDir)
        // 应用层等待比 Rootfs 内超时多留缓冲, 让正常路径走"命令自己结束", 而不是杀不干净的强杀
        val appTimeoutMillis = if (useTimeout) context.timeoutMillis + TIMEOUT_GRACE_MS else context.timeoutMillis
        val timeoutSec = if (useTimeout) timeoutSeconds(context.timeoutMillis) else ""
        System.err.println(
            "[FranHubShell] 启动 timeoutMs=${context.timeoutMillis} appTimeoutMs=$appTimeoutMillis " +
                "rootfsTimeout=$useTimeout cwd=${context.cwd} cmd=${context.command.take(200)}"
        )
        val process = processBuilder(
            context,
            buildCommand(context, TRACKED_SCRIPT, "$EXEC_DIR/${pidFile.name}", timeoutSec),
        )
            .redirectErrorStream(false)
            .start()
        return try {
            // 超时或取消时直接结束, 不留宽限期: 调用方已经不再等这条命令
            val result = process.readResult(appTimeoutMillis, context.stdin) { terminateProot(this, pidFile) }
            // timeout 以 124 表示命令被超时终止, 转成 timedOut 让模型直接看懂
            if (useTimeout && result.exitCode == 124) result.copy(timedOut = true) else result
        } finally {
            pidFile.delete()
        }
    }

    override fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand {
        unavailableReason(context)?.let { error(it) }
        // 日志放在 Rootfs 的 /tmp 下, 模型可以用 shell 直接 tail/grep; 应用启动时随 /tmp 一起清理
        val dir = File(context.linuxDir, BACKGROUND_DIR.trimStart('/')).apply { mkdirs() }
        val logFile = File(dir, "$id.log")
        val pidFile = File(dir, "$id.pid")
        val process = processBuilder(context, buildCommand(context, TRACKED_SCRIPT, "$BACKGROUND_DIR/$id.pid"))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .redirectInput(File("/dev/null"))
            .start()
        return ProotBackgroundCommand(process, logFile, "$BACKGROUND_DIR/$id.log", pidFile)
    }

    private fun unavailableReason(context: WorkspaceShellContext): String? = when {
        !WorkspaceManager.isUsableRootfs(context.linuxDir) -> "Rootfs is not installed"
        !File(nativeLibraryDir, PROOT_EXEC).isFile ->
            "proot executable not found: ${File(nativeLibraryDir, PROOT_EXEC).absolutePath}"
        !File(nativeLibraryDir, PROOT_LOADER).isFile ->
            "proot loader not found: ${File(nativeLibraryDir, PROOT_LOADER).absolutePath}"
        else -> null
    }

    private fun processBuilder(context: WorkspaceShellContext, command: List<String>): ProcessBuilder {
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = File(nativeLibraryDir, PROOT_LOADER).absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
    }

    override fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch {
        val shell = WorkspaceManager.rootfsShell(context.linuxDir)
        val args = prootArgs(context.linuxDir, context.filesDir, WORKSPACE_DIR, context.bindMounts)
        args += ROOTFS_ENV
        args += listOf(
            "USER=root",
            "SHELL=$shell",
            shell,
        )

        val env = mutableListOf(
            "PROOT_LOADER=${File(nativeLibraryDir, PROOT_LOADER).absolutePath}",
            "PROOT_TMP_DIR=${context.tempDir.absolutePath}",
            "TMPDIR=${context.tempDir.absolutePath}",
        )
        if (context.shellCompatibilityMode) {
            env += "PROOT_NO_SECCOMP=1"
        }

        return WorkspaceTerminalLaunch(
            executable = File(nativeLibraryDir, PROOT_EXEC).absolutePath,
            cwd = context.filesDir.absolutePath,
            args = args,
            env = env,
        )
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        script: String,
        vararg extraArgs: String,
    ): List<String> {
        val command = mutableListOf(File(nativeLibraryDir, PROOT_EXEC).absolutePath)
        command += prootArgs(context.linuxDir, context.filesDir, context.prootCwd(), context.bindMounts)
        command += ROOTFS_ENV
        command += listOf(
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            WorkspaceManager.rootfsShell(context.linuxDir),
            "-l",
            "-c",
            script,
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        command += extraArgs
        return command
    }

    /** PRoot 自身的参数: Rootfs、工作目录与挂载表, 命令执行和交互式终端共用 */
    private fun prootArgs(
        linuxDir: File,
        filesDir: File,
        cwd: String,
        bindMounts: List<WorkspaceBindMount>,
    ): MutableList<String> {
        val args = mutableListOf(
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
            "-b",
            "${filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        return args
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        // 命令通过位置参数传入, 避免任何转义; $1=cwd, $2=命令文本, $3=pid 文件, $4=timeout 秒数(空=不用)
        // $4 非空时用 Rootfs 自带的 timeout 包住命令, 到点由它结束命令, proot 才能正常清理子孙并退出
        private const val EXEC_SCRIPT =
            "cd -- \"\$1\" && if [ -n \"\$4\" ]; then timeout -k 5 \"\$4\" bash -lc \"\$2\"; else eval \"\$2\"; fi"

        // 先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 terminateProot
        private const val TRACKED_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; $EXEC_SCRIPT"

        /** Rootfs 内存放后台命令日志与进程号的目录 */
        private const val BACKGROUND_DIR = "/tmp/rikkahub/background"

        /** Rootfs 内存放前台命令进程号的目录 */
        private const val EXEC_DIR = "/tmp/rikkahub/exec"

        /** 应用层在 Rootfs 内超时之后额外等待的缓冲, 留时间给 timeout 结束命令 */
        private const val TIMEOUT_GRACE_MS = 10_000L

        /** Rootfs 内是否带 timeout 命令; 没有时退回直接执行 */
        private fun hasTimeoutCommand(linuxDir: File): Boolean =
            File(linuxDir, "usr/bin/timeout").exists() || File(linuxDir, "bin/timeout").exists()

        /** 毫秒换算成 timeout 需要的秒数, 向上取整且至少 1 秒 */
        private fun timeoutSeconds(millis: Long): String =
            ((millis + 999) / 1000).coerceAtLeast(1L).toString()

        /** 清空宿主机环境变量后 Rootfs 内的基础环境 */
        private val ROOTFS_ENV = listOf(
            "/usr/bin/env",
            "-i",
            "HOME=${WorkspaceManager.ROOTFS_HOME_DIR}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
    }
}
