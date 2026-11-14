package io.github.cyyzh.personaltimeagent.data

import androidx.room.withTransaction
import org.json.JSONObject
import java.util.UUID

data class TimerState(val category:String="自主学习",val startedAt:Long?=null,val taskId:String?=null)

/** Persisted immediately; process death never silently discards a running timer. */
class PersistentTimer(private val db:AgentDatabase) {
    suspend fun state():TimerState {
        val value=db.memoryDao().all().firstOrNull{it.id=="timer-state"}?.value?:return TimerState()
        val j=JSONObject(value)
        return TimerState(j.optString("category","自主学习"),j.optLong("startedAt").takeIf{!j.isNull("startedAt")},j.optString("taskId").takeIf{it.isNotBlank()&&it!="null"})
    }
    private suspend fun save(s:TimerState) {db.memoryDao().upsert(MemoryEntity("timer-state","timer-state",
        JSONObject().put("category",s.category).put("startedAt",s.startedAt).put("taskId",s.taskId).toString(),0,"USER_TIMER"))}
    suspend fun start(category:String,taskId:String?=null)=db.withTransaction {
        require(category.isNotBlank())
        require(state().startedAt==null){"已有计时正在运行"}
        save(TimerState(category.trim(),System.currentTimeMillis(),taskId))
    }
    suspend fun pause():Boolean=db.withTransaction {
        val s=state();val start=s.startedAt?:return@withTransaction false
        val end=System.currentTimeMillis()
        require(end>start){"系统时间发生变化，请手动修正记录"}
        db.ledgerDao().upsert(LedgerEntity(UUID.randomUUID().toString(),s.taskId,s.category,start,end,"TIMER"))
        s.taskId?.let{db.taskDao().get(it)}?.let {task->
            val rows=db.ledgerDao().inRange(0,Long.MAX_VALUE).filter{it.taskId==task.id}
            val minutes=(io.github.cyyzh.personaltimeagent.domain.TimeMath.unionMillis(rows.map{it.startAt to it.endAt})/60000).toInt()
            db.taskDao().upsert(task.copy(actualMinutes=minutes,revision=task.revision+1,updatedAt=end))
        }
        save(s.copy(startedAt=null));true
    }
}
