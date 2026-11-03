package com.github.ssquadteam.kscaler

import net.kyori.adventure.key.Key
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KScalerPack(
    private val namespace: String = "kscaler",
) {
    val font: Key get() = Key.key(namespace, "scale")

    fun entries(): Map<String, ByteArray> {
        val entries = sortedMapOf<String, ByteArray>()
        entries["assets/$namespace/textures/font/scale.png"] = resource("kscaler/probe.png")
        entries["assets/$namespace/font/scale.json"] = fontJson().toByteArray()
        OVERLAYS.forEach { overlay ->
            listOf("vsh", "fsh").forEach { stage ->
                val file = "${overlay.text}.$stage"
                entries["${overlay.directory}/assets/minecraft/shaders/core/$file"] =
                    resource("kscaler/shaders/${overlay.directory}/$file")
            }
        }
        return entries
    }

    fun mcmeta(description: String): String =
        buildString {
            append("""{"pack":{"description":${quote(description)},"pack_format":$MIN_FORMAT,""")
            append(""""supported_formats":[$MIN_FORMAT,$MAX_FORMAT],"min_format":$MIN_FORMAT,"max_format":$MAX_FORMAT},""")
            append(""""overlays":{"entries":[""")
            append(OVERLAYS.joinToString(",") { it.json() })
            append("]}}")
        }

    fun write(
        target: File,
        description: String = "kScaler",
    ): String {
        val entries = entries().toSortedMap()
        entries["pack.mcmeta"] = mcmeta(description).toByteArray()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, data) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(data)
                zip.closeEntry()
            }
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes.toByteArray())
        return MessageDigest
            .getInstance("SHA-1")
            .digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun fontJson(): String =
        """{"providers":[{"type":"bitmap","file":"$namespace:font/scale.png","ascent":${MARKER_HEIGHT - 1},""" +
            """"height":$MARKER_HEIGHT,"chars":["$MARKER"]}]}"""

    private fun resource(path: String): ByteArray =
        KScalerPack::class.java.classLoader
            .getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: error("$path is missing from the jar")

    class Overlay(
        val directory: String,
        val minFormat: Int,
        val maxFormat: Int,
        internal val text: String,
    ) {
        fun json(): String =
            """{"formats":[$minFormat,$maxFormat],"min_format":$minFormat,"max_format":$maxFormat,"directory":"$directory"}"""
    }

    companion object {
        const val MARKER = ""

        const val MARKER_HEIGHT = 8

        const val MIN_FORMAT = 63

        const val MAX_FORMAT = 97

        val OVERLAYS: List<Overlay> =
            listOf(
                Overlay("gui_scale_63", 63, 64, "rendertype_text"),
                Overlay("gui_scale_69", 69, 69, "rendertype_text"),
                Overlay("gui_scale_75", 75, 75, "rendertype_text"),
                Overlay("gui_scale_84", 84, 84, "rendertype_text"),
                Overlay("gui_scale_88", 88, 88, "text"),
                Overlay("gui_scale_97", 97, 97, "text"),
            )

        private fun quote(value: String): String =
            buildString {
                append('"')
                value.forEach { char ->
                    when {
                        char == '"' || char == '\\' -> append('\\').append(char)
                        char < ' ' -> append("\\u%04x".format(char.code))
                        else -> append(char)
                    }
                }
                append('"')
            }
    }
}
