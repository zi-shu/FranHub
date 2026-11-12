package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConfigMergeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun merge(backup: Settings, local: Settings): SettingsMergeResult =
        ConfigMerge(json).mergeSettings(backup, local)

    private fun mcp(name: String, url: String) = McpServerConfig.StreamableHTTPServer(
        id = Uuid.random(),
        commonOptions = McpCommonOptions(name = name),
        url = url,
    )

    private fun mcpNames(settings: Settings) = settings.mcpServers.map { it.commonOptions.name }

    @Test
    fun `本地ABC备份BCD 一致跳过 只补D 结果ABCD`() {
        val localA = mcp("A", "http://a")
        val localB = mcp("B", "http://b")
        val localC = mcp("C", "http://c")
        val backupB = mcp("B", "http://b")
        val backupC = mcp("C", "http://c")
        val backupD = mcp("D", "http://d")
        val local = Settings(mcpServers = listOf(localA, localB, localC))
        val backup = Settings(mcpServers = listOf(backupB, backupC, backupD))

        val result = merge(backup, local)

        assertEquals(listOf("B", "C", "D", "A"), mcpNames(result.settings))
        // B C 一致跳过：保留本地条目
        assertEquals(localB.id, result.settings.mcpServers[0].id)
        assertEquals(localC.id, result.settings.mcpServers[1].id)
        // D 导入：id 不变
        assertEquals(backupD.id, result.settings.mcpServers[2].id)
        // A 本地独有：保留
        assertEquals(localA.id, result.settings.mcpServers[3].id)
        // id 映射：备份 B -> 本地 B
        assertEquals(localB.id.toString(), result.idMap[backupB.id.toString()])
        assertEquals(localC.id.toString(), result.idMap[backupC.id.toString()])
    }

    @Test
    fun `同名冲突 备份条目改名加序号导入 本地条目不动`() {
        val localWeather = mcp("天气", "http://local")
        val backupWeather = mcp("天气", "http://backup")
        val local = Settings(mcpServers = listOf(localWeather))
        val backup = Settings(mcpServers = listOf(backupWeather))

        val result = merge(backup, local)

        assertEquals(listOf("天气 (2)", "天气"), mcpNames(result.settings))
        assertEquals(localWeather.id, result.settings.mcpServers[1].id)
        val renamed = result.settings.mcpServers[0]
        assertNotEquals(backupWeather.id, renamed.id)
        assertEquals("http://backup", (renamed as McpServerConfig.StreamableHTTPServer).url)
        assertEquals(renamed.id.toString(), result.idMap[backupWeather.id.toString()])
    }

    @Test
    fun `改名序号递增 避开已占用的名字`() {
        val localA = mcp("天气", "http://a")
        val localB = mcp("天气 (2)", "http://b")
        val backup = mcp("天气", "http://backup")
        val local = Settings(mcpServers = listOf(localA, localB))
        val backupSettings = Settings(mcpServers = listOf(backup))

        val result = merge(backupSettings, local)

        assertTrue(mcpNames(result.settings).contains("天气 (3)"))
    }

    @Test
    fun `助手引用一致跳过的MCP 引用重写到本地id`() {
        val localMcp = mcp("工具", "http://tool")
        val backupMcp = mcp("工具", "http://tool")
        val backupAssistant = Assistant(
            id = Uuid.random(),
            name = "助手甲",
            mcpServers = setOf(backupMcp.id),
        )
        val local = Settings(mcpServers = listOf(localMcp))
        val backup = Settings(mcpServers = listOf(backupMcp), assistants = listOf(backupAssistant))

        val result = merge(backup, local)

        val mergedAssistant = result.settings.assistants.first { it.name == "助手甲" }
        assertEquals(setOf(localMcp.id), mergedAssistant.mcpServers)
    }

    @Test
    fun `供应商一致跳过 模型id与引用重写`() {
        val localModel = Model(modelId = "ds-v4", displayName = "DeepSeek V4", id = Uuid.random())
        val backupModel = Model(modelId = "ds-v4", displayName = "DeepSeek V4", id = Uuid.random())
        val localProvider = ProviderSetting.OpenAI(id = Uuid.random(), name = "深度求索", models = listOf(localModel))
        val backupProvider = ProviderSetting.OpenAI(id = Uuid.random(), name = "深度求索", models = listOf(backupModel))
        val local = Settings(providers = listOf(localProvider))
        val backup = Settings(
            providers = listOf(backupProvider),
            chatModelId = backupModel.id,
        )

        val result = merge(backup, local)

        // 全局聊天模型引用重写到本地模型
        assertEquals(localModel.id, result.settings.chatModelId)
        // 供应商条目保留本地
        assertEquals(localProvider.id, result.settings.providers[0].id)
    }

    @Test
    fun `供应商冲突改名 模型换新id 引用跟着新id`() {
        val backupModel = Model(modelId = "m1", displayName = "模型一", id = Uuid.random())
        val localModel = Model(modelId = "m1", displayName = "模型一", id = Uuid.random())
        val localProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "中转站",
            baseUrl = "http://local",
            models = listOf(localModel),
        )
        val backupProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "中转站",
            baseUrl = "http://backup",
            models = listOf(backupModel),
        )
        val local = Settings(providers = listOf(localProvider))
        val backup = Settings(
            providers = listOf(backupProvider),
            chatModelId = backupModel.id,
        )

        val result = merge(backup, local)

        // 两个供应商并存
        assertEquals(2, result.settings.providers.size)
        val renamed = result.settings.providers.first { it.name == "中转站 (2)" }
        assertNotEquals(backupProvider.id, renamed.id)
        // 改名供应商的模型换了新 id
        assertNotEquals(backupModel.id, renamed.models[0].id)
        // 全局引用指向改名供应商的新模型
        assertEquals(renamed.models[0].id, result.settings.chatModelId)
    }

    @Test
    fun `无名字类型冲突 只换新id 名字不变`() {
        val localBing = SearchServiceOptions.BingLocalOptions()
        val backupBing = SearchServiceOptions.BingLocalOptions()
        val local = Settings(searchServices = listOf(localBing))
        val backup = Settings(searchServices = listOf(backupBing))

        val result = merge(backup, local)

        // Bing 类型两边"内容"相同（都无配置），应一致跳过
        assertEquals(1, result.settings.searchServices.size)
        assertEquals(localBing.id, result.settings.searchServices[0].id)
    }

    @Test
    fun `无名字类型内容不一致 换新id导入并存`() {
        val localBing = SearchServiceOptions.ZhipuOptions(apiKey = "key-local")
        val backupBing = SearchServiceOptions.ZhipuOptions(apiKey = "key-backup")
        val local = Settings(searchServices = listOf(localBing))
        val backup = Settings(searchServices = listOf(backupBing))

        val result = merge(backup, local)

        assertEquals(2, result.settings.searchServices.size)
        val imported = result.settings.searchServices.first { it !== localBing }
        assertNotEquals(backupBing.id, imported.id)
        assertEquals("key-backup", (imported as SearchServiceOptions.ZhipuOptions).apiKey)
    }

    @Test
    fun `计划清单包含全部动作类型`() {
        val localA = mcp("A", "http://a")
        val localB = mcp("B", "http://b")
        val backupB = mcp("B", "http://b")
        val backupC = mcp("C", "http://c")
        val backupD = mcp("D", "http://d")
        val local = Settings(mcpServers = listOf(localA, localB))
        val backup = Settings(mcpServers = listOf(backupB, backupC, backupD))

        val result = merge(backup, local)

        val byName = result.plan.associateBy { it.name }
        assertEquals(MergeAction.SKIP, byName["B"]?.action)
        assertEquals(MergeAction.IMPORT, byName["C"]?.action)
        assertEquals(MergeAction.IMPORT, byName["D"]?.action)
        assertEquals(MergeAction.LOCAL_ONLY, byName["A"]?.action)
    }

    @Test
    fun `名字匹配忽略大小写与首尾空格`() {
        val localMcp = mcp("My Tool", "http://same")
        val backupMcp = mcp("  my tool  ", "http://same")
        val local = Settings(mcpServers = listOf(localMcp))
        val backup = Settings(mcpServers = listOf(backupMcp))

        val result = merge(backup, local)

        // 归一化名字匹配上；原文名字不同，内容判为不一致，改名导入并存
        assertEquals(2, result.settings.mcpServers.size)
        assertEquals(localMcp.id, result.settings.mcpServers[1].id)
        val renamed = result.settings.mcpServers[0]
        assertEquals("my tool (2)", renamed.commonOptions.name)
    }

    @Test
    fun `内容一致判定忽略随机id字段`() {
        // 两个条目只有 id 与嵌套 id（预设消息）不同，其余全同，应判定一致
        val presetId1 = Uuid.random()
        val presetId2 = Uuid.random()
        val assistant1 = Assistant(
            id = Uuid.random(),
            name = "翻译官",
            systemPrompt = "你是翻译",
            presetMessages = listOf(
                me.rerere.ai.ui.UIMessage(
                    id = presetId1,
                    role = me.rerere.ai.core.MessageRole.USER,
                    parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("你好")),
                )
            ),
        )
        val assistant2 = assistant1.copy(
            id = Uuid.random(),
            presetMessages = assistant1.presetMessages.map { it.copy(id = presetId2) },
        )
        val local = Settings(assistants = listOf(assistant1))
        val backup = Settings(assistants = listOf(assistant2))

        val result = merge(backup, local)

        assertEquals(1, result.settings.assistants.size)
        assertEquals(assistant1.id, result.settings.assistants[0].id)
    }
}
