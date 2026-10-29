package com.focusmate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌集中在这里，页面里不再出现裸色值与裸字号。
 *
 * 选色理由：这是一个"陪伴 + 克制"的工具，不是社交应用。
 * 用暖纸底（不是纯白）降低长时间盯屏的刺眼感；主色用低饱和陶土红，
 * 它同时承担"专注中"的状态色，避免再引入第二套语义色。
 */
object Tokens {
    // 亮色
    val Paper = Color(0xFFFBF7F1)
    val PaperRaised = Color(0xFFFFFFFF)
    val PaperSunken = Color(0xFFF2EBE1)
    val Ink = Color(0xFF1E1A15)
    val InkSoft = Color(0xFF6B6157)
    val InkFaint = Color(0xFF9A8F83)
    val Clay = Color(0xFFC75B39)
    val ClaySoft = Color(0xFFF6E3DA)
    val Moss = Color(0xFF4F7A52)
    val MossSoft = Color(0xFFE3EDE2)
    val Amber = Color(0xFFB07818)
    val AmberSoft = Color(0xFFF7ECD6)
    val Danger = Color(0xFFA63D33)
    val Hairline = Color(0xFFE8DFD3)

    // 暗色
    val DarkPaper = Color(0xFF15120E)
    val DarkPaperRaised = Color(0xFF211C16)
    val DarkPaperSunken = Color(0xFF17130F)
    val DarkInk = Color(0xFFF2EAE0)
    val DarkInkSoft = Color(0xFFB7ACA0)
    val DarkInkFaint = Color(0xFF8A7F73)
    val DarkClay = Color(0xFFE08A66)
    val DarkClaySoft = Color(0xFF33241C)
    val DarkMoss = Color(0xFF8FBE8F)
    val DarkMossSoft = Color(0xFF1E2A1F)
    val DarkAmber = Color(0xFFDCA94A)
    val DarkAmberSoft = Color(0xFF2C2416)
    val DarkDanger = Color(0xFFE08A80)
    val DarkHairline = Color(0xFF2E2822)
}

/**
 * 语义色。Compose 的 ColorScheme 槽位不够表达"成功/警告/危险"，
 * 但又不该在页面里散落 if (dark) ... else ...，所以单独提供一层。
 */
data class SemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val neutralContainer: Color,
    val hairline: Color,
)

private val LightScheme = lightColorScheme(
    primary = Tokens.Clay,
    onPrimary = Color.White,
    primaryContainer = Tokens.ClaySoft,
    onPrimaryContainer = Tokens.Clay,
    background = Tokens.Paper,
    onBackground = Tokens.Ink,
    surface = Tokens.PaperRaised,
    onSurface = Tokens.Ink,
    surfaceVariant = Tokens.PaperSunken,
    onSurfaceVariant = Tokens.InkSoft,
    outline = Tokens.Hairline,
    outlineVariant = Tokens.Hairline,
    error = Tokens.Danger,
)

private val DarkScheme = darkColorScheme(
    primary = Tokens.DarkClay,
    onPrimary = Color(0xFF2A1A12),
    primaryContainer = Tokens.DarkClaySoft,
    onPrimaryContainer = Tokens.DarkClay,
    background = Tokens.DarkPaper,
    onBackground = Tokens.DarkInk,
    surface = Tokens.DarkPaperRaised,
    onSurface = Tokens.DarkInk,
    surfaceVariant = Tokens.DarkPaperSunken,
    onSurfaceVariant = Tokens.DarkInkSoft,
    outline = Tokens.DarkHairline,
    outlineVariant = Tokens.DarkHairline,
    error = Tokens.DarkDanger,
)

val LightSemantic = SemanticColors(
    success = Tokens.Moss,
    successContainer = Tokens.MossSoft,
    warning = Tokens.Amber,
    warningContainer = Tokens.AmberSoft,
    danger = Tokens.Danger,
    dangerContainer = Tokens.ClaySoft,
    neutralContainer = Tokens.PaperSunken,
    hairline = Tokens.Hairline,
)

val DarkSemantic = SemanticColors(
    success = Tokens.DarkMoss,
    successContainer = Tokens.DarkMossSoft,
    warning = Tokens.DarkAmber,
    warningContainer = Tokens.DarkAmberSoft,
    danger = Tokens.DarkDanger,
    dangerContainer = Tokens.DarkClaySoft,
    neutralContainer = Tokens.DarkPaperSunken,
    hairline = Tokens.DarkHairline,
)

/**
 * 字阶。中文没有西文那样的 x-height 差异，行高必须给足，
 * 否则一屏字挤在一起会显得脏。正文 15sp/行高 22sp 是长时间阅读的折中。
 */
val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
)

val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** 通过 CompositionLocal 让页面拿到语义色，避免每个组件都传一遍。 */
val LocalSemantic = androidx.compose.runtime.staticCompositionLocalOf { LightSemantic }

@Composable
fun FocusMateTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSemantic provides if (dark) DarkSemantic else LightSemantic,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
