package com.pocketpath.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val defaults = Typography()

private fun TextStyle.pocketPath(weight: FontWeight = fontWeight ?: FontWeight.Normal): TextStyle =
    copy(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontFeatureSettings = "tnum")

val Typography = Typography(
    displayLarge = defaults.displayLarge.pocketPath(FontWeight.Bold),
    displayMedium = defaults.displayMedium.pocketPath(FontWeight.Bold),
    displaySmall = defaults.displaySmall.pocketPath(FontWeight.Bold),
    headlineLarge = defaults.headlineLarge.pocketPath(FontWeight.Bold),
    headlineMedium = defaults.headlineMedium.pocketPath(FontWeight.Bold),
    headlineSmall = defaults.headlineSmall.pocketPath(FontWeight.SemiBold),
    titleLarge = defaults.titleLarge.pocketPath(FontWeight.SemiBold),
    titleMedium = defaults.titleMedium.pocketPath(FontWeight.SemiBold),
    titleSmall = defaults.titleSmall.pocketPath(FontWeight.SemiBold),
    bodyLarge = defaults.bodyLarge.pocketPath(),
    bodyMedium = defaults.bodyMedium.pocketPath(),
    bodySmall = defaults.bodySmall.pocketPath(),
    labelLarge = defaults.labelLarge.pocketPath(FontWeight.SemiBold),
    labelMedium = defaults.labelMedium.pocketPath(),
    labelSmall = defaults.labelSmall.pocketPath(),
)