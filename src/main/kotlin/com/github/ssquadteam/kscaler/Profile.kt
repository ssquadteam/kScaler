package com.github.ssquadteam.kscaler

import java.util.Locale

enum class Fit {
    WIDE,
    NARROW,
    ;

    fun other(): Fit = if (this == WIDE) NARROW else WIDE
}

data class Profile(
    val fit: Fit,
    val strength: Int,
    val frame: Double = 0.0,
) {
    fun encode(): String = "${fit.name}|$strength|${"%.3f".format(Locale.ROOT, frame)}"

    companion object {
        fun decode(value: String): Profile? {
            val parts = value.split('|')
            if (parts.size != 3) return null
            val fit = Fit.entries.firstOrNull { it.name == parts[0] } ?: return null
            val strength = parts[1].toIntOrNull() ?: return null
            return Profile(fit, strength, parts[2].toDoubleOrNull() ?: 0.0)
        }
    }
}
