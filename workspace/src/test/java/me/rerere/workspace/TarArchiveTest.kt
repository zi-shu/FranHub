package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

class TarArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trip keeps files directories links and modes`() {
        val source = tmp.newFolder("src")
        File(source, "readme.md").writeText("hello 世界")
        File(source, "bin").mkdirs()
        File(source, "bin/run.sh").apply {
            writeText("#!/bin/sh\necho hi\n")
            setExecutable(true, false)
        }
        File(source, "empty").mkdirs()
        val longPath = "a".repeat(60) + "/" + "b".repeat(60) + "/deep.txt"
        File(source, longPath).apply { parentFile?.mkdirs() }.writeText("deep")
        File(source, "target.txt").writeText("target")
        Files.createSymbolicLink(File(source, "link-relative").toPath(), File("target.txt").toPath())
        Files.createSymbolicLink(File(source, "link-absolute").toPath(), File("/bin/busybox").toPath())
        val big = File(source, "big.bin")
        big.outputStream().use { out ->
            val chunk = ByteArray(8192) { (it % 251).toByte() }
            repeat(128) { out.write(chunk) }
        }

        val archive = tmp.newFile("tree.tar")
        archive.outputStream().use { TarArchive.write(source, it) }

        val target = tmp.newFolder("out")
        var count = 0
        archive.inputStream().use { count = TarArchive.extract(it, target) }

        assertEquals("hello 世界", File(target, "readme.md").readText())
        assertTrue(File(target, "bin").isDirectory)
        assertEquals("#!/bin/sh\necho hi\n", File(target, "bin/run.sh").readText())
        assertTrue("可执行位必须保留", File(target, "bin/run.sh").canExecute())
        assertTrue("空目录必须建回", File(target, "empty").isDirectory)
        assertEquals("deep", File(target, longPath).readText())
        assertTrue(Files.isSymbolicLink(File(target, "link-relative").toPath()))
        assertEquals("target.txt", Files.readSymbolicLink(File(target, "link-relative").toPath()).toString())
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(target, "link-absolute").toPath()).toString())
        assertEquals(big.length(), File(target, "big.bin").length())
        assertTrue(big.readBytes().contentEquals(File(target, "big.bin").readBytes()))
        assertTrue("条目数至少覆盖 9 个成员，实际 $count", count >= 9)
    }

    @Test
    fun `extract rejects paths escaping the target`() {
        val archive = tmp.newFile("evil.tar")
        archive.outputStream().use { out ->
            out.writeTarEntry("../evil.txt", '0', "bad".toByteArray())
            out.writeTarEntry("ok.txt", '0', "good".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        archive.inputStream().use { TarArchive.extract(it, target) }

        assertFalse(File(target.parentFile, "evil.txt").exists())
        assertEquals("good", File(target, "ok.txt").readText())
    }

    @Test
    fun `extract skips entries rejected by the filter`() {
        val source = tmp.newFolder("src")
        File(source, "keep.txt").writeText("keep")
        File(source, "tmp").mkdirs()
        File(source, "tmp/scratch.txt").writeText("scratch")

        val archive = tmp.newFile("filtered.tar")
        archive.outputStream().use {
            TarArchive.write(source, it, filter = { path -> !path.startsWith("tmp") })
        }

        val target = tmp.newFolder("out")
        archive.inputStream().use { TarArchive.extract(it, target) }

        assertEquals("keep", File(target, "keep.txt").readText())
        assertFalse(File(target, "tmp").exists())
    }

    /**
     * 写一份样例归档到临时目录，供宿主机 tar 交叉验证。
     * 测试本身只断言归档已写出，GNU tar 的兼容性在构建后单独验证。
     */
    @Test
    fun `dumps sample archive for interop check`() {
        val source = tmp.newFolder("src")
        File(source, "a.txt").writeText("aaa")
        File(source, "dir").mkdirs()
        File(source, "dir/b.txt").writeText("bbb")
        Files.createSymbolicLink(File(source, "link").toPath(), File("a.txt").toPath())
        val output = File(System.getProperty("java.io.tmpdir"), "TarArchiveTest_interop.tar")
        output.outputStream().use { TarArchive.write(source, it) }
        assertTrue("样例归档应已写出: ${output.absolutePath}", output.length() > 0)
    }

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray, linkName: String = "") {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
        header[156] = type.code.toByte()
        linkName.toByteArray(Charsets.UTF_8).copyInto(header, 157)
        write(header)
        write(data)
        write(ByteArray((TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK))
    }

    companion object {
        private const val TAR_BLOCK = 512
    }
}
