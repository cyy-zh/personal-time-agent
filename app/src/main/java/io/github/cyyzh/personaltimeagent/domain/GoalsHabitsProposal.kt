package io.github.cyyzh.personaltimeagent.domain

import android.content.Context
import androidx.room.withTransaction
import io.github.cyyzh.personaltimeagent.data.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Extension actions are transactional and cannot write until the user approves the exact proposal. */
class GoalsHabitsProposal(private val context:Context) {
    private val db=AgentDatabase.get(context)
    private val modules=ModuleSettings(context)
    private fun id()=UUID.randomUUID().toString()
    private fun Long?.jsonValue():Any=this?:JSONObject.NULL
    private fun String?.jsonValue():Any=this?:JSONObject.NULL
    private fun JSONObject.stringOrNull(key:String)=optString(key).takeIf{it.isNotBlank()&&it!="null"}
    private fun JSONObject.longOrNull(key:String)=optLong(key).takeUnless{isNull(key)}
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}

    suspend fun proposeJson(kind:String,payload:String,summary:String,source:String="AI"):ProposalEntity? {
        val json=runCatching{JSONObject(payload)}.getOrNull()?:return null
        if(summary.isBlank() || !validate(kind,json)) return null
        val target=json.stringOrNull("targetId")
        val p=ProposalEntity(id(),kind,json.toString(),summary,source=source,targetId=target)
        db.proposalDao().upsert(p)
        return p
    }
    private fun validate(kind:String,j:JSONObject):Boolean=when(kind) {
        "CREATE_GOAL"->j.stringOrNull("title")!=null && j.optInt("targetCount",0) in 0..10_000 && j.optString("period","WEEK") in setOf("DAY","WEEK","MONTH")
        "CREATE_HABIT"->j.stringOrNull("title")!=null && j.optInt("frequencyPerWeek",0) in 1..7 && j.optInt("minutes",0) in 5..480 &&
            (j.isNull("preferredHour") || j.optInt("preferredHour") in 0..23)
        "RECORD_TIME"->j.stringOrNull("category")!=null && j.optLong("startAt",-1)>=0 && j.optLong("endAt",0)>j.optLong("startAt",-1) &&
            j.optLong("endAt")<=System.currentTimeMillis()+60_000 && j.optLong("endAt")-j.optLong("startAt")<=36*60*60*1000L
        "UPDATE_TIME"->j.stringOrNull("targetId")!=null && j.stringOrNull("expectedFingerprint")!=null &&
            j.stringOrNull("category")!=null && j.optLong("startAt",-1)>=0 && j.optLong("endAt",0)>j.optLong("startAt",-1) &&
            j.optLong("endAt")<=System.currentTimeMillis()+60_000
        else->false
    }
    suspend fun resolve(proposalId:String,approve:Boolean):String=db.withTransaction {
        val p=db.proposalDao().get(proposalId)?:return@withTransaction "Proposal missing"
        if(p.state!="PENDING") return@withTransaction "Already resolved"
        if(!approve){db.proposalDao().upsert(p.copy(state="REJECTED",resolvedAt=System.currentTimeMillis()));return@withTransaction "Rejected"}
        val j=runCatching{JSONObject(p.payload)}.getOrNull()?:return@withTransaction "Invalid payload"
        if(!validate(p.kind,j)) return@withTransaction "Invalid payload"
        val now=System.currentTimeMillis()
        val action=when(p.kind) {
            "CREATE_GOAL" -> {
                if(!modules.enabled(ModuleKey.GOALS)) return@withTransaction "Goals module is off"
                val goal=GoalEntity(id(),j.getString("title").trim(),j.optString("description"),j.longOrNull("targetAt"),
                    j.optInt("targetCount",0),j.optString("period","WEEK"),important=j.optBoolean("important",false))
                db.goalDao().upsert(goal);Triple(goal.id,null,goalJson(goal))
            }
            "CREATE_HABIT" -> {
                if(!modules.enabled(ModuleKey.HABITS)) return@withTransaction "Habits module is off"
                val goalId=j.stringOrNull("goalId")
                if(goalId!=null && db.goalDao().get(goalId)==null) return@withTransaction "Goal missing"
                val habit=HabitEntity(id(),goalId,j.getString("title").trim(),j.getInt("frequencyPerWeek"),
                    j.getInt("minutes"),j.longOrNull("preferredHour")?.toInt(),category=j.optString("category","OTHER"))
                db.habitDao().upsert(habit);Triple(habit.id,null,habitJson(habit))
            }
            "RECORD_TIME" -> {
                val taskId=j.stringOrNull("taskId")
                if(taskId!=null && db.taskDao().get(taskId)==null) return@withTransaction "Task missing"
                val row=LedgerEntity(id(),taskId,j.getString("category").trim(),j.getLong("startAt"),j.getLong("endAt"),
                    source="MANUAL",evidence="Approved proposal ${p.id}",confirmed=true)
                db.ledgerDao().upsert(row);Triple(row.id,null,ledgerJson(row))
            }
            "UPDATE_TIME" -> {
                val old=db.ledgerDao().get(j.getString("targetId"))?:return@withTransaction "Record missing"
                val before=ledgerJson(old)
                if(hash(before)!=j.getString("expectedFingerprint")) return@withTransaction "Proposal is stale"
                val next=old.copy(category=j.getString("category").trim(),startAt=j.getLong("startAt"),endAt=j.getLong("endAt"),
                    source="MANUAL",confirmed=true,evidence="Corrected by approved proposal ${p.id}",createdAt=maxOf(now,old.createdAt+1))
                db.ledgerDao().upsert(next);Triple(old.id,before,ledgerJson(next))
            }
            else->return@withTransaction "Unsupported"
        }
        db.auditDao().upsert(AuditEntity(id(),p.id,p.kind,action.first,action.second,action.third))
        db.proposalDao().upsert(p.copy(state="APPROVED",resolvedAt=now))
        "Applied"
    }
    suspend fun undo(auditId:String):String=db.withTransaction {
        val a=db.auditDao().get(auditId)?:return@withTransaction "Audit missing"
        if(a.undoneAt!=null) return@withTransaction "Already undone"
        val expected=a.afterJson?:return@withTransaction "Audit incomplete"
        when(a.action) {
            "CREATE_GOAL" -> {
                val current=db.goalDao().get(a.targetId)?:return@withTransaction "Goal missing"
                if(goalJson(current)!=expected) return@withTransaction "Changed since action; cannot undo"
                if(db.taskDao().observeAll().first().any{it.goalId==a.targetId} || db.habitDao().observeAll().first().any{it.goalId==a.targetId}) return@withTransaction "Goal has linked records"
                db.goalDao().delete(a.targetId)
            }
            "CREATE_HABIT" -> {
                val current=db.habitDao().get(a.targetId)?:return@withTransaction "Habit missing"
                if(habitJson(current)!=expected) return@withTransaction "Changed since action; cannot undo"
                if(db.taskDao().observeAll().first().any{it.source.startsWith("HABIT:${a.targetId}:")}) return@withTransaction "Habit has linked tasks"
                db.habitDao().delete(a.targetId)
            }
            "RECORD_TIME","UPDATE_TIME" -> {
                val current=db.ledgerDao().get(a.targetId)?:return@withTransaction "Record missing"
                if(ledgerJson(current)!=expected) return@withTransaction "Changed since action; cannot undo"
                if(a.beforeJson==null) db.ledgerDao().delete(a.targetId)
                else {
                    val old=ledgerFromJson(a.beforeJson)
                    db.ledgerDao().upsert(old.copy(createdAt=maxOf(System.currentTimeMillis(),current.createdAt+1)))
                }
            }
            else->return@withTransaction "Unsupported"
        }
        db.auditDao().upsert(a.copy(undoneAt=System.currentTimeMillis()))
        "Undone"
    }
    suspend fun timeFingerprint(id:String):String?=db.ledgerDao().get(id)?.let{hash(ledgerJson(it))}
    private fun goalJson(v:GoalEntity)=JSONObject().put("id",v.id).put("title",v.title).put("description",v.description)
        .put("targetAt",v.targetAt.jsonValue()).put("targetCount",v.targetCount).put("period",v.period).put("status",v.status)
        .put("important",v.important).put("createdAt",v.createdAt).toString()
    private fun habitJson(v:HabitEntity)=JSONObject().put("id",v.id).put("goalId",v.goalId.jsonValue()).put("title",v.title)
        .put("frequencyPerWeek",v.frequencyPerWeek).put("minutes",v.minutes).put("preferredHour",v.preferredHour?.toLong().jsonValue())
        .put("enabled",v.enabled).put("category",v.category).toString()
    private fun ledgerJson(v:LedgerEntity)=JSONObject().put("id",v.id).put("taskId",v.taskId.jsonValue()).put("category",v.category)
        .put("startAt",v.startAt).put("endAt",v.endAt).put("source",v.source).put("confidence",v.confidence.toDouble())
        .put("appPackage",v.appPackage.jsonValue()).put("evidence",v.evidence).put("confirmed",v.confirmed).put("createdAt",v.createdAt).toString()
    private fun ledgerFromJson(s:String):LedgerEntity { val j=JSONObject(s);return LedgerEntity(j.getString("id"),j.stringOrNull("taskId"),j.getString("category"),
        j.getLong("startAt"),j.getLong("endAt"),j.getString("source"),j.getDouble("confidence").toFloat(),j.stringOrNull("appPackage"),
        j.getString("evidence"),j.getBoolean("confirmed"),j.getLong("createdAt")) }
}
