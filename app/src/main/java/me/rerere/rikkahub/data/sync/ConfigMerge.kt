package me.rerere.rikkahub.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** 合并动作。 */
enum class MergeAction {
    /** 两边同名且内容一致，跳过导入，保留本地条目。 */
    SKIP,

    /** 只在备份里有，直接导入。 */
    IMPORT,

    /** 两边同名但内容不一致，备份条目改名换新 id 导入。 */
    RENAME_IMPORT,

    /** 只在本地有，保留。 */
    LOCAL_ONLY,
}

/** 预览清单里的一行。 */
@Serializable
data class MergePlanItem(
    val kind: String,
    val name: String,
    val action: MergeAction,
    val newName: String? = null,
)

/** settings 增量合并结果：合并后的设置、预览清单、id 映射表。 */
data class SettingsMergeResult(
    val settings: Settings,
    val plan: List<MergePlanItem>,
    val idMap: Map<String, String>,
)

/** 增量恢复的完整预览：条目动作清单、附件统计、记忆统计。恢复前展示给用户确认。 */
@Serializable
data class MergePreview(
    val entries: List<MergePlanItem> = emptyList(),
    val attachments: AttachmentMergeStats = AttachmentMergeStats(),
    val memories: MemoryMergeStats = MemoryMergeStats(),
)

/**
 * settings 条目级增量合并。
 *
 * 以备份为底：备份条目逐个处理，本地条目并入。
 * 同名判定忽略大小写与首尾空格。内容一致判定用序列化 JSON 深度剔除
 * 所有 "id" 键后逐字节比较（随机 UUID 不算配置内容）。
 * 冲突条目改名加「 (2)」「 (3)」递增后缀，同时换新 id。
 * 合并完成后把备份 settings 里的 UUID 引用按映射表重写。
 */
class ConfigMerge(private val json: Json) {

    private val idMap = mutableMapOf<String, String>()
    private val plan = mutableListOf<MergePlanItem>()

