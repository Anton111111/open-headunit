package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Builds the settings screen's backgrounds from a [SettingsPalette].
 *
 * Everything is made in code from GradientDrawable, InsetDrawable and StateListDrawable, which
 * exist on every API this app supports. Shape XML would need theme attributes (API 21) to follow
 * extreme dark, and vector drawables inside selectors are unsafe before API 21.
 */
object SettingsDrawables {

    private const val INNER_CORNER_DP = 4f
    private const val OUTLINE_DP = 1f
    private const val PRESSED_OVERLAY_ALPHA = 0x1F

    private const val SWITCH_TRACK_WIDTH_DP = 52
    private const val SWITCH_TRACK_HEIGHT_DP = 32
    private const val SWITCH_TRACK_OUTLINE_DP = 2f
    private const val SWITCH_THUMB_ON_DP = 24
    private const val SWITCH_THUMB_OFF_DP = 16

    /** One row of a connected group: large outer corners, small inner ones, pressed feedback. */
    fun groupItem(
        context: Context,
        palette: SettingsPalette,
        position: GroupPosition,
        outerRadiusPx: Float,
        @ColorInt fill: Int = palette.container
    ): Drawable {
        val inner = dp(context, INNER_CORNER_DP)
        val top = if (position == GroupPosition.TOP || position == GroupPosition.SINGLE) outerRadiusPx else inner
        val bottom = if (position == GroupPosition.BOTTOM || position == GroupPosition.SINGLE) outerRadiusPx else inner
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        val stroke = if (palette.outlinedGroups) palette.outlineVariant else null
        val rest = roundedRect(context, fill, radii, stroke)
        return pressable(rest, palette) {
            roundedRect(context, ColorUtils.compositeColors(pressedOverlay(palette), fill), radii, stroke)
        }
    }

    /** A flat rounded box, e.g. the info banner or the connection scope note. */
    fun box(context: Context, @ColorInt color: Int, radiusPx: Float, palette: SettingsPalette): Drawable {
        val stroke = if (palette.outlinedGroups) palette.outlineVariant else null
        return roundedRect(context, color, FloatArray(8) { radiusPx }, stroke)
    }

    /** The selection pill behind the active category in the rail or the chip row. */
    fun selectionPill(context: Context, @ColorInt color: Int, @ColorInt strokeColor: Int?, radiusPx: Float, palette: SettingsPalette): Drawable {
        val rest = roundedRect(context, color, FloatArray(8) { radiusPx }, strokeColor)
        return pressable(rest, palette) {
            roundedRect(context, ColorUtils.compositeColors(pressedOverlay(palette), solidOr(color, palette.surface)), FloatArray(8) { radiusPx }, strokeColor)
        }
    }

    /** The search field: a pill on the container color. */
    fun searchField(context: Context, palette: SettingsPalette, radiusPx: Float): Drawable =
        box(context, palette.containerHigh, radiusPx, palette)

    /** Material 3 switch track: filled when on, outlined when off. */
    fun switchTrack(context: Context, palette: SettingsPalette): Drawable {
        val radius = dp(context, SWITCH_TRACK_HEIGHT_DP / 2f)
        fun track(@ColorInt fill: Int, @ColorInt outline: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(dp(context, SWITCH_TRACK_OUTLINE_DP).toInt(), outline)
            setSize(dp(context, SWITCH_TRACK_WIDTH_DP.toFloat()).toInt(), dp(context, SWITCH_TRACK_HEIGHT_DP.toFloat()).toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(palette.primary, palette.primary))
            addState(intArrayOf(), track(palette.containerHighest, palette.outline))
        }
    }

    /**
     * Material 3 switch thumb: a large dot when on, a small one when off. Both are inset into one
     * slot (track height tall, half the track wide) so the switch keeps its size: SwitchCompat
     * makes the track twice the thumb's width, which gives Material's 52dp.
     */
    fun switchThumb(context: Context, palette: SettingsPalette): Drawable {
        val slotWidth = SWITCH_TRACK_WIDTH_DP / 2f
        val slotHeight = SWITCH_TRACK_HEIGHT_DP.toFloat()
        fun dot(@ColorInt color: Int, sizeDp: Int): Drawable {
            val size = dp(context, sizeDp.toFloat()).toInt()
            val oval = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setSize(size, size)
            }
            val insetX = dp(context, (slotWidth - sizeDp) / 2f).toInt()
            val insetY = dp(context, (slotHeight - sizeDp) / 2f).toInt()
            return InsetDrawable(oval, insetX, insetY, insetX, insetY)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), dot(palette.onPrimary, SWITCH_THUMB_ON_DP))
            addState(intArrayOf(), dot(palette.outline, SWITCH_THUMB_OFF_DP))
        }
    }

    /** A vector loaded through AppCompat (safe on API 16) and tinted to [color]. */
    fun tinted(context: Context, @DrawableRes resId: Int, @ColorInt color: Int): Drawable? {
        val drawable = AppCompatResources.getDrawable(context, resId) ?: return null
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, color)
        return wrapped
    }

    /** Colors for a checkable control: [checked] when checked, [unchecked] otherwise. */
    fun checkedColors(@ColorInt checked: Int, @ColorInt unchecked: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked)
    )

    /** Colors for a button that greys out when disabled. */
    fun enabledColors(@ColorInt enabled: Int, @ColorInt disabled: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled)
    )

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    private fun roundedRect(context: Context, @ColorInt color: Int, radii: FloatArray, @ColorInt strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(color)
            if (strokeColor != null) setStroke(dp(context, OUTLINE_DP).toInt(), strokeColor)
        }

    /** Ripple on API 21+, a pressed-state swap before that (RippleDrawable does not exist there). */
    private fun pressable(rest: GradientDrawable, palette: SettingsPalette, pressed: () -> Drawable): Drawable =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ripple(rest, palette)
        } else {
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed())
                addState(intArrayOf(android.R.attr.state_focused), pressed())
                addState(intArrayOf(), rest)
            }
        }

    // Its own method so the class reference stays out of code that runs on API 16 to 20.
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun ripple(rest: GradientDrawable, palette: SettingsPalette): Drawable =
        RippleDrawable(ColorStateList.valueOf(pressedOverlay(palette)), rest, null)

    private fun pressedOverlay(palette: SettingsPalette): Int =
        ColorUtils.setAlphaComponent(palette.onSurface, PRESSED_OVERLAY_ALPHA)

    /** A transparent pill presses against the surface behind it. */
    private fun solidOr(@ColorInt color: Int, @ColorInt behind: Int): Int =
        if (android.graphics.Color.alpha(color) == 0) behind else color
}
