package io.github.cyyzh.personaltimeagent.platform

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import io.github.cyyzh.personaltimeagent.data.AgentDatabase
import io.github.cyyzh.personaltimeagent.data.ModuleKey
import io.github.cyyzh.personaltimeagent.data.ModuleSettings
import io.github.cyyzh.personaltimeagent.data.TaskEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val ALARM_ACTION = "io.github.cyyzh.personaltimeagent.REMINDER_ALARM"
private const val ACTION_TASK = "io.github.cyyzh.personaltimeagent.REMINDER_TASK_ACTION"
private const val CHANNEL = "pta_reminders"
private const val RECONCILE = "pta_reminder_reconcile"

private object ReminderState {
    private fun prefs(c: Context) = c.getSharedPreferences("reminder_expectations", Context.MODE_PRIVATE)
    @Synchronized fun all(c: Context): MutableMap<String, JSONObject> {
        val arr = JSONArray(prefs(c).getString("expectations", "[]"))
        return (0 until arr.length()).associate { val row = arr.getJSONObject(it); row.getString("id") to row }.toMutableMap()
    }
    @Synchronized fun write(c: Context, rows: Map<String, JSONObject>): Boolean {
        val arr = JSONArray().apply { rows.values.forEach(::put) }
        return prefs(c).edit().putString("expectations", arr.toString()).commit()
    }
    @Synchronized fun mark(c: Context, id: String, version: Long, status: String): Boolean {
        val rows = all(c); val row = rows[id] ?: return false
        if (row.optLong("version") != version || row.optString("status") != "active") return false
        row.put("status", status)
        return write(c, rows)
    }
}

