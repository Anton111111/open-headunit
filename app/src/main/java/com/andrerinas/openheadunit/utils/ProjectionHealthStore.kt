package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Keeps the last few projection summaries and the one suggestion the user dismissed.
 *
 * Local only. The summaries are compact strings in their own preferences file, so reading the
 * home screen never touches the settings backup.
 */
class ProjectionHealthStore(context: Context) {

    private val hostContext = context
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(settings: Settings): ProjectionHealthPolicy.State = ProjectionHealthPolicy.State(
        sessions = ProjectionHealthCodec.decode(prefs.getString(KEY_SESSIONS, "") ?: ""),
        totalSessions = prefs.getInt(KEY_COUNT, 0),
        dismissedAction = prefs.getString(KEY_DISMISSED, null)?.let { name ->
            runCatching { ProjectionHealthPolicy.Action.valueOf(name) }.getOrNull()
        },
        dismissedAtSession = prefs.getInt(KEY_DISMISSED_AT, 0),
        storedCodec = settings.videoCodec,
        storedViewMode = settings.viewMode,
        storedResolution = Settings.Resolution.fromId(settings.resolutionId)
            ?: Settings.Resolution.AUTO,
        storedFps = settings.fpsLimit,
        panelCeiling = panelCeiling(),
    )

    private fun panelCeiling(): Settings.Resolution {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                hostContext.display?.getRealMetrics(metrics)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
            else ->
                (hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getMetrics(metrics)
        }
        return SystemOptimizer.recommendedResolution(metrics.widthPixels, metrics.heightPixels)
    }

    fun append(summary: ProjectionSessionSummary) {
        val sessions = ProjectionHealthCodec.decode(prefs.getString(KEY_SESSIONS, "") ?: "")
        val next = (sessions + summary).takeLast(MAX_SESSIONS)
        prefs.edit()
            .putString(KEY_SESSIONS, ProjectionHealthCodec.encode(next))
            .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
            .apply()
    }

    /** Keeps this suggestion quiet for [ProjectionHealthPolicy.DISMISS_SESSIONS] more sessions. */
    fun dismiss(action: ProjectionHealthPolicy.Action) {
        prefs.edit()
            .putString(KEY_DISMISSED, action.name)
            .putInt(KEY_DISMISSED_AT, prefs.getInt(KEY_COUNT, 0))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "projection-health"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_COUNT = "session-count"
        private const val KEY_DISMISSED = "dismissed-action"
        private const val KEY_DISMISSED_AT = "dismissed-at"

        const val MAX_SESSIONS = 10
    }
}
