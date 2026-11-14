package io.github.cyyzh.personaltimeagent.data

import android.content.Context
import io.github.cyyzh.personaltimeagent.domain.*
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import io.github.cyyzh.personaltimeagent.platform.ReminderPlatform
import io.github.cyyzh.personaltimeagent.platform.WidgetSurface

class AgentRepository(private val context:Context) {
    val db=AgentDatabase.get(context)
    val modules=ModuleSettings(context)
    private fun id()=UUID.randomUUID().toString()
    suspend fun updateManual(value:TaskEntity):String=withContext(Dispatchers.IO) {
        val result=db.withTransaction {
            val old=db.taskDao().get(value.id)?:return@withTransaction "任务不存在"
            if(old.revision!=value.revision) return@withTransaction "任务已改变，请重新打开编辑"
            require(value.title.isNotBlank() && value.estimatedMinutes in 1..10080)
            if(value.scheduledStart!=null && value.scheduledEnd!=null) {
                val s=SchedulePreferences(context)
                val check=Policy.rejection(ProposedChange("SCHEDULE_TASK",old.id,old.revision,start=value.scheduledStart,end=value.scheduledEnd),
                    value.copy(locked=false),db.slotDao().inRange(value.scheduledStart,value.scheduledEnd),null,null,true,s.sleepStartHour,s.sleepEndHour)
                if(check!=null)return@withTransaction check
            }
            val next=value.copy(revision=old.revision+1,updatedAt=System.currentTimeMillis())
            db.taskDao().upsert(next)
            db.slotDao().delete("plan-${next.id}")
            if(next.status=="OPEN" && next.scheduledStart!=null && next.scheduledEnd!=null)
                db.slotDao().upsert(SlotEntity("plan-${next.id}",next.id,next.title,next.scheduledStart,next.scheduledEnd,locked=next.locked))
            db.auditDao().upsert(AuditEntity(id(),null,"MANUAL_EDIT",next.id,taskJson(old),taskJson(next)))
            "已保存"
        }
        if(result=="已保存"){ReminderPlatform.reconcileFromDatabase(context);WidgetSurface.refresh(context)}
        result
    }
    suspend fun addTask(title:String, deadline:Long?=null, source:String="USER"):TaskEntity {
        val value=TaskEntity(id(),title.trim(),deadline=deadline,source=source)
        require(value.title.isNotEmpty())
        db.taskDao().upsert(value)
        return value
    }
    suspend fun addGoal(title:String,targetAt:Long?=null):GoalEntity {
        val v=GoalEntity(id(),title.trim(),targetAt=targetAt)
        require(v.title.isNotEmpty());db.goalDao().upsert(v);return v
    }
    suspend fun addHabit(title:String,frequency:Int,minutes:Int):HabitEntity {
        val v=HabitEntity(id(),title=title.trim(),frequencyPerWeek=frequency.coerceIn(1,7),minutes=minutes.coerceIn(5,240))
        require(v.title.isNotEmpty());db.habitDao().upsert(v);return v
    }
    suspend fun record(category:String,start:Long,end:Long,taskId:String?=null,source:String="MANUAL"):LedgerEntity {
        require(end>start && end<=System.currentTimeMillis()+60_000)
        val v=LedgerEntity(id(),taskId,category,start,end,source)
        db.ledgerDao().upsert(v);return v
    }
    suspend fun propose(change:ProposedChange, summary:String, reason:String="", source:String="AGENT"):ProposalEntity {
        val payload=JSONObject().put("kind",change.kind).put("targetId",change.targetId).put("targetRevision",change.targetRevision)
            .put("deadline",change.deadline).put("start",change.start).put("end",change.end).put("title",change.title)
            .put("estimatedMinutes",change.estimatedMinutes).put("goalId",change.goalId).toString()
        val p=ProposalEntity(id(),change.kind,payload,summary,reason,targetId=change.targetId,targetRevision=change.targetRevision,source=source)
        db.proposalDao().upsert(p);return p
    }
    suspend fun resolve(proposalId:String, approve:Boolean):String = withContext(Dispatchers.IO) {
        val proposal=db.proposalDao().get(proposalId) ?: return@withContext "Proposal missing"
        if(proposal.kind in setOf("CREATE_GOAL","CREATE_HABIT","RECORD_TIME","UPDATE_TIME"))
            return@withContext GoalsHabitsProposal(context).resolve(proposalId,approve)
        val result=db.withTransaction {
            val p= db.proposalDao().get(proposalId) ?: return@withTransaction "Proposal missing"
            if(p.state!="PENDING") return@withTransaction "Already resolved"
            if(!approve) { db.proposalDao().upsert(p.copy(state="REJECTED",resolvedAt=System.currentTimeMillis()));return@withTransaction "Rejected" }
            val j=JSONObject(p.payload)
            val change=ProposedChange(j.getString("kind"),j.optString("targetId").takeIf{it!="null" && it.isNotBlank()},
                j.optInt("targetRevision").takeIf{!j.isNull("targetRevision")},
                j.optLong("deadline").takeIf{!j.isNull("deadline")},j.optLong("start").takeIf{!j.isNull("start")},
                j.optLong("end").takeIf{!j.isNull("end")},j.optString("title").takeIf{it!="null" && it.isNotBlank()},
                j.optInt("estimatedMinutes").takeIf{j.has("estimatedMinutes")&&!j.isNull("estimatedMinutes")},j.optString("goalId").takeIf{it.isNotBlank()&&it!="null"})
            val task=change.targetId?.let{db.taskDao().get(it)}
            if(change.kind=="CREATE_TASK" && p.source.startsWith("HABIT:") && !modules.enabled(ModuleKey.HABITS))
                return@withTransaction "Habits module is off"
            if(change.kind=="CREATE_TASK" && p.source.startsWith("GOAL:") && !modules.enabled(ModuleKey.GOALS))
                return@withTransaction "Goals module is off"
            if(change.kind=="CREATE_TASK" && (p.source.startsWith("HABIT:") || p.source.startsWith("GOAL:")) && db.taskDao().bySource(p.source)!=null)
                return@withTransaction "Already created from this source"
            if(change.goalId!=null && db.goalDao().get(change.goalId)==null) return@withTransaction "Goal missing"
            val slots=if(change.start!=null && change.end!=null) db.slotDao().inRange(change.start,change.end) else emptyList()
            val schedule=SchedulePreferences(context)
            val error=Policy.rejection(change,task,slots,null,null,true,schedule.sleepStartHour,schedule.sleepEndHour)
            if(error!=null) return@withTransaction error
            val now=System.currentTimeMillis()
            val next=when(change.kind) {
                "CREATE_TASK"->TaskEntity(id(),change.title!!,source=p.source,goalId=change.goalId,estimatedMinutes=change.estimatedMinutes?:30,
                    deadline=change.deadline,scheduledStart=change.start,scheduledEnd=change.end,reminderAt=change.start)
                "COMPLETE_TASK"->task!!.copy(status="DONE",reminderAt=null,revision=task.revision+1,updatedAt=now)
                "UPDATE_TASK"->task!!.copy(title=change.title?:task.title,revision=task.revision+1,updatedAt=now)
                "SCHEDULE_TASK"->task!!.copy(scheduledStart=change.start,scheduledEnd=change.end,reminderAt=change.start,revision=task.revision+1,updatedAt=now)
                "CHANGE_DEADLINE"->task!!.copy(deadline=change.deadline,revision=task.revision+1,updatedAt=now)
                else->return@withTransaction "Unsupported"
            }
            db.taskDao().upsert(next)
            if(change.kind=="CREATE_TASK" && next.scheduledStart!=null && next.scheduledEnd!=null)
                db.slotDao().upsert(SlotEntity("plan-${next.id}",next.id,next.title,next.scheduledStart,next.scheduledEnd))
            if(change.kind=="SCHEDULE_TASK") db.slotDao().upsert(SlotEntity("plan-${next.id}",next.id,next.title,change.start!!,change.end!!))
            if(change.kind=="COMPLETE_TASK") db.slotDao().delete("plan-${next.id}")
            db.auditDao().upsert(AuditEntity(id(),p.id,change.kind,next.id,task?.let{taskJson(it)},taskJson(next)))
            db.proposalDao().upsert(p.copy(state="APPROVED",resolvedAt=now))
            "Applied"
        }
        if(result=="Applied") { ReminderPlatform.reconcileFromDatabase(context);WidgetSurface.refresh(context) }
        result
    }
    suspend fun undo(auditId:String):String=withContext(Dispatchers.IO) {
        val auditAction=db.auditDao().get(auditId)?.action ?: return@withContext "Audit missing"
        if(auditAction in setOf("CREATE_GOAL","CREATE_HABIT","RECORD_TIME","UPDATE_TIME"))
            return@withContext GoalsHabitsProposal(context).undo(auditId)
        val result=db.withTransaction {
            val audit=db.auditDao().get(auditId) ?: return@withTransaction "Audit missing"
            val current=db.taskDao().get(audit.targetId)
            val after=audit.afterJson?.let(::taskFromJson)
            if(!Policy.canUndo(audit,current?.revision,after?.revision)) return@withTransaction "Changed since action; cannot undo"
            val restored= audit.beforeJson?.let(::taskFromJson)
            if(restored?.status=="OPEN" && restored.scheduledStart!=null && restored.scheduledEnd!=null) {
                val prefs=SchedulePreferences(context)
                val rejection=Policy.rejection(ProposedChange("SCHEDULE_TASK",restored.id,current!!.revision,start=restored.scheduledStart,end=restored.scheduledEnd),
                    restored.copy(revision=current.revision,locked=false),db.slotDao().inRange(restored.scheduledStart,restored.scheduledEnd),null,null,true,prefs.sleepStartHour,prefs.sleepEndHour)
                if(rejection!=null)return@withTransaction "撤销会违反当前约束：$rejection"
            }
            if(audit.beforeJson==null) db.taskDao().delete(audit.targetId)
            else db.taskDao().upsert(taskFromJson(audit.beforeJson).copy(revision=current!!.revision+1,updatedAt=System.currentTimeMillis()))
            db.slotDao().delete("plan-${audit.targetId}")
            audit.beforeJson?.let { taskFromJson(it) }?.let { restored ->
                if(restored.status=="OPEN" && restored.scheduledStart!=null && restored.scheduledEnd!=null)
                    db.slotDao().upsert(SlotEntity("plan-${restored.id}",restored.id,restored.title,restored.scheduledStart,restored.scheduledEnd,locked=restored.locked))
            }
            db.auditDao().upsert(audit.copy(undoneAt=System.currentTimeMillis()))
            "Undone"
        }
        if(result=="Undone") {ReminderPlatform.reconcileFromDatabase(context);WidgetSurface.refresh(context)}
        result
    }
    private fun taskJson(t:TaskEntity)=JSONObject().put("id",t.id).put("title",t.title).put("type",t.type).put("notes",t.notes)
        .put("importance",t.importance).put("status",t.status).put("deadline",t.deadline).put("plannedCompletion",t.plannedCompletion)
        .put("scheduledStart",t.scheduledStart).put("scheduledEnd",t.scheduledEnd).put("estimatedMinutes",t.estimatedMinutes)
        .put("actualMinutes",t.actualMinutes).put("goalId",t.goalId).put("reminderAt",t.reminderAt).put("source",t.source)
        .put("locked",t.locked).put("revision",t.revision).put("createdAt",t.createdAt).put("updatedAt",t.updatedAt).toString()
    private fun taskFromJson(s:String):TaskEntity { val j=JSONObject(s);fun nullable(k:String)=j.optLong(k).takeIf{!j.isNull(k)}
        return TaskEntity(j.getString("id"),j.getString("title"),j.getString("type"),j.optString("notes"),j.getInt("importance"),
            j.getString("status"),nullable("deadline"),nullable("plannedCompletion"),nullable("scheduledStart"),nullable("scheduledEnd"),
            j.getInt("estimatedMinutes"),j.getInt("actualMinutes"),j.optString("goalId").takeIf{it!="null"&&it.isNotBlank()},
            nullable("reminderAt"),j.getString("source"),j.getBoolean("locked"),j.getInt("revision"),j.getLong("createdAt"),j.getLong("updatedAt")) }
}
