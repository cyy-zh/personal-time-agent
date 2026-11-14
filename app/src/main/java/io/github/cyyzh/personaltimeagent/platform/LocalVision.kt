package io.github.cyyzh.personaltimeagent.platform

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

data class VisionModelState(val installed: Boolean, val bytes: Long, val running: Boolean, val enabled: Boolean)

/** Model must be a verified, multimodal LiteRT-LM file selected by the user. */
class LocalVisionManager(private val context: Context) : VisionFrameConsumer {
    private val maxModelBytes = 5L * 1024 * 1024 * 1024
    companion object {
        @Volatile private var instance: LocalVisionManager? = null
        fun get(context: Context): LocalVisionManager = instance ?: synchronized(this) {
            instance ?: LocalVisionManager(context.applicationContext).also { instance = it }
        }
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)
    private val busy = AtomicBoolean(false)
    private val modelFile get() = File(context.filesDir, "models/vision.litertlm")
    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null
    fun state() = VisionModelState(modelFile.isFile, modelFile.takeIf { it.isFile }?.length() ?: 0,
        engine != null, PlatformGates.enabled(context, PlatformGates.VISION))
    fun enable(): Boolean {
        if (!modelFile.isFile || !PlatformGates.enabled(context, PlatformGates.VISION)) return false
        ActivityCapture.consumer = this
        return true
    }
    fun disable() {
        generation.incrementAndGet()
        ActivityCapture.consumer = null
        executor.execute { engine?.close(); engine = null; loadedPath = null }
    }
    fun delete(): Boolean {
        disable()
        return try { executor.submit<Boolean> { modelFile.delete() || !modelFile.exists() }.get() } catch (_: Exception) { false }
    }
    /** Caller must obtain an authorized HTTPS URL and expected SHA-256 from a trusted model listing. */
    fun install(url: String, expectedSha256: String, onDone: (Boolean) -> Unit) {
        require(url.startsWith("https://") && expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
        val token = generation.incrementAndGet()
        ActivityCapture.consumer = null
        executor.execute {
            var ok = false
            val target = modelFile
            val tmp = File(target.parentFile, "vision.download")
            try {
                engine?.close(); engine = null; loadedPath = null
                target.parentFile?.mkdirs()
                var current = URL(url)
                var connection: HttpURLConnection? = null
                for (redirect in 0..3) {
                    if (current.protocol != "https") error("HTTPS required")
                    val opened = (current.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 30_000
                    }
                    val status = opened.responseCode
                    if (status in 300..399) {
                        val location = opened.getHeaderField("Location") ?: error("Redirect missing location")
                        opened.disconnect()
                        current = URL(current, location)
                        if (redirect == 3) error("Too many redirects")
                    } else {
                        if (status != 200) error("HTTP $status")
                        if (opened.contentLengthLong > maxModelBytes) error("Model too large")
                        connection = opened; break
                    }
                }
                val source = connection ?: error("No download")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                source.inputStream.use { input -> tmp.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (generation.get() != token) error("Cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxModelBytes) error("Model too large")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                source.disconnect()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual.equals(expectedSha256, ignoreCase = true) && tmp.length() > 0 && generation.get() == token) {
                    val backup = File(target.parentFile, "vision.previous")
                    backup.delete()
                    val hadOld = target.exists()
                    if (!hadOld || target.renameTo(backup)) {
                        ok = tmp.renameTo(target)
                        if (!ok && hadOld) backup.renameTo(target)
                        if (ok) backup.delete()
                    }
                }
            } catch (_: Exception) { } finally { tmp.delete() }
            onDone(ok)
        }
    }
    override fun analyze(packageName: String, bitmap: Bitmap, atMs: Long) {
        if (!PlatformGates.enabled(context, PlatformGates.VISION) || !PlatformGates.enabled(context, PlatformGates.SCREENSHOTS) ||
            !PlatformGates.enabled(context, PlatformGates.ACTIVITY) || !ActivityCapture.allowed(context, packageName)) return
        if(!busy.compareAndSet(false,true)) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run {busy.set(false);return}
        val token = generation.get()
        val visionEpoch = PlatformGates.epoch(context, PlatformGates.VISION)
        val screenshotEpoch = PlatformGates.epoch(context, PlatformGates.SCREENSHOTS)
        executor.execute {
            try {
                if (generation.get() != token || !PlatformGates.stillEnabled(context, PlatformGates.VISION, visionEpoch) ||
                    !PlatformGates.stillEnabled(context, PlatformGates.SCREENSHOTS, screenshotEpoch)) return@execute
                val model = modelFile
                if (!model.isFile) return@execute
                if (engine == null || loadedPath != model.absolutePath) {
                    engine?.close()
                    val created = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), visionBackend = Backend.CPU()))
                    created.initialize()
                    engine = created; loadedPath = model.absolutePath
                }
                val bytes = ByteArrayOutputStream().also { copy.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                val response = engine!!.createConversation().use { conversation ->
                    conversation.sendMessage(Contents.of(
                        Content.Text("Classify only this app screen. Return JSON with category one of chat, article, short_video, video, unknown and confidence 0..1. If uncertain return unknown. No personal text."),
                        Content.ImageBytes(bytes))).contents.contents.filterIsInstance<Content.Text>().joinToString("\n") { it.text }
                }
                if (generation.get() != token || !PlatformGates.stillEnabled(context, PlatformGates.VISION, visionEpoch) ||
                    !PlatformGates.stillEnabled(context, PlatformGates.SCREENSHOTS, screenshotEpoch)) return@execute
                saveResult(packageName, atMs, response)
            } catch (_: Exception) { /* Engine/model errors degrade to unknown without logging frame content. */ }
            finally { copy.recycle(); busy.set(false) }
        }
    }
    private fun saveResult(pkg: String, at: Long, raw: String) {
        val parsed = try { JSONObject(raw.substringAfter('{').substringBeforeLast('}').let { "{$it}" }) } catch (_: Exception) { JSONObject() }
        val category = parsed.optString("category").takeIf { it in setOf("chat", "article", "short_video", "video") } ?: "unknown"
        val confidence = parsed.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        // Model self-confidence has not been calibrated against labeled ColorOS data.
        val candidateCategory = if (confidence >= 0.8) category else "unknown"
        val p = context.getSharedPreferences("vision_results", 0)
        val arr = JSONArray(p.getString("results", "[]"))
        arr.put(JSONObject().put("package", pkg).put("at", at).put("category", "unknown")
            .put("candidateCategory", candidateCategory)
            .put("confidence", confidence).put("confidenceCalibrated",false).put("source", "litertlm"))
        val bounded = JSONArray()
        for (i in maxOf(0, arr.length() - 1000) until arr.length()) bounded.put(arr.getJSONObject(i))
        p.edit().putString("results", bounded.toString()).commit()
    }
}
