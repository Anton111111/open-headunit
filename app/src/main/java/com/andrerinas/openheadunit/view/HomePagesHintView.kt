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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.withScale
import androidx.core.view.ViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.HomePagesHintPolicy
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot
import kotlin.math.min

/**
 * The one-time hint for adding pages: the home dimmed but for a spotlight on an empty spot of the
 * main button, a hand pressing there while a ring fills (touch and hold, not a tap), and a callout
 * beside it. Placed from the real card's bounds by [HomePagesHintPolicy]; this view only draws
 * and reports how it was closed.
 */
@SuppressLint("ViewConstructor")
class HomePagesHintView(
    context: Context,
    private val hole: HomePagesHintPolicy.Box,
    private val calloutSide: HomePagesHintPolicy.Side,
    small: Boolean,
    private val onClosed: (Reason) -> Unit,
) : FrameLayout(context) {

    /** LEFT: the home went away, or stopped being idle, while the hint was up. */
    enum class Reason { GOT_IT, OUTSIDE, BACK, HOLD, LEFT }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val holeRect = RectF(hole.left.toFloat(), hole.top.toFloat(), hole.right.toFloat(), hole.bottom.toFloat())
    private val holeRadius = dp(22f)
    private val ringRadius = dp(if (small) 38f else 48f)
    private val handSize = dp(if (small) 58f else 72f).toInt()
    private val gap = dp(18f).toInt()
    private val margin = dp(12f).toInt()
    private val arrow = dp(12f)

    private val scrimPath = Path()
    private val arrowPath = Path()
    private val scrimPaint = paint(ContextCompat.getColor(context, R.color.home_hint_scrim))
    private val calloutPaint = paint(ContextCompat.getColor(context, R.color.home_hint_callout))
    private val outlinePaint = stroke(Color.argb(97, 255, 255, 255), dp(2f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val trackPaint = stroke(Color.argb(64, 255, 255, 255), dp(4f))
    private val ringPaint = stroke(Color.WHITE, dp(4f)).apply { strokeCap = Paint.Cap.ROUND }
    private val ripplePaint = stroke(Color.WHITE, dp(2f))
    private val ringBounds = RectF()
    private var screen = HomePagesHintPolicy.Box(0, 0, 0, 0)
    private val outlineRect = RectF(holeRect).apply { inset(dp(10f), dp(10f)) }

    private val hand: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_home_hint_hand)?.mutate()
    private val handShadow: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_home_hint_hand)?.mutate()?.apply {
        colorFilter = PorterDuffColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_IN)
    }

    private val animated = animationsOn(context)
    private var phaseMs = STATIC_PHASE_MS
    private var animator: ValueAnimator? = null

    private val callout: LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.view_home_pages_hint_callout, this, false) as LinearLayout
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
        isClickable = true
        isFocusable = true
        if (small) {
            val res = resources
            callout.findViewById<TextView>(R.id.home_pages_hint_title)
                .setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.home_hint_title_small))
            callout.findViewById<TextView>(R.id.home_pages_hint_body)
                .setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.home_hint_body_small))
            val padding = res.getDimensionPixelSize(R.dimen.home_hint_callout_padding_small)
            callout.setPadding(padding, padding, padding, padding)
            gotIt.layoutParams = gotIt.layoutParams.apply { height = res.getDimensionPixelSize(R.dimen.home_hint_button_height_small) }
        }
        addView(callout, LayoutParams(calloutWidth(context, small), LayoutParams.WRAP_CONTENT))
        ViewCompat.setElevation(callout, dp(8f))
        ViewCompat.setAccessibilityLiveRegion(callout, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        gotIt.setOnClickListener { close(Reason.GOT_IT) }
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

        /** The width the callout takes, for the caller to choose its side before building this view. */
        fun calloutWidth(context: Context, small: Boolean): Int = context.resources.getDimensionPixelSize(
            if (small) R.dimen.home_hint_callout_width_small else R.dimen.home_hint_callout_width
        )

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screen = HomePagesHintPolicy.Box(0, 0, w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = callout.measuredWidth
        val h = callout.measuredHeight
        val x = if (calloutSide == HomePagesHintPolicy.Side.LEFT) hole.left - gap - w else hole.right + gap
        val y = HomePagesHintPolicy.calloutTop(hole, screen, h, margin)
        callout.layout(x, y, x + w, y + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        scrimPath.addRoundRect(holeRect, holeRadius, holeRadius, Path.Direction.CW)
        canvas.drawPath(scrimPath, scrimPaint)

        val outlineRadius = holeRadius - dp(5f)
        canvas.drawRoundRect(outlineRect, outlineRadius, outlineRadius, outlinePaint)
        drawArrow(canvas)
        drawRing(canvas)
        drawHand(canvas)
    }

    /** The callout's small triangle, pointing at the hand. */
    private fun drawArrow(canvas: Canvas) {
        if (callout.width == 0) return
        val radius = resources.getDimension(R.dimen.home_hint_callout_radius)
        val y = holeRect.centerY().coerceIn(callout.top + radius + arrow, callout.bottom - radius - arrow)
        arrowPath.reset()
        if (calloutSide == HomePagesHintPolicy.Side.LEFT) {
            val base = callout.right.toFloat()
            arrowPath.moveTo(base - 1, y - arrow)
            arrowPath.lineTo(base + arrow, y)
            arrowPath.lineTo(base - 1, y + arrow)
        } else {
            val base = callout.left.toFloat()
            arrowPath.moveTo(base + 1, y - arrow)
            arrowPath.lineTo(base - arrow, y)
            arrowPath.lineTo(base + 1, y + arrow)
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, calloutPaint)
    }

    private fun drawRing(canvas: Canvas) {
        val cx = holeRect.centerX()
        val cy = holeRect.centerY()
        val t = phaseMs
        val fade = if (t <= FILL_MS) 1f else (1f - (t - FILL_MS) / FADE_MS).coerceIn(0f, 1f)
        ringBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        trackPaint.alpha = (64 * fade).toInt()
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
        val cx = holeRect.centerX()
        val cy = holeRect.centerY()
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downInHole = holeRect.contains(event.x, event.y)
                downOutside = !downInHole && !inCallout(event.x, event.y)
                if (downInHole) postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (downInHole && hypot(event.x - downX, event.y - downY) > touchSlop) {
                downInHole = false
                removeCallbacks(holdRunnable)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (downOutside && !holeRect.contains(event.x, event.y) && !inCallout(event.x, event.y)) {
                    close(Reason.OUTSIDE)
                }
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close(Reason.BACK)
            return true
        }
        return super.dispatchKeyEvent(event)
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
