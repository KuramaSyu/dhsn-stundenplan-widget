package de.dhsn.stundenplan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.dhsn.stundenplan.data.Lesson
import kotlin.math.absoluteValue

/**
 * Pure helpers for the optional per-module accent colors feature.
 *
 * The idea is that we take the secondaryContainer / tertiaryContainer
 * colors (which are normally the only "container" colors available to
 * cards), rotate their **hue** by a stable, per-module offset and use the
 * rotated color as the lesson card's container color. Saturation and value stay
 * the same so the cards don't suddenly become neon on a light theme.
 *
 * Stability is the critical property here: the same `(subject, teacher)`
 * pair MUST produce the same hue rotation every time, so a lesson that
 * spans multiple 90-min chunks stays the same color throughout.
 *
 * For parallel modules (same `(start, end, originalEnd)`, multiple
 * `Lesson`s in one [BlockGroup]) each lesson gets its own hue rotation
 * and the card body is rendered with a horizontal gradient whose
 * stop positions split the available width evenly between the modules.
 */
object ModuleColors {

    /**
     * Hue rotation in `[0, 360)` for [lesson]. Computed as the absolute
     * value of `subject.hashCode() xor teacher.hashCode()` modulo 360 so
     * the result is non-negative and bounded.
     *
     * Returns `null` when the lesson has neither a subject nor a teacher
     * (a defensive guard for "we have nothing meaningful to hash"); callers
     * should fall back to the default color in that case.
     */
    fun hueDegreesFor(lesson: Lesson): Float? {
        val subject = lesson.subject.trim()
        val teacher = lesson.teacher.trim()
        if (subject.isEmpty() && teacher.isEmpty()) return null
        val raw = subject.hashCode() xor teacher.hashCode()
        val degrees = (raw.absoluteValue % 360).toFloat()
        return degrees
    }

    /**
     * Rotate the hue of [base] by [hueDegrees] degrees, keeping
     * saturation and value unchanged. Returns [base] unchanged if
     * [hueDegrees] is 0 modulo 360 (no rotation needed).
     *
     * Implemented via a small inline RGB <-> HSV conversion so we don't
     * depend on `androidx.core.graphics.ColorUtils` (which requires an
     * `android.graphics.Color` round-trip and an extra dependency import
     * for a single function).
     */
    fun rotateHue(base: Color, hueDegrees: Float): Color {
        if (hueDegrees == 0f) return base
        val (h, s, v) = rgbToHsv(base)
        val newHue = ((h + hueDegrees) % 360f + 360f) % 360f
        return hsvToRgb(newHue, s, v, base.alpha)
    }

    /**
     * Pick an on-color that contrasts with [container]. We use luminance
     * to switch between a near-black and a near-white ink, keeping the
     * pairing stable for both light and dark themes.
     */
    fun onColorFor(container: Color): Color {
        val lum = container.luminance()
        return if (lum > 0.5f) Color(0xFF1B1B1B) else Color(0xFFF5F5F5)
    }

    // --- HSV helpers (standard port; hue in [0,360), s/v in [0,1]) -------

    private fun rgbToHsv(c: Color): Triple<Float, Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val v = max
        val s = if (max == 0f) 0f else delta / max
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val hh = ((h % 360f) + 360f) % 360f
        return Triple(hh, s, v)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float, alpha: Float): Color {
        val c = v * s
        val hh = ((h % 360f) + 360f) % 360f
        val x = c * (1f - kotlin.math.abs(((hh / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r1 + m, g1 + m, b1 + m, alpha)
    }
}