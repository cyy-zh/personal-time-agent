package io.github.cyyzh.personaltimeagent.domain

import io.github.cyyzh.personaltimeagent.data.*
import java.time.*

data class ScheduleResult(val slots:List<SlotEntity>,val unscheduled:List<TaskEntity>)

object Scheduler {
    fun plan(tasks:List<TaskEntity>, fixed:List<SlotEntity>, now:Long, days:Int=7,
             sleepStartHour:Int=23, sleepEndHour:Int=7):ScheduleResult {
        val zone=ZoneId.systemDefault()
        val base=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val occupied=fixed.toMutableList()
        val planned=mutableListOf<SlotEntity>()
        val misses=mutableListOf<TaskEntity>()
        tasks.filter{it.status=="OPEN" && !it.locked}.sortedWith(compareByDescending<TaskEntity>{it.importance}.thenBy{it.deadline?:Long.MAX_VALUE})
            .forEach { task ->
                val length=task.estimatedMinutes.coerceIn(5,480)*60_000L
                var found:Long?=null
                loop@ for (d in 0 until days) {
                    val date=base.plusDays(d.toLong())
                    var t=maxOf(now, date.atTime(sleepEndHour,0).atZone(zone).toInstant().toEpochMilli())
                    val dayEnd=date.atTime(sleepStartHour,0).atZone(zone).toInstant().toEpochMilli()
                    while(t+length<=dayEnd) {
                        if (task.deadline!=null && t+length>task.deadline) break
                        val conflict=occupied.firstOrNull{ t<it.endAt && it.startAt<t+length }
                        if(conflict==null) {found=t; break@loop}
                        t=maxOf(t+15*60_000L,conflict.endAt)
                    }
                }
                if(found==null) misses+=task else {
                    val slot=SlotEntity("plan-${task.id}",task.id,task.title,found,found+length)
                    planned+=slot; occupied+=slot
                }
            }
        return ScheduleResult(planned,misses)
    }
}
