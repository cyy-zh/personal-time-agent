package io.github.cyyzh.personaltimeagent.platform

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import io.github.cyyzh.personaltimeagent.data.ModuleKey
import io.github.cyyzh.personaltimeagent.data.ModuleSettings

/** Persisted user intent is separate from OS permission. All sensitive features start disabled. */
object PlatformGates {
    const val USAGE = "usage"
    const val NOTIFICATIONS = "notifications"
    const val ACTIVITY = "activity"
    const val SCREENSHOTS = "screenshots"
    const val VISION = "vision"
    private val keys = mapOf(USAGE to ModuleKey.APP_USAGE, NOTIFICATIONS to ModuleKey.NOTIFICATION_CAPTURE,
        ACTIVITY to ModuleKey.ACTIVITY_RECOGNITION, SCREENSHOTS to ModuleKey.AUTO_SCREENSHOT,
        VISION to ModuleKey.LOCAL_VISION)
    private fun prefs(c: Context) = c.getSharedPreferences("platform_gates", Context.MODE_PRIVATE)

    @Synchronized fun enabled(c: Context, key: String): Boolean = keys[key]?.let { ModuleSettings(c).enabled(it) } ?: false
    @Synchronized fun epoch(c: Context, key: String): Long = prefs(c).getLong("${key}_epoch", 0L)
    @Synchronized fun bump(c: Context, key: String): Boolean {
        require(key in keys)
        val p = prefs(c)
        return p.edit().putLong("${key}_epoch", p.getLong("${key}_epoch", 0L) + 1).commit()
    }
    @Synchronized fun set(c: Context, key: String, enabled: Boolean): Boolean {
        val module = requireNotNull(keys[key])
        val p = prefs(c)
        // A persisted epoch invalidates callbacks already queued when a module is switched off.
        val epochSaved = p.edit().putLong("${key}_epoch", p.getLong("${key}_epoch", 0L) + 1).commit()
        if (epochSaved) ModuleSettings(c).setEnabled(module, enabled)
        if (epochSaved && !enabled && key in setOf(VISION, SCREENSHOTS, ACTIVITY)) LocalVisionManager.get(c).disable()
        if (epochSaved && enabled && key == VISION) LocalVisionManager.get(c).enable()
        if (epochSaved && key == USAGE) { if (enabled) UsageCollection.start(c) else UsageCollection.stop(c) }
        return epochSaved && ModuleSettings(c).enabled(module) == enabled
    }
    fun stillEnabled(c: Context, key: String, epoch: Long) = enabled(c, key) && epoch(c, key) == epoch
    fun usagePermission(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun notificationListenerPermission(c: Context, service: Class<*>): Boolean {
        val target = ComponentName(c, service)
        return Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == target } == true
    }
    fun accessibilityPermission(c: Context, service: Class<*>): Boolean {
        val target = ComponentName(c, service)
        return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == target } == true
    }
    fun exactAlarmPermission(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationPermission(c: Context): Boolean = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun notificationDeliveryEnabled(c: Context): Boolean = notificationPermission(c) && c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
