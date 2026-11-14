package io.github.cyyzh.personaltimeagent.domain

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.cyyzh.personaltimeagent.data.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LearningSample(val key:String,val conclusion:String,val sampleCount:Int,val confidence:String)
data class ReviewSnapshot(val tasksCompleted:Int,val timeMinutes:Long,val categories:List<CategoryDuration>,
    val estimateBias:LearningSample?,val preferredWindow:LearningSample?,val rangeStart:Long,val rangeEnd:Long)

class MemoryReviewService(private val context:Context) {
    private val db=AgentDatabase.get(context)
    suspend fun snapshot(start:Long,end:Long):ReviewSnapshot {
        require(start>=0 && end>start)
        val tasks=db.taskDao().observeAll().first()
        val ledger=db.ledgerDao().inRange(start,end).filter { it.source!="PLANNED" && it.confirmed }
        val categories=TimeMath.byCategory(ledger,start,end)
        val completed=tasks.filter{it.status=="DONE" && it.updatedAt in start until end}
        val elapsed=TimeMath.unionMillis(ledger.map{maxOf(start,it.startAt) to minOf(end,it.endAt)})/60_000L
        val pairs=completed.mapNotNull { task ->
            val actual=ledger.filter{it.taskId==task.id}.let{TimeMath.unionMillis(it.map{row->row.startAt to row.endAt})/60_000L}
            if(actual>0 && task.estimatedMinutes>0) task.estimatedMinutes.toLong() to actual else null
        }
        val bias=if(pairs.size>=5) {
            val ratio=pairs.sumOf{it.second}.toDouble()/pairs.sumOf{it.first}.coerceAtLeast(1)
            LearningSample("duration_bias","实际/预计耗时比 ${"%.2f".format(ratio)}",pairs.size,
                if(pairs.size>=20)"中等" else "低")
        } else null
        val timed=ledger.filter{it.source=="TIMER" || it.source=="MANUAL"}
        val hours=timed.groupingBy{Instant.ofEpochMilli(it.startAt).atZone(ZoneId.systemDefault()).hour/4*4}.eachCount()
        val preference=if(timed.size>=8 && hours.isNotEmpty()) {
            val top=hours.maxBy{it.value}
            LearningSample("recorded_time_window","记录较多时段 ${top.key}:00–${top.key+4}:00",timed.size,
                if(timed.size>=24)"中等" else "低")
        } else null
        return ReviewSnapshot(completed.size,elapsed,categories,bias,preference,start,end)
    }
    suspend fun refreshMemory(start:Long,end:Long):ReviewSnapshot {
        val result=snapshot(start,end)
        listOfNotNull(result.estimateBias,result.preferredWindow).forEach { sample ->
            db.memoryDao().upsert(MemoryEntity(sample.key,sample.key,sample.conclusion,sample.sampleCount,"INFERRED"))
        }
        return result
    }
    fun localSummary(r:ReviewSnapshot):String = buildString {
        append("本期已完成 ${r.tasksCompleted} 项任务；有证据的实际活动 ${r.timeMinutes} 分钟。")
        if(r.categories.isNotEmpty()) append("分类："+r.categories.joinToString("，"){"${it.category} ${it.millis/60_000} 分钟"}+"。")
        r.estimateBias?.let{append("${it.conclusion}（样本 ${it.sampleCount}，可信度${it.confidence}）。")}
        r.preferredWindow?.let{append("${it.conclusion}（样本 ${it.sampleCount}，可信度${it.confidence}；仅表示记录分布）。")}
        if(r.estimateBias==null && r.preferredWindow==null) append("个性化样本不足，暂不推断时间偏好。")
    }
}

class ReviewPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("review_budget",Context.MODE_PRIVATE)
    var intervalDays:Int
        get()=prefs.getInt("interval_days",7).coerceIn(1,30)
        set(value){prefs.edit().putInt("interval_days",value.coerceIn(1,30)).commit()}
    var callsPerMonth:Int
        get()=prefs.getInt("calls_per_month",4).coerceIn(0,30)
        set(value){prefs.edit().putInt("calls_per_month",value.coerceIn(0,30)).commit()}
    @Synchronized fun reserveCall(now:Long):Boolean {
        val month=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let{it.year*12+it.monthValue}
        val oldMonth=prefs.getInt("month",-1)
        val used=if(oldMonth==month)prefs.getInt("used",0) else 0
        if(used>=callsPerMonth) return false
        return prefs.edit().putInt("month",month).putInt("used",used+1).commit()
    }
}

class ReviewWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val c=applicationContext;val modules=ModuleSettings(c)
        if(!modules.enabled(ModuleKey.AI_REVIEW) || !modules.enabled(ModuleKey.AI_PLANNING)) return Result.success()
        val provider=ProviderStore(c).config()
        if(!provider.allowTasks && !provider.allowLedger) return Result.success()
        val now=System.currentTimeMillis()
        val prefs=ReviewPreferences(c)
        if(!prefs.reserveCall(now)) return Result.success()
        val start=now-prefs.intervalDays*86_400_000L
        val snapshot=MemoryReviewService(c).refreshMemory(start,now)
        val summary=buildString {
            if(provider.allowTasks) append("本期完成任务 ${snapshot.tasksCompleted} 项。")
            if(provider.allowLedger) append("实际活动 ${snapshot.timeMinutes} 分钟；"+snapshot.categories.joinToString("，"){"${it.category} ${it.millis/60_000} 分钟"}+"。")
        }
        val text="根据以下本机汇总做复盘并提出可审核的计划建议：$summary"
        val item=InboxEntity(UUID.randomUUID().toString(),text,source="REVIEW")
        AgentDatabase.get(c).inboxDao().upsert(item)
        if(!modules.enabled(ModuleKey.AI_REVIEW) || !modules.enabled(ModuleKey.AI_PLANNING)) return Result.success()
        AgentClient(c).process(item)
        return Result.success()
    }
    companion object {
        private const val NAME="approved-ai-review"
        fun schedule(context:Context) {
            val enabled=ModuleSettings(context).enabled(ModuleKey.AI_REVIEW)
            val manager=WorkManager.getInstance(context)
            if(!enabled){manager.cancelUniqueWork(NAME);return}
            val days=ReviewPreferences(context).intervalDays.toLong()
            val work=PeriodicWorkRequestBuilder<ReviewWorker>(days,TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.UPDATE,work)
        }
    }
}
