package io.github.cyyzh.personaltimeagent.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import io.github.cyyzh.personaltimeagent.R
import io.github.cyyzh.personaltimeagent.data.AgentDatabase
import io.github.cyyzh.personaltimeagent.data.AgentRepository
import io.github.cyyzh.personaltimeagent.data.TaskEntity
import io.github.cyyzh.personaltimeagent.domain.ProposedChange
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val WIDGET_ACTION = "io.github.cyyzh.personaltimeagent.WIDGET_ACTION"
private const val DONE = "done"
private const val DEFER = "defer"
private fun formatted(ms: Long?) = ms?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "未排程"

object WidgetSurface {
    private fun prefs(c: Context) = c.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
    private fun ids(c: Context) = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, AgentWidget::class.java))
    fun refresh(c: Context) {
        val tasks = runBlocking { AgentDatabase.get(c).taskDao().open() }
        val sorted = tasks.sortedWith(compareBy<TaskEntity> { it.scheduledStart ?: it.deadline ?: Long.MAX_VALUE }.thenBy { it.id })
        val current = sorted.getOrNull(0); val next = sorted.getOrNull(1)
        val snapshot = JSONObject().put("at", System.currentTimeMillis())
            .put("id", current?.id ?: JSONObject.NULL).put("revision", current?.revision ?: -1)
            .put("title", current?.title ?: "暂无任务").put("time", formatted(current?.scheduledStart ?: current?.deadline))
            .put("next", next?.title ?: "暂无下一项")
        if (prefs(c).edit().putString("snapshot", snapshot.toString()).commit()) ids(c).forEach { render(c, it) }
    }
    fun render(c: Context, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(c)
        if (widgetId !in ids(c)) return
        val options = manager.getAppWidgetOptions(widgetId)
        val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110) < 180 ||
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) < 105
        val s = JSONObject(prefs(c).getString("snapshot", "{}"))
        val taskId = s.optString("id").takeUnless { it.isBlank() || it == "null" }
        val revision = s.optInt("revision", -1)
        val views = RemoteViews(c.packageName, if (compact) R.layout.agent_widget_compact else R.layout.agent_widget_full)
        views.setTextViewText(R.id.widget_header, "个人时间 Agent")
        views.setTextViewText(R.id.widget_current, "${s.optString("title", "正在加载")} · ${s.optString("time", "")}")
        if (!compact) {
            views.setTextViewText(R.id.widget_next, "下一项：${s.optString("next", "暂无")}")
            views.setTextViewText(R.id.widget_hint, "建议：先完成当前计划")
            views.setTextViewText(R.id.widget_stamp, "更新 ${formatted(s.optLong("at").takeIf { it > 0 })}")
        }
        views.setOnClickPendingIntent(R.id.widget_done, action(c, widgetId, DONE, taskId, revision))
        views.setOnClickPendingIntent(R.id.widget_defer, action(c, widgetId, DEFER, taskId, revision))
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
        if (launch != null) {
            views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(c, widgetId * 2, launch.putExtra("quick_add", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(c, widgetId * 2 + 1, c.packageManager.getLaunchIntentForPackage(c.packageName)!!, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.updateAppWidget(widgetId, views)
    }
    private fun action(c: Context, widgetId: Int, command: String, id: String?, revision: Int): PendingIntent {
        val intent = Intent(c, WidgetActionReceiver::class.java).setAction(WIDGET_ACTION)
            .setData(Uri.parse("pta://widget/$widgetId/$command/${Uri.encode(id ?: "none")}/$revision"))
            .putExtra("widget", widgetId).putExtra("command", command).putExtra("id", id).putExtra("revision", revision)
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun valid(c: Context, widgetId: Int) = widgetId in ids(c)
}

class AgentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetSurface.render(context, it) }
        val pending = goAsync(); Thread { try { WidgetSurface.refresh(context) } finally { pending.finish() } }.start()
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = WidgetSurface.render(context, id)
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widget = intent.getIntExtra("widget", -1)
        val command = intent.getStringExtra("command") ?: return
        val id = intent.getStringExtra("id") ?: return
        val revision = intent.getIntExtra("revision", -1)
        if (intent.action != WIDGET_ACTION || command !in setOf(DONE, DEFER) || !WidgetSurface.valid(context, widget) ||
            intent.data != Uri.parse("pta://widget/$widget/$command/${Uri.encode(id)}/$revision")) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    val dao = AgentDatabase.get(context).taskDao()
                    val task = dao.get(id) ?: return@runBlocking
                    if (task.status != "OPEN" || task.revision != revision) return@runBlocking
                    if (command == DONE) dao.upsert(task.copy(status = "DONE", revision = revision + 1, updatedAt = System.currentTimeMillis()))
                    else if (task.scheduledStart != null && task.scheduledEnd != null)
                        AgentRepository(context).propose(ProposedChange("SCHEDULE_TASK", id, revision,
                            start = task.scheduledStart + 3600000L, end = task.scheduledEnd + 3600000L),
                            "推迟任务一小时：${task.title}", source = "USER")
                }
                ReminderPlatform.reconcileFromDatabase(context)
                WidgetSurface.refresh(context)
            } finally { pending.finish() }
        }.start()
    }
}
