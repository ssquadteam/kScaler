package com.github.ssquadteam.kscaler

data class KScalerSettings(
    val threshold: Int = 608,
    val namespace: String = "kscaler",
    val calibrateOnJoin: Boolean = true,
    val debug: Boolean = false,
)

data class ScaleResult(
    val kind: Kind,
    val profile: Profile?,
    val millis: Long,
) {
    enum class Kind {
        CALIBRATED,
        FAILED,
        CONFIRMED,
        CHANGED,
        UNSURE,
    }
}
