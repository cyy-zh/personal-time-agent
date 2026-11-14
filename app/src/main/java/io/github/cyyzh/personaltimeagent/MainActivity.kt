package io.github.cyyzh.personaltimeagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.lifecycleScope
import io.github.cyyzh.personaltimeagent.data.*
import io.github.cyyzh.personaltimeagent.domain.*
import io.github.cyyzh.personaltimeagent.platform.PlatformGates
import io.github.cyyzh.personaltimeagent.platform.ReminderPlatform
import io.github.cyyzh.personaltimeagent.domain.AgentClient
import io.github.cyyzh.personaltimeagent.ui.GoalsScreen
import io.github.cyyzh.personaltimeagent.ui.HabitsScreen
import io.github.cyyzh.personaltimeagent.ui.MemoryScreen
import io.github.cyyzh.personaltimeagent.platform.UsageCollection
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity:ComponentActivity() {
    private val repo by lazy { AgentRepository(this) }
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);setContent { App() } }
    @Composable private fun App() {
        var page by remember { mutableStateOf("对话") }
        var notice by remember { mutableStateOf("") }
        val tasks by repo.db.taskDao().observeAll().collectAsState(initial=emptyList())
        val goals by repo.db.goalDao().observeAll().collectAsState(initial=emptyList())
        val habits by repo.db.habitDao().observeAll().collectAsState(initial=emptyList())
        val slots by repo.db.slotDao().observeAll().collectAsState(initial=emptyList())
        val ledger by repo.db.ledgerDao().observeAll().collectAsState(initial=emptyList())
        val proposals by repo.db.proposalDao().observePending().collectAsState(initial=emptyList())
        val audit by repo.db.auditDao().observeAll().collectAsState(initial=emptyList())
        val inbox by repo.db.inboxDao().observeAll().collectAsState(initial=emptyList())
        MaterialTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("个人时间 Agent", style=MaterialTheme.typography.headlineMedium, modifier=Modifier.padding(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
                    listOf("对话","今日","目标","习惯","账本","统计","历史趋势与复盘","提案","手机使用","通知收件箱","活动与模型","权限与提醒","备份","设置").forEach { name ->
                        FilterChip(selected=page==name,onClick={page=name},label={Text(name)},modifier=Modifier.padding(end=6.dp))
                    }
                }
                if(notice.isNotBlank()) Text(notice, color=MaterialTheme.colorScheme.primary, modifier=Modifier.padding(12.dp))
                when(page) {
                    "对话" -> ChatPage(inbox,{entry -> lifecycleScope.launch { notice=AgentClient(this@MainActivity).process(entry) } }) { text -> lifecycleScope.launch {
                        val trimmed=text.trim(); if(trimmed.isNotEmpty()) {
                            val entry=InboxEntity(java.util.UUID.randomUUID().toString(),trimmed)
                            repo.db.inboxDao().upsert(entry)
                            val answer=StatsQuery(this@MainActivity).answer(trimmed)
                            if(answer!=null) {
                                repo.db.inboxDao().upsert(entry.copy(status="ANSWERED",error=answer));notice=answer
                            } else {
                            val result=LocalCommand.interpret(trimmed)
                            if(result!=null) {
                                repo.propose(result,"根据输入建议：$trimmed",source="LOCAL")
                                repo.db.inboxDao().upsert(entry.copy(status="PROPOSED"));notice="已生成提案，请审核后执行"
                            } else notice="已保存待处理指令；可配置 AI 服务后处理"
                            }
                        }
                    } }
                    "今日" -> TodayPage(tasks,slots,{ title -> lifecycleScope.launch { repo.addTask(title) } },{ task -> lifecycleScope.launch {
                        repo.propose(ProposedChange("COMPLETE_TASK",task.id,task.revision),"完成任务：${task.title}",source="USER")
                        notice="请在提案页确认变更"
                    } },{ lifecycleScope.launch {
                        val prefs=SchedulePreferences(this@MainActivity)
                        val result=Scheduler.plan(tasks,slots,System.currentTimeMillis(),sleepStartHour=prefs.sleepStartHour,sleepEndHour=prefs.sleepEndHour)
                        result.slots.forEach { slot -> val task=tasks.firstOrNull{it.id==slot.taskId}?:return@forEach
                            repo.propose(ProposedChange("SCHEDULE_TASK",task.id,task.revision,start=slot.startAt,end=slot.endAt),"安排 ${task.title}：${format(slot.startAt)}",source="LOCAL") }
                        notice="已生成 ${result.slots.size} 项排程提案；${result.unscheduled.size} 项时间不足"
                    } })
                    "目标" -> GoalsScreen(this@MainActivity)
                    "习惯" -> HabitsScreen(this@MainActivity)
                    "账本" -> LedgerPage(ledger,{category,start,end -> lifecycleScope.launch{repo.record(category,start,end)} })
                    "统计" -> StatsPage(ledger,tasks)
                    "历史趋势与复盘" -> MemoryScreen(this@MainActivity)
                    "提案" -> ProposalPage(proposals,audit,{id,approve -> lifecycleScope.launch{notice=repo.resolve(id,approve)} },{id -> lifecycleScope.launch{notice=repo.undo(id)} })
                    "设置" -> SettingsPage { notice=it }
                    "备份" -> BackupPage()
                    else -> DevicePage(page)
                }
            }
        }
    }
    @Composable private fun ChatPage(inbox:List<InboxEntity>,process:(InboxEntity)->Unit,submit:(String)->Unit) {
        var input by remember { mutableStateOf("") }
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(value=input,onValueChange={input=it},label={Text("告诉 Agent 你的计划或调整")},modifier=Modifier.fillMaxWidth(),minLines=3)
            Button(onClick={submit(input);input=""},enabled=input.isNotBlank()) { Text("生成待审核提案") }
            Text("所有更改需要逐项确认；离线指令会保存在本机。",style=MaterialTheme.typography.bodySmall)
            LazyColumn { items(inbox) { entry -> Row(Modifier.fillMaxWidth()) {Column(Modifier.weight(1f).padding(vertical=6.dp)){Text("${entry.text} · ${entry.status}");if(entry.status=="ANSWERED") Text(entry.error.orEmpty(),color=MaterialTheme.colorScheme.primary)};if(entry.status=="QUEUED") TextButton(onClick={process(entry)}){Text("调用 AI")}} } }
        }
    }
    @Composable private fun TodayPage(tasks:List<TaskEntity>,slots:List<SlotEntity>,add:(String)->Unit,complete:(TaskEntity)->Unit,schedule:()->Unit) {
        var input by remember { mutableStateOf("") }
        var editing by remember { mutableStateOf<TaskEntity?>(null) }
        var error by remember { mutableStateOf("") }
        editing?.let { task -> TaskEditor(task,{editing=null}){value->lifecycleScope.launch{error=repo.updateManual(value);if(error=="已保存")editing=null}} }
        Column(Modifier.padding(16.dp)) {
            Text(error)
            Row { OutlinedTextField(input,{input=it},label={Text("手动添加任务")},modifier=Modifier.weight(1f));Button(onClick={add(input);input=""},enabled=input.isNotBlank()) {Text("添加")} }
            Button(onClick=schedule){Text("生成一周排程提案")}
            LazyColumn { items(tasks,key={it.id}) { task -> Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(12.dp)) {
                Text(task.title,style=MaterialTheme.typography.titleMedium)
                Text("${task.status} · 预计 ${task.estimatedMinutes} 分钟 · 截止 ${task.deadline?.let(::format)?:"未设"}")
                Text("计划 ${task.scheduledStart?.let(::format)?:"待安排"} · 实际 ${task.actualMinutes} 分钟")
                TextButton(onClick={editing=task}){Text("编辑详情、时间与提醒")}
                if(task.status=="OPEN") TextButton(onClick={complete(task)}){Text("标记完成（需确认）")}
            } } } }
        }
    }
    @Composable private fun GoalsPage(goals:List<GoalEntity>,add:(String)->Unit) { var title by remember{mutableStateOf("")};Column(Modifier.padding(16.dp)) {
        Row { OutlinedTextField(title,{title=it},label={Text("长期目标")},modifier=Modifier.weight(1f));Button(onClick={add(title);title=""},enabled=title.isNotBlank()){Text("添加")} }
        LazyColumn { items(goals) { Text("${it.title} · ${it.status} · ${it.period} ${it.targetCount}",modifier=Modifier.padding(12.dp)) } }
    } }
    @Composable private fun HabitsPage(habits:List<HabitEntity>,add:(String,Int,Int)->Unit) { var title by remember{mutableStateOf("")};var count by remember{mutableStateOf("3")};var minutes by remember{mutableStateOf("30")};Column(Modifier.padding(16.dp)) {
        OutlinedTextField(title,{title=it},label={Text("习惯")},modifier=Modifier.fillMaxWidth())
        Row { OutlinedTextField(count,{count=it},label={Text("每周次数")},modifier=Modifier.weight(1f));OutlinedTextField(minutes,{minutes=it},label={Text("每次分钟")},modifier=Modifier.weight(1f)) }
        Button(onClick={add(title,count.toIntOrNull()?:3,minutes.toIntOrNull()?:30);title=""},enabled=title.isNotBlank()){Text("添加习惯")}
        LazyColumn { items(habits) { Text("${it.title} · 每周 ${it.frequencyPerWeek} 次 · ${it.minutes} 分钟",modifier=Modifier.padding(12.dp)) } }
    } }
    @Composable private fun LedgerPage(ledger:List<LedgerEntity>,record:(String,Long,Long)->Unit) { var category by remember{mutableStateOf("自主学习")};var started by remember{mutableLongStateOf(0L)};val timer=remember{PersistentTimer(repo.db)}
        var feedback by remember{mutableStateOf("")}
        LaunchedEffect(Unit){val saved=timer.state();category=saved.category;started=saved.startedAt?:0L}
        Column(Modifier.padding(16.dp)) {
        OutlinedTextField(category,{category=it},label={Text("活动类别")},modifier=Modifier.fillMaxWidth())
        Row { Button(onClick={lifecycleScope.launch{try{timer.start(category);started=timer.state().startedAt?:0L}catch(_:Exception){feedback="无法开始计时"}}},enabled=started==0L){Text("开始 / 继续")};Spacer(Modifier.width(8.dp));Button(onClick={lifecycleScope.launch{try{timer.pause();started=0L;feedback="已保存本段实际时间"}catch(_:Exception){feedback="未保存，请检查系统时间"}}},enabled=started>0L){Text("暂停 / 结束记录")} }
        Text(feedback)
        Text(if(started>0)"计时中：${format(started)}" else "暂无运行中的计时")
        LazyColumn { items(ledger) { Text("${it.category} · ${format(it.startAt)}–${format(it.endAt)} · ${it.source}",modifier=Modifier.padding(8.dp)) } }
    } }
    @Composable private fun StatsPage(ledger:List<LedgerEntity>,tasks:List<TaskEntity>) { var range by remember{mutableStateOf("日")};val now=System.currentTimeMillis();val days=when(range){"周"->7;"月"->30;else->1};val start=now-days*86_400_000L
        val totals=TimeMath.byCategory(ledger,start,now)
        Column(Modifier.padding(16.dp)) { Row { listOf("日","周","月").forEach{ FilterChip(selected=range==it,onClick={range=it},label={Text(it)},modifier=Modifier.padding(end=6.dp)) } }
            Text("实际记录总计 ${(TimeMath.unionMillis(ledger.map{maxOf(start,it.startAt) to minOf(now,it.endAt)})/60_000)} 分钟")
            totals.forEach{Text("${it.category}: ${it.millis/60_000} 分钟",modifier=Modifier.padding(8.dp))}
            Text("已完成任务 ${tasks.count{it.status=="DONE"}} / ${tasks.size}")
            Text("统计仅来自实际记录，重叠时间按手动、计时、系统、AI 证据优先级归类。",style=MaterialTheme.typography.bodySmall)
        }
    }
    @Composable private fun ProposalPage(proposals:List<ProposalEntity>,audits:List<AuditEntity>,resolve:(String,Boolean)->Unit,undo:(String)->Unit) { LazyColumn(Modifier.padding(16.dp)) {
        item{Text("等待确认",style=MaterialTheme.typography.titleLarge)}
        items(proposals) { p -> Card(Modifier.fillMaxWidth().padding(vertical=5.dp)) { Column(Modifier.padding(12.dp)) { Text(p.summary);Text(p.reason);Text("实际待执行变更（${p.kind}）：\n${p.payload}",style=MaterialTheme.typography.bodySmall);Row { Button(onClick={resolve(p.id,true)}){Text("确认")};TextButton(onClick={resolve(p.id,false)}){Text("拒绝")} } } } }
        item{Text("操作记录",style=MaterialTheme.typography.titleLarge)}
        items(audits) { a -> Row(Modifier.fillMaxWidth().padding(8.dp)) { Text("${a.action} · ${format(a.occurredAt)}",Modifier.weight(1f));if(a.undoneAt==null) TextButton(onClick={undo(a.id)}){Text("撤销")} } }
    } }
    @Composable private fun SettingsPage(notify:(String)->Unit) { var tick by remember{mutableIntStateOf(0)};val settings=remember{ModuleSettings(this)}
        val provider=remember{ProviderStore(this)};val initial=remember{provider.config()}
        var providerName by remember{mutableStateOf(initial.provider)};var endpoint by remember{mutableStateOf(initial.endpoint)};var model by remember{mutableStateOf(initial.model)}
        var apiKey by remember{mutableStateOf("")};var allowTasks by remember{mutableStateOf(initial.allowTasks)};var allowGoals by remember{mutableStateOf(initial.allowGoals)}
        var allowLedger by remember{mutableStateOf(initial.allowLedger)};var allowNotifications by remember{mutableStateOf(initial.allowNotifications)}
        val schedule=remember{SchedulePreferences(this)};var sleepStart by remember{mutableStateOf(schedule.sleepStartHour.toString())};var sleepEnd by remember{mutableStateOf(schedule.sleepEndHour.toString())}
        LazyColumn(Modifier.padding(16.dp)) { item{Text("功能管理",style=MaterialTheme.typography.titleLarge)}
            items(ModuleKey.entries) { key -> val sensitive=when(key){ModuleKey.APP_USAGE->PlatformGates.USAGE;ModuleKey.NOTIFICATION_CAPTURE->PlatformGates.NOTIFICATIONS;ModuleKey.ACTIVITY_RECOGNITION->PlatformGates.ACTIVITY;ModuleKey.LOCAL_VISION->PlatformGates.VISION;ModuleKey.AUTO_SCREENSHOT->PlatformGates.SCREENSHOTS;else->null};val enabled=remember(tick,key){if(sensitive!=null) PlatformGates.enabled(this@MainActivity,sensitive) else settings.enabled(key)}
                Row(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Text(key.name.replace('_',' '),Modifier.weight(1f));Switch(checked=enabled,onCheckedChange={ value ->
                    val ok=if(sensitive!=null)PlatformGates.set(this@MainActivity,sensitive,value) else settings.setEnabled(key,value)
                    if(key==ModuleKey.REMINDERS) { if(value) { ReminderPlatform.reconcileFromDatabase(this@MainActivity);ReminderPlatform.startReconciliation(this@MainActivity) } else ReminderPlatform.stop(this@MainActivity) }
                    if(key==ModuleKey.AI_REVIEW || key==ModuleKey.AI_PLANNING) ReviewWorker.schedule(this@MainActivity)
                    if(key==ModuleKey.APP_USAGE) { if(value) UsageCollection.start(this@MainActivity) else UsageCollection.stop(this@MainActivity) }
                    tick++;notify(if(ok)"设置已保存" else "设置保存失败")
                }) }
            }
            item {
                Text("规划 API 与数据授权",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(providerName,{providerName=it},label={Text("服务商")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS Chat Completions 地址")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(model,{model=it},label={Text("模型名称")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(apiKey,{apiKey=it},label={Text("API Key（留空不更改）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                listOf("任务" to allowTasks,"目标" to allowGoals,"时间记录" to allowLedger,"通知内容（仅逐条授权）" to allowNotifications).forEach { (label, checked) ->
                    Row {Text("允许发送$label",Modifier.weight(1f));Switch(checked=checked,onCheckedChange={ when(label){"任务"->allowTasks=it;"目标"->allowGoals=it;"时间记录"->allowLedger=it;else->allowNotifications=it} })}
                }
                Text("截图永不发送给 API；通知内容当前不进入自动请求。",style=MaterialTheme.typography.bodySmall)
                Button(onClick={ if(endpoint.isNotBlank()&&!endpoint.startsWith("https://")){notify("仅允许 HTTPS 地址")}else{
                    val ok=provider.save(ProviderConfig(providerName,endpoint,model,0.3f,allowTasks,allowGoals,allowLedger,allowNotifications))
                    if(apiKey.isNotBlank()){provider.saveApiKey(apiKey);apiKey=""};notify(if(ok)"API 配置已保存" else "保存失败")
                } }){Text("保存 API 配置")}
                Text("睡眠保护时段",style=MaterialTheme.typography.titleLarge)
                Row {OutlinedTextField(sleepStart,{sleepStart=it},label={Text("开始小时")},modifier=Modifier.weight(1f));OutlinedTextField(sleepEnd,{sleepEnd=it},label={Text("结束小时")},modifier=Modifier.weight(1f))}
                Button(onClick={val s=sleepStart.toIntOrNull();val e=sleepEnd.toIntOrNull();if(s!=null&&e!=null&&s in 0..23&&e in 0..23&&s!=e){schedule.setSleepHours(s,e);notify("睡眠保护已保存")}else notify("请输入两个不同的 0–23 小时")}){Text("保存睡眠时段")}
            }
        }
    }
    private fun format(epoch:Long)=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(epoch))
}
