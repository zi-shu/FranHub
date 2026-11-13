package me.rerere.rikkahub.data.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

/**
 * 会话输入框草稿的持久化存储。
 *
 * 一个会话最多保留一条草稿：切换会话时 ChatVM 销毁前把输入框文本写进这里，
 * 再次进入该会话时读回。存成单个 JSON 文件，App 重启后草稿仍在。
 *
 * 存储目录下只放 [FILE_NAME] 一个文件。写盘先写 .tmp 再 rename，避免写到一半
 * 进程被杀留下损坏文件；读到损坏文件时改名隔离，不阻塞正常读写。
 */
@Serializable
data class ChatDraftEntry(
    val assistantId: String,
    val text: String,
    val updatedAt: Long,
)

@Serializable
private data class DraftFilePayload(
    val version: Int = 1,
    val drafts: Map<String, ChatDraftEntry> = emptyMap(),
)

class ChatDraftStore(private val rootDir: File) {

    companion object {
        const val MAX_CONVERSATIONS = 100
        const val MAX_TEXT_LENGTH = 10_000
        const val FILE_NAME = "chat_drafts.json"
        private const val TAG = "ChatDraftStore"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val ioLock = Mutex()

    private val _drafts = MutableStateFlow<Map<String, ChatDraftEntry>>(emptyMap())

    /** 全部草稿，key 为会话 id。写盘后同步刷新，供抽屉列表响应式显示草稿会话。 */
    val drafts: StateFlow<Map<String, ChatDraftEntry>> = _drafts.asStateFlow()

    private val draftFile: File
        get() = File(rootDir, FILE_NAME)

    init {
        _drafts.value = runCatching { readFromDisk() }.getOrDefault(emptyMap())
    }

    /** 读某个会话的草稿文本；没有草稿返回 null。 */
    suspend fun load(conversationId: Uuid): String? = ioLock.withLock {
        _drafts.value[conversationId.toString()]?.text
    }

    /**
     * 保存某个会话的草稿。文本空白表示清除草稿。
     * 文本超长截断，会话数超上限时按更新时间淘汰最老的。
     */
    suspend fun save(conversationId: Uuid, assistantId: Uuid, text: String) = ioLock.withLock {
        val key = conversationId.toString()
        val current = _drafts.value
        val newMap = upsertDraft(current, key, assistantId.toString(), text)
        if (newMap !== current) {
            _drafts.value = newMap
            writeToDisk(newMap)
        }
    }

    /** 清除某个会话的草稿。 */
    suspend fun clear(conversationId: Uuid) = ioLock.withLock {
        val key = conversationId.toString()
        val current = _drafts.value
        if (key in current) {
            val newMap = current - key
            _drafts.value = newMap
            writeToDisk(newMap)
        }
    }

    private fun upsertDraft(
        current: Map<String, ChatDraftEntry>,
        key: String,
        assistantId: String,
        text: String,
    ): Map<String, ChatDraftEntry> {
        if (text.isBlank()) {
            return if (key in current) current - key else current
        }
        val capped = text.take(MAX_TEXT_LENGTH)
        val entry = ChatDraftEntry(
            assistantId = assistantId,
            text = capped,
            updatedAt = System.currentTimeMillis(),
        )
        var map = current + (key to entry)
        if (map.size > MAX_CONVERSATIONS) {
            val dropCount = map.size - MAX_CONVERSATIONS
            map = map.entries
                .sortedBy { it.value.updatedAt }
                .drop(dropCount)
                .associate { it.key to it.value }
        }
        return map
    }

    private fun readFromDisk(): Map<String, ChatDraftEntry> {
        val file = draftFile
        if (!file.isFile) return emptyMap()
        return try {
            json.decodeFromString<DraftFilePayload>(file.readText(Charsets.UTF_8)).drafts
        } catch (e: Exception) {
            quarantineCorrupted(file, e)
            emptyMap()
        }
    }

    private suspend fun writeToDisk(drafts: Map<String, ChatDraftEntry>) {
        withContext(Dispatchers.IO) {
            val file = draftFile
            runCatching {
                if (drafts.isEmpty()) {
                    file.delete()
                    return@runCatching
                }
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                try {
                    tmp.writeText(
                        json.encodeToString(DraftFilePayload.serializer(), DraftFilePayload(drafts = drafts)),
                        Charsets.UTF_8,
                    )
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }.onFailure {
                Log.e(TAG, "writeToDisk failed", it)
            }
        }
    }

    private fun quarantineCorrupted(file: File, cause: Exception) {
        val quarantined = runCatching {
            val target = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            if (file.renameTo(target)) {
                target.absolutePath
            } else {
                file.copyTo(target, overwrite = true)
                file.delete()
                target.absolutePath
            }
        }.getOrNull()
        if (quarantined != null) {
            Log.w(TAG, "corrupted chat draft file quarantined at: $quarantined")
        } else {
            Log.e(TAG, "failed to quarantine corrupted chat draft file", cause)
        }
    }
}
