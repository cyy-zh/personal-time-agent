package io.github.cyyzh.personaltimeagent.domain

import android.content.Context
import androidx.room.withTransaction
import io.github.cyyzh.personaltimeagent.data.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.UUID
import org.json.JSONObject

data class HabitProgress(val habitId:String,val title:String,val target:Int,val completed:Int,
    val scheduled:Int,val streakWeeks:Int,val history:List<Int>) {
    val ratio:Double get()=if(target==0) 0.0 else completed.toDouble()/target
}
data class GoalProgress(val goalId:String,val title:String,val target:Int,val completed:Int,
    val totalMilestones:Int,val minutesInvested:Long)
data class SuggestionBatch(val proposed:Int,val skippedExisting:Int,val unscheduled:Int)

/** Suggests concrete work but leaves every mutation in the confirmation queue. */
class GoalsHabitsService(private val context:Context) {
    private val db=AgentDatabase.get(context)
    private val modules=ModuleSettings(context)
    private val repo=AgentRepository(context)
    private val zone=ZoneId.systemDefault()
    private fun weekStart(now:Long):Long=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()

    suspend fun suggestWeeklyHabitTasks(now:Long=System.currentTimeMillis()):SuggestionBatch {
        if(!modules.enabled(ModuleKey.HABITS)) return SuggestionBatch(0,0,0)
        val habits=db.habitDao().active()
        val week=weekStart(now)
        val pending=db.proposalDao().observePending().first()
        val occupied=db.slotDao().observeAll().first().toMutableList()
        pending.filter{it.kind=="CREATE_TASK"}.forEach{p->runCatching{JSONObject(p.payload)}.getOrNull()?.let{j->
            if(!j.isNull("start")&&!j.isNull("end")) occupied+=SlotEntity("pending-${p.id}",null,p.summary,j.getLong("start"),j.getLong("end"))
        }}
        var proposed=0;var skipped=0;var unscheduled=0
        habits.forEach { habit ->
            val count=habit.frequencyPerWeek.coerceIn(1,7)
            repeat(count) { index ->
                val source="HABIT:${habit.id}:$week:$index"
                if(db.taskDao().bySource(source)!=null || db.proposalDao().bySource(source)!=null) { skipped++;return@repeat }
                val virtual=TaskEntity("candidate-${UUID.randomUUID()}",habit.title,source=source,
                    estimatedMinutes=habit.minutes,goalId=habit.goalId,
                    deadline=week+7*86_400_000L-1)
                val result=Scheduler.plan(listOf(virtual),occupied,now,days=7)
                val slot=result.slots.firstOrNull()
                if(slot==null) { unscheduled++;return@repeat }
                val made=db.withTransaction {
                    if(db.taskDao().bySource(source)!=null || db.proposalDao().bySource(source)!=null) false else {
                        repo.propose(ProposedChange("CREATE_TASK",null,null,title=habit.title,start=slot.startAt,end=slot.endAt),
                            "习惯 ${habit.title} · 本周第 ${index+1}/$count 次 · ${Instant.ofEpochMilli(slot.startAt).atZone(zone)}",
                            reason="根据已占用时段建议；批准后才创建和提醒",source=source);true
                    }
                }
                if(!made){skipped++;return@repeat}
                occupied.add(slot)
                proposed++
            }
        }
        return SuggestionBatch(proposed,skipped,unscheduled)
    }

    suspend fun suggestGoalMilestones(goalId:String,now:Long=System.currentTimeMillis()):SuggestionBatch {
        if(!modules.enabled(ModuleKey.GOALS)) return SuggestionBatch(0,0,0)
        val goal=db.goalDao().get(goalId)?:return SuggestionBatch(0,0,0)
        if(goal.status!="ACTIVE") return SuggestionBatch(0,0,0)
        val target=goal.targetAt ?: now+28*86_400_000L
        val weeks=((target-now)/(7*86_400_000L)).toInt().coerceIn(1,12)
        var proposed=0;var skipped=0
        repeat(weeks) { index ->
            val source="GOAL:${goal.id}:${index+1}"
            if(db.taskDao().bySource(source)!=null || db.proposalDao().bySource(source)!=null) {skipped++;return@repeat}
            val due=now+((target-now)*(index+1)/(weeks.toLong()))
            val made=db.withTransaction {
                if(db.taskDao().bySource(source)!=null || db.proposalDao().bySource(source)!=null) false else {
                    repo.propose(ProposedChange("CREATE_TASK",null,null,title="${goal.title} · 阶段 ${index+1}/$weeks",deadline=due,goalId=goal.id),
                        "目标阶段 ${index+1}/$weeks：${goal.title}",reason="建议阶段截止时间；需逐项确认",source=source);true
                }
            }
            if(made)proposed++ else skipped++
        }
        return SuggestionBatch(proposed,skipped,0)
    }

    suspend fun habitProgress(now:Long=System.currentTimeMillis()):List<HabitProgress> {
        val tasks=db.taskDao().observeAll().first()
        val habits=db.habitDao().observeAll().first()
        val occurrences=db.habitDao().occurrences(weekStart(now)-4*7*86_400_000L,weekStart(now)+7*86_400_000L)
        return habits.map { habit ->
            val prefix="HABIT:${habit.id}:"
            val history=(3 downTo 0).map { offset ->
                val w=weekStart(now)-offset*7*86_400_000L
                val doneTasks=tasks.count{it.source.startsWith(prefix) && it.status=="DONE" && it.source.split(':').getOrNull(2)?.toLongOrNull()==w}
                val doneOccurrences=occurrences.count{it.habitId==habit.id && it.completedAt!=null && it.scheduledAt in w until w+7*86_400_000L}
                maxOf(doneTasks,doneOccurrences)
            }
            var streak=0
            for (n in history.asReversed()) { if(n>=habit.frequencyPerWeek) streak++ else break }
            val currentWeek=weekStart(now)
            val scheduled=tasks.count{it.source.startsWith("$prefix$currentWeek:")}
            HabitProgress(habit.id,habit.title,habit.frequencyPerWeek,history.last(),scheduled,streak,history)
        }
    }

    suspend fun goalProgress(now:Long=System.currentTimeMillis()):List<GoalProgress> {
        val goals=db.goalDao().all();val tasks=db.taskDao().observeAll().first()
        val ledger=db.ledgerDao().inRange(0,now)
        return goals.map { goal ->
            val linked=tasks.filter{it.goalId==goal.id || it.source.startsWith("GOAL:${goal.id}:")}
            val ids=linked.map{it.id}.toSet()
            val actual=TimeMath.unionMillis(ledger.filter{it.taskId in ids && it.source!="PLANNED"}.map{it.startAt to it.endAt})/60_000L
            GoalProgress(goal.id,goal.title,goal.targetCount,linked.count{it.status=="DONE"},linked.size,actual)
        }
    }
}
