package com.andrerinas.openheadunit.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.withScale
import androidx.core.view.ViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.HomePagesHintKeyPolicy
import com.andrerinas.openheadunit.main.HomePagesHintPolicy
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot
import kotlin.math.min

/**
 * The one-time hint for adding pages: the home dimmed but for a spotlight on an empty spot that
 * opens the page menu on a long press, a hand pressing there while a ring fills (touch and hold,
 * not a tap), and a callout beside it. Without a spot ([hole] null) the whole home is dimmed and
 * the hand and ring are drawn inside a centred callout instead. Placed by [HomePagesHintPolicy];
 * this view only draws and reports how it was closed.
 */
@SuppressLint("ViewConstructor")
class HomePagesHintView(
    context: Context,
    hole: HomePagesHintPolicy.Box?,
    private val calloutBox: HomePagesHintPolicy.Box,
    /** Where the callout sits from the spotlight; null without one. */
    private val calloutSide: HomePagesHintPolicy.Side?,
    /** The smaller hand and ring, for a short screen. */
    small: Boolean,
    private val look: Look,
    private val onClosed: (Reason) -> Unit,
) : FrameLayout(context) {

    /** LEFT: the home went away, or stopped being idle, while the hint was up. */
    enum class Reason { GOT_IT, OUTSIDE, BACK, HOLD, LEFT }

    /**
     * How the callout is dressed: [small] text, and for the hint without a spotlight the art
     * ([withArt]) above the text or, on a short screen, [artBeside] it.
     */
    data class Look(val small: Boolean, val withArt: Boolean = false, val artBeside: Boolean = false)

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val holeRect: RectF? = hole?.let { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    private val holeRadius = dp(22f)
    private val arrow = dp(12f)

    // The hand and ring: on the spotlight, sized to it; in the callout's art, sized to that.
    private var centerX = 0f
    private var centerY = 0f
    private var ringRadius: Float
    private var handSize: Int

    private val scrimPath = Path()
    private val arrowPath = Path()
    private val scrimPaint = paint(ContextCompat.getColor(context, R.color.home_hint_scrim))
    private val calloutPaint = paint(ContextCompat.getColor(context, R.color.home_hint_callout))
    private val outlinePaint = stroke(Color.argb(97, 255, 255, 255), dp(2f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    // White on the dimmed home; the button's blue on the light callout, where white would vanish.
    private val ringColor = if (hole != null) Color.WHITE else ContextCompat.getColor(context, R.color.home_hint_button)
    private val trackAlpha = if (hole != null) 64 else 51
    private val trackPaint = stroke(ringColor, dp(4f))
    private val ringPaint = stroke(ringColor, dp(4f)).apply { strokeCap = Paint.Cap.ROUND }
    private val ripplePaint = stroke(ringColor, dp(2f))
    private val ringBounds = RectF()
    private val outlineRect = holeRect?.let { RectF(it).apply { inset(dp(10f), dp(10f)) } }
    private val artRect = Rect()

    private val hand: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_home_hint_hand)?.mutate()
    private val handShadow: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_home_hint_hand)?.mutate()?.apply {
        colorFilter = PorterDuffColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_IN)
    }

    private val animated = animationsOn(context)
    private var phaseMs = STATIC_PHASE_MS
    private var animator: ValueAnimator? = null

    private val callout: LinearLayout = inflateCallout(context, this, look, holeless = hole == null)
    private val art: View = callout.findViewById(R.id.home_pages_hint_art)
    private val gotIt: MaterialButton = callout.findViewById(R.id.home_pages_hint_got_it)

    private var closed = false
    private var downOutside = false
    private var downInHole = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private val holdRunnable = Runnable { close(Reason.HOLD) }

    init {
        setWillNotDraw(false)
        // Only "Got it" takes focus, and the dim layer is no node of its own for TalkBack: it
        // lands on the callout's text and its button. Touches are still taken in onTouchEvent.
        isClickable = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (hole != null) {
            val diameter = HomePagesHintPolicy.ringDiameter(hole, dp(if (small) 76f else 96f).toInt())
            ringRadius = diameter / 2f
            handSize = (diameter * HAND_TO_RING).toInt()
            centerX = holeRect!!.centerX()
            centerY = holeRect.centerY()
        } else {
            val diameter = resources.getDimension(if (look.small) R.dimen.home_hint_art_small else R.dimen.home_hint_art)
            ringRadius = diameter / 2f - dp(3f)
            handSize = (diameter * HAND_IN_ART).toInt()
        }
        // A thinner ring on a small one, so it does not read as a solid disc.
        val strokeWidth = if (ringRadius < dp(30f)) dp(3f) else dp(4f)
        trackPaint.strokeWidth = strokeWidth
        ringPaint.strokeWidth = strokeWidth
        addView(callout, LayoutParams(calloutBox.width, LayoutParams.WRAP_CONTENT))
        ViewCompat.setElevation(callout, dp(8f))
        ViewCompat.setAccessibilityLiveRegion(callout, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        gotIt.setOnClickListener { close(Reason.GOT_IT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) gotIt.isFocusedByDefault = true
    }

    companion object {
        /** Where the static picture stops: the ring three quarters full, the hand pressed. */
        private const val STATIC_PHASE_MS = 1125f
        private const val LOOP_MS = 2400L
        private const val FILL_MS = 1500f
        private const val PRESS_MS = 200f
        private const val RIPPLE_MS = 500f
        private const val FADE_MS = 400f
        private const val PRESSED_SCALE = 0.92f
        private const val ENTRANCE_MS = 250L
        /** The hand to the ring's diameter on the spotlight, as in the approved design (72 to 96). */
        private const val HAND_TO_RING = 0.75f
        /** Smaller in the art, where it has to stay inside the callout. */
        private const val HAND_IN_ART = 0.6f

        /** The callout's width, regular or small, no wider than [room]. */
        fun calloutWidth(context: Context, small: Boolean, room: Int): Int = minOf(
            room,
            context.resources.getDimensionPixelSize(
                if (small) R.dimen.home_hint_callout_width_small else R.dimen.home_hint_callout_width
            )
        )

        private fun inflateCallout(context: Context, parent: ViewGroup, look: Look, holeless: Boolean): LinearLayout {
            val callout = LayoutInflater.from(context)
                .inflate(R.layout.view_home_pages_hint_callout, parent, false) as LinearLayout
            val res = callout.resources
            if (holeless) {
                callout.findViewById<TextView>(R.id.home_pages_hint_body).setText(R.string.home_pages_hint_body_anywhere)
            }
            if (look.small) {
                callout.findViewById<TextView>(R.id.home_pages_hint_title)
                    .setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.home_hint_title_small))
                callout.findViewById<TextView>(R.id.home_pages_hint_body)
                    .setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.home_hint_body_small))
                val padding = res.getDimensionPixelSize(R.dimen.home_hint_callout_padding_small)
                callout.setPadding(padding, padding, padding, padding)
                val button = callout.findViewById<View>(R.id.home_pages_hint_got_it)
                (button.layoutParams as LinearLayout.LayoutParams).apply {
                    height = res.getDimensionPixelSize(R.dimen.home_hint_button_height_small)
                    topMargin = (12 * res.displayMetrics.density).toInt()
                }
            }
            if (look.withArt) {
                val art = callout.findViewById<View>(R.id.home_pages_hint_art)
                art.visibility = View.VISIBLE
                val size = res.getDimensionPixelSize(if (look.small) R.dimen.home_hint_art_small else R.dimen.home_hint_art)
                val gap = (16 * res.displayMetrics.density).toInt()
                if (look.artBeside) {
                    callout.findViewById<LinearLayout>(R.id.home_pages_hint_content).orientation = LinearLayout.HORIZONTAL
                    art.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        rightMargin = gap
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) marginEnd = gap
                    }
                    callout.findViewById<View>(R.id.home_pages_hint_texts).layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    (art.layoutParams as LinearLayout.LayoutParams).apply {
                        width = size
                        height = size
                    }
                }
            }
            return callout
        }

        /**
         * The callout's size with [look] at [width], measured as it will be drawn, so the caller
         * can find it a place before building this view.
         */
        fun calloutSize(context: Context, look: Look, width: Int, holeless: Boolean): HomePagesHintPolicy.Size {
            val callout = inflateCallout(context, FrameLayout(context), look, holeless)
            callout.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            return HomePagesHintPolicy.Size(width, callout.measuredHeight)
        }

        /** How far the arrow stays from the callout's corners, so it leaves a straight edge. */
        fun arrowInset(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.home_hint_callout_radius) +
                (12 * context.resources.displayMetrics.density).toInt()

        /** Off when the user turned animations off, so the hint is a still picture instead. */
        fun animationsOn(context: Context): Boolean = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> ValueAnimator.areAnimatorsEnabled()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 -> try {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            } catch (e: Exception) {
                true
            }
            else -> true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) {
            alpha = 0f
            animate().alpha(1f).setDuration(ENTRANCE_MS).start()
            animator = ValueAnimator.ofFloat(0f, LOOP_MS.toFloat()).apply {
                duration = LOOP_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phaseMs = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        val title = callout.findViewById<TextView>(R.id.home_pages_hint_title).text
        val body = callout.findViewById<TextView>(R.id.home_pages_hint_body).text
        callout.announceForAccessibility("$title. $body")
        gotIt.requestFocus()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val x = calloutBox.left
        val y = calloutBox.top
        callout.layout(x, y, x + callout.measuredWidth, y + callout.measuredHeight)
        if (holeRect == null && art.visibility == View.VISIBLE) {
            artRect.set(0, 0, art.width, art.height)
            offsetDescendantRectToMyCoords(art, artRect)
            centerX = artRect.exactCenterX()
            centerY = artRect.exactCenterY()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        holeRect?.let { scrimPath.addRoundRect(it, holeRadius, holeRadius, Path.Direction.CW) }
        canvas.drawPath(scrimPath, scrimPaint)
        if (holeRect == null) return

        outlineRect?.let {
            val outlineRadius = holeRadius - dp(5f)
            canvas.drawRoundRect(it, outlineRadius, outlineRadius, outlinePaint)
        }
        drawArrow(canvas, holeRect)
        drawRing(canvas)
        drawHand(canvas)
    }

    // Over the callout, where its art is: the callout is a child, drawn after onDraw.
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (holeRect != null || art.visibility != View.VISIBLE) return
        drawRing(canvas)
        drawHand(canvas)
    }

    /** The callout's small triangle, pointing at the hand. */
    private fun drawArrow(canvas: Canvas, hole: RectF) {
        val side = calloutSide ?: return
        if (callout.width == 0) return
        val inset = resources.getDimension(R.dimen.home_hint_callout_radius) + arrow
        arrowPath.reset()
        when (side) {
            HomePagesHintPolicy.Side.LEFT, HomePagesHintPolicy.Side.RIGHT -> {
                val y = hole.centerY().coerceIn(callout.top + inset, callout.bottom - inset)
                val base = if (side == HomePagesHintPolicy.Side.LEFT) callout.right - 1f else callout.left + 1f
                val tip = if (side == HomePagesHintPolicy.Side.LEFT) base + arrow + 1f else base - arrow - 1f
                arrowPath.moveTo(base, y - arrow)
                arrowPath.lineTo(tip, y)
                arrowPath.lineTo(base, y + arrow)
            }
            HomePagesHintPolicy.Side.ABOVE, HomePagesHintPolicy.Side.BELOW -> {
                val x = hole.centerX().coerceIn(callout.left + inset, callout.right - inset)
                val base = if (side == HomePagesHintPolicy.Side.ABOVE) callout.bottom - 1f else callout.top + 1f
                val tip = if (side == HomePagesHintPolicy.Side.ABOVE) base + arrow + 1f else base - arrow - 1f
                arrowPath.moveTo(x - arrow, base)
                arrowPath.lineTo(x, tip)
                arrowPath.lineTo(x + arrow, base)
            }
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, calloutPaint)
    }

    private fun drawRing(canvas: Canvas) {
        val cx = centerX
        val cy = centerY
        val t = phaseMs
        val fade = if (t <= FILL_MS) 1f else (1f - (t - FILL_MS) / FADE_MS).coerceIn(0f, 1f)
        ringBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        trackPaint.alpha = (trackAlpha * fade).toInt()
        canvas.drawCircle(cx, cy, ringRadius, trackPaint)
        val sweep = 360f * min(t, FILL_MS) / FILL_MS
        ringPaint.alpha = (255 * fade).toInt()
        if (sweep > 0f) canvas.drawArc(ringBounds, -90f, sweep, false, ringPaint)
        // A soft ripple once the ring is full, the moment the press "takes".
        if (animated && t > FILL_MS && t < FILL_MS + RIPPLE_MS) {
            val p = (t - FILL_MS) / RIPPLE_MS
            ripplePaint.alpha = (140 * (1f - p)).toInt()
            canvas.drawCircle(cx, cy, ringRadius * (1f + 0.5f * p), ripplePaint)
        }
    }

    private fun drawHand(canvas: Canvas) {
        val hand = hand ?: return
        val cx = centerX
        val cy = centerY
        val t = phaseMs
        // Pressed down while the ring fills, then lifted.
        val pressed = when {
            !animated -> 1f
            t < PRESS_MS -> t / PRESS_MS
            t < FILL_MS -> 1f
            t < FILL_MS + PRESS_MS -> 1f - (t - FILL_MS) / PRESS_MS
            else -> 0f
        }
        val scale = 1f - (1f - PRESSED_SCALE) * pressed
        // The fingertip of the drawing is at (11.5, 6) of its 24 by 24 box.
        val left = (cx - handSize * 11.5f / 24f).toInt()
        val top = (cy - handSize * 6f / 24f).toInt()
        canvas.withScale(scale, scale, cx, cy) {
            handShadow?.let {
                val offset = dp(2f).toInt()
                it.setBounds(left + offset, top + offset * 2, left + offset + handSize, top + offset * 2 + handSize)
                it.draw(this)
            }
            hand.setBounds(left, top, left + handSize, top + handSize)
            hand.draw(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hole = holeRect
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // Without a spotlight a long press opens nothing: the hint only says where to.
                downInHole = hole != null && hole.contains(event.x, event.y)
                downOutside = !downInHole && !inCallout(event.x, event.y)
                if (downInHole) postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (downInHole && hypot(event.x - downX, event.y - downY) > touchSlop) {
                downInHole = false
                removeCallbacks(holdRunnable)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                val upInHole = hole != null && hole.contains(event.x, event.y)
                if (downOutside && !upInHole && !inCallout(event.x, event.y)) close(Reason.OUTSIDE)
                downOutside = false
                downInHole = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                downOutside = false
                downInHole = false
            }
        }
        // Everything under the hint is out of reach while it is up.
        return true
    }

    private fun inCallout(x: Float, y: Float): Boolean =
        x >= callout.left && x <= callout.right && y >= callout.top && y <= callout.bottom

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val isUp = event.action == KeyEvent.ACTION_UP
        return when (HomePagesHintKeyPolicy.onKey(event.keyCode, isUp, gotIt.isFocused)) {
            HomePagesHintKeyPolicy.Action.PASS -> super.dispatchKeyEvent(event)
            HomePagesHintKeyPolicy.Action.CONSUME -> true
            HomePagesHintKeyPolicy.Action.FOCUS_BUTTON -> {
                gotIt.requestFocus()
                true
            }
            HomePagesHintKeyPolicy.Action.CONFIRM -> {
                close(Reason.GOT_IT)
                true
            }
            HomePagesHintKeyPolicy.Action.BACK -> {
                close(Reason.BACK)
                true
            }
        }
    }

    /** Closes the hint once, whichever way it was closed first. */
    fun close(reason: Reason) {
        if (closed) return
        closed = true
        removeCallbacks(holdRunnable)
        animator?.cancel()
        onClosed(reason)
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }
}
