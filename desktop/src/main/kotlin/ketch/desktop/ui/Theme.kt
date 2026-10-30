// Material 3 on ketch's brand tokens: the accent and status colours, the type scale and the radii come from
// tokens.json, the surfaces are Material's own, as the Windows and Linux apps keep their platform's.
package ketch.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.awt.Toolkit

/** The brand colours Material 3 has no role for. */
@Immutable
data class StatusColors(
    val installed: Color,
    val installedSubtle: Color,
    val update: Color,
    val updateSubtle: Color,
    val busy: Color,
    val busySubtle: Color,
    val warning: Color,
    val warningSubtle: Color,
    val error: Color,
    val errorSubtle: Color,
    /** Accent text on a plain surface, darker than the accent fill. */
    val accentInk: Color,
)

/** The spacing scale, `space.*`. */
@Immutable
data class Spacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
)

/** Which of the app's faces this window wears: `ui/glass` on macOS, `ui/fluent` on Windows, `ui/adwaita` on Linux, Material 3 elsewhere. */
enum class Look { Material, Glass, Fluent, Adwaita }

fun systemLook(): Look = lookFor(System.getenv("KETCH_LOOK"), System.getProperty("os.name"), highContrast())

/** [chosen] names a look to wear instead, which is how one is seen on another system. High contrast keeps Material's. */
fun lookFor(
    chosen: String?,
    os: String,
    highContrast: Boolean,
): Look =
    Look.entries.firstOrNull { it.name.equals(chosen, ignoreCase = true) } ?: when {
        os.startsWith("Mac") -> Look.Glass
        os.startsWith("Windows") && !highContrast -> Look.Fluent
        os.startsWith("Linux") && !highContrast -> Look.Adwaita
        else -> Look.Material
    }

private fun highContrast() = Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") == true

val LocalLook = staticCompositionLocalOf { Look.Material }
val LocalAppearance = staticCompositionLocalOf { Appearance.Light }
val LocalStatusColors = staticCompositionLocalOf<StatusColors> { error("KetchTheme is not applied") }
val LocalSpacing = staticCompositionLocalOf<Spacing> { error("KetchTheme is not applied") }

/** Monospaced text: paths, versions, commands. */
val LocalMonoStyle = staticCompositionLocalOf { TextStyle(fontFamily = FontFamily.Monospace) }

/**
 * The system's appearance. High contrast is read where the JVM can see it, Windows; macOS's Increase Contrast and
 * GNOME's high contrast have no reading from AWT.
 */
@Composable
fun systemAppearance(): Appearance {
    val contrast = highContrast()
    val dark = isSystemInDarkTheme()
    return when {
        contrast && dark -> Appearance.HighContrastDark
        contrast -> Appearance.HighContrast
        dark -> Appearance.Dark
        else -> Appearance.Light
    }
}

@Composable
fun KetchTheme(
    appearance: Appearance = systemAppearance(),
    tokens: Tokens = Tokens.brand,
    look: Look = systemLook(),
    content: @Composable () -> Unit,
) {
    val color = { path: String -> tokens.color(path, appearance) }
    val scheme = colorScheme(appearance, color)
    val status =
        StatusColors(
            installed = color("status.installed"),
            installedSubtle = color("status.installedSubtle").compositeOver(scheme.surface),
            update = color("status.update"),
            updateSubtle = color("status.updateSubtle").compositeOver(scheme.surface),
            busy = color("status.busy"),
            busySubtle = color("status.busySubtle").compositeOver(scheme.surface),
            warning = color("status.warning"),
            warningSubtle = color("status.warningSubtle").compositeOver(scheme.surface),
            error = color("status.error"),
            errorSubtle = color("status.errorSubtle").compositeOver(scheme.surface),
            accentInk = color("accent.ink"),
        )
    val space = tokens.space
    val spacing = Spacing(space.getValue("xxs"), space.getValue("xs"), space.getValue("sm"), space.getValue("md"), space.getValue("lg"), space.getValue("xl"), space.getValue("xxl"))
    val radius = tokens.radius
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(radius.getValue("xs")),
            small = RoundedCornerShape(radius.getValue("sm")),
            medium = RoundedCornerShape(radius.getValue("md")),
            large = RoundedCornerShape(radius.getValue("lg")),
            extraLarge = RoundedCornerShape(radius.getValue("xl")),
        )
    CompositionLocalProvider(
        LocalLook provides look,
        LocalAppearance provides appearance,
        LocalStatusColors provides status,
        LocalSpacing provides spacing,
        LocalMonoStyle provides style(tokens.type.getValue("mono")).copy(fontFamily = FontFamily.Monospace),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(tokens), shapes = shapes, content = content)
    }
}

private fun colorScheme(
    appearance: Appearance,
    color: (String) -> Color,
): ColorScheme {
    val base = if (appearance.isDark) darkColorScheme() else lightColorScheme()
    val contrast = appearance == Appearance.HighContrast || appearance == Appearance.HighContrastDark
    // High contrast keeps Material's roles but drops the tinted greys: ink on paper, paper on ink.
    val plain =
        if (!contrast) {
            base
        } else {
            val ink = if (appearance.isDark) Color.White else Color.Black
            val paper = if (appearance.isDark) Color.Black else Color.White
            base.copy(
                background = paper,
                surface = paper,
                surfaceContainer = paper,
                surfaceContainerLow = paper,
                surfaceContainerHigh = paper,
                onBackground = ink,
                onSurface = ink,
                onSurfaceVariant = ink,
                outline = ink,
            )
        }
    return plain.copy(
        primary = color("accent.default"),
        onPrimary = color("accent.on"),
        primaryContainer = color("accent.subtle").compositeOver(plain.surface),
        onPrimaryContainer = color("accent.ink"),
        secondaryContainer = color("accent.subtle").compositeOver(plain.surface),
        onSecondaryContainer = color("accent.ink"),
        error = color("status.error"),
        errorContainer = color("status.errorSubtle").compositeOver(plain.surface),
        onErrorContainer = color("status.error"),
    )
}

private fun style(token: TypeToken) =
    TextStyle(
        fontSize = token.size.sp,
        fontWeight = FontWeight(token.weight),
        lineHeight = token.lineHeight.em,
        letterSpacing = token.letterSpacing.sp,
    )

/** The macOS type scale on Material's roles: each role takes the step that plays the same part. */
private fun typography(tokens: Tokens): Typography {
    val t = tokens.type.mapValues { style(it.value) }
    val display = t.getValue("display")
    val title = t.getValue("title")
    val body = t.getValue("body")
    return Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = display,
        headlineMedium = title,
        headlineSmall = title,
        titleLarge = title,
        titleMedium = t.getValue("title2"),
        titleSmall = t.getValue("headline"),
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = t.getValue("callout"),
        labelLarge = body.copy(fontWeight = FontWeight.Medium),
        labelMedium = t.getValue("caption"),
        labelSmall = t.getValue("overline"),
    )
}
