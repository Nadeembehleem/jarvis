package com.jarvis.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

interface Callbacks {
    fun token(s: String)
    fun sentence(s: String)
    fun tool(name: String)
    fun confirm(desc: String)
    fun done()
}

private val YES = setOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "haan", "han", "ha", "haa", "हाँ", "हां", "हा", "ठीक", "कर")
private val NO = setOf("no", "nope", "cancel", "nahi", "nahin", "na", "नहीं", "नही", "मत", "stop")
private val CLEAN = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
private val SENT = Regex("(.+?[.!?।\\n])\\s+", RegexOption.DOT_MATCHES_ALL)
private val TOOL_RE = Regex("<tool>\\s*(\\{.*\\})\\s*(</tool>)?", RegexOption.DOT_MATCHES_ALL)

/** On-device Gemma (LiteRT-LM) + prompt-based tool calling + confirmations. */
class Brain(private val ctx: Context, private val tools: Tools, private val store: Store, private val prefs: android.content.SharedPreferences) {
    private val exec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
    private var engine: Engine? = null
    private var conv: Conversation? = null
    private var turns = 0
    private var job: Job? = null
    @Volatile var ready = false
    @Volatile var busy = false
    @Volatile var visionOk = false
    @Volatile var pending: Pair<String, JSONObject>? = null
    val modelFile: File get() = File(ctx.filesDir, "model.litertlm")

    private fun systemPrompt(): String = """
You are JARVIS, a concise, witty, loyal personal assistant running fully on the user's phone. Answer in 1-3 short sentences.
Reply in the user's language. If the user speaks Hindi or Hinglish, answer in Hindi using Devanagari script. No markdown, no emojis (replies are spoken aloud).
To use a tool, reply with ONLY this and nothing else: <tool>{"name":"TOOL_NAME","args":{"arg":"value"}}</tool>
You will then receive TOOL_RESULT. For multi-step requests call tools one after another until done, then answer briefly.
Save lasting facts about the user (name, job, family, preferences, goals) with the remember tool right away, silently.
send_sms, make_call and forget need the user's confirmation: call the tool, then ask the user to confirm, stating exactly what will happen.
Use find_contact before messaging or calling someone by name. Use search_notes for questions about the user's notes or work.
Tools:
$TOOL_SPEC
""".trimIndent()

    private fun newConversation() {
        conv?.close()
        conv = engine!!.createConversation(ConversationConfig(systemInstruction = Contents.of(systemPrompt())))
        turns = 0
    }

    fun load(done: (String?) -> Unit) {
        scope.launch {
            try {
                ready = false
                conv?.close(); conv = null
                engine?.close(); engine = null
                val backend = if (prefs.getBoolean("gpu", false)) Backend.GPU() else Backend.CPU()
                fun cfg(vision: Boolean) =
                    if (vision) EngineConfig(modelPath = modelFile.path, backend = backend, visionBackend = Backend.CPU(), cacheDir = ctx.cacheDir.path)
                    else EngineConfig(modelPath = modelFile.path, backend = backend, cacheDir = ctx.cacheDir.path)
                var e = Engine(cfg(true))
                try {
                    e.initialize()
                    visionOk = true
                } catch (ex: Exception) {
                    // text-only model: retry without the vision encoder
                    visionOk = false
                    e = Engine(cfg(false))
                    e.initialize()
                }
                engine = e
                newConversation()
                ready = true
                done(null)
            } catch (e: Exception) {
                done(e.message ?: e.toString())
            }
        }
    }

    fun cancel() { scope.launch { try { conv?.cancelProcess() } catch (e: Exception) { } } }

    fun close() { scope.launch { try { if (busy) { conv?.cancelProcess(); delay(400) }; conv?.close(); engine?.close() } catch (e: Exception) { } } }