    fun mergeSettings(backup: Settings, local: Settings): SettingsMergeResult {
        idMap.clear()
        plan.clear()

        val providers = mergeProviders(backup.providers, local.providers)
        val mediaGen = mergeMediaGenProviders(backup.mediaGenerationProviders, local.mediaGenerationProviders)
        val mcpServers = mergeEntries(
            kind = "mcpServers",
            backup = backup.mcpServers,
            local = local.mcpServers,
            serializer = McpServerConfig.serializer(),
            nameOf = { it.commonOptions.name },
            idOf = { it.id.toString() },
            nameField = "name", // 非空表示可改名；实际改名走 rename lambda（名字在 commonOptions 里）
            rename = { item, newName -> item.clone(commonOptions = item.commonOptions.copy(name = newName)) },
            withId = { item, newId -> item.clone(id = Uuid.parse(newId)) },
        )
        val ttsProviders = mergeEntries(
            kind = "ttsProviders",
            backup = backup.ttsProviders,
            local = local.ttsProviders,
            serializer = TTSProviderSetting.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copyProvider(name = newName) },
            withId = { item, newId -> item.copyProvider(id = Uuid.parse(newId)) },
        )
        val asrProviders = mergeEntries(
            kind = "asrProviders",
            backup = backup.asrProviders,
            local = local.asrProviders,
            serializer = ASRProviderSetting.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copyProvider(name = newName) },
            withId = { item, newId -> item.copyProvider(id = Uuid.parse(newId)) },
        )
        val assistants = mergeEntries(
            kind = "assistants",
            backup = backup.assistants,
            local = local.assistants,
            serializer = Assistant.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copy(name = newName) },
            withId = { item, newId -> item.copy(id = Uuid.parse(newId)) },
        )
        val quickMessages = mergeEntries(
            kind = "quickMessages",
            backup = backup.quickMessages,
            local = local.quickMessages,
            serializer = QuickMessage.serializer(),
            nameOf = { it.title },
            idOf = { it.id.toString() },
            nameField = "title",
            rename = { item, newName -> item.copy(title = newName) },
            withId = { item, newId -> item.copy(id = Uuid.parse(newId)) },
        )
        val lorebooks = mergeEntries(
            kind = "lorebooks",
            backup = backup.lorebooks,
            local = local.lorebooks,
            serializer = Lorebook.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copy(name = newName) },
            withId = { item, newId -> item.copy(id = Uuid.parse(newId)) },
        )
        val modeInjections = mergeEntries(
            kind = "modeInjections",
            backup = backup.modeInjections,
            local = local.modeInjections,
            serializer = PromptInjection.ModeInjection.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copy(name = newName) },
            withId = { item, newId -> item.copy(id = Uuid.parse(newId)) },
        )
        val tags = mergeEntries(
            kind = "assistantTags",
            backup = backup.assistantTags,
            local = local.assistantTags,
            serializer = Tag.serializer(),
            nameOf = { it.name },
            idOf = { it.id.toString() },
            nameField = "name",
            rename = { item, newName -> item.copy(name = newName) },
            withId = { item, newId -> item.copy(id = Uuid.parse(newId)) },
        )
        val searchServices = mergeEntries(
            kind = "searchServices",
            backup = backup.searchServices,
            local = local.searchServices,
            serializer = SearchServiceOptions.serializer(),
            nameOf = { it.displayName },
            idOf = { it.id.toString() },
            nameField = null, // 无名字字段，冲突时只换新 id
            rename = { item, _ -> item },
            withId = { item, newId -> patch(SearchServiceOptions.serializer(), item, "id", newId) },
        )
        val customThemes = mergeEntries(
            kind = "customThemes",
            backup = backup.customThemes,
            local = local.customThemes,
            serializer = CustomTheme.serializer(),
            nameOf = { it.name },
            idOf = { it.id },
            nameField = "name",
            rename = { item, newName -> item.copy(name = newName) },
            withId = { item, newId -> item.copy(id = newId) },
        )

        val merged = rewriteReferences(
            backup.copy(
                providers = providers,
                mediaGenerationProviders = mediaGen,
                mcpServers = mcpServers,
                ttsProviders = ttsProviders,
                asrProviders = asrProviders,
                assistants = assistants,
                quickMessages = quickMessages,
                lorebooks = lorebooks,
                modeInjections = modeInjections,
                assistantTags = tags,
                searchServices = searchServices,
                customThemes = customThemes,
            )
        )
        return SettingsMergeResult(merged, plan.toList(), idMap.toMap())
    }

    // ---- 通用条目合并 ----

    private data class EntrySpec<T>(
        val serializer: KSerializer<T>,
        val nameOf: (T) -> String,
        val idOf: (T) -> String,
        val nameField: String?,
        val rename: (T, String) -> T,
        val withId: (T, String) -> T,
    )

    private fun <T> mergeEntries(
        kind: String,
        backup: List<T>,
        local: List<T>,
        serializer: KSerializer<T>,
        nameOf: (T) -> String,
        idOf: (T) -> String,
        nameField: String?,
        rename: (T, String) -> T,
        withId: (T, String) -> T,
    ): List<T> {
        val spec = EntrySpec(serializer, nameOf, idOf, nameField, rename, withId)
        return mergeEntries(kind, backup, local, spec)
    }

    private fun <T> mergeEntries(kind: String, backup: List<T>, local: List<T>, spec: EntrySpec<T>): List<T> {
        val result = backup.toMutableList()
        local.forEach { localItem ->
            val index = result.indexOfFirst { sameName(spec.nameOf(it), spec.nameOf(localItem)) }
            if (index < 0) {
                result.add(localItem)
                plan += MergePlanItem(kind, spec.nameOf(localItem), MergeAction.LOCAL_ONLY)
                return@forEach
            }
            val backupItem = result[index]
            if (fingerprint(spec.serializer, backupItem) == fingerprint(spec.serializer, localItem)) {
                idMap[spec.idOf(backupItem)] = spec.idOf(localItem)
                result[index] = localItem
                plan += MergePlanItem(kind, spec.nameOf(backupItem), MergeAction.SKIP)
            } else {
                val newId = Uuid.random().toString()
                val newName = if (spec.nameField != null) {
                    uniqueName(spec.nameOf(backupItem).trim(), result.map(spec.nameOf) + local.map(spec.nameOf))
                } else {
                    spec.nameOf(backupItem)
                }
                val renamed = spec.withId(spec.rename(backupItem, newName), newId)
                idMap[spec.idOf(backupItem)] = newId
                result[index] = renamed
                result.add(localItem)
                plan += MergePlanItem(
                    kind = kind,
                    name = spec.nameOf(backupItem),
                    action = MergeAction.RENAME_IMPORT,
                    newName = if (newName == spec.nameOf(backupItem)) null else newName,
                )
            }
        }
        backup.forEach { backupItem ->
            if (local.none { sameName(spec.nameOf(it), spec.nameOf(backupItem)) }) {
                plan += MergePlanItem(kind, spec.nameOf(backupItem), MergeAction.IMPORT)
            }
        }
        return result
    }

    // ---- 供应商合并（含模型 id 子映射） ----

    private fun mergeProviders(backup: List<ProviderSetting>, local: List<ProviderSetting>): List<ProviderSetting> {
        val serializer = ProviderSetting.serializer()
        val result = backup.toMutableList()
        local.forEach { localP ->
            val index = result.indexOfFirst { sameName(it.name, localP.name) }
            if (index < 0) {
                result.add(localP)
                plan += MergePlanItem("providers", localP.name, MergeAction.LOCAL_ONLY)
                return@forEach
            }
            val backupP = result[index]
            if (fingerprint(serializer, backupP) == fingerprint(serializer, localP)) {
                backupP.models.forEach { backupModel ->
                    localP.models.find { modelKey(it) == modelKey(backupModel) }?.let { localModel ->
                        idMap[backupModel.id.toString()] = localModel.id.toString()
                    }
                }
                idMap[backupP.id.toString()] = localP.id.toString()
                result[index] = localP
                plan += MergePlanItem("providers", backupP.name, MergeAction.SKIP)
            } else {
                val newId = Uuid.random().toString()
                val newName = uniqueName(backupP.name.trim(), result.map { it.name } + local.map { it.name })
                val renamed = backupP.copyProvider(
                    id = Uuid.parse(newId),
                    name = newName,
                    models = backupP.models.map { model ->
                        val modelId = Uuid.random().toString()
                        idMap[model.id.toString()] = modelId
                        model.copy(id = Uuid.parse(modelId))
                    },
                )
                idMap[backupP.id.toString()] = newId
                result[index] = renamed
                result.add(localP)
                plan += MergePlanItem("providers", backupP.name, MergeAction.RENAME_IMPORT, newName)
            }
        }
        backup.forEach { backupP ->
            if (local.none { sameName(it.name, backupP.name) }) {
                plan += MergePlanItem("providers", backupP.name, MergeAction.IMPORT)
            }
        }
        return result
    }

    private fun mergeMediaGenProviders(
        backup: List<MediaGenerationProviderSetting>,
        local: List<MediaGenerationProviderSetting>,
    ): List<MediaGenerationProviderSetting> {
        val serializer = MediaGenerationProviderSetting.serializer()
        val result = backup.toMutableList()
        local.forEach { localP ->
            val index = result.indexOfFirst { sameName(it.name, localP.name) }
            if (index < 0) {
                result.add(localP)
                plan += MergePlanItem("mediaGenerationProviders", localP.name, MergeAction.LOCAL_ONLY)
                return@forEach
            }
            val backupP = result[index]
            if (fingerprint(serializer, backupP) == fingerprint(serializer, localP)) {
                backupP.models.forEach { backupModel ->
                    localP.models.find { modelKey(it) == modelKey(backupModel) }?.let { localModel ->
                        idMap[backupModel.id.toString()] = localModel.id.toString()
                    }
                }
                idMap[backupP.id.toString()] = localP.id.toString()
                result[index] = localP
                plan += MergePlanItem("mediaGenerationProviders", backupP.name, MergeAction.SKIP)
            } else {
                val newId = Uuid.random().toString()
                val newName = uniqueName(backupP.name.trim(), result.map { it.name } + local.map { it.name })
                val renamed = backupP.copyProvider(
                    id = Uuid.parse(newId),
                    name = newName,
                    models = backupP.models.map { model ->
                        val modelId = Uuid.random().toString()
                        idMap[model.id.toString()] = modelId
                        model.copy(id = Uuid.parse(modelId))
                    },
                )
                idMap[backupP.id.toString()] = newId
                result[index] = renamed
                result.add(localP)
                plan += MergePlanItem("mediaGenerationProviders", backupP.name, MergeAction.RENAME_IMPORT, newName)
            }
        }
        backup.forEach { backupP ->
            if (local.none { sameName(it.name, backupP.name) }) {
                plan += MergePlanItem("mediaGenerationProviders", backupP.name, MergeAction.IMPORT)
            }
        }
        return result
    }

    // ---- 引用重写 ----

    private fun rewriteReferences(settings: Settings): Settings {
        return settings.copy(
            favoriteModels = settings.favoriteModels.map { mapId(it) ?: it },
            chatModelId = mapId(settings.chatModelId) ?: settings.chatModelId,
            fastModelId = mapId(settings.fastModelId) ?: settings.fastModelId,
            memoryModelId = mapId(settings.memoryModelId),
            imageGenerationModelId = mapId(settings.imageGenerationModelId) ?: settings.imageGenerationModelId,
            translateModeId = mapId(settings.translateModeId) ?: settings.translateModeId,
            ocrModelId = mapId(settings.ocrModelId) ?: settings.ocrModelId,
            assistantId = mapId(settings.assistantId) ?: settings.assistantId,
            selectedTTSProviderId = mapId(settings.selectedTTSProviderId) ?: settings.selectedTTSProviderId,
            selectedASRProviderId = mapId(settings.selectedASRProviderId),
            themeId = idMap[settings.themeId] ?: settings.themeId,
            assistants = settings.assistants.map { assistant ->
                assistant.copy(
                    chatModelId = mapId(assistant.chatModelId),
                    tags = assistant.tags.map { mapId(it) ?: it },
                    quickMessageIds = assistant.quickMessageIds.map { mapId(it) ?: it }.toSet(),
                    mcpServers = assistant.mcpServers.map { mapId(it) ?: it }.toSet(),
                    workspaceId = mapId(assistant.workspaceId),
                    modeInjectionIds = assistant.modeInjectionIds.map { mapId(it) ?: it }.toSet(),
                    lorebookIds = assistant.lorebookIds.map { mapId(it) ?: it }.toSet(),
                    presetMessages = assistant.presetMessages.map { message ->
                        message.copy(modelId = mapId(message.modelId))
                    },
                )
            },
        )
    }

    private fun mapId(uuid: Uuid?): Uuid? {
        if (uuid == null) return null
        val mapped = idMap[uuid.toString()] ?: return uuid
        return Uuid.parse(mapped)
    }

    // ---- 工具 ----

    private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun uniqueName(base: String, taken: List<String>): String {
        if (taken.none { sameName(it, base) }) return base
        var n = 2
        while (taken.any { sameName(it, "$base ($n)") }) n++
        return "$base ($n)"
    }

    private fun <T> fingerprint(serializer: KSerializer<T>, item: T): String =
        json.encodeToJsonElement(serializer, item).withoutIds().toString()

    private fun JsonElement.withoutIds(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries.filter { it.key != "id" }.associate { it.key to it.value.withoutIds() }
        )
        is JsonArray -> JsonArray(map { it.withoutIds() })
        else -> this
    }

    private fun <T> patch(serializer: KSerializer<T>, item: T, field: String, value: String): T {
        val obj = json.encodeToJsonElement(serializer, item) as JsonObject
        val map = obj.toMutableMap()
        map[field] = JsonPrimitive(value)
        return json.decodeFromJsonElement(serializer, JsonObject(map))
    }

    private fun modelKey(model: MediaGenerationModel): String =
        model.displayName.ifEmpty { model.modelId }

    private fun modelKey(model: Model): String =
        model.displayName.ifEmpty { model.modelId }
}
