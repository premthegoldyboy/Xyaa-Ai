package ai.xyaa.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native port of the Uiverse.io "molten loader" by andrew-manzyk.
 *
 * The CSS effect is: seven SVG polygons (blur 7px) rotating around different
 * origins, thresholded with filter: contrast(15) so they merge like liquid, used
 * as a mask over a vertical amber->burnt gradient, inside a translucent disc,
 * with the whole loader hue-rotating (colorize) and the contrast pulsing
 * (roundness). This computes that same field per pixel on a small grid and
 * upscales it, so there is no RenderEffect/blur/offscreen layer on the GPU.
 */
internal object MoltenSpec {
    const val PERIOD = 2f            // --time-animation
    const val BLUR_SIGMA = 7f        // polygon blur(7px)
    const val MAX_HUE = 8f           // degrees; kept inside the amber/orange family
    val COLOR_ONE = Color(0xFFFFBF48)
    val COLOR_TWO = Color(0xFFBE4A1D)
    val COLOR_THREE = Color(0x80FFBF47)
    val COLOR_FOUR = Color(0x80BF4A1D)
    val COLOR_FIVE = Color(0x40FFBF47)

    private val TRI_A = floatArrayOf(25f, 25f, 75f, 25f, 50f, 75f)
    private val TRI_B = floatArrayOf(50f, 25f, 75f, 75f, 25f, 75f)
    private val TRI_C = floatArrayOf(35f, 35f, 65f, 35f, 50f, 65f)

    /** points, originX, originY, direction (0 static, 1 forward, -1 reverse), delay in seconds */
    class Shape(val pts: FloatArray, val ox: Float, val oy: Float, val dir: Int, val delay: Float)

    val SHAPES = listOf(
        Shape(TRI_A, 50f, 50f, 0, 0f),
        Shape(TRI_B, 50f, 50f, -1, 0f),
        Shape(TRI_C, 50f, 60f, 1, -PERIOD / 3f),
        Shape(TRI_C, 40f, 40f, -1, 0f),
        Shape(TRI_C, 40f, 40f, -1, -PERIOD / 2f),
        Shape(TRI_C, 60f, 40f, 1, 0f),
        Shape(TRI_C, 60f, 40f, 1, -PERIOD / 1.5f)
    )

    /** keyframed contrast: 15 -> 3 -> 3 -> 15 -> 15 over PERIOD / 2 */
    fun contrast(tau: Float): Float {
        val half = PERIOD / 2f
        val p = (tau % half) / half
        return when {
            p < .2f -> 15f + (3f - 15f) * (p / .2f)
            p < .4f -> 3f
            p < .6f -> 3f + (15f - 3f) * ((p - .4f) / .2f)
            else -> 15f
        }
    }

    /** colorize keyframes (0,-30,-60,-90,-45,0 in the CSS) scaled to MAX_HUE so it never leaves amber/orange */
    fun hueDegrees(tau: Float): Float {
        val cycle = PERIOD * 3f
        val p = (tau % cycle) / cycle
        val keys = floatArrayOf(0f, -30f, -60f, -90f, -45f, 0f)
        val seg = (p / .2f).toInt().coerceIn(0, 4)
        val u = (p - seg * .2f) / .2f
        val e = u * u * (3f - 2f * u)
        val deg = keys[seg] + (keys[seg + 1] - keys[seg]) * e
        return deg * (MAX_HUE / 30f)
    }

    fun hueMatrix(deg: Float, out: FloatArray) {
        val a = deg * (PI / 180.0)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        out[0] = .213f + c * .787f - s * .213f; out[1] = .715f - c * .715f - s * .715f; out[2] = .072f - c * .072f + s * .928f
        out[3] = .213f - c * .213f + s * .143f; out[4] = .715f + c * .285f + s * .140f; out[5] = .072f - c * .072f - s * .283f
        out[6] = .213f - c * .213f - s * .787f; out[7] = .715f - c * .715f + s * .715f; out[8] = .072f + c * .928f + s * .072f
    }

