package io.github.cyyzh.personaltimeagent.platform

import android.app.Notification
import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val NLS_KEY = "pta_notifications_aes_v1"
private val sourcePackages = setOf("com.tencent.mm", "com.tencent.mobileqq")

/** Body consent is independent of listener permission and module switch. */
object NotificationCaptureStore {
    private fun prefs(c: Context) = c.getSharedPreferences("notification_capture", Context.MODE_PRIVATE)
    fun bodyConsent(c: Context) = prefs(c).getBoolean("body_consent", false)
    fun retentionMs(c: Context) = prefs(c).getLong("retention_ms", 86400000L)
    @Synchronized fun setRetentionMs(c: Context, value: Long): Boolean {
        require(value in setOf(3600000L, 86400000L, 604800000L))
        return prefs(c).edit().putLong("retention_ms", value).commit()
    }
    @Synchronized fun setBodyConsent(c: Context, value: Boolean): Boolean {
        val ok = prefs(c).edit().putBoolean("body_consent", value).commit()
        if(ok) PlatformGates.bump(c, PlatformGates.NOTIFICATIONS)
        return ok
    }
    fun allowed(c: Context, pkg: String) = pkg in sourcePackages && prefs(c).getBoolean("allow_$pkg", false)
    @Synchronized fun setAllowed(c: Context, pkg: String, value: Boolean): Boolean {
        require(pkg in sourcePackages)
        val ok = prefs(c).edit().putBoolean("allow_$pkg", value).commit()
        if (ok) PlatformGates.bump(c, PlatformGates.NOTIFICATIONS)
        return ok
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(NLS_KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(NLS_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private fun digest(c: Context, value: String): String {
        val p = prefs(c)
        var salt = p.getString("salt", null)
        if (salt == null) {
            val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
            salt = Base64.encodeToString(random, Base64.NO_WRAP)
            check(p.edit().putString("salt", salt).commit())
        }
        return MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun records(c: Context): List<JSONObject> = try {
        val raw = prefs(c).getString("records", null) ?: return emptyList()
        val array = JSONArray(decrypt(raw))
        val cutoff = System.currentTimeMillis() - retentionMs(c)
        val all = (0 until array.length()).map { array.getJSONObject(it) }
        val fresh = all.filter { it.optLong("lastObserved", it.optLong("observedAt")) >= cutoff }
        if (fresh.size != all.size) {
            val next = JSONArray().apply { fresh.forEach(::put) }
            prefs(c).edit().putString("records", encrypt(next.toString())).commit()
        }
        fresh
    } catch (_: Exception) { emptyList() }
    @Synchronized fun deleteAll(c: Context): Boolean {
        PlatformGates.bump(c, PlatformGates.NOTIFICATIONS)
        val ok = prefs(c).edit().remove("records").remove("salt").putBoolean("body_consent", false).commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(NLS_KEY) }
        return ok
    }
    @Synchronized fun save(c: Context, sbn: StatusBarNotification, event: String, epoch: Long, reason: Int? = null) {
        val pkg = sbn.packageName
        if (!PlatformGates.enabled(c, PlatformGates.NOTIFICATIONS) || !allowed(c, pkg) ||
            !PlatformGates.notificationListenerPermission(c, AgentNotificationListener::class.java)) return
        val extras = sbn.notification.extras
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()
        val messages = try {
            @Suppress("DEPRECATION")
            val parcels = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (parcels == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) emptyList()
            else Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcels)
                .mapNotNull { it.text?.toString() }
        } catch (_: Exception) { emptyList() }
        val body = if (bodyConsent(c)) listOfNotNull(
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            lines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ).firstOrNull { it.isNotBlank() } else null
        val bodyState = when { body != null -> "body"; bodyConsent(c) -> "no_body"; else -> "metadata_only" }
        val id = digest(c, sbn.key)
        val signature = digest(c, "$event|$id|${body ?: ""}|${sbn.postTime}|${reason ?: -1}")
        val old = records(c)
        val duplicate = old.indexOfFirst { it.optString("signature") == signature }
        if (duplicate >= 0) {
            old[duplicate].put("lastObserved", System.currentTimeMillis())
                .put("duplicateCount", old[duplicate].optInt("duplicateCount") + 1)
        }
        val version = try {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(pkg, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unavailable" }
        val row = JSONObject().put("source", pkg).put("notificationId", id).put("signature", signature)
            .put("event", event).put("removedReason", reason ?: JSONObject.NULL)
            .put("postTime", sbn.postTime).put("observedAt", System.currentTimeMillis())
            .put("firstObserved", System.currentTimeMillis()).put("lastObserved", System.currentTimeMillis())
            .put("duplicateCount", 0).put("sourceVersion", version)
            .put("bodyState", bodyState).put("body", body ?: JSONObject.NULL)
            .put("titlePresent", extras?.containsKey(Notification.EXTRA_TITLE) == true)
            .put("textPresent", extras?.containsKey(Notification.EXTRA_TEXT) == true)
            .put("bigTextPresent", extras?.containsKey(Notification.EXTRA_BIG_TEXT) == true)
            .put("textLinesCount", lines.size).put("messagesCount", messages.size)
        val cutoff = System.currentTimeMillis() - retentionMs(c)
        val kept = (old.filter { it.optLong("lastObserved", it.optLong("observedAt")) >= cutoff } +
            if (duplicate >= 0) emptyList() else listOf(row)).takeLast(500)
        val array = JSONArray().apply { kept.forEach(::put) }
        if (!PlatformGates.stillEnabled(c, PlatformGates.NOTIFICATIONS, epoch) || !allowed(c, pkg) ||
            !PlatformGates.notificationListenerPermission(c, AgentNotificationListener::class.java)) return
        check(prefs(c).edit().putString("records", encrypt(array.toString())).commit())
    }
}

class AgentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn, "posted")
    override fun onNotificationRemoved(sbn: StatusBarNotification) = capture(sbn, "removed")
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) = capture(sbn, "removed", reason)
    private fun capture(sbn: StatusBarNotification, event: String, reason: Int? = null) {
        val epoch = PlatformGates.epoch(this, PlatformGates.NOTIFICATIONS)
        if (!PlatformGates.stillEnabled(this, PlatformGates.NOTIFICATIONS, epoch)) return
        try { NotificationCaptureStore.save(this, sbn, event, epoch, reason) } catch (_: Exception) { /* No private text in logs. */ }
    }
}
