package io.github.cyyzh.personaltimeagent.platform

data class TimedSignal(val pkg: String, val at: Long, val className: String? = null)
data class ActivityCandidate(val pkg: String, val start: Long, val end: Long, val category: String,
    val evidence: String, val confidenceCalibrated: Boolean = false, val boundaryUncertain: Boolean = true)

/** Bounds derive from observed foreground/page transitions, never sample count times interval. */
object ActivityTimeline {
    fun candidates(signals: List<TimedSignal>, observedUntil: Long, maxGapMs: Long = 5 * 60_000L): List<ActivityCandidate> {
        if (signals.isEmpty()) return emptyList()
        val ordered = signals.sortedBy { it.at }.distinctBy { Triple(it.pkg, it.at, it.className) }
        val out = mutableListOf<ActivityCandidate>()
        for (i in ordered.indices) {
            val start = ordered[i]
            val next = ordered.getOrNull(i + 1)
            val boundary = minOf(next?.at ?: observedUntil, observedUntil, start.at + maxGapMs)
            if (boundary <= start.at) continue
            val candidate = rule(start)
            out += ActivityCandidate(start.pkg, start.at, boundary, candidate, "accessibility_window_event",
                boundaryUncertain = next == null || next.at > start.at + maxGapMs)
        }
        return out
    }
    private fun rule(signal: TimedSignal): String {
        val clazz = signal.className?.lowercase() ?: return "unknown"
        return when {
            signal.pkg == "com.tencent.mm" && clazz.contains("finder") -> "short_video_candidate"
            signal.pkg == "com.tencent.mm" && clazz.contains("webview") -> "article_candidate"
            signal.pkg == "tv.danmaku.bili" && clazz.contains("video") -> "video_candidate"
            else -> "unknown"
        }
    }
}
