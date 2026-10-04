package io.github.yulbax.frkn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.yulbax.frkn.ui.platform.platformTextStyle
import io.github.yulbax.frkn.ui.res.*
import org.jetbrains.compose.resources.Font

private val base = Typography()

@Composable
fun frknTypography(): Typography {
    val googleSans = FontFamily(
        Font(Res.font.google_sans_regular, FontWeight.Normal),
        Font(Res.font.google_sans_medium, FontWeight.Medium),
        Font(Res.font.google_sans_bold, FontWeight.Bold)
    )
    return Typography(
        displayLarge = base.displayLarge.frkn(googleSans),
        displayMedium = base.displayMedium.frkn(googleSans),
        displaySmall = base.displaySmall.frkn(googleSans),
        headlineLarge = base.headlineLarge.frkn(googleSans),
        headlineMedium = base.headlineMedium.frkn(googleSans),
        headlineSmall = base.headlineSmall.frkn(googleSans),
        titleLarge = base.titleLarge.frkn(googleSans),
        titleMedium = base.titleMedium.frkn(googleSans),
        titleSmall = base.titleSmall.frkn(googleSans),
        bodyLarge = base.bodyLarge.frkn(googleSans),
        bodyMedium = base.bodyMedium.frkn(googleSans),
        bodySmall = base.bodySmall.frkn(googleSans),
        labelLarge = base.labelLarge.frkn(googleSans),
        labelMedium = base.labelMedium.frkn(googleSans),
        labelSmall = base.labelSmall.frkn(googleSans)
    )
}

private fun TextStyle.frkn(fontFamily: FontFamily): TextStyle {
    val styled = copy(fontFamily = fontFamily)
    return platformTextStyle?.let { styled.copy(platformStyle = it) } ?: styled
}
