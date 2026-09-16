package io.github.yulbax.frkn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        displayLarge = base.displayLarge.copy(fontFamily = googleSans),
        displayMedium = base.displayMedium.copy(fontFamily = googleSans),
        displaySmall = base.displaySmall.copy(fontFamily = googleSans),
        headlineLarge = base.headlineLarge.copy(fontFamily = googleSans),
        headlineMedium = base.headlineMedium.copy(fontFamily = googleSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = googleSans),
        titleLarge = base.titleLarge.copy(fontFamily = googleSans),
        titleMedium = base.titleMedium.copy(fontFamily = googleSans),
        titleSmall = base.titleSmall.copy(fontFamily = googleSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = googleSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = googleSans),
        bodySmall = base.bodySmall.copy(fontFamily = googleSans),
        labelLarge = base.labelLarge.copy(fontFamily = googleSans),
        labelMedium = base.labelMedium.copy(fontFamily = googleSans),
        labelSmall = base.labelSmall.copy(fontFamily = googleSans)
    )
}
