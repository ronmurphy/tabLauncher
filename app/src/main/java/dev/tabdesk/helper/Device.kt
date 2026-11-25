package dev.tabdesk.helper

import android.os.Build

/**
 * Which maker's Android this tablet runs. Makers move settings around and add their own locks, so the helper's
 * setup steps differ between them.
 */
internal object Device {
    enum class Family { OPLUS, XIAOMI, SAMSUNG, OTHER }

    private val OPLUS = setOf("oneplus", "oppo", "realme")
    private val XIAOMI = setOf("xiaomi", "redmi", "poco")
    private val maker = Build.MANUFACTURER.lowercase()
    private val brand = Build.BRAND.lowercase()

    /** OnePlus, Oppo and realme all run Oppo's ColorOS underneath (OnePlus calls it OxygenOS outside China). */
    val family: Family = when {
        maker in OPLUS || brand in OPLUS -> Family.OPLUS
        maker in XIAOMI || brand in XIAOMI -> Family.XIAOMI
        maker == "samsung" -> Family.SAMSUNG
        else -> Family.OTHER
    }

    /** The maker's name for its Android, with its version where the tablet says it, e.g. "OxygenOS 15.0.0". */
    val system: String by lazy {
        fun prop(name: String) = getprop(name)
        when (family) {
            Family.OPLUS -> {
                val v = (prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") })
                    .removePrefix("V").removePrefix("v")
                val name = when {
                    maker == "oneplus" || brand == "oneplus" -> "OxygenOS"
                    maker == "realme" || brand == "realme" -> "realme UI"
                    else -> "ColorOS"
                }
                "$name $v".trim()
            }
            Family.XIAOMI -> prop("ro.mi.os.version.name").takeIf { it.isNotEmpty() }?.let { "HyperOS ${it.removePrefix("OS")}" }
                ?: prop("ro.miui.ui.version.name").takeIf { it.isNotEmpty() }?.let { "MIUI ${it.removePrefix("V")}" }
                ?: "HyperOS"
            Family.SAMSUNG -> prop("ro.build.version.oneui").toIntOrNull()?.let { "One UI ${it / 10000}.${it / 100 % 100}" }
                ?: "One UI"
            Family.OTHER -> when {
                prop("ro.com.zui.version").isNotEmpty() -> "ZUI ${prop("ro.com.zui.version")}"
                prop("ro.build.version.magic").isNotEmpty() -> "MagicOS ${prop("ro.build.version.magic").substringAfter("_")}"
                prop("ro.vivo.os.name").isNotEmpty() -> "${prop("ro.vivo.os.name")} ${prop("ro.vivo.os.version")}".trim()
                else -> "Android"
            }
        }
    }

    /** E.g. "OnePlus OPD2203 · OxygenOS 15.0.0 · Android 15". */
    val summary: String by lazy {
        // "LENOVO" → "Lenovo", "samsung" → "Samsung"; mixed case like "OnePlus" is kept.
        val m = Build.MANUFACTURER.let { if (it.length > 3 && it == it.uppercase()) it.lowercase() else it }
            .replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(m, ignoreCase = true)) Build.MODEL else "$m ${Build.MODEL}"
        listOf(model, system.takeIf { it != "Android" }, "Android ${Build.VERSION.RELEASE}").filterNotNull().joinToString(" · ")
    }

    /** A system property, or "" if it isn't set or Android doesn't let apps read it. */
    private fun getprop(name: String): String = try {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }
}
