package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageInventoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `collect reads apk dpkg pip and npm metadata from a rootfs tree`() {
        val linux = tmp.newFolder("linux")
        File(linux, "lib/apk/db").mkdirs()
        File(linux, "lib/apk/db/installed").writeText(
            "C:Q1abcdef\nP:musl\nV:1.2.4-r2\nA:x86_64\n\nC:Q1ghijkl\nP:python3\nV:3.12.0-r0\nA:x86_64\n"
        )
        File(linux, "var/lib/dpkg").mkdirs()
        File(linux, "var/lib/dpkg/status").writeText(
            "Package: curl\nStatus: install ok installed\nVersion: 8.5.0-2\n\n" +
                "Package: vim\nStatus: deinstall ok config-files\nVersion: 9.0.1-1\n"
        )
        val distInfo = File(linux, "usr/lib/python3.12/site-packages/requests-2.31.0.dist-info")
        distInfo.mkdirs()
        File(distInfo, "METADATA").writeText(
            "Metadata-Version: 2.1\nName: requests\nVersion: 2.31.0\nSummary: sample\n\n"
        )
        val npmPackage = File(linux, "usr/lib/node_modules/pnpm")
        npmPackage.mkdirs()
        File(npmPackage, "package.json").writeText("""{"name":"pnpm","version":"9.1.0","bin":{}}""")
        // 依赖树里的 package.json 不应进清单
        val nested = File(linux, "usr/lib/node_modules/pnpm/node_modules/isexe")
        nested.mkdirs()
        File(nested, "package.json").writeText("""{"name":"isexe","version":"2.0.0"}""")
        // 缓存目录应被剪掉
        File(linux, "root/.cache/pip").mkdirs()
        val fake = File(linux, "root/.cache/pip/fake.dist-info")
        fake.mkdirs()
        File(fake, "METADATA").writeText("Name: fake\nVersion: 0.0.0\n")

        val inventory = PackageInventory.collect(linux)

        assertEquals(listOf("musl", "python3"), inventory.apk.map { it.name })
        assertEquals("1.2.4-r2", inventory.apk.first().version)
        assertEquals(listOf("curl"), inventory.dpkg.map { it.name })
        assertEquals(listOf("requests"), inventory.pip.map { it.name })
        assertEquals(listOf("pnpm"), inventory.npm.map { it.name })
        assertEquals(5, inventory.total)
    }

    @Test
    fun `missing files yield empty inventory`() {
        val linux = tmp.newFolder("empty")
        val inventory = PackageInventory.collect(linux)
        assertTrue(inventory.apk.isEmpty())
        assertTrue(inventory.dpkg.isEmpty())
        assertTrue(inventory.pip.isEmpty())
        assertTrue(inventory.npm.isEmpty())
        assertEquals(0, inventory.total)
    }

    @Test
    fun `malformed records are dropped instead of throwing`() {
        assertNull(PackageInventory.parsePipMetadata("Name: broken"))
        assertNull(PackageInventory.parseNpmPackageJson("not json"))
        assertNull(PackageInventory.parseNpmPackageJson("""{"name":"x"}"""))
        assertEquals(
            PackageEntry("x", "1"),
            PackageInventory.parseNpmPackageJson("""{"name":"x","version":"1"}"""),
        )
    }
}
