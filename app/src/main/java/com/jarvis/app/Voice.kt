package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val DEV = Regex("[\\u0900-\\u097F]")

/** Speaks sentence by sentence. Uses ElevenLabs if a key is set, else Android's offline TextToSpeech. */
class Speaker(private val ctx: Context, private val prefs: SharedPreferences) {
    private var tts: TextToSpeech? = null
    private val pool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    @Volatile private var gen = 0
    private val pending = AtomicInteger(0)
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var mp: MediaPlayer? = null
    val busy: Boolean get() = pending.get() > 0

    init {
        tts = TextToSpeech(ctx) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { latch?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { latch?.countDown() }
        })
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\[.*?\\]\\(.*?\\)"), "").replace(Regex("[*_`#>~|]"), "")
            .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}]"), "").trim()

    fun say(text: String) {
        val t = clean(text)
        if (t.isBlank()) return
        val g = gen
        pending.incrementAndGet()
        pool.execute {
            try { if (g == gen) speakBlocking(t) } catch (e: Exception) { } finally { pending.decrementAndGet() }
        }
    }

    fun stop() {
        gen++
        tts?.stop()
        latch?.countDown()
        try { mp?.stop() } catch (e: Exception) { }
    }

    private fun speakBlocking(t: String) {
        val key = prefs.getString("el_key", "") ?: ""
        if (key.isNotBlank()) {
            try {
                val f = File(ctx.cacheDir, "v${System.nanoTime()}.mp3")
                eleven(t, key, prefs.getString("el_voice", "") ?: "", f)
                playFile(f)
                f.delete()
                return
            } catch (e: Exception) { /* fall back to local voice */ }
        }
        val l = CountDownLatch(1)
        latch = l
        tts?.setLanguage(if (DEV.containsMatchIn(t)) Locale("hi", "IN") else Locale("en", "IN"))
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
        l.await(60, TimeUnit.SECONDS)
    }

    private fun eleven(text: String, key: String, voice: String, out: File) {
        val v = voice.ifBlank { "21m00Tcm4TlvDq8ikWAM" }
        val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$v?output_format=mp3_44100_128").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.setRequestProperty("xi-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString().toByteArray()) }
        if (c.responseCode != 200) throw RuntimeException("ElevenLabs HTTP ${c.responseCode}")
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun playFile(f: File) {
        val l = CountDownLatch(1)
        latch = l
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(f.path)
            p.setOnCompletionListener { l.countDown() }
            p.setOnErrorListener { _, _, _ -> l.countDown(); true }
            p.prepare()
            p.start()
            l.await(120, TimeUnit.SECONDS)
        } finally {
            mp = null
            p.release()
        }
    }
}

/** Continuous (hands-free) or one-shot speech recognition via Android's recognizer (offline if the language pack is installed). */
class Listener(
    private val ctx: Context,
    private val onText: (String, Boolean) -> Unit,
    private val canListen: () -> Boolean,
    private val lang: () -> String
) {
    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var active = false
    private var oneShot = false
    @Volatile var listening = false

    fun start(one: Boolean) {
        oneShot = one
        active = true
        listen()
    }

    fun stop() {
        active = false
        listening = false
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        sr = null
    }

    private fun listen() {
        if (!active) return
        if (!canListen()) { h.postDelayed({ listen() }, 400); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { active = false; return }
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) {
                listening = false
                if (oneShot) active = false
                else h.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 400L)
            }
            override fun onResults(b: Bundle?) {
                listening = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                val one = oneShot
                if (one) active = false else h.postDelayed({ listen() }, 300)
                if (t.isNotBlank()) onText(t, one)
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }
}

object Wake {
    private val LATIN = Regex("[^a-z]")

    /** Returns (heard wake word, text after it). Accepts Latin and Devanagari spellings of "Jarvis". */
    fun split(t: String): Pair<Boolean, String> {
        val words = t.trim().split(Regex("\\s+"))
        for (i in 0 until minOf(4, words.size)) {
            val w = words[i].lowercase()
            val latin = w.replace(LATIN, "")
            val dev = w.startsWith("ज") && (w.contains("र्व") || w.contains("रव")) && w.length <= 8
            if ((latin.length >= 4 && sim(latin, "jarvis") >= 0.7) || dev) {
                return Pair(true, words.drop(i + 1).joinToString(" ").trim(',', '.', '!', '?', '।', ' '))
            }
        }
        return Pair(false, t.trim())
    }
}
