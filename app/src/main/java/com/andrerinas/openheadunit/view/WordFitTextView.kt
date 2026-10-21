package com.andrerinas.openheadunit.view

import android.content.Context
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.andrerinas.openheadunit.main.LabelFitPolicy

/**
 * A tile label that never splits a word across lines: see [LabelFitPolicy]. The size anyone sets
 * stays the one it asks for, so scaling the label from outside still works; the fitted size is
 * worked out from it on every measure, with the width the tile really has.
 */
class WordFitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** The size asked for, in px; null until the first measure takes the one from the XML. */
    private var requestedPx: Float? = null
    private var xmlMaxLines = -1
    private var fitting = false
    private val measurePaint = TextPaint()

    override fun setTextSize(unit: Int, size: Float) {
        if (!fitting) requestedPx = TypedValue.applyDimension(unit, size, resources.displayMetrics)
        super.setTextSize(unit, size)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requested = requestedPx ?: textSize.also { requestedPx = it }
        if (xmlMaxLines < 0) xmlMaxLines = maxLines
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        if (mode != MeasureSpec.UNSPECIFIED) {
            // A pixel or two of slack: a word measured to the exact width can still be broken.
            val available = (MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight - SLACK_PX).toFloat()
            measurePaint.set(paint)
            measurePaint.textSize = requested
            val widest = LabelFitPolicy.words(text).maxOfOrNull { measurePaint.measureText(it) } ?: 0f
            val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_SP, resources.displayMetrics)
            val fit = LabelFitPolicy.fit(requested, widest, available, min)
            applyFit(fit)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyFit(fit: LabelFitPolicy.Fit) {
        fitting = true
        try {
            if (textSize != fit.size) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.size)
            val lines = if (fit.singleLine) 1 else xmlMaxLines
            if (maxLines != lines) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        } finally {
            fitting = false
        }
    }

    private companion object {
        /** The smallest label worth reading on a head unit. */
        const val MIN_SP = 9f
        const val SLACK_PX = 2
    }
}
