package me.rerere.rikkahub.data.tavern

import android.content.Context
import android.net.Uri
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.png.PngChunkType
import com.drew.metadata.png.PngDirectory
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.utils.ImageUtils

/**
 * 酒馆角色卡解析结果。
 *
 * [assistant] 带新的 Uuid，导入时直接追加到 settings.assistants，不改动已有助手。
 * [lorebook] 卡片内嵌的 character_book，没有时为 null。
 */
data class TavernCardImport(
    val assistant: Assistant,
    val lorebook: Lorebook?,
)

/** 一张角色卡的原始字段，V1 与 V2/V3 解析后统一成这个结构 */
data class TavernCharacter(
    val name: String,
    val description: String? = null,
    val personality: String? = null,
    val scenario: String? = null,
    val systemPrompt: String? = null,
    val firstMessage: String? = null,
    val exampleDialogue: String? = null,
    val creatorNotes: String? = null,
    val characterBook: JsonObject? = null,
)

object TavernImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 读取角色卡 JSON 文本，生成新助手与可选世界书 */
    fun readCharacterCard(
        context: Context?,
        jsonText: String,
        background: String?,
        userName: String = "user",
    ): TavernCardImport {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { error(context.invalidJsonMessage()) }
        val character = parseCharacter(context, root)
        val lorebook = character.characterBook?.let { book ->
            parseLorebookObject(context, book, fallbackName = character.name)
        }
        val assistant = buildAssistant(character, background, userName).let { base ->
            if (lorebook != null) {
                base.copy(lorebookIds = base.lorebookIds + lorebook.id)
            } else {
                base
            }
        }
        return TavernCardImport(assistant = assistant, lorebook = lorebook)
    }

    /** 读取世界书 JSON 文本，生成 Lorebook */
    fun readLorebook(
        context: Context?,
        jsonText: String,
        fallbackName: String,
    ): Lorebook {
        val root = runCatching { json.parseToJsonElement(jsonText) }
            .getOrElse { error(context.invalidJsonMessage()) }
        return when (root) {
            is JsonObject -> parseLorebookObject(context, root, fallbackName)
            is JsonArray -> buildLorebook(context, root.objects(), fallbackName)
            else -> error(context.invalidJsonMessage())
        }
    }

    /** 从下载到的字节读取角色卡，PNG 同时保存为背景图 */
    suspend fun readCardBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        filesManager: FilesManager,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        if (!isPng(bytes)) {
            return@withContext bytes.toString(Charsets.UTF_8) to null
        }
        val jsonText = extractCharaJson(context, bytes)
        val background = filesManager.createChatFilesByByteArrays(
            byteArrays = listOf(bytes),
            displayName = fileName.ifBlank { "tavern_card.png" },
            mimeType = "image/png",
        ).firstOrNull()?.toString()
        jsonText to background
    }

    /** 从 PNG 或 JSON 文件读出角色卡文本，PNG 同时保存为背景图 */
    suspend fun readCardPayload(
        context: Context,
        uri: Uri,
        filesManager: FilesManager,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val mime = filesManager.getFileMimeType(uri)
        if (mime == "image/png") {
            val result = ImageUtils.getTavernCharacterMeta(context, uri)
            val base64Data = result.getOrElse { throw it }
            val text = String(android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT))
            val background = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()?.toString()
            return@withContext text to background
        }
        if (mime?.startsWith("image/") == true) {
            error(context.getString(R.string.assistant_importer_unsupported_file_type, mime))
        }
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()
            .use { it?.readText() }
            ?: error(context.getString(R.string.assistant_importer_read_json_failed))
        text to null
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() &&
            bytes[3] == 0x47.toByte()

    private fun extractCharaJson(context: Context, bytes: ByteArray): String {
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        val directory = metadata.getDirectoriesOfType(PngDirectory::class.java).firstOrNull { dir ->
            dir.pngChunkType == PngChunkType.tEXt &&
                dir.getString(PngDirectory.TAG_TEXTUAL_DATA)?.contains("[chara:") == true
        } ?: error(
            context.getString(R.string.tavern_import_png_no_chara)
        )
        val value = directory.getString(PngDirectory.TAG_TEXTUAL_DATA)
            ?: error(context.getString(R.string.tavern_import_png_no_chara))
        val base64 = Regex("""\[chara:\s*(.+?)]""").find(value)?.groupValues?.get(1)
            ?: error(context.getString(R.string.tavern_import_png_no_chara))
        return String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
    }

    private fun parseCharacter(context: Context?, root: JsonObject): TavernCharacter {
        val data = (root["data"] as? JsonObject) ?: root
        val name = data.string("name")
            ?: error(context.missingNameMessage())
        val book = (data["character_book"] as? JsonObject)
            ?: (root["character_book"] as? JsonObject)
        return TavernCharacter(
            name = name,
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            systemPrompt = data.string("system_prompt"),
            firstMessage = data.string("first_mes"),
            exampleDialogue = data.string("mes_example"),
            creatorNotes = data.string("creator_notes") ?: data.string("creatorcomment"),
            characterBook = book,
        )
    }

    private fun buildAssistant(
        character: TavernCharacter,
        background: String?,
        userName: String,
    ): Assistant {
        fun String.fillPlaceholders(): String = replace("{{char}}", character.name)
            .replace("{{user}}", userName)
        val prompt = buildString {
            appendLine("You are roleplaying as {{char}}.")
            appendLine()
            character.systemPrompt?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("## Description of the character")
            appendLine(character.description ?: "Empty")
            appendLine()
            appendLine("## Personality of the character")
            appendLine(character.personality ?: "Empty")
            appendLine()
            appendLine("## Scenario")
            appendLine(character.scenario ?: "Empty")
            character.exampleDialogue?.let {
                appendLine()
                appendLine("## Example dialogue")
                appendLine(it)
            }
        }
        return Assistant(
            name = character.name,
            presetMessages = character.firstMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { message -> listOf(UIMessage.assistant(message.fillPlaceholders())) }
                ?: emptyList(),
            systemPrompt = prompt,
            background = background,
        )
    }

    private fun parseLorebookObject(
        context: Context?,
        book: JsonObject,
        fallbackName: String,
    ): Lorebook {
        val entries = when (val element = book["entries"]) {
            is JsonArray -> element.objects()
            is JsonObject -> element.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        val name = book.string("name") ?: fallbackName
        return buildLorebook(context, entries, name)
    }

    private fun buildLorebook(
        context: Context?,
        entries: List<JsonObject>,
        name: String,
    ): Lorebook {
        val injections = entries.mapIndexedNotNull { index, entry ->
            val content = entry.string("content") ?: return@mapIndexedNotNull null
            if (content.isBlank()) return@mapIndexedNotNull null
            PromptInjection.RegexInjection(
                name = entry.string("comment")
                    ?: entry.string("name")
                    ?: context?.getString(R.string.tavern_lorebook_entry_name, index + 1)
                    ?: "Entry ${index + 1}",
                enabled = entry.enabled(),
                priority = entry.int("insertion_order") ?: entry.int("order") ?: entry.int("priority") ?: 0,
                position = entry.position(),
                content = content,
                injectDepth = entry.intWithExtensions("depth") ?: 4,
                role = entry.role(),
                keywords = entry.stringList("keys").ifEmpty { entry.stringList("key") },
                useRegex = entry.boolWithExtensions("use_regex")
                    ?: entry.boolWithExtensions("useRegex")
                    ?: false,
                caseSensitive = entry.boolWithExtensions("case_sensitive")
                    ?: entry.boolWithExtensions("caseSensitive")
                    ?: false,
                constantActive = entry.bool("constant") ?: false,
            )
        }
        return Lorebook(name = name, entries = injections)
    }

    private fun Context?.invalidJsonMessage(): String =
        this?.getString(R.string.tavern_import_invalid_json) ?: "Invalid JSON"

    private fun Context?.missingNameMessage(): String =
        this?.getString(R.string.assistant_importer_missing_name_field) ?: "Missing name field"

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

    private fun JsonObject.string(key: String): String? {
        val value = (this[key] as? JsonPrimitive)?.contentOrNull
        return value?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.enabled(): Boolean {
        bool("enabled")?.let { return it }
        bool("disable")?.let { return !it }
        return true
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = (this[key] as? JsonPrimitive) ?: return null
        return value.contentOrNull?.toBooleanStrictOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        val value = (this[key] as? JsonPrimitive) ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.boolWithExtensions(key: String): Boolean? =
        bool(key) ?: (this["extensions"] as? JsonObject)?.bool(key)

    private fun JsonObject.intWithExtensions(key: String): Int? =
        int(key) ?: (this["extensions"] as? JsonObject)?.int(key)

    private fun JsonObject.stringList(key: String): List<String> {
        val element: JsonElement = this[key] ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.contentOrNull?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun JsonObject.position(): InjectionPosition {
        val raw = (this["position"] as? JsonPrimitive)?.contentOrNull
            ?: ((this["extensions"] as? JsonObject)?.get("position") as? JsonPrimitive)?.contentOrNull
            ?: return InjectionPosition.AFTER_SYSTEM_PROMPT
        return when (raw) {
            "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "at_depth" -> InjectionPosition.AT_DEPTH
            else -> when (raw.toIntOrNull()) {
                0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
                2, 3 -> InjectionPosition.BOTTOM_OF_CHAT
                4 -> InjectionPosition.AT_DEPTH
                else -> InjectionPosition.AFTER_SYSTEM_PROMPT
            }
        }
    }

    private fun JsonObject.role(): MessageRole {
        val raw = (this["role"] as? JsonPrimitive)?.contentOrNull
            ?: ((this["extensions"] as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull
            ?: return MessageRole.SYSTEM
        return when {
            raw.equals("system", ignoreCase = true) -> MessageRole.SYSTEM
            raw.equals("user", ignoreCase = true) -> MessageRole.USER
            raw.equals("assistant", ignoreCase = true) -> MessageRole.ASSISTANT
            else -> when (raw.toIntOrNull()) {
                1 -> MessageRole.USER
                2 -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            }
        }
    }
}
