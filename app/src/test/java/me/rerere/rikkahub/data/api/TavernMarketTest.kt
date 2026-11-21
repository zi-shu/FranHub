package me.rerere.rikkahub.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernMarketTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `market entry parses from index array`() {
        val text = """
            [
              {
                "id": "Anonymous/marcus-dd23bc70e28c",
                "name": "Marcus",
                "description": "A refined presence.",
                "tags": ["R18", "Male"],
                "emoji": "💕",
                "file": "https://avatars.charhub.io/avatars/Anonymous/marcus-dd23bc70e28c/chara_card_v2.png",
                "type": "card"
              }
            ]
        """.trimIndent()

        val listings = json.decodeFromString<List<TavernListing>>(text)

        assertEquals(1, listings.size)
        val listing = listings.first()
        assertEquals("Marcus", listing.name)
        assertEquals(listOf("R18", "Male"), listing.tags)
        assertEquals("card", listing.type)
    }

    @Test
    fun `market entry falls back to defaults for optional fields`() {
        val text = """[{"id":"a/b","name":"Suma"}]"""

        val listing = json.decodeFromString<List<TavernListing>>(text).first()

        assertEquals("", listing.description)
        assertEquals(emptyList<String>(), listing.tags)
        assertEquals("card", listing.type)
        assertEquals("\uD83C\uDFAD", listing.emoji)
    }

    @Test
    fun `search matches name description and tags`() {
        val listing = TavernListing(
            id = "a/b",
            name = "Charlotte",
            description = "A young nun in a fantasy kingdom.",
            tags = listOf("nun", "Fantasy"),
        )

        assertTrue(listing.matches("char"))
        assertTrue(listing.matches("fantasy"))
        assertTrue(listing.matches("nun"))
        assertTrue(listing.matches("  "))
        assertFalse(listing.matches("driver"))
    }
}
