package io.github.cyyzh.personaltimeagent.domain

import io.github.cyyzh.personaltimeagent.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

data class ProposedChange(val kind:String, val targetId:String?, val targetRevision:Int?,
    val deadline:Long?=null, val start:Long?=null, val end:Long?=null, val title:String?=null,
    val estimatedMinutes:Int?=null,val goalId:String?=null)

/** The same gate is used when presenting and committing an Agent proposal. */
object Policy {
    fun rejection(change:ProposedChange, task:TaskEntity?, slots:List<SlotEntity>,
                  sleepStart:Long?, sleepEnd:Long?, confirmed:Boolean,
                  sleepStartHour:Int=23,sleepEndHour:Int=7):String? {
        if (!confirmed) return "Needs your confirmation"
        if (change.kind !in setOf("CREATE_TASK","UPDATE_TASK","COMPLETE_TASK","SCHEDULE_TASK","CHANGE_DEADLINE")) return "Unsupported action"
        if (change.kind=="CREATE_TASK" && change.title.isNullOrBlank()) return "Title missing"
        if(change.kind=="CREATE_TASK" && (change.targetId!=null || task!=null)) return "Create cannot target an existing task"
        if(change.estimatedMinutes!=null && change.estimatedMinutes !in 1..10080) return "Invalid duration"
        if (change.kind=="SCHEDULE_TASK" && (change.start==null || change.end==null)) return "Interval missing"
        if (change.kind != "CREATE_TASK" && task == null) return "Target missing"
        if (task != null && change.targetRevision != task.revision) return "Proposal is stale"
        if (change.kind == "CHANGE_DEADLINE" && change.deadline == null) return "Deadline missing"
        if (task?.locked == true && change.kind in setOf("SCHEDULE_TASK","UPDATE_TASK","CHANGE_DEADLINE")) return "Task is locked"
        if (change.start != null || change.end != null) {
            val start=change.start ?: return "Incomplete interval"
            val end=change.end ?: return "Incomplete interval"
            if (start>=end) return "Invalid interval"
            if(start<=0 || end-start>7*86_400_000L) return "Interval outside supported bounds"
            val effectiveDeadline=change.deadline?:task?.deadline
            if (effectiveDeadline!=null && end>effectiveDeadline) return "Past real deadline"
            if (slots.any { it.taskId!=task?.id && start<it.endAt && it.startAt<end }) return "Conflicts with existing event"
            if (sleepStart != null && sleepEnd != null && start<sleepEnd && sleepStart<end) return "Conflicts with protected sleep"
            val zone=ZoneId.systemDefault()
            var day=Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
            val last=Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
            while(!day.isAfter(last)) {
                val s=day.atTime(sleepStartHour,0).atZone(zone).toInstant().toEpochMilli()
                val e=day.plusDays(if(sleepStartHour>=sleepEndHour)1 else 0).atTime(sleepEndHour,0).atZone(zone).toInstant().toEpochMilli()
                if(start<e && s<end) return "Conflicts with protected sleep"
                day=day.plusDays(1)
            }
        }
        return null
    }
    fun canUndo(audit:AuditEntity, currentRevision:Int?, expectedRevision:Int?):Boolean =
        audit.undoneAt == null && currentRevision != null && currentRevision == expectedRevision
}

data class CategoryDuration(val category:String,val millis:Long)

object TimeMath {
    fun unionMillis(intervals:List<Pair<Long,Long>>):Long {
        val sorted=intervals.filter{it.second>it.first}.sortedBy{it.first}
        var total=0L; var lo=0L; var hi=0L
        sorted.forEach { (s,e) -> if (hi<=s) { total+=hi-lo; lo=s; hi=e } else hi=maxOf(hi,e) }
        return total+hi-lo
    }
    /** Manual evidence takes precedence over AI and usage samples at overlap boundaries. */
    fun byCategory(entries:List<LedgerEntity>, start:Long, end:Long):List<CategoryDuration> {
        val clipped=entries.filter{it.source!="PLANNED"}.mapNotNull { e ->
            val s=maxOf(start,e.startAt); val t=minOf(end,e.endAt)
            if (s<t) Triple(e,s,t) else null
        }
        val points=(listOf(start,end)+clipped.flatMap{listOf(it.second,it.third)}).distinct().sorted()
        val sums=mutableMapOf<String,Long>()
        points.zipWithNext().forEach { (a,b) ->
            val winner=clipped.filter{it.second<=a && it.third>=b}.maxWithOrNull(
                compareBy<Triple<LedgerEntity,Long,Long>> { when(it.first.source) { "MANUAL"->4;"TIMER"->3;"SYSTEM"->2;"AI"->1;else->0 } }
                    .thenBy{it.first.createdAt})?.first
            if(winner!=null) sums[winner.category]=(sums[winner.category]?:0)+(b-a)
        }
        return sums.map{CategoryDuration(it.key,it.value)}.sortedByDescending{it.millis}
    }
}
