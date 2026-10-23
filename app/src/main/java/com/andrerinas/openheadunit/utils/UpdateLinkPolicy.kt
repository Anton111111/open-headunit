package com.andrerinas.openheadunit.utils

/**
 * Chooses the links "Check for updates" offers. Some head unit browsers cannot run the script that
 * lists a release page's assets, so the APK link itself is the first choice on every version.
 */
object UpdateLinkPolicy {

    enum class Choice { APK, RELEASE_PAGE }

    /** The release APK's download URL from (name, url) asset pairs, skipping the debug build. */
    fun pickApk(assets: List<Pair<String, String>>): String? {
        for ((name, url) in assets) {
            if (name.endsWith(".apk") && !name.endsWith("_debug.apk") && url.isNotEmpty()) return url
        }
        return null
    }

    /** The dialog's (positive, neutral) buttons; with no release APK the page is the only one. */
    fun choices(apkUrl: String?): Pair<Choice, Choice?> =
        if (apkUrl != null) Choice.APK to Choice.RELEASE_PAGE else Choice.RELEASE_PAGE to null
}
