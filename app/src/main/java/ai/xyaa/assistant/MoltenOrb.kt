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
        val shapes = MoltenSpec.SHAPES
        for (i in shapes.indices) {
            val s = shapes[i]
            val deg = if (s.dir == 0) 90f else {
                val ph = (((tau - s.delay) / MoltenSpec.PERIOD) % 1f + 1f) % 1f
                s.dir * 360f * ph
            }
            val r = deg * (PI / 180.0)
            val c = cos(r).toFloat(); val sn = sin(r).toFloat()
            for (p in 0 until 3) {
                val dx = s.pts[p * 2] - s.ox; val dy = s.pts[p * 2 + 1] - s.oy
                verts[i * 6 + p * 2] = s.ox + dx * c - dy * sn
                verts[i * 6 + p * 2 + 1] = s.oy + dx * sn + dy * c
            }
        }
        val k = MoltenSpec.contrast(tau)
        MoltenSpec.hueMatrix(MoltenSpec.hueDegrees(tau), hue)
        val step = 100f / grid
        val toPx = grid / 100f
        val sigK = 1.702f / MoltenSpec.BLUR_SIGMA
        val one = MoltenSpec.COLOR_ONE; val two = MoltenSpec.COLOR_TWO
        val five = MoltenSpec.COLOR_FIVE; val four = MoltenSpec.COLOR_FOUR

        for (j in 0 until grid) {
            val py = (j + .5f) * step
            val tg = ((py / 100f - .3f) / .4f).coerceIn(0f, 1f)
            val boxR = one.red + (two.red - one.red) * tg
            val boxG = one.green + (two.green - one.green) * tg
            val boxB = one.blue + (two.blue - one.blue) * tg
            val f = py / 100f
            val discR = one.red * (1 - f) + two.red * f
            val discG = one.green * (1 - f) + two.green * f
            val discB = (71f / 255f) * (1 - f) + (29f / 255f) * f
            val discA = .25f * (1 - f) + .5f * f
            for (i in 0 until grid) {
                val px = (i + .5f) * step
                val dxc = px - 50f; val dyc = py - 50f
                val dist = sqrt(dxc * dxc + dyc * dyc)
                val cc = ((50f - dist) * toPx + .5f).coerceIn(0f, 1f)
                if (cc <= 0f) { out[j * grid + i] = 0; continue }

                // union of blurred polygons (src-over), then contrast threshold
                var prod = 1f
                for (n in 0 until 7) {
                    val b = n * 6
                    val x0 = verts[b]; val y0 = verts[b + 1]
                    val x1 = verts[b + 2]; val y1 = verts[b + 3]
                    val x2 = verts[b + 4]; val y2 = verts[b + 5]
                    var d2 = Float.MAX_VALUE
                    var e = 0
                    while (e < 3) {
                        val ax: Float; val ay: Float; val bx: Float; val by: Float
                        when (e) {
                            0 -> { ax = x0; ay = y0; bx = x1; by = y1 }
                            1 -> { ax = x1; ay = y1; bx = x2; by = y2 }
                            else -> { ax = x2; ay = y2; bx = x0; by = y0 }
                        }
                        val ex = bx - ax; val ey = by - ay
                        val t = (((px - ax) * ex + (py - ay) * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
                        val qx = px - (ax + t * ex); val qy = py - (ay + t * ey)
                        val dd = qx * qx + qy * qy
                        if (dd < d2) d2 = dd
                        e++
                    }
                    val c0 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)
                    val c1 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                    val c2 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)
                    val inside = (c0 >= 0 && c1 >= 0 && c2 >= 0) || (c0 <= 0 && c1 <= 0 && c2 <= 0)
                    val d = sqrt(d2)
                    val sd = if (inside) -d else d
                    prod *= 1f - sigmoid(sigK * sd)
                }
                val m = (k * ((1f - prod) - .5f) + .5f).coerceIn(0f, 1f) * cc

                // premultiplied composite: liquid, disc fill, inset highlights
                var r = boxR * m; var g = boxG * m; var bl = boxB * m; var a = m
                // disc fill
                var ca = discA * cc
                r = discR * ca + r * (1 - ca); g = discG * ca + g * (1 - ca); bl = discB * ca + bl * (1 - ca); a = ca + a * (1 - ca)
                // inset highlight (top)
                val topShadow = 1f / (1f + exp(-1.702f * (hypot(dxc, py - 60f) - 50f) / 5f))
                ca = .5f * cc * topShadow
                r = one.red * ca + r * (1 - ca); g = one.green * ca + g * (1 - ca); bl = (71f / 255f) * ca + bl * (1 - ca); a = ca + a * (1 - ca)
                // inset shade (bottom)
                val botShadow = 1f / (1f + exp(-1.702f * (hypot(dxc, py - 40f) - 50f) / 5f))
                ca = .5f * cc * botShadow
                r = four.red * ca + r * (1 - ca); g = four.green * ca + g * (1 - ca); bl = four.blue * ca + bl * (1 - ca); a = ca + a * (1 - ca)

                if (a <= 1e-4f) { out[j * grid + i] = 0; continue }
                val ur = r / a; val ug = g / a; val ub = bl / a
                val hr = (hue[0] * ur + hue[1] * ug + hue[2] * ub).coerceIn(0f, 1f)
                val hg = (hue[3] * ur + hue[4] * ug + hue[5] * ub).coerceIn(0f, 1f)
                val hb = (hue[6] * ur + hue[7] * ug + hue[8] * ub).coerceIn(0f, 1f)
                out[j * grid + i] = ((a * 255f + .5f).toInt() shl 24) or
                    ((hr * 255f + .5f).toInt() shl 16) or ((hg * 255f + .5f).toInt() shl 8) or (hb * 255f + .5f).toInt()
            }
        }
    }
}

private const val MOLTEN_GRID = 80
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
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                if (dt >= FRAME_NANOS) {
                    last = now
                    tau = (tau + dt / 1_000_000_000f * (if (active) 1.35f else 1f)) % (MoltenSpec.PERIOD * 3f)
                    field.render(tau, pixels)
                    bitmap.setPixels(pixels, 0, MOLTEN_GRID, 0, 0, MOLTEN_GRID, MOLTEN_GRID)
                    hueDeg = MoltenSpec.hueDegrees(tau)
                    version++
                }
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
                drawImage(image, filterQuality = FilterQuality.Low)
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