    fun rotated(color: Color, m: FloatArray): Color = Color(
        (m[0] * color.red + m[1] * color.green + m[2] * color.blue).coerceIn(0f, 1f),
        (m[3] * color.red + m[4] * color.green + m[5] * color.blue).coerceIn(0f, 1f),
        (m[6] * color.red + m[7] * color.green + m[8] * color.blue).coerceIn(0f, 1f),
        color.alpha
    )
}

/** Allocation-free per-frame renderer for the molten field. */
internal class MoltenField(private val grid: Int) {
    private val verts = FloatArray(7 * 6)
    private val hue = FloatArray(9)

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(x.coerceIn(-30f, 30f)))

    fun render(tau: Float, out: IntArray) {
        // A continuous liquid-glass body replaces the old sharp polygon fragments.
        val step = 100f / grid
        val phase = tau * 1.15f
        val base = MoltenSpec.COLOR_ONE
        val deep = MoltenSpec.COLOR_TWO
        val warm = Color(1f, .82f, .48f, 1f)
        for (j in 0 until grid) {
            val py = (j + .5f) * step
            for (i in 0 until grid) {
                val px = (i + .5f) * step
                val dx = (px - 50f) / 44f
                val dy = (py - 50f) / 44f
                val radius = hypot(dx, dy)
                val angle = kotlin.math.atan2(dy, dx)
                val ripple = sin(angle * 3f - phase) * .012f +
                    sin(angle * 5f + phase * .62f) * .006f
                val edge = 1f - radius + ripple
                val alpha = ((edge * 44f + 1.35f) / 2.7f).coerceIn(0f, 1f)
                if (alpha <= .002f) {
                    out[j * grid + i] = 0
                    continue
                }
                val depth = (1f - radius).coerceIn(0f, 1f)
                val facing = (1f - dx * dx - dy * dy).coerceAtLeast(0f)
                val normalZ = sqrt(facing)
                val diffuse = (dx * -.34f + dy * -.48f + normalZ * .62f).coerceIn(0f, 1f)
                val shade = (.22f + .58f * diffuse + .20f * depth).coerceIn(0f, 1f)
                val grad = ((py - 12f) / 76f).coerceIn(0f, 1f)
                var r = (base.red * (1f - grad) + deep.red * grad) * shade
                var g = (base.green * (1f - grad) + deep.green * grad) * shade
                var b = (base.blue * (1f - grad) + deep.blue * grad) * shade

                // Low-contrast moving caustics create depth without particle fragments.
                val band = (sin(py * .105f + px * .045f + phase * 1.35f) *
                    sin(px * .072f - phase * .8f)).coerceAtLeast(0f)
                val caustic = band * depth * .10f
                r = (r + warm.red * caustic).coerceIn(0f, 1f)
                g = (g + warm.green * caustic).coerceIn(0f, 1f)
                b = (b + warm.blue * caustic).coerceIn(0f, 1f)

                // Broad glass reflection and a crisp specular highlight.
                val hx = (dx + .34f) / .19f
                val hy = (dy + .48f) / .29f
                val broad = exp(-(hx * hx + hy * hy) * 1.7f) * .34f
                val gx = (dx + .29f) / .055f
                val gy = (dy + .43f) / .075f
                val glint = exp(-(gx * gx + gy * gy) * 2f) * .82f
                val reflection = (broad + glint).coerceIn(0f, .92f)
                r += (1f - r) * reflection
                g += (1f - g) * reflection
                b += (1f - b) * reflection

                val rimDelta = (radius - .88f) / .045f
                val rim = exp(-(rimDelta * rimDelta)) * .24f
                r = (r + warm.red * rim).coerceIn(0f, 1f)
                g = (g + warm.green * rim).coerceIn(0f, 1f)
                b = (b + warm.blue * rim).coerceIn(0f, 1f)

                // Bitmap.setPixels expects straight (not premultiplied) ARGB values.
                val a = (alpha * .98f).coerceIn(0f, 1f)
                val pa = (a * 255f + .5f).toInt().coerceIn(0, 255)
                val pr = (r * 255f + .5f).toInt().coerceIn(0, 255)
                val pg = (g * 255f + .5f).toInt().coerceIn(0, 255)
                val pb = (b * 255f + .5f).toInt().coerceIn(0, 255)
                out[j * grid + i] = (pa shl 24) or (pr shl 16) or (pg shl 8) or pb
            }
        }
    }
}

