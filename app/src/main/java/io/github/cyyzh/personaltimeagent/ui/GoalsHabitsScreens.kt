package io.github.cyyzh.personaltimeagent.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.cyyzh.personaltimeagent.data.*
import io.github.cyyzh.personaltimeagent.domain.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

@Composable fun GoalsScreen(context:Context=LocalContext.current) {
    val db=remember{AgentDatabase.get(context)};val service=remember{GoalsHabitsService(context)}
    val proposal=remember{GoalsHabitsProposal(context)};val scope=rememberCoroutineScope()
    val goals by db.goalDao().observeAll().collectAsState(initial=emptyList())
    val tasks by db.taskDao().observeAll().collectAsState(initial=emptyList())
    var progress by remember{mutableStateOf<List<GoalProgress>>(emptyList())}
    var title by remember{mutableStateOf("")};var weeks by remember{mutableStateOf("4")};var message by remember{mutableStateOf("")}
    LaunchedEffect(goals,tasks){progress=service.goalProgress()}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("长期目标",style=MaterialTheme.typography.headlineSmall)
        if(!ModuleSettings(context).enabled(ModuleKey.GOALS)) Text("目标模块已关闭；已有数据仍保留。")
        OutlinedTextField(title,{title=it},label={Text("目标名称")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(weeks,{weeks=it},label={Text("预计持续周数")},modifier=Modifier.fillMaxWidth())
        Button(onClick={scope.launch{
            val n=(weeks.toIntOrNull()?:4).coerceIn(1,52)
            val due=System.currentTimeMillis()+n*7*86_400_000L
            val json=JSONObject().put("title",title.trim()).put("targetAt",due).put("period","WEEK")
            message=if(proposal.proposeJson("CREATE_GOAL",json.toString(),"建立长期目标：$title",source="USER")!=null) "已放入提案，等待确认" else "请输入有效目标"
            if(message.startsWith("已"))title=""
        }},enabled=title.isNotBlank()&&ModuleSettings(context).enabled(ModuleKey.GOALS)){Text("提出目标")}
        if(message.isNotBlank()) Text(message)
        LazyColumn { items(goals,key={it.id}) { goal ->
            val p=progress.firstOrNull{it.goalId==goal.id}
            Card(Modifier.fillMaxWidth().padding(vertical=5.dp)) {Column(Modifier.padding(12.dp)) {
                Text(goal.title,style=MaterialTheme.typography.titleMedium)
                Text("${goal.status} · 阶段完成 ${p?.completed?:0}/${p?.totalMilestones?:0} · 实际投入 ${p?.minutesInvested?:0} 分钟")
                Text("目标截止 ${goal.targetAt?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}?:"未设置"}")
                TextButton(onClick={scope.launch { val result=service.suggestGoalMilestones(goal.id);message="新增 ${result.proposed} 项阶段提案，跳过重复 ${result.skippedExisting} 项" }}) {Text("建议阶段安排")}
            }}
        } }
    }
}

@Composable fun HabitsScreen(context:Context=LocalContext.current) {
    val db=remember{AgentDatabase.get(context)};val service=remember{GoalsHabitsService(context)}
    val proposal=remember{GoalsHabitsProposal(context)};val scope=rememberCoroutineScope()
    val habits by db.habitDao().observeAll().collectAsState(initial=emptyList())
    val tasks by db.taskDao().observeAll().collectAsState(initial=emptyList())
    var progress by remember{mutableStateOf<List<HabitProgress>>(emptyList())}
    var title by remember{mutableStateOf("")};var weekly by remember{mutableStateOf("3")};var minutes by remember{mutableStateOf("30")}
    var message by remember{mutableStateOf("")}
    LaunchedEffect(habits,tasks){progress=service.habitProgress()}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("习惯",style=MaterialTheme.typography.headlineSmall)
        if(!ModuleSettings(context).enabled(ModuleKey.HABITS)) Text("习惯模块已关闭；已有记录仍保留。")
        OutlinedTextField(title,{title=it},label={Text("习惯名称")},modifier=Modifier.fillMaxWidth())
        Row {OutlinedTextField(weekly,{weekly=it},label={Text("每周次数")},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));OutlinedTextField(minutes,{minutes=it},label={Text("每次分钟")},modifier=Modifier.weight(1f))}
        Button(onClick={scope.launch {
            val n=weekly.toIntOrNull();val m=minutes.toIntOrNull()
            val json=JSONObject().put("title",title.trim()).put("frequencyPerWeek",n).put("minutes",m)
            message=if(proposal.proposeJson("CREATE_HABIT",json.toString(),"建立习惯：$title",source="USER")!=null) "已放入提案，等待确认" else "请输入有效次数与时长"
            if(message.startsWith("已"))title=""
        }},enabled=title.isNotBlank()&&ModuleSettings(context).enabled(ModuleKey.HABITS)){Text("提出习惯")}
        Button(onClick={scope.launch{val r=service.suggestWeeklyHabitTasks();message="新增 ${r.proposed} 项，跳过重复 ${r.skippedExisting} 项，无法安排 ${r.unscheduled} 项"}},enabled=habits.isNotEmpty()&&ModuleSettings(context).enabled(ModuleKey.HABITS)){Text("建议本周执行时间")}
        if(message.isNotBlank()) Text(message)
        LazyColumn {items(habits,key={it.id}){habit-> val p=progress.firstOrNull{it.habitId==habit.id}
            Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(12.dp)){
                Text(habit.title,style=MaterialTheme.typography.titleMedium)
                Text("本周完成 ${p?.completed?:0}/${habit.frequencyPerWeek} · 已安排 ${p?.scheduled?:0} · 连续达标 ${p?.streakWeeks?:0} 周")
                Text("近四周：${p?.history?.joinToString(" / ")?:"暂无"}")
            }}
        }}
    }
}

