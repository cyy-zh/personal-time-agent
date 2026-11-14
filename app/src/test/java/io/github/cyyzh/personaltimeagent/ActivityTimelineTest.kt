package io.github.cyyzh.personaltimeagent

import io.github.cyyzh.personaltimeagent.platform.ActivityTimeline
import io.github.cyyzh.personaltimeagent.platform.TimedSignal
import org.junit.Assert.*
import org.junit.Test

class ActivityTimelineTest {
    @Test fun transitionBoundsIntervalsWithoutSampleMultiplication() {
        val spans = ActivityTimeline.candidates(listOf(
            TimedSignal("com.tencent.mm", 1000, "FinderFeed"),
            TimedSignal("com.tencent.mm", 4000, "FinderFeed"),
            TimedSignal("com.tencent.mobileqq", 9000, "Chat")), 11000)
        assertEquals(listOf(3000L, 5000L, 2000L), spans.map { it.end - it.start })
        assertEquals("short_video_candidate", spans.first().category)
        assertTrue(spans.all { !it.confidenceCalibrated })
    }
    @Test fun unobservedGapIsCappedAndMarkedUncertain() {
        val spans = ActivityTimeline.candidates(listOf(TimedSignal("com.tencent.mm", 1000)), 1_000_000)
        assertEquals(300000L, spans.single().end - spans.single().start)
        assertTrue(spans.single().boundaryUncertain)
        assertEquals("unknown", spans.single().category)
    }
}