    fun chat(text: String, cb: Callbacks, imagePath: String? = null) {
        job = scope.launch {
            busy = true
            try { run(text, cb, imagePath) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { cb.token("\n[error: ${e.message}]") }
            finally { busy = false; cb.done() }
        }
    }

    private fun norm(t: String) = t.lowercase().replace(CLEAN, "").trim()

    private fun emit(cb: Callbacks, s: String) { cb.token(s); cb.sentence(s) }

    private suspend fun run(text: String, cb: Callbacks, imagePath: String? = null) {
        if (imagePath != null && !visionOk) {
            emit(cb, "This model can't see images. Download Gemma 4 E2B in Settings.")
            return
        }
        val p = pending
        if (p != null) {
            pending = null
            val first = norm(text).split(" ").firstOrNull() ?: ""
            if (first in YES) { emit(cb, "Done. " + tools.run(p.first, p.second)); return }
            if (first in NO) { emit(cb, "Cancelled."); return }
        }
        if (turns >= 10) newConversation()
        turns++
        val mem = store.memories().takeLast(25)
        val now = SimpleDateFormat("EEE dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        var msg = "[Now: $now]" + (if (mem.isNotEmpty()) " [Known about user: ${mem.joinToString("; ")}]" else "") + "\n" + text
        var hops = 0
        var image = imagePath
        while (true) {
            val full = stream(msg, cb, image)
            image = null
            val call = parseTool(full) ?: break
            if (hops++ >= 5) break
            cb.tool(call.first)
            if (call.first in tools.risky) {
                pending = call
                msg = "TOOL_RESULT: NOT executed yet. Ask the user to confirm, stating exactly what will happen."
                cb.confirm("${call.first}(${call.second.keys().asSequence().joinToString(", ") { k -> call.second.optString(k) }})")
            } else {
                msg = "TOOL_RESULT(${call.first}): " + tools.run(call.first, call.second)
            }
        }
    }

    private fun parseTool(s: String): Pair<String, JSONObject>? {
        val m = TOOL_RE.find(s) ?: return null
        return try {
            val j = JSONObject(m.groupValues[1])
            Pair(j.getString("name"), j.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) { null }
    }

    private suspend fun stream(msg: String, cb: Callbacks, img: String? = null): String {
        val sb = StringBuilder()
        var spoken = 0
        var toolMode = false
        val flow = if (img != null) conv!!.sendMessageAsync(Message.of(Content.ImageFile(img), Content.Text(msg))) else conv!!.sendMessageAsync(msg)
        flow.collect { m ->
            val chunk = m.toString()
            sb.append(chunk)
            if (!toolMode && sb.toString().trimStart().startsWith("<")) toolMode = true
            if (!toolMode) {
                cb.token(chunk)
                val s = sb.toString().substring(spoken)
                var rest = s
                while (true) {
                    val mm = SENT.find(rest) ?: break
                    cb.sentence(mm.groupValues[1])
                    rest = rest.substring(mm.range.last + 1)
                }
                spoken = sb.length - rest.length
            }
        }
        val s = sb.toString()
        if (!toolMode && spoken < s.length && s.substring(spoken).isNotBlank()) cb.sentence(s.substring(spoken))
        return s
    }
}

object ModelStore {
    /** Resumable download. Returns an error message or null. */
    fun download(url: String, token: String, dest: File, progress: (Long, Long) -> Unit): String? {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        var u = url
        var hops = 0
        while (hops++ < 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            if (token.isNotBlank() && u.contains("huggingface.co")) c.setRequestProperty("Authorization", "Bearer $token")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) { u = c.getHeaderField("Location") ?: return "Bad redirect"; c.disconnect(); continue }
            if (code == 401 || code == 403) return "HTTP $code: accept the model licence on huggingface.co and paste a read token in Settings."
            if (code == 416) { part.renameTo(dest); return null }
            if (code != 200 && code != 206) return "HTTP $code"
            if (code == 200) have = 0L
            val total = have + c.contentLengthLong
            var got = have
            var last = 0L
            c.inputStream.use { ins ->
                FileOutputStream(part, code == 206).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var n = ins.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        got += n
                        if (got - last > 3_000_000) { progress(got, total); last = got }
                        n = ins.read(buf)
                    }
                }
            }
            part.renameTo(dest)
            return null
        }
        return "Too many redirects"
    }

    fun copyFrom(ctx: Context, uri: Uri, dest: File, progress: (Long, Long) -> Unit) {
        var size = 0L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) size = it.getLong(0) }
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                var last = 0L
                var n = ins.read(buf)
                while (n > 0) {
                    out.write(buf, 0, n)
                    got += n
                    if (got - last > 5_000_000) { progress(got, size); last = got }
                    n = ins.read(buf)
                }
            }
        }
    }
}
