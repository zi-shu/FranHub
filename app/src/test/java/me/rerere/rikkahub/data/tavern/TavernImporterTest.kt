package me.rerere.rikkahub.data.tavern

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernImporterTest {

    @Test
    fun `v2 card builds a new assistant with first message and character book`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Charlotte",
                "description": "A cafe maid.",
                "personality": "Calm and polite.",
                "scenario": "A quiet cafe.",
                "first_mes": "Welcome back, master.",
                "mes_example": "{{user}}: hello\n{{char}}: welcome",
                "system_prompt": "Stay in character.",
                "character_book": {
                  "name": "Cafe Lore",
                  "entries": [
                    {
                      "keys": ["cafe", "coffee"],
                      "content": "The cafe opens at 8.",
                      "enabled": true,
                      "insertion_order": 5,
                      "constant": false,
                      "position": "after_char",
                      "depth": 4,
                      "role": "system"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = TavernImporter.readCharacterCard(context = null, jsonText = json, background = "file://bg.png")

        assertEquals("Charlotte", result.assistant.name)
        assertEquals("file://bg.png", result.assistant.background)
        assertTrue(result.assistant.systemPrompt.contains("## Description of the character"))
        assertTrue(result.assistant.systemPrompt.contains("## Example dialogue"))
        assertEquals(1, result.assistant.presetMessages.size)
        val firstText = result.assistant.presetMessages.first()
            .parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertEquals("Welcome back, master.", firstText)

        val lorebook = requireNotNull(result.lorebook)
        assertEquals("Cafe Lore", lorebook.name)
        assertEquals(1, lorebook.entries.size)
        val entry = lorebook.entries.first()
        assertEquals(listOf("cafe", "coffee"), entry.keywords)
        assertEquals(5, entry.priority)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, entry.position)
        assertTrue(result.assistant.lorebookIds.contains(lorebook.id))
    }

    @Test
    fun `v1 card reads fields from the top level`() {
        val json = """
            {
              "name": "Suma",
              "description": "A cheerful assistant.",
              "personality": "Curious.",
              "scenario": "An office.",
              "first_mes": "Hi there!",
              "mes_example": "{{char}}: hi"
            }
        """.trimIndent()

        val result = TavernImporter.readCharacterCard(context = null, jsonText = json, background = null)

        assertEquals("Suma", result.assistant.name)
        assertEquals(1, result.assistant.presetMessages.size)
        assertEquals(null, result.lorebook)
    }

    @Test
    fun `standalone world info accepts sillytavern field names`() {
        val json = """
            {
              "entries": {
                "0": {
                  "key": ["harbor", "ship"],
                  "content": "The harbor closes at dusk.",
                  "constant": true,
                  "order": 12,
                  "position": 1,
                  "depth": 3,
                  "disable": false,
                  "caseSensitive": true,
                  "useRegex": false
                },
                "1": {
                  "key": ["unused"],
                  "content": "Disabled entry.",
                  "disable": true
                }
              }
            }
        """.trimIndent()

        val lorebook = TavernImporter.readLorebook(context = null, jsonText = json, fallbackName = "Harbor")

        assertEquals("Harbor", lorebook.name)
        assertEquals(2, lorebook.entries.size)
        val active = lorebook.entries.first { it.content == "The harbor closes at dusk." }
        assertEquals(listOf("harbor", "ship"), active.keywords)
        assertEquals(12, active.priority)
        assertEquals(3, active.injectDepth)
        assertTrue(active.constantActive)
        assertTrue(active.caseSensitive)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, active.position)
        val disabled = lorebook.entries.first { it.content == "Disabled entry." }
        assertEquals(false, disabled.enabled)
    }

    @Test
    fun `first message placeholders are filled at import`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Charlotte",
                "first_mes": "Hello {{user}}, I am {{char}}."
              }
            }
        """.trimIndent()

        val result = TavernImporter.readCharacterCard(
            context = null,
            jsonText = json,
            background = null,
            userName = "小明",
        )

        val text = result.assistant.presetMessages.first()
            .parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertEquals("Hello 小明, I am Charlotte.", text)
    }

    @Test
    fun `invalid json is rejected`() {
        val failure = runCatching {
            TavernImporter.readCharacterCard(context = null, jsonText = "{oops", background = null)
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `character book entries are turned into regex injections`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Nova",
                "character_book": {
                  "entries": [
                    { "keys": ["a"], "content": "first", "insertion_order": 1 },
                    { "keys": ["b"], "content": "second", "insertion_order": 2 }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = TavernImporter.readCharacterCard(context = null, jsonText = json, background = null)

        val entries = requireNotNull(result.lorebook).entries
        assertEquals(2, entries.size)
        assertEquals(listOf("a", "b"), entries.map { it.keywords.first() })
    }
}
