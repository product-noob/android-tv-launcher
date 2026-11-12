package me.prince.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// Art for each part of the day, 1920x1080 JPEGs in res/drawable-nodpi (made by tools/wallpapers.py)
enum class DayPart(@DrawableRes val image: Int) {
    Dawn(R.drawable.wallpaper_dawn),
    Day(R.drawable.wallpaper_day),
    Dusk(R.drawable.wallpaper_dusk),
    Night(R.drawable.wallpaper_night),
}

fun dayPart(hour: Int): DayPart = when (hour) {
    in 5..7 -> DayPart.Dawn
    in 8..16 -> DayPart.Day
    in 17..19 -> DayPart.Dusk
    else -> DayPart.Night
}

class Wallpaper(val part: DayPart, val image: ImageBitmap, val frosted: ImageBitmap)

// ~8 MB decoded; only held while the home screen is visible
fun loadWallpaper(context: Context, part: DayPart): Wallpaper {
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888 // 565 bands on smooth skies
    }
    val bitmap = BitmapFactory.decodeResource(context.resources, part.image, options)
    return Wallpaper(part, bitmap.asImageBitmap(), frostedWallpaper(bitmap, ::scrimAlpha))
}

// The darkening drawn over the wallpaper so text stays readable, as an alpha at a point (fractions of the screen):
// the left side behind the hero text, the bottom behind the shelves and a little at the top behind the header.
// WallpaperScrim draws the same thing with gradients; keep the two in sync.
private const val SCRIM_LEFT = 0.6f
private const val SCRIM_LEFT_END = 0.6f
private const val SCRIM_BOTTOM = 0.8f
private const val SCRIM_BOTTOM_START = 0.38f
private const val SCRIM_TOP = 0.35f
private const val SCRIM_TOP_END = 0.22f

fun scrimAlpha(x: Float, y: Float): Float {
    val left = SCRIM_LEFT * (1f - x / SCRIM_LEFT_END).coerceIn(0f, 1f)
    val bottom = SCRIM_BOTTOM * ((y - SCRIM_BOTTOM_START) / (1f - SCRIM_BOTTOM_START)).coerceIn(0f, 1f)
    val top = SCRIM_TOP * (1f - y / SCRIM_TOP_END).coerceIn(0f, 1f)
    return 1f - (1f - left) * (1f - bottom) * (1f - top)
}

// Slow drift: the image is drawn 8% larger and wanders inside that margin on two unrelated periods, so it never
// visibly repeats. Updated 4 times a second (sub-pixel steps at ~1 px/s), not every frame: the TV's GPU stays idle.
private const val DRIFT_SCALE = 1.08f
private const val DRIFT_X = 0.035f
private const val DRIFT_Y = 0.03f
private const val DRIFT_PERIOD_X_S = 240f
private const val DRIFT_PERIOD_Y_S = 170f
private const val DRIFT_TICK_MS = 250L

/** Seconds since the home screen came up, ticking only while it is visible. */
@Composable
fun rememberDriftClock(visible: Boolean): State<Float> = produceState(0f, visible) {
    val start = System.nanoTime()
    while (visible) {
        value = (System.nanoTime() - start) / 1e9f
        delay(DRIFT_TICK_MS)
    }
}

// Where the (enlarged) wallpaper sits on a screen of this size at time t. Glass uses it to line its blur up.
fun wallpaperBounds(screen: Size, t: Float): Rect {
    val w = screen.width * DRIFT_SCALE
    val h = screen.height * DRIFT_SCALE
    val dx = screen.width * DRIFT_X * sin(2 * PI.toFloat() * t / DRIFT_PERIOD_X_S)
    val dy = screen.height * DRIFT_Y * sin(2 * PI.toFloat() * t / DRIFT_PERIOD_Y_S + 1f)
    val left = (screen.width - w) / 2 + dx
    val top = (screen.height - h) / 2 + dy
    return Rect(left, top, left + w, top + h)
}

/**
 * Full-screen wallpaper that crossfades when the part of the day changes and drifts slowly.
 * [dim] darkens it further (scrolling down the page). Draw-phase only: the drift never recomposes anything.
 */
@Composable
fun LivingWallpaper(wallpaper: Wallpaper?, clock: State<Float>, dim: () -> Float) {
    Crossfade(wallpaper, animationSpec = tween(1500), label = "wallpaper") { shown ->
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (shown == null) return@drawBehind
                    val bounds = wallpaperBounds(size, clock.value)
                    drawImage(
                        shown.image,
                        dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                        dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = SCRIM_LEFT), SCRIM_LEFT_END to Color.Transparent))
                    drawRect(Brush.verticalGradient(SCRIM_BOTTOM_START to Color.Transparent, 1f to Color.Black.copy(alpha = SCRIM_BOTTOM)))
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = SCRIM_TOP), SCRIM_TOP_END to Color.Transparent))
                    val extra = dim()
                    if (extra > 0f) drawRect(Color.Black.copy(alpha = extra))
                }
        )
    }
}
