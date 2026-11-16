package com.way.facebiometricfix

import android.content.SharedPreferences

/**
 * Per-app policy deciding which apps get face unlock offered in BiometricPrompt.
 *
 * Stored in the LSPosed remote preferences group [GROUP]: the UI writes it through
 * XposedService, and the hook in system_server reads it with getRemotePreferences().
 *
 * Apps in [KEY_ALLOWED] or [KEY_BLOCKED] have an explicit setting; every other app
 * follows [KEY_DEFAULT_ALLOW].
 */
object FacePolicy {
    const val GROUP = "face_policy"

    const val KEY_DEFAULT_ALLOW = "default_allow"
    const val KEY_ALLOWED = "allowed_packages"
    const val KEY_BLOCKED = "blocked_packages"

    const val DEFAULT_ALLOW = true

    /** Apps blocked until the user changes it. Matches the module's original hardcoded exclusion. */
    val DEFAULT_BLOCKED: Set<String> = setOf("com.coloros.codebook")

    enum class Setting { DEFAULT, ALLOWED, BLOCKED }

    fun defaultAllow(prefs: SharedPreferences?): Boolean =
        prefs?.getBoolean(KEY_DEFAULT_ALLOW, DEFAULT_ALLOW) ?: DEFAULT_ALLOW

    fun allowed(prefs: SharedPreferences?): Set<String> =
        prefs?.getStringSet(KEY_ALLOWED, null) ?: emptySet()

    fun blocked(prefs: SharedPreferences?): Set<String> =
        prefs?.getStringSet(KEY_BLOCKED, null) ?: DEFAULT_BLOCKED

    fun setting(prefs: SharedPreferences?, packageName: String): Setting = when {
        packageName in blocked(prefs) -> Setting.BLOCKED
        packageName in allowed(prefs) -> Setting.ALLOWED
        else -> Setting.DEFAULT
    }

    /** With no readable preferences this reproduces the module's original behaviour. */
    fun isAllowed(prefs: SharedPreferences?, packageName: String): Boolean =
        when (setting(prefs, packageName)) {
            Setting.BLOCKED -> false
            Setting.ALLOWED -> true
            Setting.DEFAULT -> defaultAllow(prefs)
        }

    /** Writes an explicit [setting] for [packageName]; [Setting.DEFAULT] removes it. */
    fun write(prefs: SharedPreferences, packageName: String, setting: Setting) {
        val allowed = allowed(prefs).toMutableSet()
        val blocked = blocked(prefs).toMutableSet()
        allowed.remove(packageName)
        blocked.remove(packageName)
        when (setting) {
            Setting.ALLOWED -> allowed.add(packageName)
            Setting.BLOCKED -> blocked.add(packageName)
            Setting.DEFAULT -> Unit
        }
        prefs.edit()
            .putStringSet(KEY_ALLOWED, allowed)
            .putStringSet(KEY_BLOCKED, blocked)
            .apply()
    }

    fun writeDefaultAllow(prefs: SharedPreferences, allow: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_DEFAULT_ALLOW, allow)
        // Persist the built-in blocked list the first time anything is saved, so it
        // doesn't silently disappear once the key exists.
        if (!prefs.contains(KEY_BLOCKED)) editor.putStringSet(KEY_BLOCKED, DEFAULT_BLOCKED)
        editor.apply()
    }
}
