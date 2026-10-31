package com.slopus.happy.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.slopus.happy.BuildConfig
import java.io.File
import kotlin.math.ceil

/**
 * Frosted glass over scrolling content, hand-rolled on [GraphicsLayer]
 * (RenderNodes): the content records itself into one layer ([glassSource]),
 * drawn as usual; each glass surface ([glass], [glassFade], [glassBar]) paints
 * the source's ground, then draws that same layer again, the part round it
 * only, shrunk by [GlassSpec.DOWNSCALE], blurred and saturated there, scaled
 * back up under a tint. A layer drawn inside another stays live, so scrolling
 * the content re-blurs without the glass recording anything again. Blur needs
 * RenderEffect (API 31+); below that, or with [LocalGlassBlur] off, the glass
 * is its tint over the ground alone, opaque.
 *
 * The numbers are Telegram's frosted glass (13.1.1, blur3's
 * DownscaleScrollableNoiseSuppressor and BlurredBackgroundProviderImpl; see
 * research/telegram-glass.md): an eighth-size copy, a 40 dp blur, saturation
 * tripled, and the page's colour over it at 216/255 in light and 178/255
 * in dark (bars; floating glass keeps 0.85 in dark, for contrast).
 */
object GlassSpec {
    /** The blur runs on a copy this many times smaller; cheaper, and softer for it. */
    const val DOWNSCALE = 8f
    /** The blur's radius at full size; [downscaledRadius] keeps it that wide on the small copy. */
    val blur = 40.dp
    const val SATURATION = 3f
    /**
     * Floating glass's tint over the blur, light and dark; [FALLBACK_ALPHA]
     * when there is no blur to see through. Telegram's dark is 0.76; 0.85
     * keeps the composer's placeholder at 4.5:1 over a white picture.
     */
    const val ALPHA = 216f / 255f
    const val ALPHA_DARK = 0.85f
    const val FALLBACK_ALPHA = 0.94f
    val hairline = 0.55.dp
    /** A bar's ground over the blur, light and dark: Telegram's chat list bar. */
    const val BAR_ALPHA = 216f / 255f
    const val BAR_ALPHA_DARK = 178f / 255f
    /**
     * How far down from the top of what it backs the fade ramps in. It starts
     * at that top, not above it, so content resting above a dock is never
     * under it (Telegram's lies inside its input zone).
     */
    val fade = 48.dp
    /**
     * How much content round a glass goes into its blur (Telegram records 48
     * dp round each glass), so what nears the glass glows into it before it
     * slides under, instead of the blur clamping at the glass's edge.
     */
    val capture = 48.dp

    /**
     * A blur [radius] for a copy [scale] times smaller that looks as wide as
     * [radius] at full size: through sigma (hwui's 1/sqrt(3) and 0.5), not
     * straight division, as Telegram's downscaleRadius does.
     */
    fun downscaledRadius(radius: Float, scale: Float): Float {
        val sigma = if (radius > 0) SIGMA_SCALE * radius + 0.5f else 0f
        val small = sigma / scale
        return maxOf(1f, if (small > 0.5f) (small - 0.5f) / SIGMA_SCALE else 0f)
    }

    private const val SIGMA_SCALE = 0.57735f
}

/** Whether this OS can blur at all (RenderEffect, API 31). */
fun glassBlurs(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.S

/**
 * Whether glass blurs here: [glassBlurs], and in a debug build not switched
 * off by a `files/glass-off` file (`adb shell run-as <app> touch files/glass-off`),
 * so a fling can be measured with and without it on one install.
 */
fun glassBlurEnabled(context: Context): Boolean =
    glassBlurs() && !(BuildConfig.DEBUG && File(context.filesDir, "glass-off").exists())

/** Off to draw every glass as its tint alone; tests set it to reach that path on any API. */
val LocalGlassBlur: ProvidableCompositionLocal<Boolean?> = compositionLocalOf { null }

@Composable
fun rememberGlassBlur(): Boolean {
    val forced = LocalGlassBlur.current
    val context = LocalContext.current
    return forced ?: remember(context) { glassBlurEnabled(context) }
}

/** One theme's glass: [tint] over the blur, and the [edge] hairline round it. */
@Immutable
data class GlassColors(val tint: Color, val edge: Color, val alpha: Float = GlassSpec.ALPHA) {
    fun fill(blurs: Boolean): Color = tint.copy(alpha = if (blurs) alpha else GlassSpec.FALLBACK_ALPHA)

    companion object {
        /**
         * Light: a pale grey a shade under the page, a dark hairline; dark:
         * lifted grey, Telegram's white hairline at 0x20.
         */
        fun of(dark: Boolean): GlassColors =
            if (dark) GlassColors(tint = Color(0xFF2C2C2E), edge = Color.White.copy(alpha = 0x20 / 255f), alpha = GlassSpec.ALPHA_DARK)
            else GlassColors(tint = Color(0xFFF2F2F4), edge = Color.Black.copy(alpha = 0.09f))
    }
}

/**
 * What the glass shows through: the content's layer, where it sits, and the
 * [ground] it is drawn on. Telegram's frosted source is the window's colour
 * with the blurred content over it (DialogsActivity's
 * `renderNodeUpdateDisplayList`: `drawColor(windowBackgroundWhite)`, then
 * the blur), so the glass is opaque and hides the sharp content under it.
 * The content's layer has no ground of its own (the screen paints it), so
 * the glass paints it: without it the blurred copy is nearly empty and the
 * sharp content shows through the tint.
 */
@Stable
class GlassSource internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var recorded by mutableStateOf(false)
    internal var ground by mutableStateOf(Color.Unspecified)
}

/** A source drawn on [ground], the screen's colour under the content; unspecified for content opaque itself. */
@Composable
fun rememberGlassSource(ground: Color = Color.Unspecified): GlassSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassSource(layer) }.also { it.ground = ground }
}

