package com.nuvio.app.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NuvioTokens {
    object Space {
        val none = 0.dp
        val hairline = 0.5.dp
        val s1 = 1.dp
        val s2 = 2.dp
        val s3 = 3.dp
        val s4 = 4.dp
        val s5 = 5.dp
        val s6 = 6.dp
        val s7 = 7.dp
        val s8 = 8.dp
        val s10 = 10.dp
        val s12 = 12.dp
        val s14 = 14.dp
        val s16 = 16.dp
        val s18 = 18.dp
        val s20 = 20.dp
        val s22 = 22.dp
        val s24 = 24.dp
        val s28 = 28.dp
        val s32 = 32.dp
        val s36 = 36.dp
        val s40 = 40.dp
        val s48 = 48.dp
        val s56 = 56.dp
        val s64 = 64.dp
        val s72 = 72.dp
        val s80 = 80.dp
        val s96 = 96.dp
    }

    object Radius {
        val none = Space.none
        val xs = Space.s4
        val sm = Space.s6
        val md = Space.s8
        val lg = Space.s12
        val xl = Space.s16
        val xxl = Space.s24
        val full = 999.dp

        val card = xxl
        val compactCard = lg
        val sheet = xxl
        val dialog = Space.s28
        val button = xl
        val chip = full
        val poster = lg
        val avatar = full
        val playerPanel = xxl
    }

    object Border {
        val hairline = Space.hairline
        val thin = Space.s1
        val medium = Space.s2
    }

    object Elevation {
        val none = Space.none
        val raised = Space.s2
        val modal = Space.s8
        val overlay = Space.s12
        val playerControls = Space.s4
    }

    object Opacity {
        const val invisible = 0f
        const val disabled = 0.38f
        const val secondary = 0.70f
        const val muted = 0.60f
        const val selected = 0.15f
        const val hover = 0.08f
        const val pressed = 0.12f
        const val subtle = 0.06f
        const val medium = 0.52f
        const val strong = 0.75f
        const val overlayLight = 0.35f
        const val overlayMedium = 0.56f
        const val overlayHeavy = 0.82f
        const val visible = 1f
    }

    object Motion {
        const val instantMillis = 0
        const val fastMillis = 150
        const val normalMillis = 220
        const val sheetEnterMillis = 300
        const val sheetExitMillis = 250
        const val slowMillis = 400
        const val cinematicMillis = 700

        val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
        val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    }

    object Icon {
        val xs = Space.s12
        val sm = Space.s16
        val md = Space.s20
        val lg = Space.s24
        val xl = Space.s32
        val xxl = Space.s40
    }

    object Type {
        val labelXs = 11.sp
        val labelSm = 12.sp
        val bodySm = 13.sp
        val bodyMd = 14.sp
        val bodyApp = 15.sp
        val bodyLg = 16.sp
        val titleSm = 18.sp
        val headline = 26.sp
        val titleMd = 22.sp
        val titleLg = 28.sp
        val displaySm = 32.sp
        val pageDisplay = 38.sp
        val displayMd = 48.sp
    }

    object LineHeight {
        val labelXs = 14.sp
        val labelSm = 15.sp
        val bodySm = 18.sp
        val bodyMd = 20.sp
        val bodyApp = 22.sp
        val bodyLg = 22.sp
        val titleSm = 22.sp
        val materialTitleLarge = 24.sp
        val headline = 30.sp
        val titleMd = 26.sp
        val titleLg = 32.sp
        val displaySm = 36.sp
        val pageDisplay = 42.sp
        val displayMd = 52.sp
    }

    object LetterSpacing {
        val none = 0.sp
        val pageDisplay = (-1.2).sp
        val headline = (-0.8).sp
        val label = 0.8.sp
    }

    object Breakpoint {
        val phone = 0.dp
        val largePhone = 420.dp
        val tablet = 600.dp
        val largeTablet = 840.dp
        val desktop = 1024.dp
        val playerWide = 1280.dp
    }

    object Z {
        const val base = 0f
        const val stickyHeader = 2f
        const val navigation = 4f
        const val sheet = 8f
        const val dialog = 10f
        const val playerOverlay = 12f
        const val toast = 16f
    }
}

