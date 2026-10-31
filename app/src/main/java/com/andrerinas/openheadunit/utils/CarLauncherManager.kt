package com.andrerinas.openheadunit.utils

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

object CarLauncherManager {

    private const val ALIAS_CLASS_NAME = "com.andrerinas.openheadunit.CarLauncherAlias"

    /**
     * Checks if the CarLauncherAlias component is currently enabled in PackageManager.
     */
    fun isLauncherEnabled(context: Context): Boolean {
        return try {
            val pm = context.packageManager ?: return false
            val component = ComponentName(context, ALIAS_CLASS_NAME)
            val state = pm.getComponentEnabledSetting(component)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            AppLog.w("CarLauncherManager: failed to check launcher enabled state: ${e.message}")
            false
        }
    }

    /**
     * Enables or disables the CarLauncherAlias component.
     */
    fun setLauncherEnabled(context: Context, enabled: Boolean) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context, ALIAS_CLASS_NAME)
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
            AppLog.i("CarLauncherManager: set launcher alias enabled=$enabled")
        } catch (e: Exception) {
            AppLog.e("CarLauncherManager: failed to set component enabled setting: ${e.message}")
        }
    }

    /**
     * Synchronizes the alias enabled state with the user's setting.
     * Useful on application startup.
     */
    fun syncWithSettings(context: Context, enabledSetting: Boolean) {
        val currentlyEnabled = isLauncherEnabled(context)
        if (currentlyEnabled != enabledSetting) {
            setLauncherEnabled(context, enabledSetting)
        }
    }

    /**
     * Checks whether Open Headunit is the unit's home app.
     *
     * From Android 10 the home app is a role, and the package manager answers this question with
     * this app's own alias as soon as the alias is enabled, which is not the same as the system
     * having picked it. The role manager is the one that knows, and the intent check stays for the
     * older units and for the ones where the role is not available.
     */
    fun isDefaultLauncher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
                }
            } catch (e: Exception) {
                AppLog.w("CarLauncherManager: RoleManager failed: ${e.message}, falling back to the intent check")
            }
        }
        return try {
            val pm = context.packageManager ?: return false
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName == context.packageName
        } catch (e: Exception) {
            AppLog.w("CarLauncherManager: failed to check default launcher: ${e.message}")
            false
        }
    }

    /**
     * Prompts the user to set Open Headunit as the default Home app.
     * From Android 10 the role request goes through [roleRequest]: the system drops it unless it is
     * started for a result, because it reads the requesting app from the caller of that result.
     * Older units, and units without the role, get the system Home settings instead.
     */
    fun promptSetDefaultLauncher(context: Context, roleRequest: ActivityResultLauncher<Intent>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        AppLog.i("CarLauncherManager: OpenHU is already the default Home app (ROLE_HOME held)")
                        return
                    }
                    roleRequestedAt = SystemClock.elapsedRealtime()
                    roleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    AppLog.i("CarLauncherManager: asked the system for the home role")
                    return
                }
            } catch (e: Exception) {
                AppLog.w("CarLauncherManager: RoleManager failed: ${e.message}, falling back to settings")
            }
        }
        openHomeSettings(context)
    }

    /**
     * Settles a home-role request when its result comes back. A request the system closed before
     * anyone could answer it (a denied-always app, or a ROM that refuses it) opens the Home settings.
     */
    fun onRoleRequestResult(context: Context) {
        val held = isDefaultLauncher(context)
        val elapsedMs = SystemClock.elapsedRealtime() - roleRequestedAt
        when {
            held -> {
                com.andrerinas.openheadunit.utils.Settings(context).enableCarLauncher = true
                AppLog.i("CarLauncherManager: home role granted after ${elapsedMs}ms, car launcher mode stored")
            }
            HomeRoleRequestPolicy.opensHomeSettings(held, elapsedMs) -> {
                AppLog.i("CarLauncherManager: the home role request closed in ${elapsedMs}ms with no answer, opening the Home settings")
                openHomeSettings(context)
            }
            else -> AppLog.i("CarLauncherManager: home role not granted after ${elapsedMs}ms")
        }
    }

    private var roleRequestedAt = 0L

    private fun openHomeSettings(context: Context) {
        try {
            val homeSettingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeSettingsIntent)
        } catch (_: Exception) {
            try {
                val chooserIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooserIntent)
            } catch (e2: Exception) {
                AppLog.e("CarLauncherManager: failed to prompt for default launcher: ${e2.message}")
            }
        }
    }

    /**
     * Determines whether the main screen Exit button should be shown.
     * When Car Launcher mode is enabled or Open Headunit is acting as the system Home launcher,
     * the Exit button is hidden because exiting the Home launcher has no meaningful target.
     */
    fun shouldShowExitButton(isCarLauncherEnabled: Boolean, isDefaultLauncher: Boolean): Boolean {
        return !isCarLauncherEnabled && !isDefaultLauncher
    }
}
