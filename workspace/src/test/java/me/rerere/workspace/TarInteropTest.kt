package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 与宿主机 GNU tar 的双向互操作验证。
 * 本机没有 tar 命令时整类跳过，不影响纯 JVM 环境的构建。
 */
class TarInteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun hasTar(): Boolean = runCatching {
        ProcessBuilder("tar", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun `reads archive produced by gnu tar`() {
        assumeTrue("本机没有 tar 命令", hasTar())
        val source = tmp.newFolder("src")
        File(source, "plain.txt").writeText("plain")
        val longRelative = "long-" + "x".repeat(120) + ".txt"
        File(source, longRelative).writeText("long name")
        File(source, "sub").mkdirs()
        File(source, "sub/inner.txt").writeText("inner")
        Files.createSymbolicLink(File(source, "sub/link").toPath(), File("inner.txt").toPath())

        val archive = File(tmp.root, "gnu.tar")
        val process = ProcessBuilder("tar", "-cf", archive.absolutePath, "-C", source.absolutePath, ".")
            .redirectErrorStream(true)
            .start()
        assertEquals("gnu tar 打包失败", 0, process.waitFor())

        val target = tmp.newFolder("out")
        archive.inputStream().use { TarArchive.extract(it, target) }

        assertEquals("plain", File(target, "plain.txt").readText())
        assertEquals("long name", File(target, longRelative).readText())
        assertEquals("inner", File(target, "sub/inner.txt").readText())
        assertTrue(Files.isSymbolicLink(File(target, "sub/link").toPath()))
    }

    @Test
    fun `archive written here is readable by gnu tar`() {
        assumeTrue("本机没有 tar 命令", hasTar())
        val source = tmp.newFolder("src")
        File(source, "a.txt").writeText("aaa")
        File(source, "run.sh").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true, false)
        }
        Files.createSymbolicLink(File(source, "link").toPath(), File("a.txt").toPath())

        val archive = File(tmp.root, "ours.tar")
        archive.outputStream().use { TarArchive.write(source, it) }

        val listing = ProcessBuilder("tar", "-tvf", archive.absolutePath)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()
        assertTrue("GNU tar 应能列出 a.txt: $listing", listing.contains("a.txt"))
        assertTrue("GNU tar 应识别软链接: $listing", listing.contains("link -> a.txt"))
        assertTrue("可执行位应为 rwxr-xr-x: $listing", listing.contains("rwxr-xr-x"))
    }
}