@Immutable
data class NuvioColorTokens(
    val background: Color,
    val backgroundAmoled: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceCard: Color,
    val surfaceSheet: Color,
    val surfaceDialog: Color,
    val surfacePopover: Color,
    val nativeChrome: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val textInverse: Color,
    val accent: Color,
    val accentStrong: Color,
    val onAccent: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val borderFocus: Color,
    val borderSelected: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val neutral: Color,
    val overlayScrim: Color,
    val overlayHover: Color,
    val overlayPressed: Color,
    val overlaySelected: Color,
    val overlayDisabled: Color,
    val shimmer: Color,
    val skeleton: Color,
    val playerControlsBackground: Color,
    val playerControlsForeground: Color,
    val playerTimelineTrack: Color,
    val playerTimelineFill: Color,
    val playerSubtitlePreview: Color,
    val playerBuffering: Color,
    val parentalGuide: Color,
)

@Immutable
data class NuvioShapeTokens(
    val card: Shape,
    val compactCard: Shape,
    val sheet: Shape,
    val dialog: Shape,
    val button: Shape,
    val chip: Shape,
    val poster: Shape,
    val avatar: Shape,
    val playerPanel: Shape,
)

@Immutable
data class NuvioSpacingTokens(
    val screenHorizontal: Dp,
    val screenTop: Dp,
    val screenBottom: Dp,
    val sectionGap: Dp,
    val listGap: Dp,
    val railGap: Dp,
    val cardPadding: Dp,
    val cardPaddingCompact: Dp,
    val controlGap: Dp,
    val dialogPadding: Dp,
    val sheetPadding: Dp,
    val playerOverlayPadding: Dp,
)

@Immutable
data class NuvioBorderTokens(
    val hairline: Dp,
    val thin: Dp,
    val medium: Dp,
)

@Immutable
data class NuvioElevationTokens(
    val flat: Dp,
    val raised: Dp,
    val modal: Dp,
    val overlay: Dp,
    val playerControls: Dp,
)

@Immutable
data class NuvioMotionTokens(
    val instantMillis: Int,
    val fastMillis: Int,
    val normalMillis: Int,
    val sheetEnterMillis: Int,
    val sheetExitMillis: Int,
    val slowMillis: Int,
    val cinematicMillis: Int,
    val standard: Easing,
    val emphasized: Easing,
    val decelerate: Easing,
    val accelerate: Easing,
)

@Immutable
data class NuvioOpacityTokens(
    val invisible: Float,
    val disabled: Float,
    val secondary: Float,
    val muted: Float,
    val selected: Float,
    val hover: Float,
    val pressed: Float,
    val subtle: Float,
    val medium: Float,
    val strong: Float,
    val overlayLight: Float,
    val overlayMedium: Float,
    val overlayHeavy: Float,
    val visible: Float,
)

@Immutable
data class NuvioIconTokens(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
)

@Immutable
data class NuvioBreakpointTokens(
    val phone: Dp,
    val largePhone: Dp,
    val tablet: Dp,
    val largeTablet: Dp,
    val desktop: Dp,
    val playerWide: Dp,
)

@Immutable
data class NuvioComponentTokens(
    val navItemShape: Shape,
    val navIconSize: Dp,
    val navItemMaxWidth: Dp,
    val sheetMaxWidth: Dp,
    val dialogMaxWidth: Dp,
    val chipHorizontalPadding: Dp,
    val chipVerticalPadding: Dp,
    val posterRadius: Dp,
    val avatarSize: Dp,
    val playerPanelMaxWidth: Dp,
)

@Immutable
data class NuvioThemeTokens(
    val colors: NuvioColorTokens,
    val spacing: NuvioSpacingTokens,
    val shapes: NuvioShapeTokens,
    val borders: NuvioBorderTokens,
    val elevation: NuvioElevationTokens,
    val motion: NuvioMotionTokens,
    val opacity: NuvioOpacityTokens,
    val icons: NuvioIconTokens,
    val breakpoints: NuvioBreakpointTokens,
    val components: NuvioComponentTokens,
)

