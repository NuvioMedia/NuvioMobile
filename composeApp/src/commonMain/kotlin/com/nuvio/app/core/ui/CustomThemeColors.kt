package com.nuvio.app.core.ui

data class CustomThemeColors(
    val first: Int = 0xB75AFF,
    val second: Int = 0xEC70A9,
    val third: Int = 0xFFB37A,
) {
    init {
        require(listOf(first, second, third).all { it in 0..0xFFFFFF })
    }

    val colors: List<Int> get() = listOf(first, second, third)
    val isSolid: Boolean get() = first == second && second == third

    fun withColor(index: Int, color: Int): CustomThemeColors = when (index) {
        0 -> copy(first = color)
        1 -> copy(second = color)
        2 -> copy(third = color)
        else -> this
    }

    fun encode(): String = colors.joinToString(",", transform = ::formatHexColor)

    companion object {
        val Default = CustomThemeColors()

        fun solid(color: Int): CustomThemeColors = CustomThemeColors(color, color, color)

        fun decode(value: String?): CustomThemeColors {
            val colors = value?.split(",")?.map { parseHexColor(it) ?: return Default }
                ?: return Default
            return if (colors.size == 3) CustomThemeColors(colors[0], colors[1], colors[2]) else Default
        }
    }
}

fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }) return null
    return hex.toIntOrNull(16)
}

fun formatHexColor(color: Int): String = "#" + color.toString(16).padStart(6, '0').uppercase()