object ReminderPlatform {
    private fun pi(c: Context, id: String, version: Long): PendingIntent {
        val intent = Intent(c, ReminderAlarmReceiver::class.java).setAction(ALARM_ACTION)
            .setData(Uri.parse("pta://reminder/${Uri.encode(id)}/$version"))
            .putExtra("id", id).putExtra("version", version)
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun manager(c: Context) = c.getSystemService(AlarmManager::class.java)
    private fun cancel(c: Context, row: JSONObject) {
        val pending = pi(c, row.getString("id"), row.getLong("version"))
        manager(c).cancel(pending); pending.cancel()
        c.getSystemService(NotificationManager::class.java).cancel(row.getString("id"), 1)
    }
    private fun schedule(c: Context, row: JSONObject): Boolean = try {
        val due = row.getLong("due")
        val pending = pi(c, row.getString("id"), row.getLong("version"))
        if (PlatformGates.exactAlarmPermission(c)) manager(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        else manager(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        true
    } catch (_: Exception) { false }

    /** Called after task mutations, at boot, and by periodic WorkManager reconciliation. */
    @Synchronized fun reconcile(c: Context, tasks: List<TaskEntity>) {
        val old = ReminderState.all(c)
        val enabled = ModuleSettings(c).enabled(ModuleKey.REMINDERS)
        val desired = if (enabled) tasks.filter { it.status == "OPEN" && it.reminderAt != null && it.reminderAt > System.currentTimeMillis() }
            .associateBy { it.id } else emptyMap()
        val next = old.toMutableMap()
        for ((id, prior) in old) if (id !in desired || prior.optLong("due") != desired[id]?.reminderAt ||
            prior.optInt("taskRevision") != desired[id]?.revision) {
            next[id] = JSONObject(prior.toString()).put("status", "cancelled").put("version", prior.optLong("version") + 1)
        }
        for ((id, task) in desired) {
            val prior = old[id]
            if (prior != null && prior.optLong("due") == task.reminderAt && prior.optInt("taskRevision") == task.revision &&
                prior.optString("status") in setOf("active", "fired")) continue
            next[id] = JSONObject().put("id", id).put("title", task.title).put("due", task.reminderAt)
                .put("version", (prior?.optLong("version") ?: 0L) + 1).put("taskRevision", task.revision).put("status", "active")
                .put("registeredAt", JSONObject.NULL).put("delivery", "pending")
        }
        if (!ReminderState.write(c, next)) return
        for ((id, prior) in old) if (next[id]?.optLong("version") != prior.optLong("version")) cancel(c, prior)
        for ((_, row) in next) if (row.optString("status") == "active") {
            if (schedule(c, row)) row.put("registeredAt", System.currentTimeMillis())
            else row.put("status", "blocked")
        }
        ReminderState.write(c, next)
    }
    fun reconcileFromDatabase(c: Context) = runBlocking { reconcile(c, AgentDatabase.get(c).taskDao().open()) }
    fun startReconciliation(c: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderReconcileWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(RECONCILE, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun stop(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(RECONCILE)
        reconcile(c, emptyList())
    }
    fun status(c: Context): List<JSONObject> = ReminderState.all(c).values.toList()
    @Synchronized internal fun fire(c: Context, id: String, version: Long) {
        val row = ReminderState.all(c)[id] ?: return
        if (row.optLong("version") != version || row.optString("status") != "active" ||
            !ModuleSettings(c).enabled(ModuleKey.REMINDERS)) return
        val task = runBlocking { AgentDatabase.get(c).taskDao().get(id) } ?: return
        if (task.status != "OPEN" || task.revision != row.optInt("taskRevision") || task.reminderAt != row.optLong("due")) return
        if (!ReminderState.mark(c, id, version, "fired")) return
        val latest = ReminderState.all(c); latest[id]?.put("delivery", if (PlatformGates.notificationDeliveryEnabled(c)) "submitted" else "permission_denied")
        ReminderState.write(c, latest)
        if (!PlatformGates.notificationDeliveryEnabled(c)) return
        val notifications = c.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val builder = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(row.optString("title")).setContentText("计划提醒 · 可完成或推迟")
            .setAutoCancel(true)
        for ((label, action) in listOf("完成" to "done", "推迟10分钟" to "defer")) {
            val intent = Intent(c, ReminderActionReceiver::class.java).setAction(ACTION_TASK)
                .setData(Uri.parse("pta://reminder-action/${Uri.encode(id)}/$version/$action"))
                .putExtra("id", id).putExtra("version", version).putExtra("task_action", action)
            val pending = PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, label, pending).build())
        }
        try { notifications.notify(id, 1, builder.build()) } catch (_: Exception) {
            val failed = ReminderState.all(c); failed[id]?.put("delivery", "notify_failed"); ReminderState.write(c, failed)
        }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val version = intent.getLongExtra("version", -1)
        if (intent.action != ALARM_ACTION || intent.data != Uri.parse("pta://reminder/${Uri.encode(id)}/$version")) return
        val pending = goAsync()
        Thread { try { ReminderPlatform.fire(context, id, version) } finally { pending.finish() } }.start()
    }
}
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            Thread { try { ReminderPlatform.reconcileFromDatabase(context); ReminderPlatform.startReconciliation(context) } finally { pending.finish() } }.start()
        }
    }
}
class ReminderReconcileWorker(c: Context, params: WorkerParameters) : Worker(c, params) {
    override fun doWork(): Result = try { ReminderPlatform.reconcileFromDatabase(applicationContext); Result.success() } catch (_: Exception) { Result.retry() }
}
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val version = intent.getLongExtra("version", -1)
        val action = intent.getStringExtra("task_action") ?: return
        if (intent.action != ACTION_TASK || action !in setOf("done", "defer") ||
            intent.data != Uri.parse("pta://reminder-action/${Uri.encode(id)}/$version/$action")) return
        if (ReminderPlatform.status(context).none { it.optString("id") == id && it.optLong("version") == version && it.optString("status") == "fired" }) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    val dao = AgentDatabase.get(context).taskDao()
                    val task = dao.get(id) ?: return@runBlocking
                    if (action == "done") dao.upsert(task.copy(status = "DONE", revision = task.revision + 1, updatedAt = System.currentTimeMillis()))
                    else dao.upsert(task.copy(reminderAt = System.currentTimeMillis() + 600000L, revision = task.revision + 1, updatedAt = System.currentTimeMillis()))
                }
                ReminderPlatform.reconcileFromDatabase(context)
            } finally { pending.finish() }
        }.start()
    }
}
