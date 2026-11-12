package me.rerere.rikkahub.data.sync

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/** 记忆合并统计，供预览与恢复报告使用。 */
@Serializable
data class MemoryMergeStats(
    val imported: Int = 0,
    val skipped: Int = 0,
    val renamed: Int = 0,
)

/**
 * 增量恢复的数据库端合并。全部在备份库（暂存副本）上执行，
 * 不触碰正在使用的本地库。本地库只读一份 VACUUM INTO 快照。
 */
object DatabaseMerge {

    /**
     * 把备份库里的 UUID 引用按 [idMap] 重写。
     * 覆盖：会话的助手与配置、消息的模型、记忆与文件夹的助手、用量流水、翻译记录。
     */
    fun rewriteReferences(db: SQLiteDatabase, idMap: Map<String, String>, json: Json) {
        if (idMap.isEmpty()) return
        rewriteConversations(db, idMap, json)
        rewriteAssistantIdColumn(db, "conversation_folder", "id", "assistant_id", idMap)
        rewriteMessages(db, idMap, json)
        rewriteAssistantIdColumn(db, "memory_file", null, "assistant_id", idMap)
        rewriteUsageRecords(db, idMap)
        rewriteTranslationHistory(db, idMap)
    }

    private fun mapOrNull(idMap: Map<String, String>, value: String?): String? =
        value?.let { idMap[it] ?: it }

