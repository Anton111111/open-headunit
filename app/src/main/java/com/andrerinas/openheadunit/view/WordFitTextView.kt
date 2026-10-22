package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.Layout
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

    /**
     * The size from the layout, in px, before anyone scaled it or the fit shrank it. A caller that
     * scales the label works from this one: [getTextSize] may already be a fitted size.
     */
    val layoutTextSize: Float = textSize
    private var xmlMaxLines = -1
    private var fitting = false
    private val measurePaint = TextPaint()

    // Lint asks for LineBreaker.BREAK_STRATEGY_SIMPLE, which is API 29; Layout's constant has
    // the same value (0) and exists from API 23, where this branch starts.
    @SuppressLint("WrongConstant")
    private fun avoidHyphenation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        }
    }

    init {
        // From Android 6 the line breaker may hyphenate a word that fits ("Calen-dar") to even out
        // the lines; a tile label only breaks between words. Older versions never hyphenate.
        avoidHyphenation()
    }

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
            val widest = widestWord(text, requested)
            val floor = floorPx(requested)
            val estimate = LabelFitPolicy.fit(requested, widest, available, floor)
            applyFit(LabelFitPolicy.settle(estimate, requested, available, floor) { widestWord(text, it) })
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * How wide the tile holding this label has to be for none of [texts] to split or cut a word,
     * at the size the label is asked for now: [tilePx] when they already fit, at most [maxPx].
     */
    fun tileWidthForWholeWords(texts: Iterable<CharSequence>, tilePx: Int, maxPx: Int): Int {
        val requested = requestedPx ?: textSize
        val padding = totalPaddingLeft + totalPaddingRight + SLACK_PX
        // Measured at the size the label would settle on, not scaled from the requested one: at a
        // few pixels the two differ by more than the slack.
        val floor = floorPx(requested)
        val widestAtRequested = texts.maxOfOrNull { widestWord(it, requested) } ?: return tilePx
        if (widestAtRequested + padding <= tilePx) return tilePx
        val widestAtFloor = texts.maxOf { widestWord(it, floor) }
        return LabelFitPolicy.tileWidth(tilePx, floor, widestAtFloor, floor, padding, maxPx)
    }

    private fun widestWord(text: CharSequence, sizePx: Float): Float {
        measurePaint.set(paint)
        measurePaint.textSize = sizePx
        return LabelFitPolicy.words(text).maxOfOrNull { measurePaint.measureText(it) } ?: 0f
    }

    private fun floorPx(requested: Float): Float {
        val metrics = resources.displayMetrics
        return LabelFitPolicy.floor(
            requested,
            layoutTextSize,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_SP, metrics),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ABSOLUTE_MIN_SP, metrics),
        )
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
        /** The smallest label worth reading on a head unit, at the label's own size. */
        const val MIN_SP = 9f
        /** The floor of a label the home button scale made smaller: still readable at arm's length. */
        const val ABSOLUTE_MIN_SP = 7f
        const val SLACK_PX = 2
    }
}
