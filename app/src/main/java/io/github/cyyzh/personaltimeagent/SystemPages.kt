package io.github.cyyzh.personaltimeagent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cyyzh.personaltimeagent.data.*
import io.github.cyyzh.personaltimeagent.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

@Composable fun DevicePage(page: String) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        message=if(it) "已允许应用通知" else "未允许通知，任务数据仍可使用";refresh++
    }
    fun settings(action:String, app:Boolean=false) {
        runCatching { context.startActivity(Intent(action).apply { if(app) data=Uri.parse("package:${context.packageName}") }) }
            .onFailure { message="系统未提供此入口，请从应用信息页手动进入" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(page,style=MaterialTheme.typography.headlineSmall)
        Text(message)
        when(page) {
            "权限与提醒" -> {
                Text("功能开关表达你的选择；系统授权决定实际能力。权限未就绪时不会采集。")
                Text("通知：${PlatformGates.notificationDeliveryEnabled(context)} · 精确闹钟：${PlatformGates.exactAlarmPermission(context)} · 状态检查 $refresh")
                Button(onClick={if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS) else settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)}){Text("允许提醒通知")}
                Button(onClick={if(Build.VERSION.SDK_INT>=31)settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,true)}){Text("设置精确闹钟权限")}
                Text("没有精确闹钟权限时使用非精确提醒，系统可能延迟；提交成功不等于已可见。")
                Button(onClick={scope.launch {withContext(Dispatchers.IO){ReminderPlatform.reconcileFromDatabase(context)};refresh++}}){Text("重新核对未来提醒")}
                ReminderPlatform.status(context).forEach { Text("${it.optString("title")} · ${it.optString("status")} · ${it.optString("delivery")}",Modifier.padding(vertical=6.dp)) }
            }
            "手机使用" -> {
                var summaries by remember {mutableStateOf(emptyList<Pair<String,Long>>())}
                var days by remember {mutableIntStateOf(1)}
                Text("使用情况权限：${PlatformGates.usagePermission(context)}；需另在功能管理开启手机使用统计。")
                Button(onClick={settings(Settings.ACTION_USAGE_ACCESS_SETTINGS)}){Text("打开使用情况授权")}
                Row { listOf(1,7,30).forEach { d -> FilterChip(selected=days==d,onClick={days=d},label={Text("$d 天")},modifier=Modifier.padding(end=8.dp)) } }
                Button(enabled=!busy,onClick={busy=true;scope.launch {
                    try {
                        val now=System.currentTimeMillis()
                        val from=LocalDate.now().minusDays((days-1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val result=withContext(Dispatchers.IO){UsageObservationReader.read(context,from,now)}
                        summaries=result.intervals.groupBy{it.packageName}.map{it.key to it.value.sumOf{v->v.durationMs}}.sortedByDescending{it.second}
                        message=if(result.permissionGranted) "来自系统事件，未提供事件的区间无法确定；不等于已完成 ColorOS 对照验收" else "模块未开启或缺少系统权限"
                    } catch(_:Exception){message="无法读取系统统计"} finally {busy=false}
                }}){Text("读取所选周期")}
                summaries.forEach { (pkg,ms) -> Text("$pkg：${ms/60000} 分钟",Modifier.padding(vertical=6.dp)) }
                Text("App 使用时长不等于具体活动时长；未识别的微信使用不会算成短视频。")
            }
            "通知收件箱" -> {
                var rows by remember {mutableStateOf(NotificationCaptureStore.records(context))}
                var bodyConsent by remember {mutableStateOf(NotificationCaptureStore.bodyConsent(context))}
                Text("系统通知使用权：${PlatformGates.notificationListenerPermission(context,AgentNotificationListener::class.java)}")
                Button(onClick={settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)}){Text("打开通知使用权")}
                Text("授权后仍需开启通知采集，并选择来源。保存正文会将可访问聊天通知加密保存在本机；默认不上传。")
                listOf("微信" to "com.tencent.mm","QQ" to "com.tencent.mobileqq").forEach { (label,pkg) ->
                    var enabled by remember {mutableStateOf(NotificationCaptureStore.allowed(context,pkg))}
                    Toggle(label,enabled){enabled=it;NotificationCaptureStore.setAllowed(context,pkg,it)}
                }
                Toggle("我同意在本机保存所选来源的通知正文",bodyConsent){bodyConsent=it;NotificationCaptureStore.setBodyConsent(context,it)}
                Button(onClick={rows=NotificationCaptureStore.records(context);refresh++}){Text("刷新记录")}
                Button(onClick={NotificationCaptureStore.deleteAll(context);rows=emptyList();bodyConsent=false}){Text("删除采集记录并关闭正文保存")}
                Text("没有记录可能是未授权、未开启或没有回调，不能直接证明来源应用没有发布通知。")
                rows.takeLast(100).reversed().forEach { row ->
                    Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(12.dp)) {
                        Text("${row.optString("source")} · ${row.optString("event")} · ${row.optString("bodyState")}")
                        val body=row.optString("body").takeUnless{it=="null"}.orEmpty()
                        if(body.isNotEmpty()) {
                            Text(body)
                            TextButton(onClick={scope.launch {
                                val repo=AgentRepository(context)
                                repo.db.inboxDao().upsert(InboxEntity(java.util.UUID.randomUUID().toString(),body,source="NOTIFICATION:${row.optString("notificationId")}"))
                                message="已加入待处理输入；调用外部 AI 仍须通知数据范围授权"
                            }}){Text("作为待办候选输入")}
                        }
                    } }
                }
            }
            "活动与模型" -> {
                val manager=remember {LocalVisionManager.get(context)}
                var modelState by remember {mutableStateOf(manager.state())}
                var url by remember {mutableStateOf("")};var hash by remember {mutableStateOf("")}
                Text("模型：${if(modelState.installed) "已安装 ${modelState.bytes/1024/1024} MB" else "未安装"} · 运行：${modelState.running} · 开关：${modelState.enabled}")
                Text("无障碍权限：${PlatformGates.accessibilityPermission(context,AgentAccessibilityService::class.java)}。仅观察你允许的应用，不操作其他应用。截图只在内存推理，不上传。")
                Button(onClick={settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)}){Text("打开无障碍授权页面")}
                listOf("微信" to "com.tencent.mm","QQ" to "com.tencent.mobileqq","B站" to "tv.danmaku.bili","Chrome" to "com.android.chrome").forEach { (name,pkg) ->
                    var allowed by remember {mutableStateOf(ActivityCapture.allowed(context,pkg))}
                    Toggle("允许分析 $name",allowed){allowed=it;ActivityCapture.setAllowed(context,pkg,it)}
                }
                Text("需分别开启活动识别、本地视觉模型和自动截图。当前模型输出置信度尚未校准，不代表实测准确率。")
                OutlinedTextField(url,{url=it},label={Text("兼容图像输入的 .litertlm HTTPS 下载地址")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(hash,{hash=it},label={Text("官方文件 SHA-256")},modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy,onClick={
                    if(!url.startsWith("https://") || !hash.matches(Regex("[a-fA-F0-9]{64}"))) message="请填写 HTTPS 下载地址和 64 位 SHA-256"
                    else {busy=true;manager.install(url,hash){ok->scope.launch{busy=false;modelState=manager.state();message=if(ok)"模型校验完成；启用后才会运行" else "下载或校验失败，旧模型保留"}}}
                }){Text(if(busy)"正在下载与校验…" else "下载并校验模型")}
                Button(onClick={manager.disable();message="已停止新推理，等待在途计算释放资源";modelState=manager.state()}){Text("暂停运行")}
                Button(onClick={scope.launch {val ok=withContext(Dispatchers.IO){manager.delete()};modelState=manager.state();message=if(ok)"模型文件已删除" else "文件删除失败"}}){Text("删除模型文件")}
                Button(onClick={modelState=manager.state();refresh++}){Text("刷新模型状态")}
                val results=remember(refresh){runCatching{JSONArray(context.getSharedPreferences("vision_results",0).getString("results","[]"))}.getOrDefault(JSONArray())}
                for(i in results.length()-1 downTo maxOf(0,results.length()-30)) {val item=results.getJSONObject(i);Text("${item.optString("package")} · ${item.optString("category")} · 未校准得分 ${item.optDouble("confidence")}")}
            }
        }
        Button(onClick={refresh++}){Text("刷新权限状态")}
    }
}