internal val LocalNuvioThemeTokens = staticCompositionLocalOf {
    defaultNuvioThemeTokens(ThemeColors.White, amoled = false, colorScheme = null)
}

val MaterialTheme.nuvio: NuvioThemeTokens
    @Composable
    @Stable
    get() = LocalNuvioThemeTokens.current

internal fun defaultNuvioThemeTokens(
    palette: ThemeColorPalette,
    amoled: Boolean,
    colorScheme: ColorScheme?,
): NuvioThemeTokens {
    val background = if (amoled) Color.Black else palette.background
    val textPrimary = Color(0xFFF5F7F8)
    val textSecondary = Color(0xFFB8BEC5)
    val textMuted = Color(0xFF969CA3)
    val surface = palette.backgroundElevated
    val surfaceCard = palette.backgroundCard
    val accent = palette.secondary
    val accentStrong = palette.secondaryVariant
    val borderSubtle = Color(0xFF252A2A).copy(alpha = 0.55f)
    val borderDefault = Color(0xFF252A2A)
    val overlayScrim = Color.Black.copy(alpha = NuvioTokens.Opacity.overlayMedium)

    return NuvioThemeTokens(
        colors = NuvioColorTokens(
            background = background,
            backgroundAmoled = Color.Black,
            surface = surface,
            surfaceElevated = surface,
            surfaceCard = surfaceCard,
            surfaceSheet = surface,
            surfaceDialog = surface,
            surfacePopover = surfaceCard,
            nativeChrome = background,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textMuted = textMuted,
            textDisabled = textMuted.copy(alpha = NuvioTokens.Opacity.disabled),
            textInverse = Color(0xFF111111),
            accent = accent,
            accentStrong = accentStrong,
            onAccent = palette.onSecondary,
            focusRing = palette.focusRing,
            focusBackground = palette.focusBackground,
            borderSubtle = borderSubtle,
            borderDefault = borderDefault,
            borderStrong = Color(0xFF3A4040),
            borderFocus = palette.focusRing,
            borderSelected = accent.copy(alpha = NuvioTokens.Opacity.strong),
            success = Color(0xFF66BB6A),
            warning = Color(0xFFFFC857),
            danger = colorScheme?.error ?: Color(0xFFE36A8A),
            info = Color(0xFF42A5F5),
            neutral = textMuted,
            overlayScrim = overlayScrim,
            overlayHover = Color.White.copy(alpha = NuvioTokens.Opacity.hover),
            overlayPressed = Color.White.copy(alpha = NuvioTokens.Opacity.pressed),
            overlaySelected = Color.White.copy(alpha = NuvioTokens.Opacity.selected),
            overlayDisabled = Color.Black.copy(alpha = NuvioTokens.Opacity.disabled),
            shimmer = Color.White.copy(alpha = 0.10f),
            skeleton = Color.White.copy(alpha = 0.06f),
            playerControlsBackground = Color.Black.copy(alpha = 0.72f),
            playerControlsForeground = Color.White,
            playerTimelineTrack = Color.White.copy(alpha = 0.30f),
            playerTimelineFill = accent,
            playerSubtitlePreview = Color.Black.copy(alpha = 0.55f),
            playerBuffering = accent,
            parentalGuide = Color(0xFF5D1F1F),
        ),
        spacing = NuvioSpacingTokens(
            screenHorizontal = NuvioTokens.Space.s16,
            screenTop = NuvioTokens.Space.s10,
            screenBottom = NuvioTokens.Space.s18,
            sectionGap = NuvioTokens.Space.s24,
            listGap = NuvioTokens.Space.s12,
            railGap = NuvioTokens.Space.s14,
            cardPadding = NuvioTokens.Space.s18,
            cardPaddingCompact = NuvioTokens.Space.s14,
            controlGap = NuvioTokens.Space.s8,
            dialogPadding = NuvioTokens.Space.s20,
            sheetPadding = NuvioTokens.Space.s20,
            playerOverlayPadding = NuvioTokens.Space.s24,
        ),
        shapes = NuvioShapeTokens(
            card = RoundedCornerShape(NuvioTokens.Radius.card),
            compactCard = RoundedCornerShape(NuvioTokens.Radius.compactCard),
            sheet = RoundedCornerShape(NuvioTokens.Radius.sheet),
            dialog = RoundedCornerShape(NuvioTokens.Radius.dialog),
            button = RoundedCornerShape(NuvioTokens.Radius.button),
            chip = RoundedCornerShape(NuvioTokens.Radius.chip),
            poster = RoundedCornerShape(NuvioTokens.Radius.poster),
            avatar = RoundedCornerShape(NuvioTokens.Radius.avatar),
            playerPanel = RoundedCornerShape(NuvioTokens.Radius.playerPanel),
        ),
        borders = NuvioBorderTokens(
            hairline = NuvioTokens.Border.hairline,
            thin = NuvioTokens.Border.thin,
            medium = NuvioTokens.Border.medium,
        ),
        elevation = NuvioElevationTokens(
            flat = NuvioTokens.Elevation.none,
            raised = NuvioTokens.Elevation.raised,
            modal = NuvioTokens.Elevation.modal,
            overlay = NuvioTokens.Elevation.overlay,
            playerControls = NuvioTokens.Elevation.playerControls,
        ),
        motion = NuvioMotionTokens(
            instantMillis = NuvioTokens.Motion.instantMillis,
            fastMillis = NuvioTokens.Motion.fastMillis,
            normalMillis = NuvioTokens.Motion.normalMillis,
            sheetEnterMillis = NuvioTokens.Motion.sheetEnterMillis,
            sheetExitMillis = NuvioTokens.Motion.sheetExitMillis,
            slowMillis = NuvioTokens.Motion.slowMillis,
            cinematicMillis = NuvioTokens.Motion.cinematicMillis,
            standard = NuvioTokens.Motion.standard,
            emphasized = NuvioTokens.Motion.emphasized,
            decelerate = NuvioTokens.Motion.decelerate,
            accelerate = NuvioTokens.Motion.accelerate,
        ),
        opacity = NuvioOpacityTokens(
            invisible = NuvioTokens.Opacity.invisible,
            disabled = NuvioTokens.Opacity.disabled,
            secondary = NuvioTokens.Opacity.secondary,
            muted = NuvioTokens.Opacity.muted,
            selected = NuvioTokens.Opacity.selected,
            hover = NuvioTokens.Opacity.hover,
            pressed = NuvioTokens.Opacity.pressed,
            subtle = NuvioTokens.Opacity.subtle,
            medium = NuvioTokens.Opacity.medium,
            strong = NuvioTokens.Opacity.strong,
            overlayLight = NuvioTokens.Opacity.overlayLight,
            overlayMedium = NuvioTokens.Opacity.overlayMedium,
            overlayHeavy = NuvioTokens.Opacity.overlayHeavy,
            visible = NuvioTokens.Opacity.visible,
        ),
        icons = NuvioIconTokens(
            xs = NuvioTokens.Icon.xs,
            sm = NuvioTokens.Icon.sm,
            md = NuvioTokens.Icon.md,
            lg = NuvioTokens.Icon.lg,
            xl = NuvioTokens.Icon.xl,
            xxl = NuvioTokens.Icon.xxl,
        ),
        breakpoints = NuvioBreakpointTokens(
            phone = NuvioTokens.Breakpoint.phone,
            largePhone = NuvioTokens.Breakpoint.largePhone,
            tablet = NuvioTokens.Breakpoint.tablet,
            largeTablet = NuvioTokens.Breakpoint.largeTablet,
            desktop = NuvioTokens.Breakpoint.desktop,
            playerWide = NuvioTokens.Breakpoint.playerWide,
        ),
        components = NuvioComponentTokens(
            navItemShape = RoundedCornerShape(NuvioTokens.Radius.xl),
            navIconSize = NuvioTokens.Icon.xl,
            navItemMaxWidth = 150.dp,
            sheetMaxWidth = 520.dp,
            dialogMaxWidth = 460.dp,
            chipHorizontalPadding = NuvioTokens.Space.s14,
            chipVerticalPadding = NuvioTokens.Space.s8,
            posterRadius = NuvioTokens.Radius.poster,
            avatarSize = NuvioTokens.Space.s48,
            playerPanelMaxWidth = 600.dp,
        ),
    )
}
