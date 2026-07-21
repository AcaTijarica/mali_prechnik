package rs.maliprecnik.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material 3 типографија. За сада мењамо само `bodyLarge`, а остале стилове
// MaterialTheme преузима из подразумеваног скупа.
private val BaseTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)

val Typography = BaseTypography

private val EbGaramondFontFamily = FontFamily(
    Font(R.font.eb_garamond, FontWeight.Normal),
    Font(R.font.eb_garamond, FontWeight.Bold),
    Font(R.font.eb_garamond_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.eb_garamond_italic, FontWeight.Bold, FontStyle.Italic)
)

private val NotoSerifFontFamily = FontFamily(
    Font(R.font.noto_serif, FontWeight.Normal),
    Font(R.font.noto_serif, FontWeight.Bold),
    Font(R.font.noto_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.noto_serif_italic, FontWeight.Bold, FontStyle.Italic)
)

private val MonomakhFontFamily = FontFamily(
    Font(R.font.monomakh_regular, FontWeight.Normal),
    Font(R.font.monomakh_regular, FontWeight.Bold)
)

fun typographyForFontChoice(fontChoice: AppFontChoice): Typography {
    val fontFamily = when (fontChoice) {
        AppFontChoice.System -> return BaseTypography
        AppFontChoice.EBGaramond -> EbGaramondFontFamily
        AppFontChoice.NotoSerif -> NotoSerifFontFamily
        AppFontChoice.Monomakh -> MonomakhFontFamily
    }
    return BaseTypography.withFontFamily(fontFamily)
}

fun typographyForDisplaySettings(settings: DisplaySettings): Typography =
    typographyForFontChoice(settings.fontChoice).withFontScale(settings.fontSizeChoice.scale)

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.copy(fontFamily = fontFamily),
        displayMedium = displayMedium.copy(fontFamily = fontFamily),
        displaySmall = displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = titleLarge.copy(fontFamily = fontFamily),
        titleMedium = titleMedium.copy(fontFamily = fontFamily),
        titleSmall = titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = bodySmall.copy(fontFamily = fontFamily),
        labelLarge = labelLarge.copy(fontFamily = fontFamily),
        labelMedium = labelMedium.copy(fontFamily = fontFamily),
        labelSmall = labelSmall.copy(fontFamily = fontFamily)
    )

private fun Typography.withFontScale(scale: Float): Typography =
    if (scale == 1.0f) {
        this
    } else {
        copy(
            displayLarge = displayLarge.scaled(scale),
            displayMedium = displayMedium.scaled(scale),
            displaySmall = displaySmall.scaled(scale),
            headlineLarge = headlineLarge.scaled(scale),
            headlineMedium = headlineMedium.scaled(scale),
            headlineSmall = headlineSmall.scaled(scale),
            titleLarge = titleLarge.scaled(scale),
            titleMedium = titleMedium.scaled(scale),
            titleSmall = titleSmall.scaled(scale),
            bodyLarge = bodyLarge.scaled(scale),
            bodyMedium = bodyMedium.scaled(scale),
            bodySmall = bodySmall.scaled(scale),
            labelLarge = labelLarge.scaled(scale),
            labelMedium = labelMedium.scaled(scale),
            labelSmall = labelSmall.scaled(scale)
        )
    }

private fun TextStyle.scaled(scale: Float): TextStyle =
    copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale
    )