@Composable private fun Toggle(label:String,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp)){Text(label,Modifier.weight(1f));Switch(checked=enabled,onCheckedChange=onChange)}
}

@Composable fun BackupPage() {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val archive=remember{BackupArchive(context)}
    var password by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    var pending by remember{mutableStateOf<JSONObject?>(null)};var busy by remember{mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        if(uri!=null) scope.launch {busy=true;val secret=password.toCharArray();try {
            withContext(Dispatchers.IO){val bytes=archive.export(secret);context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("文件不可写")}
            message="已保存加密备份，请妥善保管口令"
        }catch(_:Exception){message="导出失败，原数据未改变"}finally{secret.fill('\u0000');busy=false}}
    }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) scope.launch {busy=true;val secret=password.toCharArray();try {
            pending=withContext(Dispatchers.IO){val bytes=context.contentResolver.openInputStream(uri)?.use{it.readNBytes(BackupArchive.MAX_BYTES+1)}?:error("文件不可读");archive.inspect(bytes,secret)}
            message="备份已解密并校验，请确认是否替换当前数据库"
        }catch(_:Exception){message="口令错误、备份损坏或版本不兼容；原数据未改变"}finally{secret.fill('\u0000');busy=false}}
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("备份与恢复",style=MaterialTheme.typography.headlineSmall)
        Text("导出任务、目标、账本和历史。备份使用口令加密；不包含 API Key、通知原文、截图、模型文件或采集授权。口令无法找回。")
        OutlinedTextField(password,{password=it;pending=null},label={Text("备份口令（至少12字符）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy&&password.length>=12,onClick={export.launch("personal-time-agent.ptabak")}){Text("导出加密备份")}
        Button(enabled=!busy&&password.length>=12,onClick={restore.launch(arrayOf("*/*"))}){Text("选择备份并预览")}
        pending?.let { value ->
            val tables=value.getJSONObject("tables")
            Text("将替换为：${tables.getJSONArray("tasks").length()} 个任务，${tables.getJSONArray("ledger").length()} 条时间记录。当前数据库会先保存为本机加密恢复副本。恢复后所有模块关闭，需重新开启提醒。")
            Button(enabled=!busy,onClick={scope.launch{busy=true;val secret=password.toCharArray();try {val count=archive.restore(value,secret);pending=null;message="已恢复 $count 条记录，采集与提醒保持关闭"}catch(_:Exception){message="恢复失败；请保留本机恢复副本"}finally{secret.fill('\u0000');busy=false}}}){Text("确认替换当前数据")}
            TextButton(onClick={pending=null}){Text("取消")}
        }
        Text(message)
    }
}
