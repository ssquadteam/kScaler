package com.github.ssquadteam.kscaler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

class KScalerPackTest {
    @Test
    fun `pack holds the marker font and both shader stages for every overlay`() {
        val target = File(System.getProperty("java.io.tmpdir"), "kscaler-pack-test.zip")
        val pack = KScalerPack("example")
        val sha1 = pack.write(target, "Example \"pack\"")
        assertEquals(sha1, pack.write(target, "Example \"pack\""))
        ZipFile(target).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("assets/example/font/scale.json" in names)
            assertTrue("assets/example/textures/font/scale.png" in names)
            KScalerPack.OVERLAYS.forEach { overlay ->
                assertEquals(2, names.count { it.startsWith("${overlay.directory}/assets/minecraft/shaders/core/") })
            }
            val meta = zip.getInputStream(zip.getEntry("pack.mcmeta")).readBytes().decodeToString()
            assertTrue("\"Example \\\"pack\\\"\"" in meta)
            KScalerPack.OVERLAYS.forEach { assertTrue("\"directory\":\"${it.directory}\"" in meta) }
        }
        target.delete()
    }
}
