package io.github.cyyzh.personaltimeagent.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class ActivitySignal(val packageName: String, val eventType: Int, val atMs: Long, val className: String?)

/** An in-process consumer must be installed by an active, local vision runtime. */
interface VisionFrameConsumer { fun analyze(packageName: String, bitmap: Bitmap, atMs: Long) }

object ActivityCapture {
    private val allowedPackages = setOf("com.tencent.mm", "com.tencent.mobileqq", "tv.danmaku.bili", "com.android.chrome")
    private val sensitivePrefixes = listOf("com.eg.android.AlipayGphone", "com.tencent.mm.plugin.wallet", "com.android.settings", "com.google.android.apps.authenticator")
    @Volatile var consumer: VisionFrameConsumer? = null
    fun allowed(c: android.content.Context, pkg: String): Boolean = pkg in allowedPackages &&
        c.getSharedPreferences("activity_capture", 0).getBoolean("allow_$pkg", false)
    fun setAllowed(c: android.content.Context, pkg: String, allowed: Boolean): Boolean {
        require(pkg in allowedPackages)
        val ok = c.getSharedPreferences("activity_capture", 0).edit().putBoolean("allow_$pkg", allowed).commit()
        if (ok) { PlatformGates.bump(c, PlatformGates.ACTIVITY); PlatformGates.bump(c, PlatformGates.SCREENSHOTS) }
        return ok
    }
    fun screenshotSafe(pkg: String, clazz: String?) = sensitivePrefixes.none { pkg.startsWith(it) || clazz?.startsWith(it) == true } &&
        clazz?.contains("Password", ignoreCase = true) != true && clazz?.contains("Payment", ignoreCase = true) != true
    fun sampleIntervalMs(c: android.content.Context) = c.getSharedPreferences("activity_capture", 0)
        .getLong("sample_interval_ms", 60000L).coerceIn(30000L, 600000L)
    fun setSampleIntervalMs(c: android.content.Context, value: Long): Boolean {
        if (value !in 30000L..600000L) return false
        val ok = c.getSharedPreferences("activity_capture", 0).edit().putLong("sample_interval_ms", value).commit()
        if (ok) PlatformGates.bump(c, PlatformGates.SCREENSHOTS)
        return ok
    }
    @Synchronized fun saveSignal(c: android.content.Context, signal: ActivitySignal, epoch: Long) {
        if (!PlatformGates.stillEnabled(c, PlatformGates.ACTIVITY, epoch) || !allowed(c, signal.packageName)) return
        val p = c.getSharedPreferences("activity_capture", 0)
        val old = JSONArray(p.getString("signals", "[]"))
        val cutoff = System.currentTimeMillis() - 86400000L
        val list = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optLong("at") >= cutoff) list.put(old.getJSONObject(i))
        list.put(JSONObject().put("pkg", signal.packageName).put("type", signal.eventType).put("at", signal.atMs)
            .put("class", signal.className ?: JSONObject.NULL))
        val bounded = JSONArray()
        for (i in maxOf(0, list.length() - 2000) until list.length()) bounded.put(list.getJSONObject(i))
        if (PlatformGates.stillEnabled(c, PlatformGates.ACTIVITY, epoch) && allowed(c, signal.packageName))
            p.edit().putString("signals", bounded.toString()).commit()
    }
}

class AgentAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastScreenshotAt = 0L
    private var lastPackage: String? = null
    @Volatile private var observedPackage: String? = null
    override fun onServiceConnected() { super.onServiceConnected() }
    override fun onInterrupt() { ActivityCapture.consumer = null }
    override fun onDestroy() { ActivityCapture.consumer = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        observedPackage = pkg
        if (!PlatformGates.enabled(this, PlatformGates.ACTIVITY) || !ActivityCapture.allowed(this, pkg) ||
            !PlatformGates.accessibilityPermission(this, AgentAccessibilityService::class.java)) return
        val type = event.eventType
        if (type !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED)) return
        val clazz = event.className?.toString()
        val at = System.currentTimeMillis()
        ActivityCapture.saveSignal(this, ActivitySignal(pkg, type, at, clazz), PlatformGates.epoch(this, PlatformGates.ACTIVITY))
        val changed = pkg != lastPackage || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        lastPackage = pkg
        if (changed) maybeScreenshot(pkg, clazz, at)
    }
    private fun maybeScreenshot(pkg: String, clazz: String?, at: Long) {
        if (Build.VERSION.SDK_INT < 34 || at - lastScreenshotAt < ActivityCapture.sampleIntervalMs(this) || clazz == null || !ActivityCapture.screenshotSafe(pkg, clazz)) return
        if (!PlatformGates.enabled(this, PlatformGates.SCREENSHOTS) || !PlatformGates.enabled(this, PlatformGates.VISION)) return
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return
        val consumer = ActivityCapture.consumer ?: return
        if (!safeCurrentWindow(pkg)) return
        val activityEpoch = PlatformGates.epoch(this, PlatformGates.ACTIVITY)
        val shotEpoch = PlatformGates.epoch(this, PlatformGates.SCREENSHOTS)
        val visionEpoch = PlatformGates.epoch(this, PlatformGates.VISION)
        lastScreenshotAt = at
        val currentRoot = rootInActiveWindow ?: return
        val captureWindow = currentRoot.windowId
        currentRoot.recycle()
        takeScreenshotOfWindow(captureWindow, { command -> handler.post(command) }, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    if (!PlatformGates.stillEnabled(this@AgentAccessibilityService, PlatformGates.ACTIVITY, activityEpoch) ||
                        !PlatformGates.stillEnabled(this@AgentAccessibilityService, PlatformGates.SCREENSHOTS, shotEpoch) ||
                        !PlatformGates.stillEnabled(this@AgentAccessibilityService, PlatformGates.VISION, visionEpoch) ||
                        !safeCurrentWindow(pkg)) return
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                    try { consumer.analyze(pkg, bitmap, at) } finally { bitmap.recycle() }
                } finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                getSharedPreferences("activity_capture", 0).edit().putInt("last_screenshot_error", errorCode)
                    .putLong("last_screenshot_error_at", System.currentTimeMillis()).apply()
            }
        })
    }
    private fun safeCurrentWindow(pkg: String): Boolean {
        if (observedPackage != pkg || !ActivityCapture.allowed(this, pkg) ||
            !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return false
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != pkg) return false
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 100) {
                val node = queue.removeFirst()
                if (node.isPassword || !ActivityCapture.screenshotSafe(pkg, node.className?.toString())) return false
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
            return queue.isEmpty()
        } finally { root.recycle() }
    }
}
