package io.github.cyyzh.personaltimeagent.data

import android.content.Context

enum class ModuleKey { AI_PLANNING, GOALS, HABITS, APP_USAGE, NOTIFICATION_CAPTURE, ACTIVITY_RECOGNITION, LOCAL_VISION, AUTO_SCREENSHOT, AI_REVIEW, REMINDERS }

/** SharedPreferences commit makes a disabled module visible before its caller returns. */
class ModuleSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("modules", Context.MODE_PRIVATE)
    fun enabled(key: ModuleKey): Boolean = prefs.getBoolean(key.name, false)
    fun setEnabled(key: ModuleKey, enabled: Boolean): Boolean = prefs.edit().putBoolean(key.name, enabled).commit()
}
