package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.subagent.SubagentRun
import kotlin.uuid.Uuid

/**
 * 会话页底栏上方的子代理运行条。
 *
 * 放在这里而不是输入栏内部, 因为输入栏的可见区域会被其它控件挤占, 运行时状态容易被藏起来。
 * 有子代理在跑时固定占一行, 点开看全部任务, 可单独取消。
 */
@Composable
fun SubagentRunningBar(
    runs: List<SubagentRun>,
    onCancel: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    // 作为输入框卡片的一段渲染, 不带背景与形状
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.subagent_running_bar, runs.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = runs.first().description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.subagent_view),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.subagent_running_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                runs.forEach { run ->
                    SubagentRunRow(run = run, onCancel = { onCancel(run.taskId) })
                }
            }
        }
    }
}

@Composable
private fun SubagentRunRow(run: SubagentRun, onCancel: () -> Unit) {
    val elapsed by produceState(initialValue = 0L, run.taskId) {
        while (true) {
            value = System.currentTimeMillis() - run.startedAt
            delay(1000)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = run.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.subagent_tool_calls, run.toolCallCount) +
                    " · " + formatElapsed(elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.subagent_cancel))
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
