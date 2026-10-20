package com.andrerinas.openheadunit.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A container that measures itself as 16:9, capped at 180dp high.
 *
 * The onboarding video test reads the frame marker out of a bitmap of this view, so the video has
 * to fill it exactly. Every clip is 16:9 and this container keeps that aspect, which makes the
 * marker's move from clip coordinates to bitmap coordinates a plain scale.
 */
class VideoPreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maxHeightPx = (180 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        var width = availableWidth
        var height = width * 9 / 16
        if (height > maxHeightPx) {
            height = maxHeightPx
            width = height * 16 / 9
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
