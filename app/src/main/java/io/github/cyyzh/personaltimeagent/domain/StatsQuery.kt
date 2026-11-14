package io.github.cyyzh.personaltimeagent.domain

import android.content.Context
import io.github.cyyzh.personaltimeagent.data.AgentDatabase
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Recognised statistical queries are answered by local records, never model-generated numbers. */
class StatsQuery(context:Context) {
    private val db=AgentDatabase.get(context)
    suspend fun answer(text:String):String? {
        if(!listOf("多久","多少小时","多少分钟","时间分布").any{text.contains(it)})return null
        val today=LocalDate.now()
        val startDate=when {
            text.contains("这周")||text.contains("本周")->today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            text.contains("最近一周")->today.minusDays(6)
            text.contains("本月")->today.withDayOfMonth(1)
            else->today
        }
        val start=startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end=System.currentTimeMillis()
        val all=db.ledgerDao().inRange(start,end).filter{it.source!="PLANNED" && it.confirmed}
        val category=listOf("学习","科研","运动","冥想","短视频","社交","睡眠").firstOrNull{text.contains(it)}
        val totals=TimeMath.byCategory(all,start,end).filter{category==null||it.category.contains(category)}
        if(totals.isEmpty())return "所选周期没有可确认的${category?:"活动"}记录；不能将缺少记录解释为实际投入为零。"
        return "从 $startDate 至今，本机已确认记录："+totals.joinToString("；"){"${it.category} ${it.millis/60000} 分钟"}+"。按区间去重，未记录时间未计入。"
    }
}
