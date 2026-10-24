package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Reads and starts app shortcuts, the deep links Android shows on a long press, for the drawer and
 * the home screen's favorites row.
 *
 * Two guards keep this off the units that cannot use it: shortcuts exist from API 25, and only the
 * current home app may read other apps' shortcuts. Outside launcher mode, or on an old unit, the
 * drawer simply lists apps.
 */
object LauncherShortcuts {

    private const val TAG = "LauncherShortcuts"

    /** One shortcut of an app, ready for the drawer and the favorites row. */
    data class Entry(val packageName: String, val shortcutId: String, val label: String)

    /** @return the shortcuts of every app, by package name, or empty when they cannot be read. */
    fun allByPackage(context: Context): Map<String, List<Entry>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyMap()
        return try {
            val service = launcherApps(context) ?: return emptyMap()
            read(service, packageName = null, shortcutId = null).orEmpty()
                .mapNotNull { entryOf(it) }
                .groupBy { it.packageName }
        } catch (e: Exception) {
            AppLog.w(TAG, "Shortcuts could not be read: ${e.message}")
            emptyMap()
        }
    }

    /**
     * @return the shortcuts of one app, for the favorites row, or null when they cannot be read:
     *   below API 25, outside the home role, or while the user is locked. An app with no
     *   shortcuts is an empty list, which is not the same thing.
     */
    fun forPackage(context: Context, packageName: String): List<Entry>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        return try {
            val service = launcherApps(context) ?: return null
            read(service, packageName, shortcutId = null)?.mapNotNull { entryOf(it) }
        } catch (e: Exception) {
            AppLog.w(TAG, "Shortcuts of $packageName could not be read: ${e.message}")
            null
        }
    }

    /** @return whether shortcuts can be read now: API 25 and up, as the home app, user unlocked. */
    fun canRead(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && launcherApps(context) != null

    /** @return the shortcut's own icon, or null when it cannot be read and the app icon is used. */
    fun icon(context: Context, packageName: String, shortcutId: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        return try {
            val service = launcherApps(context) ?: return null
            val shortcut = read(service, packageName, shortcutId)?.firstOrNull() ?: return null
            service.getShortcutIconDrawable(shortcut, 0)
        } catch (e: Exception) {
            AppLog.w(TAG, "Icon of $packageName/$shortcutId could not be read: ${e.message}")
            null
        }
    }

    /** @return true when the shortcut was started. */
    fun launch(context: Context, packageName: String, shortcutId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        val service = launcherApps(context) ?: return false
        return try {
            service.startShortcut(packageName, shortcutId, null, null, Process.myUserHandle())
            true
        } catch (e: Exception) {
            // The app dropped the shortcut, or its target activity is gone.
            AppLog.w(TAG, "Shortcut $packageName/$shortcutId could not be started: ${e.message}")
            false
        }
    }

    /** The label the drawer shows: the short one, and the long one only when there is no short. */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun entryOf(shortcut: ShortcutInfo): Entry? {
        val label = shortcut.shortLabel?.toString()?.trim().orEmpty()
            .ifEmpty { shortcut.longLabel?.toString()?.trim().orEmpty() }
        if (label.isEmpty()) return null
        return Entry(shortcut.`package`, shortcut.id, label)
    }

    /** @return every shortcut the query matches; null when the system would not say, e.g. while locked. */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun read(service: LauncherApps, packageName: String?, shortcutId: String?): List<ShortcutInfo>? {
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        if (packageName != null) query.setPackage(packageName)
        // setShortcutIds needs a package, so it is only ever set together with one.
        if (packageName != null && shortcutId != null) query.setShortcutIds(listOf(shortcutId))
        return service.getShortcuts(query, Process.myUserHandle())
    }

    /** @return the service, or null when this is not the home app that may read shortcuts. */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun launcherApps(context: Context): LauncherApps? = try {
        val service = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (service != null && service.hasShortcutHostPermission()) service else null
    } catch (e: Exception) {
        // The permission check throws while the user is locked.
        AppLog.w(TAG, "Shortcut host permission could not be read: ${e.message}")
        null
    }
}