    private fun rewriteConversations(db: SQLiteDatabase, idMap: Map<String, String>, json: Json) {
        db.query(
            "SELECT id, assistant_id, config, mode_injection_ids, lorebook_ids FROM conversation"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val assistantId = cursor.getString(1)
                val config = cursor.getString(2)
                val modeIds = cursor.getString(3)
                val lorebookIds = cursor.getString(4)
                val mappedAssistant = mapOrNull(idMap, assistantId)
                val mappedConfig = mapConfigJson(config, idMap, json)
                val mappedMode = mapStringArrayField(modeIds, idMap, json)
                val mappedLorebook = mapStringArrayField(lorebookIds, idMap, json)
                db.execSQL(
                    "UPDATE conversation SET assistant_id = ?, config = ?, mode_injection_ids = ?, lorebook_ids = ? WHERE id = ?",
                    arrayOf<Any?>(mappedAssistant, mappedConfig, mappedMode, mappedLorebook, id),
                )
            }
        }
    }

    private fun mapConfigJson(config: String, idMap: Map<String, String>, json: Json): String {
        if (config.isBlank()) return config
        val root = runCatching { json.parseToJsonElement(config).jsonObject }.getOrNull() ?: return config
        val result = root.toMutableMap()
        for (field in listOf("chatModelId", "workspaceId")) {
            val value = result[field]?.jsonPrimitiveOrNull()?.content ?: continue
            idMap[value]?.let { result[field] = JsonPrimitive(it) }
        }
        val mcpServers = result["mcpServers"] as? JsonArray
        if (mcpServers != null) {
            result["mcpServers"] = JsonArray(
                mcpServers.map { element ->
                    val content = element.jsonPrimitiveOrNull()?.content
                    JsonPrimitive(content?.let { idMap[it] ?: it } ?: "")
                }
            )
        }
        return json.encodeToString(JsonObject(result))
    }

    private fun mapStringArrayField(field: String, idMap: Map<String, String>, json: Json): String {
        if (field.isBlank()) return field
        val array = runCatching { json.parseToJsonElement(field).jsonArray }.getOrNull() ?: return field
        val mapped = JsonArray(
            array.map { element ->
                val content = element.jsonPrimitiveOrNull()?.content
                JsonPrimitive(content?.let { idMap[it] ?: it } ?: "")
            }
        )
        return json.encodeToString(mapped)
    }

    private fun rewriteMessages(db: SQLiteDatabase, idMap: Map<String, String>, json: Json) {
        db.query("SELECT id, messages FROM message_node").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val messages = cursor.getString(1)
                if (messages.isBlank()) continue
                val array = runCatching { json.parseToJsonElement(messages).jsonArray }.getOrNull() ?: continue
                var changed = false
                val mapped = JsonArray(
                    array.map { element ->
                        val obj = element as? JsonObject ?: return@map element
                        val modelId = obj["modelId"]?.jsonPrimitiveOrNull()?.content
                        if (modelId != null && modelId in idMap) {
                            changed = true
                            val copy = obj.toMutableMap()
                            copy["modelId"] = JsonPrimitive(idMap.getValue(modelId))
                            JsonObject(copy)
                        } else {
                            obj
                        }
                    }
                )
                if (changed) {
                    db.execSQL(
                        "UPDATE message_node SET messages = ? WHERE id = ?",
                        arrayOf(json.encodeToString(mapped), id),
                    )
                }
            }
        }
    }

    private fun rewriteAssistantIdColumn(
        db: SQLiteDatabase,
        table: String,
        keyColumn: String?,
        assistantColumn: String,
        idMap: Map<String, String>,
    ) {
        db.query("SELECT ${keyColumn ?: "rowid"}, $assistantColumn FROM $table").use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val assistantId = cursor.getString(1)
                val mapped = mapOrNull(idMap, assistantId) ?: continue
                db.execSQL(
                    "UPDATE $table SET $assistantColumn = ? WHERE ${keyColumn ?: "rowid"} = ?",
                    arrayOf(mapped, key),
                )
            }
        }
    }

    private fun rewriteUsageRecords(db: SQLiteDatabase, idMap: Map<String, String>) {
        db.query("SELECT id, provider_id, model_id, assistant_id FROM usage_record").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val provider = mapOrNull(idMap, cursor.getString(1))
                val model = mapOrNull(idMap, cursor.getString(2))
                val assistant = mapOrNull(idMap, cursor.getString(3))
                db.execSQL(
                    "UPDATE usage_record SET provider_id = ?, model_id = ?, assistant_id = ? WHERE id = ?",
                    arrayOf<Any?>(provider, model, assistant, id),
                )
            }
        }
    }

    private fun rewriteTranslationHistory(db: SQLiteDatabase, idMap: Map<String, String>) {
        db.query("SELECT id, model_id FROM translation_history").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val modelId = cursor.getString(1)
                val mapped = mapOrNull(idMap, modelId) ?: continue
                db.execSQL(
                    "UPDATE translation_history SET model_id = ? WHERE id = ?",
                    arrayOf<Any?>(mapped, id),
                )
            }
        }
    }

    /**
     * 把本地库快照里的记忆合并进备份库。
     * 按 (assistant_id, path) 匹配：缺失导入、一致跳过、冲突时 path 加「 (n)」递增改名。
     */
    fun mergeMemories(
        db: SQLiteDatabase,
        localSnapshot: File,
        idMap: Map<String, String>,
        context: Context,
    ): MemoryMergeStats {
        val existing = HashMap<String, String>() // (assistant_id + "\u0000" + path) -> content
        db.query("SELECT assistant_id, path, content FROM memory_file").use { cursor ->
            while (cursor.moveToNext()) {
                existing[key(cursor.getString(0), cursor.getString(1))] = cursor.getString(2)
            }
        }
        val usedPaths = existing.keys.map { it.substringAfter('\u0000') }.toHashSet()

        var imported = 0
        var skipped = 0
        var renamed = 0
        val localConfiguration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(localSnapshot.absolutePath, SQLiteDatabase.OPEN_READONLY)
        )
        val localDb = SQLiteDatabase.openDatabase(localConfiguration, null) {
            error("Local database snapshot is corrupt")
        }
        localDb.use {
            it.query("SELECT assistant_id, path, content, updated_at FROM memory_file").use { cursor ->
                while (cursor.moveToNext()) {
                    val assistantId = cursor.getString(0)
                    val path = cursor.getString(1)
                    val content = cursor.getString(2)
                    val updatedAt = cursor.getLong(3)
                    val mappedAssistant = mapOrNull(idMap, assistantId) ?: continue
                    val entryKey = key(mappedAssistant, path)
                    val backupContent = existing[entryKey]
                    when {
                        backupContent == null -> {
                            insertMemory(db, mappedAssistant, path, content, updatedAt)
                            usedPaths += path
                            imported++
                        }
                        backupContent == content -> {
                            skipped++
                        }
                        else -> {
                            val newPath = uniqueMemoryPath(path, usedPaths)
                            insertMemory(db, mappedAssistant, newPath, content, updatedAt)
                            usedPaths += newPath
                            renamed++
                        }
                    }
                }
            }
        }
        return MemoryMergeStats(imported = imported, skipped = skipped, renamed = renamed)
    }

    private fun insertMemory(db: SQLiteDatabase, assistantId: String, path: String, content: String, updatedAt: Long) {
        db.execSQL(
            "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(assistantId, path, content, updatedAt),
        )
    }

    /** path 加「 (n)」递增，后缀插在扩展名前。 */
    private fun uniqueMemoryPath(path: String, used: Set<String>): String {
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val ext = if (dot > 0) path.substring(dot) else ""
        var n = 2
        var candidate = "$base ($n)$ext"
        while (candidate in used) {
            n++
            candidate = "$base ($n)$ext"
        }
        return candidate
    }

    /** 备份库里存在的全部工作区 id，用于校验助手的工作区引用。 */
    fun existingWorkspaceIds(db: SQLiteDatabase): Set<String> {
        val ids = HashSet<String>()
        db.query("SELECT id FROM workspaces").use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    private fun key(assistantId: String, path: String) = "$assistantId\u0000$path"

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
        if (this is JsonPrimitive && !(this is JsonNull)) this else null
}
