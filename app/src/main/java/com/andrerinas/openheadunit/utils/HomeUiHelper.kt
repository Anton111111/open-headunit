package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Connection
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Decision
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Status

/** Paints the home screen. What it shows is decided by [com.andrerinas.openheadunit.main.HomePrimaryPolicy]. */
object HomeUiHelper {

    private const val RIPPLE_COLOR = 0x33000000

    /** The rows a right-hand drive car reads from the other end. Vertical ones are left alone. */
    private val mirroredRows = listOf(
        R.id.main_buttons_container,
        R.id.home_driver_bar,
        R.id.home_favorites,
        R.id.home_primary_header,
        R.id.home_primary_actions
    )

    val favoriteSlotIds = listOf(
        R.id.favorite_slot_1, R.id.favorite_slot_2, R.id.favorite_slot_3, R.id.favorite_slot_4
    )

    private val scaledTiles = listOf(
        R.id.usb_button, R.id.wifi_button, R.id.self_mode_button, R.id.settings_button, R.id.exit_button
    )

    fun chosenConnections(settings: Settings): Set<Connection> = settings.connectionModes.map {
        when (it) {
            Settings.ConnectionMode.USB -> Connection.USB
            Settings.ConnectionMode.WIFI -> Connection.WIFI
            Settings.ConnectionMode.SELF -> Connection.SELF
        }
    }.toSet()

    fun lastConnection(settings: Settings): Connection? = when (settings.lastConnectionType) {
        Settings.CONNECTION_TYPE_USB -> Connection.USB
        Settings.CONNECTION_TYPE_WIFI, Settings.CONNECTION_TYPE_NEARBY -> Connection.WIFI
        else -> null
    }

    private fun tileId(connection: Connection): Int = when (connection) {
        Connection.USB -> R.id.usb_button
        Connection.WIFI -> R.id.wifi_button
        Connection.SELF -> R.id.self_mode_button
    }

    /**
     * Puts the bar and the start of every row on the driver's side, and aligns the main button with
     * it. LEFT and RIGHT on purpose: the driver's side is physical, not a reading direction.
     */
    @SuppressLint("RtlHardcoded")
    fun applyDriverSide(rootView: View, rightHandDrive: Boolean) {
        mirroredRows.mapNotNull { rootView.findViewById<LinearLayout>(it) }
            .filter { it.orientation == LinearLayout.HORIZONTAL }
            .forEach { placeChildren(it, reversed = rightHandDrive) }

        val side = if (rightHandDrive) Gravity.RIGHT else Gravity.LEFT
        rootView.findViewById<LinearLayout>(R.id.home_primary_card)?.gravity = side
        listOf(R.id.home_primary_kicker, R.id.home_primary_title, R.id.home_primary_subtitle).forEach {
            rootView.findViewById<TextView>(it)?.gravity = side
        }
    }

    // The XML order is the left-hand drive one, kept on the row so applying twice is harmless.
    private fun placeChildren(row: LinearLayout, reversed: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val xmlOrder = row.getTag(R.id.home_xml_order) as? List<View>
            ?: (0 until row.childCount).map { row.getChildAt(it) }.also { row.setTag(R.id.home_xml_order, it) }
        row.removeAllViews()
        // A for loop, not forEach: lint reads that one as Iterable.forEach, which is API 24.
        for (child in if (reversed) xmlOrder.asReversed() else xmlOrder) row.addView(child)
    }

    fun applyBar(context: Context, rootView: View, settings: Settings, barConnections: List<Connection>) {
        Connection.values().forEach { connection ->
            rootView.findViewById<View>(tileId(connection))?.visibility =
                if (connection in barConnections) View.VISIBLE else View.GONE
        }

        val settingsTile = rootView.findViewById<View>(R.id.settings_button) ?: return
        val customColor = settings.customSettingsButtonColor
        settingsTile.background = if (customColor != 0) {
            pressable(customColor, context.resources.getDimension(R.dimen.home_tile_radius))
        } else {
            ContextCompat.getDrawable(context, R.drawable.home_tile_bg)
        }
    }

    /** Scales the bar tiles and the favorites row by the user's home button scale. */
    fun applyButtonScale(rootView: View, scalePercent: Int) {
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val factor = validScale / 100.0f
        (scaledTiles + R.id.home_favorites).mapNotNull { rootView.findViewById<View>(it) }.forEach { scale(it, factor) }
    }

    // Sizes from the XML, kept on the view so every call scales from them rather than compounding.
    private fun scale(view: View, factor: Float) {
        val params = view.layoutParams ?: return
        val base = view.getTag(R.id.home_base_size) as? IntArray
            ?: intArrayOf(params.width, params.height).also { view.setTag(R.id.home_base_size, it) }
        if (base[0] > 0) params.width = (base[0] * factor).toInt()
        if (base[1] > 0) params.height = (base[1] * factor).toInt()
        view.layoutParams = params
    }

