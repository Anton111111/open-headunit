package com.andrerinas.openheadunit.main

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.appcompat.view.ContextThemeWrapper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.random.Random

/**
 * The host for the widgets the home screen shows under the main button.
 *
 * Binding a widget is the system's decision, not this app's: from Android 5.0 the app asks and the
 * user answers, and only then may the widget be shown. Below that there is no way to ask, so the
 * home screen offers apps and shortcuts only, the same way shortcuts wait for Android 7.1.
 *
 * The host listens while the home screen is on screen and lets go when it is not, or the widgets
 * keep being updated for a screen nobody is looking at.
 */
object HomeWidgets {

    private const val TAG = "HomeWidgets"

    /** The host id has to survive restarts: the widgets that were bound name it. */
    private const val HOST_ID_MAX = 100_000

    /** Nothing reads it: a setup started by the host answers the Activity, which ignores it. */
    private const val CONFIGURE_REQUEST_CODE = 0x5717

    private var hostInstance: AppWidgetHost? = null

    /** Widgets need a user to allow them, which only exists from Android 5.0. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.LOLLIPOP)
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    private fun hostId(context: Context): Int {
        val settings = App.provide(context).settings
        val stored = settings.homeWidgetHostId
        if (stored > 0) return stored
        val fresh = Random.nextInt(1, HOST_ID_MAX)
        settings.homeWidgetHostId = fresh
        return fresh
    }

    private fun host(context: Context): AppWidgetHost = hostInstance ?: synchronized(this) {
        hostInstance ?: Host(context.applicationContext, hostId(context)).also { hostInstance = it }
    }

    /** Hands out views that answer a long press even where the widget fills its box with taps. */
    private class Host(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            HomeWidgetHostView(context)

        fun releaseViews() = clearViews()
    }

    /**
     * Lets go of the views the host handed out. The host lives as long as the process and keeps
     * the last view of every widget, which holds the Activity it was built on.
     */
    fun releaseViews() {
        try {
            (hostInstance as? Host)?.releaseViews()
        } catch (e: Exception) {
            AppLog.w("$TAG: the widget views could not be released: ${e.message}")
        }
    }

    fun startListening(context: Context) {
        if (!supported()) return
        try {
            host(context).startListening()
        } catch (e: Exception) {
            AppLog.w("$TAG: the widget host could not start listening: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            hostInstance?.stopListening()
        } catch (e: NullPointerException) {
            // The system service trips over a widget id this host allocated but that is not bound
            // yet, the one waiting on the user to allow it while the home is off screen for that
            // very dialog. It fails after dropping the host's callbacks, while it updates which
            // widget apps count as visible, so the host did stop listening. The id cannot be freed
            // here to avoid it: the user's answer is still on its way for it.
            AppLog.i("$TAG: the widget host stopped listening; the system tripped over a widget not bound yet: ${e.message}")
        } catch (e: Exception) {
            AppLog.w("$TAG: the widget host could not stop listening: ${e.message}")
        }
    }

    /** @return whether the widget is still bound to a provider that exists. */
    fun isAlive(context: Context, appWidgetId: Int): Boolean = try {
        manager(context).getAppWidgetInfo(appWidgetId) != null
    } catch (e: Exception) {
        AppLog.w("$TAG: widget $appWidgetId could not be looked up: ${e.message}")
        false
    }

    /** @return the view that draws the widget, or null when its provider is gone. */
    fun view(context: Context, appWidgetId: Int): AppWidgetHostView? {
        if (!supported()) return null
        val info = try {
            manager(context).getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            // The widget is inflated with the theme of the context it is given. The home screen's
            // own theme is what its widgets expect from a launcher, not the splash or the
            // projection one, so it is named here rather than inherited from whatever is on top.
            val themed = ContextThemeWrapper(context, R.style.AppTheme)
            host(context).createView(themed, appWidgetId, info)
        } catch (e: Exception) {
            // A provider that misbehaves while being created must not take the home screen with it.
            AppLog.w("$TAG: widget $appWidgetId could not be created: ${e.message}")
            null
        }
    }

    /** The width the widget asks for, in pixels, which is the smallest it can draw at. */
    fun minWidthPx(context: Context, appWidgetId: Int): Int = minSizePx(context, appWidgetId) { it.minWidth }

    /** The height the widget asks for, in pixels. */
    fun minHeightPx(context: Context, appWidgetId: Int): Int = minSizePx(context, appWidgetId) { it.minHeight }

    // The system already turned the provider's dp into pixels for this screen when it read them.
    private fun minSizePx(context: Context, appWidgetId: Int, pick: (AppWidgetProviderInfo) -> Int): Int {
        val info = try {
            manager(context).getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        } ?: return 0
        return pick(info)
    }

    /**
     * The activity that sets up a widget the user just added, or null when it needs none. From
     * Android 12 a provider can say the setup is optional; the widget then works as it comes.
     */
    fun configureActivity(context: Context, appWidgetId: Int): ComponentName? {
        val info = try {
            manager(context).getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        } ?: return null
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        return info.configure.takeIf { !optional }
    }

    fun configureRequest(appWidgetId: Int, activity: ComponentName): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = activity
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

    /**
     * Starts a setup activity its app did not export. Only the host may, and its answer goes to
     * the Activity rather than to a result launcher, so the caller keeps the widget either way.
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun configureThroughHost(context: Context, activity: Activity, appWidgetId: Int) {
        host(context).startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, CONFIGURE_REQUEST_CODE, null)
    }

    /**
     * Asks the system to bind a widget the user picked.
     *
     * @return true when this app may host it straight away; false when the user has to be asked
     *   with [bindRequest], which the caller launches for the id [allocateId] handed out.
     */
    fun tryBind(context: Context, appWidgetId: Int, provider: ComponentName): Boolean = try {
        manager(context).bindAppWidgetIdIfAllowed(appWidgetId, provider)
    } catch (e: Exception) {
        AppLog.w("$TAG: binding widget $appWidgetId was refused: ${e.message}")
        false
    }

    fun allocateId(context: Context): Int = host(context).allocateAppWidgetId()

    fun bindRequest(appWidgetId: Int, provider: ComponentName): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }

