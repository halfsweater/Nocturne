package app.nocturne.ui.keyboard

import kotlin.math.floor
import kotlin.math.pow

/** 88-key layout maths (A0 = MIDI 21 .. C8 = MIDI 108), independent of Android for unit testing. */
object Piano {
    const val LOWEST = 21
    const val HIGHEST = 108
    val WHITE_NOTES: IntArray = (LOWEST..HIGHEST).filter { !isBlack(it) }.toIntArray()   // 52 keys
    val WHITE_COUNT = WHITE_NOTES.size

    fun isBlack(note: Int) = when (note % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

    /** Index among white keys of a white note, or of the white key just left of a black note. */
    fun whiteIndexOf(note: Int): Int {
        val n = if (isBlack(note)) note - 1 else note
        return WHITE_NOTES.indexOf(n)
    }

    /** Horizontal centre offset of a black key from the boundary of its neighbouring white keys. */
    fun blackOffset(note: Int): Float = when (note % 12) {
        1 -> -0.08f; 3 -> 0.08f; 6 -> -0.1f; 8 -> 0f; 10 -> 0.1f; else -> 0f
    }

    fun name(note: Int): String {
        val names = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        return names[note % 12] + (note / 12 - 1)
    }
}

class KeyboardGeometry(
    val width: Float,
    val height: Float,
    val firstWhite: Float,
    val visibleWhite: Int,
) {
    val whiteWidth = width / visibleWhite
    val blackWidth = whiteWidth * 0.58f
    val blackHeight = height * 0.62f

    fun whiteLeft(index: Int) = (index - firstWhite) * whiteWidth

    fun blackLeft(note: Int): Float {
        val boundary = whiteLeft(Piano.whiteIndexOf(note) + 1)
        return boundary + Piano.blackOffset(note) * whiteWidth - blackWidth / 2
    }

    /** Visible white key index range (inclusive). */
    fun visibleWhiteRange(): IntRange {
        val first = floor(firstWhite).toInt().coerceAtLeast(0)
        val last = (floor(firstWhite + visibleWhite).toInt()).coerceAtMost(Piano.WHITE_COUNT - 1)
        return first..last
    }

    /** MIDI note under a point, or -1. Black keys win in their area. */
    fun noteAt(x: Float, y: Float): Int {
        if (y < 0 || y > height) return -1
        val wi = floor(x / whiteWidth + firstWhite).toInt()
        if (wi < 0 || wi >= Piano.WHITE_COUNT) return -1
        if (y <= blackHeight) {
            // candidate black keys: right of this white key and right of the previous one
            for (cand in intArrayOf(Piano.WHITE_NOTES[wi] + 1, Piano.WHITE_NOTES[wi] - 1)) {
                if (cand < Piano.LOWEST || cand > Piano.HIGHEST || !Piano.isBlack(cand)) continue
                val l = blackLeft(cand)
                if (x >= l && x <= l + blackWidth) return cand
            }
        }
        return Piano.WHITE_NOTES[wi]
    }

    /**
     * Touch position along the key maps to strike strength: playing toward the front edge
     * of a key is louder, like pressing a real key further from the fallboard.
     */
    fun velocityAt(note: Int, y: Float, pressure: Float, usePressure: Boolean): Int {
        val len = if (Piano.isBlack(note)) blackHeight else height
        val frac = (y / len).coerceIn(0f, 1f)
        var v = 34f + 93f * frac.pow(0.85f)
        if (usePressure) v = v * 0.7f + v * 0.6f * pressure.coerceIn(0f, 1f)
        return v.toInt().coerceIn(1, 127)
    }
}
