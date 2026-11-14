package io.github.cyyzh.personaltimeagent.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import io.github.cyyzh.personaltimeagent.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, authenticated backup. Keys, consent, notification bodies and model files are excluded. */
class BackupArchive(private val context: Context) {
    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        private val tables = listOf("tasks", "slots", "goals", "habits", "habit_occurrences", "ledger",
            "proposals", "audit", "inbox", "memories", "usage_samples")
        private val magic = "PTABAK01".toByteArray()
    }
    private val db = AgentDatabase.get(context)
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        require(password.size >= 12) { "备份口令至少 12 个字符" }
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv)); updateAAD(magic)
        } } finally { derived.fill(0) }
    }
    private suspend fun snapshot(): JSONObject = db.withTransaction {
        val data = JSONObject()
        tables.forEach { table ->
            val rows = JSONArray()
            db.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                while(cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { index, name -> row.put(name, when(cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        else -> error("不支持的数据库字段")
                    }) }
                    if(table != "inbox" || !row.optString("source").startsWith("NOTIFICATION:")) rows.put(row)
                }
            }
            data.put(table, rows)
        }
        JSONObject().put("format",1).put("schema",1).put("createdAt",System.currentTimeMillis()).put("tables",data)
    }
    private fun encode(value: JSONObject, password: CharArray): ByteArray {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size < MAX_BYTES - 64) { "备份超出大小限制" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return try { magic + salt + iv + cipher(Cipher.ENCRYPT_MODE,password,salt,iv).doFinal(bytes) } finally { bytes.fill(0) }
    }
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.IO) { encode(snapshot(),password) }
    suspend fun inspect(bytes: ByteArray, password: CharArray): JSONObject = withContext(Dispatchers.IO) {
        require(bytes.size in 52..MAX_BYTES && bytes.copyOfRange(0,8).contentEquals(magic)) { "不是有效的加密备份" }
        val plain = cipher(Cipher.DECRYPT_MODE,password,bytes.copyOfRange(8,24),bytes.copyOfRange(24,36)).doFinal(bytes.copyOfRange(36,bytes.size))
        val document = try { JSONObject(String(plain,Charsets.UTF_8)) } finally { plain.fill(0) }
        validate(document)
        document
    }
    private fun columns(table: String): Set<String> = db.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet { while(cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }
    private fun validate(document: JSONObject) {
        require(document.getInt("format")==1 && document.getInt("schema")==1) { "不支持的备份版本" }
        val data=document.getJSONObject("tables")
        require(data.keys().asSequence().toSet()==tables.toSet()) { "备份数据表不完整" }
        var count=0
        tables.forEach { table ->
            val rows=data.getJSONArray(table); count+=rows.length(); require(count<=100_000)
            val fields=columns(table); val ids=mutableSetOf<String>()
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                require(row.keys().asSequence().toSet()==fields) { "备份字段不兼容" }
                require(row.getString("id").isNotBlank() && ids.add(row.getString("id"))) { "重复或空记录 ID" }
                fields.forEach { key -> val value=row.get(key); require(value===JSONObject.NULL || value is String || value is Number) }
                if(table=="ledger" || table=="slots") require(row.getLong("endAt")>row.getLong("startAt"))
                if(table=="tasks") require(row.getString("title").isNotBlank() && row.getInt("estimatedMinutes") in 1..10080)
            }
        }
    }
    /** Only invoked after the UI has previewed the archive and obtained explicit replacement consent. */
    suspend fun restore(document: JSONObject, password: CharArray): Int = withContext(Dispatchers.IO) {
        validate(document)
        val previous=encode(snapshot(),password)
        File(context.filesDir,"before-restore.ptabak").writeBytes(previous)
        // Never restore data collection consent from an archive.
        listOf(PlatformGates.USAGE,PlatformGates.NOTIFICATIONS,PlatformGates.ACTIVITY,PlatformGates.VISION,PlatformGates.SCREENSHOTS).forEach {
            PlatformGates.set(context,it,false)
        }
        ModuleKey.entries.forEach { ModuleSettings(context).setEnabled(it,false) }
        ReminderPlatform.stop(context)
        val count=db.withTransaction {
            val sql=db.openHelper.writableDatabase
            tables.reversed().forEach { sql.execSQL("DELETE FROM `$it`") }
            var total=0
            tables.forEach { table ->
                val rows=document.getJSONObject("tables").getJSONArray(table)
                val fields=columns(table).toList()
                val statement="INSERT INTO `$table` (${fields.joinToString(","){"`$it`"}}) VALUES (${fields.joinToString(","){"?"}})"
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i)
                    sql.execSQL(statement,fields.map { row.get(it).takeUnless { v -> v===JSONObject.NULL } }.toTypedArray())
                    total++
                }
            }
            // Old proposals must be reviewed afresh against restored state.
            sql.execSQL("UPDATE proposals SET state='REJECTED' WHERE state='PENDING'")
            total
        }
        WidgetSurface.refresh(context)
        count
    }
}