    /** Frees a widget nobody is showing any more, so the system stops keeping it alive. */
    fun forget(context: Context, appWidgetId: Int) {
        try {
            host(context).deleteAppWidgetId(appWidgetId)
        } catch (e: Exception) {
            AppLog.w("$TAG: widget $appWidgetId could not be released: ${e.message}")
        }
    }

    /** Tells a hosted widget the box it has, so one that can reflow lays itself out for it. */
    @Suppress("DEPRECATION")
    fun tellSize(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        // The widget reads the size in dp, the unit its own layouts are written in.
        val density = view.resources.displayMetrics.density
        val widthDp = HomeWidgetsPolicy.toDp(widthPx, density)
        val heightDp = HomeWidgetsPolicy.toDp(heightPx, density)
        try {
            view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        } catch (e: Exception) {
            AppLog.w("$TAG: the widget could not be told its size: ${e.message}")
        }
    }

    /** The name the widget's own app gives it, for the menu that a long press on it opens. */
    fun label(context: Context, appWidgetId: Int): String? {
        val info = try {
            manager(context).getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        } ?: return null
        return if (supported()) labelOf(info, context.packageManager) else null
    }

    /** One widget the user can add, with the labels the picker shows. */
    class Provider(
        val info: AppWidgetProviderInfo,
        val label: String,
        val appLabel: String,
        val icon: Drawable?
    )

    /** @return every widget installed on the unit, named the way its own app names it. */
    fun providers(context: Context): List<Provider> {
        if (!supported()) return emptyList()
        val pm = context.packageManager
        return try {
            manager(context).installedProviders
                .filter { it.provider.packageName != context.packageName }
                .mapNotNull { info ->
                    val label = labelOf(info, pm) ?: return@mapNotNull null
                    Provider(info, label, appLabel(pm, info.provider), iconOf(info, context))
                }
                .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
        } catch (e: Exception) {
            AppLog.w("$TAG: the installed widgets could not be listed: ${e.message}")
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun labelOf(info: AppWidgetProviderInfo, pm: PackageManager): String? = try {
        info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun appLabel(pm: PackageManager, provider: ComponentName): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(provider.packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        provider.packageName
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun iconOf(info: AppWidgetProviderInfo, context: Context): Drawable? = try {
        info.loadIcon(context, 0)
    } catch (e: Exception) {
        null
    }

    private fun manager(context: Context): AppWidgetManager = AppWidgetManager.getInstance(context)
}