@Composable fun MemoryScreen(context:Context=LocalContext.current) {
    val scope=rememberCoroutineScope();val service=remember{MemoryReviewService(context)}
    val prefs=remember{ReviewPreferences(context)}
    var range by remember{mutableStateOf(7)};var snapshot by remember{mutableStateOf<ReviewSnapshot?>(null)}
    var frequency by remember{mutableStateOf(prefs.intervalDays.toString())};var budget by remember{mutableStateOf(prefs.callsPerMonth.toString())}
    var message by remember{mutableStateOf("")}
    LaunchedEffect(range){snapshot=service.snapshot(System.currentTimeMillis()-range*86_400_000L,System.currentTimeMillis())}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("历史趋势与复盘",style=MaterialTheme.typography.headlineSmall)
        Row {listOf(1 to "今日",7 to "七天",30 to "三十天").forEach{(days,label)->FilterChip(selected=range==days,onClick={range=days},label={Text(label)},modifier=Modifier.padding(end=6.dp))}}
        snapshot?.let {r->
            Text(service.localSummary(r))
            if(r.categories.isNotEmpty()) Text("以上为真实账本记录，重叠时段只计一次。")
        }
        Text("主动 AI 复盘",style=MaterialTheme.typography.titleMedium)
        Text("仅在功能开关、AI 规划及授权的数据范围同时启用后运行。",style=MaterialTheme.typography.bodySmall)
        Row {OutlinedTextField(frequency,{frequency=it},label={Text("间隔天数")},modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));OutlinedTextField(budget,{budget=it},label={Text("每月最多调用")},modifier=Modifier.weight(1f))}
        Button(onClick={
            val f=frequency.toIntOrNull();val b=budget.toIntOrNull()
            if(f!=null&&f in 1..30&&b!=null&&b in 0..30){prefs.intervalDays=f;prefs.callsPerMonth=b;ReviewWorker.schedule(context);message="复盘频率与预算已保存"}
            else message="请输入有效范围：天数 1–30，调用 0–30"
        }){Text("保存复盘设置")}
        if(message.isNotBlank())Text(message)
    }
}