private const val MOLTEN_GRID = 128
private const val FRAME_NANOS = 33_000_000L // ~30 fps keeps CPU and battery low

@Composable
internal fun MoltenOrb(modifier: Modifier = Modifier, active: Boolean = false) {
    val bitmap = remember { Bitmap.createBitmap(MOLTEN_GRID, MOLTEN_GRID, Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pixels = remember { IntArray(MOLTEN_GRID * MOLTEN_GRID) }
    val field = remember { MoltenField(MOLTEN_GRID) }
    var version by remember { mutableIntStateOf(0) }
    val hueM = remember { FloatArray(9) }
    var hueDeg by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active) {
        var last = 0L
        var tau = 0f
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (last == 0L) last = now
            val dt = now - last
            if (dt >= FRAME_NANOS) {
                last = now
                tau = (tau + dt / 1_000_000_000f * (if (active) 1.35f else 1f)) % (MoltenSpec.PERIOD * 3f)
                // The pixel field is CPU-heavy; keep it off the Compose/UI thread.
                withContext(Dispatchers.Default) { field.render(tau, pixels) }
                bitmap.setPixels(pixels, 0, MOLTEN_GRID, 0, 0, MOLTEN_GRID, MOLTEN_GRID)
                hueDeg = MoltenSpec.hueDegrees(tau)
                version++
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val v = version
        MoltenSpec.hueMatrix(hueDeg, hueM)
        val s = size.minDimension * .57f / 100f
        val center = Offset(size.width / 2f, size.height / 2f)
        val three = MoltenSpec.rotated(MoltenSpec.COLOR_THREE, hueM)
        val four = MoltenSpec.rotated(MoltenSpec.COLOR_FOUR, hueM)
        val glow = if (active) 1f else .8f

        // box-shadow: 0 0 25px three, 0 20px 50px four
        drawCircle(
            Brush.radialGradient(
                0f to three.copy(alpha = three.alpha * glow), .66f to three.copy(alpha = three.alpha * glow), 1f to Color.Transparent,
                center = center, radius = 75f * s
            ), 75f * s, center
        )
        val low = Offset(center.x, center.y + 20f * s)
        drawCircle(
            Brush.radialGradient(
                0f to four.copy(alpha = four.alpha * glow), .5f to four.copy(alpha = four.alpha * glow), 1f to Color.Transparent,
                center = low, radius = 90f * s
            ), 90f * s, low
        )

        // the liquid, disc and inset shadows, upscaled from the 80x80 field
        val px = 100f * s / MOLTEN_GRID
        translate(center.x - 50f * s, center.y - 50f * s) {
            scale(px, px, pivot = Offset.Zero) {
                drawImage(image, filterQuality = FilterQuality.Medium)
            }
        }

        // ::before borders: 1px top in color-one, 1px bottom in color-two
        val ring = Size(100f * s, 100f * s)
        val tl = Offset(center.x - 50f * s, center.y - 50f * s)
        val stroke = Stroke(width = (1.2f * s).coerceAtLeast(1f))
        drawArc(MoltenSpec.rotated(MoltenSpec.COLOR_ONE, hueM), 180f, 180f, false, tl, ring, style = stroke)
        drawArc(MoltenSpec.rotated(MoltenSpec.COLOR_TWO, hueM), 0f, 180f, false, tl, ring, style = stroke)
    }
}

