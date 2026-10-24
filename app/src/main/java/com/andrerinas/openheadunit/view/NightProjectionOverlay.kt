package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.NightOverlayState
import java.util.Date

/** Covers the picture without hiding or detaching the decoder's surface. */
class NightProjectionOverlay(context: Context) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = label()
    private val maneuver = label()
    private val title = label()
    private var configuredWidth = 0
    private var configuredHeight = 0
    private val details = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
    private val clockTick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (!isShown) return
            clock.text = DateFormat.getTimeFormat(context).format(Date())
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.night_overlay_dismiss)
        visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Above the performance HUD as well as all projection backends.
            elevation = 201f
        }
        details.addView(clock)
        details.addView(maneuver)
        details.addView(title)
        addView(details, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { hide() }
    }

    private fun label() = AppCompatTextView(context).apply {
        setTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show() {
        visibility = View.VISIBLE
        bringToFront()
        clockTick.run()
    }

    fun hide() {
        visibility = View.GONE
        handler.removeCallbacks(clockTick)
    }

    fun render(content: NightOverlayState.Content) {
        val navigation = content.navigation
        maneuver.text = navigation?.let {
            val distance = it.distanceMeters?.let { meters ->
                if (meters < 1000) context.getString(R.string.night_overlay_meters, meters)
                else context.getString(R.string.night_overlay_kilometers, meters / 1000.0)
            }
            if (distance == null) it.maneuver
            else context.getString(R.string.night_overlay_navigation, it.maneuver, distance)
        }.orEmpty()
        maneuver.visibility = if (maneuver.text.isNotBlank()) View.VISIBLE else View.GONE
        val showTrack = content.isPlaying != false
        title.text = listOf(content.title, content.artist).filter { it.isNotBlank() }.joinToString(" - ")
        title.visibility = if (showTrack && title.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    fun updateTextSizes() {
        configuredWidth = 0
        configuredHeight = 0
        requestLayout()
    }

    private fun configureTextSizes(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        val shorterSide = minOf(width, height)
        val shorterDp = shorterSide / density
        val clockHeight = (shorterSide * 0.25f).toInt()
        val rowHeight = (shorterSide * 0.10f).toInt()
        val padding = (shorterDp * 0.04f * density).toInt()
        clock.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, clockHeight)
        details.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply {
            leftMargin = padding
            rightMargin = padding
        }
        for (label in listOf(maneuver, title)) {
            label.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        }
        autoSize(clock, 32, (shorterDp * 0.22f).coerceIn(48f, 120f).toInt())
        autoSize(maneuver, 12, (shorterDp * 0.065f).coerceIn(16f, 32f).toInt())
        autoSize(title, 12, (shorterDp * 0.065f).coerceIn(16f, 32f).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth > 0 && availableHeight > 0 &&
            (configuredWidth != availableWidth || configuredHeight != availableHeight)) {
            configuredWidth = availableWidth
            configuredHeight = availableHeight
            // Child layout parameters must be ready before FrameLayout measures them.
            configureTextSizes(availableWidth, availableHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun autoSize(view: AppCompatTextView, minSp: Int, maxSp: Int) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            view, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clockTick.run()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) clockTick.run()
        else handler.removeCallbacks(clockTick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }
}
