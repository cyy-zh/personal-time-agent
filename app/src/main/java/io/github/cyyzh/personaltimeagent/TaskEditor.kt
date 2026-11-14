package io.github.cyyzh.personaltimeagent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.cyyzh.personaltimeagent.data.TaskEntity
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun TaskEditor(task:TaskEntity,close:()->Unit,save:(TaskEntity)->Unit) {
    val formatter=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    fun display(ms:Long?)=ms?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter)}?:""
    fun parse(value:String)=value.trim().takeIf{it.isNotEmpty()}?.let{LocalDateTime.parse(it,formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}
    var title by remember{mutableStateOf(task.title)};var notes by remember{mutableStateOf(task.notes)}
    var minutes by remember{mutableStateOf(task.estimatedMinutes.toString())};var importance by remember{mutableStateOf(task.importance.toString())}
    var deadline by remember{mutableStateOf(display(task.deadline))};var start by remember{mutableStateOf(display(task.scheduledStart))}
    var locked by remember{mutableStateOf(task.locked)};var remind by remember{mutableStateOf(task.reminderAt!=null)}
    var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("编辑任务 / 固定活动")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("这里的保存操作会修改真实截止日期和锁定状态，请核对后确认。时间使用设备当前时区。")
        OutlinedTextField(title,{title=it},label={Text("名称")})
        OutlinedTextField(notes,{notes=it},label={Text("备注")})
        OutlinedTextField(minutes,{minutes=it},label={Text("预计分钟数")})
        OutlinedTextField(importance,{importance=it},label={Text("重要程度 1–5")})
        OutlinedTextField(deadline,{deadline=it},label={Text("真实截止 yyyy-MM-dd HH:mm；可留空")})
        OutlinedTextField(start,{start=it},label={Text("执行开始 yyyy-MM-dd HH:mm；可留空")})
        Row{Text("锁定活动（如课程）",Modifier.weight(1f));Switch(locked,{locked=it})}
        Row{Text("执行开始时提醒",Modifier.weight(1f));Switch(remind,{remind=it})}
        Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={
        try {val duration=minutes.toInt();val priority=importance.toInt();require(duration in 1..10080 && priority in 1..5 && title.isNotBlank())
            val began=parse(start);val due=parse(deadline)
            require(began==null || due==null || began+duration*60000L<=due)
            save(task.copy(title=title.trim(),notes=notes,estimatedMinutes=duration,importance=priority,deadline=due,scheduledStart=began,
                scheduledEnd=began?.plus(duration*60000L),locked=locked,reminderAt=if(remind) began else null))
        }catch(_:Exception){error="请检查数值和日期格式，执行结束不能晚于真实截止时间"}
    }){Text("确认保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})
}