/** Records this content for the glass over it, and draws it as usual. */
fun Modifier.glassSource(source: GlassSource): Modifier =
    onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            source.layer.record { this@drawWithContent.drawContent() }
            drawLayer(source.layer)
            if (!source.recorded) source.recorded = true
        }

/** A glass surface in [shape]: the blurred content under it, the tint, the hairline. */
fun Modifier.glass(source: GlassSource?, shape: Shape, colors: GlassColors, blurs: Boolean): Modifier = composed {
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val hairline = GlassSpec.hairline.toPx()
            val fill = colors.fill(blurs)
            onDrawBehind {
                if (source != null) clipPath(path) { drawSource(blurred, source, origin, blurs) }
                drawPath(path, fill)
                drawPath(path, colors.edge, style = Stroke(hairline))
            }
        }
}

/**
 * The band behind a bottom dock: the content blurred and tinted like the
 * glass, fading in over its top [GlassSpec.fade] so what scrolls under it
 * goes soft and pale instead of meeting an edge.
 */
fun Modifier.glassFade(source: GlassSource?, tint: Color, blurs: Boolean): Modifier = composed {
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawBehind {
            if (source != null) clipRect { drawSource(blurred, source, origin, blurs) }
            drawRect(tint.copy(alpha = if (blurs) glassAlpha(tint) else GlassSpec.FALLBACK_ALPHA))
            val fade = (GlassSpec.fade.toPx() / size.height).coerceIn(0f, 1f)
            // Telegram's fade (BlurredBackgroundWithFadeDrawable, opacity mode): 0, 0x60, 0xB0, 0xE8 of
            // 285 over its height, eased in as a straight ramp reads as a band. Below it Telegram holds
            // 0.81; this goes opaque, so nothing shows sharp beside a dock narrower than the window.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    fade / 3 to Color.Black.copy(alpha = 0x60 / 285f),
                    fade * 2 / 3 to Color.Black.copy(alpha = 0xB0 / 285f),
                    fade to Color.Black.copy(alpha = 0xE8 / 285f),
                    1f to Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * A top bar's glass ([GlassBarLayout]): the content under it blurred, then
 * [ground] over the blur, [GlassSpec.BAR_ALPHA] of it on a light ground and
 * [GlassSpec.BAR_ALPHA_DARK] on a dark one (nearly opaque where nothing
 * blurs). No edge, as Telegram's chat list bar has none: at rest the
 * content's own ground is all that is under the bar, so bar and page are one
 * surface.
 */
fun Modifier.glassBar(source: GlassSource?, ground: Color, blurs: Boolean): Modifier = composed {
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            // The blur spreads past the layer's edge: keep it to the bar.
            if (source != null) clipRect { drawSource(blurred, source, origin, blurs) }
            val alpha = if (ground.luminance() < DARK_LUMINANCE) GlassSpec.BAR_ALPHA_DARK else GlassSpec.BAR_ALPHA
            drawRect(ground.copy(alpha = if (blurs) alpha else GlassSpec.FALLBACK_ALPHA))
        }
}

/** Below this a ground is dark (Telegram's perceived-brightness 0.721, as relative luminance). */
private const val DARK_LUMINANCE = 0.48f

/** A fade's tint over the blur: the floating glass's, by how dark [tint] is. */
private fun glassAlpha(tint: Color): Float = if (tint.luminance() < DARK_LUMINANCE) GlassSpec.ALPHA_DARK else GlassSpec.ALPHA

private val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GlassSpec.SATURATION) })

/**
 * Records [area] of [source] (in the source's coordinates), shrunk and
 * blurred: DownscaleScrollableNoiseSuppressor's SourcePart, recorded at an
 * eighth of its size and blurred there, saturation chained after the blur.
 */
private fun GraphicsLayer.recordBlurOf(scope: DrawScope, source: GlassSource, area: Rect) {
    val k = GlassSpec.DOWNSCALE
    val radius = GlassSpec.downscaledRadius(with(scope) { GlassSpec.blur.toPx() }, k)
    if (renderEffect == null) {
        renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        colorFilter = saturate
    }
    with(scope) {
        this@recordBlurOf.record(IntSize(ceil(area.width / k).toInt().coerceAtLeast(1), ceil(area.height / k).toInt().coerceAtLeast(1))) {
            scale(1 / k, 1 / k, pivot = Offset.Zero) {
                translate(-area.left, -area.top) { drawLayer(source.layer) }
            }
        }
    }
}

/**
 * What a glass at [origin] shows of [source]: its ground, then (when it
 * [blurs]) the content blurred over that, as Telegram's
 * BlurredBackgroundSourceRenderNode draws. The blur takes in
 * [GlassSpec.capture] of content round the glass, kept to the source as
 * Telegram keeps it to the list; the caller clips it to the glass. Without
 * blur, the ground alone, so the tint over it is opaque, as Telegram's bar is
 * with blur off.
 */
private fun DrawScope.drawSource(blurred: GraphicsLayer, source: GlassSource, origin: Offset, blurs: Boolean) {
    if (source.ground.isSpecified) drawRect(source.ground)
    if (!blurs || !source.recorded) return
    val offset = origin - source.origin
    val bounds = Rect(Offset.Zero, source.layer.size.toSize())
    val area = Rect(offset, size).inflate(GlassSpec.capture.toPx()).intersect(bounds)
    if (area.isEmpty) return
    blurred.recordBlurOf(this, source, area)
    translate(area.left - offset.x, area.top - offset.y) {
        scale(GlassSpec.DOWNSCALE, GlassSpec.DOWNSCALE, pivot = Offset.Zero) { drawLayer(blurred) }
    }
}
