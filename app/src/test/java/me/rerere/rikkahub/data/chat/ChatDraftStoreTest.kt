package me.rerere.rikkahub.data.chat

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class ChatDraftStoreTest {

    private val rootDir = Files.createTempDirectory("chat-draft-test").toFile()

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun newStore() = ChatDraftStore(rootDir)

    @Test
    fun `save then load returns the draft text`() = runBlocking {
        val store = newStore()
        val id = Uuid.random()
        val assistant = Uuid.random()

        store.save(id, assistant, "写了一半的消息")

        assertEquals("写了一半的消息", store.load(id))
    }

    @Test
    fun `blank text removes the draft`() = runBlocking {
        val store = newStore()
        val id = Uuid.random()
        val assistant = Uuid.random()

        store.save(id, assistant, "有内容")
        store.save(id, assistant, "   \n  ")

        assertNull(store.load(id))
        assertTrue(store.drafts.value.isEmpty())
    }

    @Test
    fun `text is capped at max length`() = runBlocking {
        val store = newStore()
        val id = Uuid.random()
        val longText = "a".repeat(ChatDraftStore.MAX_TEXT_LENGTH + 500)

        store.save(id, Uuid.random(), longText)

        assertEquals(ChatDraftStore.MAX_TEXT_LENGTH, store.load(id)!!.length)
    }

    @Test
    fun `oldest drafts are evicted beyond the conversation cap`() = runBlocking {
        val store = newStore()
        val assistant = Uuid.random()

        repeat(ChatDraftStore.MAX_CONVERSATIONS + 10) { index ->
            store.save(Uuid.random(), assistant, "draft-$index")
        }

        assertEquals(ChatDraftStore.MAX_CONVERSATIONS, store.drafts.value.size)
    }

    @Test
    fun `clear removes a single draft`() = runBlocking {
        val store = newStore()
        val keep = Uuid.random()
        val drop = Uuid.random()
        val assistant = Uuid.random()

        store.save(keep, assistant, "保留")
        store.save(drop, assistant, "删除")

        store.clear(drop)

        assertNull(store.load(drop))
        assertEquals("保留", store.load(keep))
    }

    @Test
    fun `new store instance reloads drafts from disk`() = runBlocking {
        val id = Uuid.random()
        val assistant = Uuid.random()

        newStore().save(id, assistant, "重启后还在")
        val reloaded = newStore()

        assertEquals("重启后还在", reloaded.load(id))
    }

    @Test
    fun `corrupted file is quarantined and returns empty`() = runBlocking {
        File(rootDir, ChatDraftStore.FILE_NAME).writeText("not json at all")

        val store = newStore()

        assertNull(store.load(Uuid.random()))
        val quarantined = rootDir.listFiles()!!.filter { it.name.startsWith(ChatDraftStore.FILE_NAME + ".corrupt-") }
        assertEquals(1, quarantined.size)
    }

    @Test
    fun `draft file is valid json after save`() = runBlocking {
        val store = newStore()
        store.save(Uuid.random(), Uuid.random(), "序列化检查")

        val file = File(rootDir, ChatDraftStore.FILE_NAME)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("序列化检查"))
    }
}