    fun applyPrimaryCard(
        context: Context,
        rootView: View,
        settings: Settings,
        decision: Decision,
        phoneName: String?,
        secondsLeft: Int,
        isNightActive: Boolean
    ) {
        val card = rootView.findViewById<View>(R.id.home_primary_card) ?: return
        val reconnecting = decision.status == Status.RECONNECTING

        rootView.findViewById<TextView>(R.id.home_primary_kicker)?.text = context.getString(kickerRes(decision))
        rootView.findViewById<TextView>(R.id.home_primary_title)?.text = title(context, decision, phoneName)
        rootView.findViewById<TextView>(R.id.home_primary_subtitle)?.text = context.getString(subtitleRes(decision))
        rootView.findViewById<TextView>(R.id.home_primary_action)?.text = context.getString(actionRes(decision))
        rootView.findViewById<ImageView>(R.id.home_primary_icon)?.setImageResource(iconRes(decision.primary))
        val cancel = rootView.findViewById<View>(R.id.home_primary_cancel)
        cancel?.visibility = if (reconnecting) View.VISIBLE else View.GONE
        rootView.findViewById<TextView>(R.id.home_countdown)?.apply {
            visibility = if (reconnecting) View.VISIBLE else View.GONE
            text = secondsLeft.toString()
        }

        val color = cardColor(context, settings, decision.primary, isNightActive)
        val shade = ColorUtils.darkenColor(color, 0.78f)
        val resources = context.resources
        card.background = rounded(color, resources.getDimension(R.dimen.home_panel_radius))
        rootView.findViewById<View>(R.id.home_primary_icon_tile)?.background =
            rounded(shade, resources.getDimension(R.dimen.home_tile_radius))
        rootView.findViewById<TextView>(R.id.home_primary_action)?.apply {
            background = pressable(0xFFFFFFFF.toInt(), resources.getDimension(R.dimen.home_cta_height) / 3.5f)
            setTextColor(ColorUtils.darkenColor(color, 0.66f))
        }
        cancel?.background = pressable(shade, resources.getDimension(R.dimen.home_secondary_cta_height) / 2f)
    }

    /**
     * The apps row exists only while Open Headunit is the launcher, and takes the height the main
     * button's icon would use. With the favorites switched off it keeps "All", the only way left
     * to the other apps.
     */
    fun applyFavoritesVisibility(rootView: View, isLauncherActive: Boolean, showFavorites: Boolean) {
        rootView.findViewById<View>(R.id.home_favorites)?.visibility = if (isLauncherActive) View.VISIBLE else View.GONE
        rootView.findViewById<View>(R.id.home_primary_icon_tile)?.visibility =
            if (isLauncherActive) View.GONE else View.VISIBLE
        favoriteSlotIds.forEach {
            rootView.findViewById<View>(it)?.visibility = if (showFavorites) View.VISIBLE else View.GONE
        }
    }

    private fun cardColor(context: Context, settings: Settings, connection: Connection, isNightActive: Boolean): Int {
        val isDarkTheme = settings.appTheme == Settings.AppTheme.DARK ||
                settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                isNightActive
        if (isDarkTheme && settings.autoMonochromeButtonsAtNight) {
            return ContextCompat.getColor(context, R.color.home_monochrome)
        }
        val (customColor, defaultColorRes) = when (connection) {
            Connection.USB -> settings.customUsbButtonColor to R.color.home_usb
            Connection.WIFI -> settings.customWifiButtonColor to R.color.home_wifi
            Connection.SELF -> settings.customSelfModeButtonColor to R.color.home_self
        }
        return if (customColor != 0) customColor else ContextCompat.getColor(context, defaultColorRes)
    }

    private fun kickerRes(decision: Decision): Int = when (decision.status) {
        Status.ACTIVE_SESSION -> R.string.home_kicker_active
        Status.RECONNECTING -> R.string.home_kicker_last_connection
        else -> when (decision.primary) {
            Connection.USB -> R.string.home_kicker_cable
            Connection.WIFI -> R.string.home_kicker_wireless
            Connection.SELF -> R.string.self_mode
        }
    }

    private fun title(context: Context, decision: Decision, phoneName: String?): String = when (decision.status) {
        Status.ACTIVE_SESSION -> context.getString(R.string.home_title_active)
        Status.RECONNECTING -> context.getString(R.string.home_title_reconnecting)
        Status.SEARCHING -> context.getString(R.string.home_title_searching)
        Status.PHONE_DETECTED -> phoneName?.let { context.getString(R.string.home_title_phone_detected, it) }
            ?: context.getString(R.string.home_title_phone_detected_generic)
        Status.READY -> context.getString(
            when (decision.primary) {
                Connection.USB -> R.string.home_title_usb_ready
                Connection.WIFI -> R.string.home_title_wifi_ready
                Connection.SELF -> R.string.home_title_self_ready
            }
        )
    }

    private fun subtitleRes(decision: Decision): Int = when {
        decision.status == Status.SEARCHING -> R.string.home_subtitle_searching
        decision.primary == Connection.USB -> R.string.home_subtitle_usb
        decision.primary == Connection.WIFI -> R.string.home_subtitle_wifi
        else -> R.string.home_subtitle_self
    }

    private fun actionRes(decision: Decision): Int = when (decision.status) {
        Status.ACTIVE_SESSION -> R.string.home_action_back_to_android_auto
        Status.RECONNECTING -> R.string.home_action_connect_now
        Status.SEARCHING -> R.string.home_action_search_again
        else -> when (decision.primary) {
            Connection.USB -> R.string.home_action_connect_usb
            Connection.WIFI -> R.string.home_action_connect_wifi
            Connection.SELF -> R.string.home_action_start_self_mode
        }
    }

    private fun iconRes(connection: Connection): Int = when (connection) {
        Connection.USB -> R.drawable.ic_usb_white
        Connection.WIFI -> R.drawable.ic_network_wifi_white
        Connection.SELF -> R.drawable.ic_launch_white
    }

    private fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    // Ripple is API 21; older units get a darker shade while pressed instead.
    private fun pressable(color: Int, radiusPx: Float): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), rounded(color, radiusPx), null)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ColorUtils.darkenColor(color, 0.85f), radiusPx))
            addState(intArrayOf(), rounded(color, radiusPx))
        }
    }
}
