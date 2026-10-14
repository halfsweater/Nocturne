package app.nocturne.ui.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * The playable keyboard. A plain View (rather than Compose) so touch events reach
 * the audio engine with no frame batching: unbuffered dispatch is requested on the
 * first touch, and every pointer is tracked independently for chords and glissandi.
 */
class PianoKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onNoteOn(note: Int, velocity: Int)
        fun onNoteOff(note: Int)
    }

    var listener: Listener? = null

    /** Velocity post-processing (touch mode, soft mode). */
    var velocityTransform: (Int) -> Int = { it }

    var fixedVelocity: Int? = null
    var showLabels = true
        set(value) { field = value; invalidate() }

    var visibleWhite = 10
        set(value) {
            field = value.coerceIn(6, 30)
            firstWhite = firstWhite.coerceIn(0f, maxFirst())
            invalidate()
        }

    var firstWhite = 23f
        private set

    /** Notified when the visible window moves (to keep the mini-map in sync). */
    var onWindowChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pointerNotes = SparseIntArray()
    private val holdCount = IntArray(128)
    private val pressedAt = LongArray(128)
    private val releasedAt = LongArray(128)
    private var scroller: ValueAnimator? = null
    private var pressureMin = 1f
    private var pressureMax = 0f
    private var pressureSamples = 0

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val separatorPaint = Paint().apply { color = 0xFF9C978C.toInt(); strokeWidth = max(1f, density * 0.75f) }
    private val fasciaPaint = Paint()
    private val silverLine = Paint().apply { color = 0xFFB9BDC4.toInt(); strokeWidth = max(1f, density) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8D8A83.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.05f
    }
    private val rect = RectF()
    private var geometry = KeyboardGeometry(1f, 1f, firstWhite, visibleWhite)

    init {
        isHapticFeedbackEnabled = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun maxFirst() = (Piano.WHITE_COUNT - visibleWhite).toFloat().coerceAtLeast(0f)

    fun setFirstWhite(value: Float, animate: Boolean) {
        val target = value.coerceIn(0f, maxFirst())
        scroller?.cancel()
        if (!animate || width == 0) {
            firstWhite = target
            invalidate()
            onWindowChanged?.invoke(firstWhite)
            return
        }
        scroller = ValueAnimator.ofFloat(firstWhite, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                firstWhite = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { onWindowChanged?.invoke(firstWhite) }
            start()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline f: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = f()
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val hf = h.toFloat()
        whitePaint.shader = LinearGradient(0f, 0f, 0f, hf,
            intArrayOf(0xFFDCD6CA.toInt(), 0xFFF4EFE4.toInt(), 0xFFF7F3EA.toInt(), 0xFFE9E3D6.toInt()),
            floatArrayOf(0f, 0.08f, 0.85f, 1f), Shader.TileMode.CLAMP)
        whitePressedPaint.shader = LinearGradient(0f, 0f, 0f, hf,
            intArrayOf(0xFFC9C2B4.toInt(), 0xFFE3DDD0.toInt(), 0xFFE8E2D6.toInt()),
            floatArrayOf(0f, 0.12f, 1f), Shader.TileMode.CLAMP)
        val bh = hf * 0.62f
        blackPaint.shader = LinearGradient(0f, 0f, 0f, bh,
            intArrayOf(0xFF07070A.toInt(), 0xFF1E1E22.toInt(), 0xFF2C2C31.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        blackPressedPaint.shader = LinearGradient(0f, 0f, 0f, bh,
            intArrayOf(0xFF0B0B0E.toInt(), 0xFF26262B.toInt(), 0xFF33333A.toInt()),
            floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
        blackBevelPaint.color = 0xFF3A3A41.toInt()
        fasciaPaint.shader = LinearGradient(0f, 0f, 0f, 10f * density,
            0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        labelPaint.textSize = 11f * density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        geometry = KeyboardGeometry(w, h, firstWhite, visibleWhite)
        val g = geometry
        canvas.drawColor(0xFF0E0F11.toInt())
        val now = SystemClock.uptimeMillis()
        var animating = false
        val inset = max(1f, density * 0.6f)
        val radius = 4f * density

        for (i in g.visibleWhiteRange()) {
            val note = Piano.WHITE_NOTES[i]
            val l = g.whiteLeft(i)
            val pressed = holdCount[note] > 0
            rect.set(l + inset, -radius, l + g.whiteWidth - inset, h - inset)
            canvas.drawRoundRect(rect, radius, radius, if (pressed) whitePressedPaint else whitePaint)
            val glow = glowAlpha(note, now)
            if (glow > 0f) {
                animating = animating || holdCount[note] == 0
                glowPaint.color = argb((glow * 46).toInt(), 0x6E, 0x7C, 0x96)
                canvas.drawRoundRect(rect, radius, radius, glowPaint)
            }
            if (showLabels && note % 12 == 0) {
                canvas.drawText(Piano.name(note), l + g.whiteWidth / 2, h - 10f * density, labelPaint)
            }
        }
        // key-slip shadow under the fallboard + silver hairline
        canvas.drawRect(0f, 0f, w, 10f * density, fasciaPaint)

        for (i in g.visibleWhiteRange()) {
            val note = Piano.WHITE_NOTES[i] + 1
            if (note > Piano.HIGHEST || !Piano.isBlack(note)) continue
            val l = g.blackLeft(note)
            if (l > w || l + g.blackWidth < 0) continue
            val pressed = holdCount[note] > 0
            val bevel = if (pressed) g.blackHeight * 0.025f else g.blackHeight * 0.07f
            rect.set(l, -radius, l + g.blackWidth, g.blackHeight)
            canvas.drawRoundRect(rect, radius * 0.6f, radius * 0.6f, blackBevelPaint)
            rect.set(l + g.blackWidth * 0.08f, -radius, l + g.blackWidth * 0.92f, g.blackHeight - bevel)
            canvas.drawRoundRect(rect, radius * 0.5f, radius * 0.5f, if (pressed) blackPressedPaint else blackPaint)
            val glow = glowAlpha(note, now)
            if (glow > 0f) {
                animating = animating || holdCount[note] == 0
                glowPaint.color = argb((glow * 70).toInt(), 0xB9, 0xBD, 0xC4)
                canvas.drawRoundRect(rect, radius * 0.5f, radius * 0.5f, glowPaint)
            }
        }
        canvas.drawLine(0f, 0f, w, 0f, silverLine)
        if (animating) postInvalidateOnAnimation()
    }

    private fun glowAlpha(note: Int, now: Long): Float = when {
        holdCount[note] > 0 -> 1f
        releasedAt[note] > 0 -> (1f - (now - releasedAt[note]) / 140f).coerceIn(0f, 1f)
        else -> 0f
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    // ------------------------------------------------------------------ touch

    private fun usePressure(event: MotionEvent, index: Int): Boolean {
        val p = event.getPressure(index)
        pressureSamples++
        pressureMin = min(pressureMin, p)
        pressureMax = max(pressureMax, p)
        // Many screens report a constant (fake) pressure; only trust it once it varies.
        return pressureSamples > 8 && pressureMax - pressureMin > 0.2f
    }

    private fun press(pointerId: Int, note: Int, velocity: Int) {
        pointerNotes.put(pointerId, note)
        holdCount[note]++
        pressedAt[note] = SystemClock.uptimeMillis()
        listener?.onNoteOn(note, velocityTransform(velocity))
    }

    private fun release(pointerId: Int) {
        val note = pointerNotes.get(pointerId, -1)
        pointerNotes.delete(pointerId)
        if (note < 0) return
        holdCount[note] = max(0, holdCount[note] - 1)
        if (holdCount[note] == 0) {
            releasedAt[note] = SystemClock.uptimeMillis()
            listener?.onNoteOff(note)
        }
    }

    private fun velocityFor(event: MotionEvent, index: Int, note: Int): Int {
        fixedVelocity?.let { return it }
        val pressure = usePressure(event, index)
        return geometry.velocityAt(note, event.getY(index), event.getPressure(index), pressure)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        geometry = KeyboardGeometry(width.toFloat(), height.toFloat(), firstWhite, visibleWhite)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    requestUnbufferedDispatch(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val i = event.actionIndex
                val note = geometry.noteAt(event.getX(i), event.getY(i))
                if (note >= 0) press(event.getPointerId(i), note, velocityFor(event, i, note))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val current = pointerNotes.get(id, -1)
                    val note = geometry.noteAt(event.getX(i), event.getY(i))
                    if (note != current) {
                        if (current >= 0) release(id)
                        if (note >= 0) press(id, note, velocityFor(event, i, note))
                        changed = true
                    }
                }
                if (changed) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
            }
        }
        return true
    }

    fun releaseAll() {
        for (k in 0 until pointerNotes.size()) {
            val note = pointerNotes.valueAt(k)
            if (holdCount[note] > 0) {
                holdCount[note] = 0
                releasedAt[note] = SystemClock.uptimeMillis()
                listener?.onNoteOff(note)
            }
        }
        pointerNotes.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        scroller?.cancel()
        super.onDetachedFromWindow()
    }
}
