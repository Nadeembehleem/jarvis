package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: Store
    private lateinit var brain: Brain
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var reactor: ReactorView
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var hfBtn: Button
    private lateinit var confirmBar: LinearLayout
    private lateinit var confirmText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var handsFree = false
    private var awakeUntil = 0L
    private var bubble: TextView? = null
    private var note = ""

    private val modelUrlGemma = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private val modelUrlSmall = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        store = Store(this)
        brain = Brain(this, Tools(this, store), store, prefs)
        speaker = Speaker(this, prefs)
        listener = Listener(this, { t, one -> onVoice(t, one) }, { !brain.busy && !speaker.busy }, { prefs.getString("lang", "hi-IN") ?: "hi-IN" })
        setContentView(buildUi())
        askPermissions()
        addMsg("Jarvis starting. Say \"Hey Jarvis\" with Hands-free on, or type.", false)
        loadModel()
        tick()
    }

    override fun onDestroy() {
        listener.stop()
        speaker.stop()
        brain.close()
        super.onDestroy()
    }

    private fun askPermissions() {
        val want = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    // ---------------- UI ----------------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#27D3FF"))
        setBackgroundColor(Color.parseColor("#0B2A3C"))
        textSize = 13f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04101A"))
            setPadding(dp(10), dp(30), dp(10), dp(8))
        }
        reactor = ReactorView(this)
        root.addView(reactor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)))
        status = TextView(this).apply { setTextColor(Color.parseColor("#7AB8CC")); gravity = Gravity.CENTER; textSize = 12f }
        root.addView(status)
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        confirmText = TextView(this).apply { setTextColor(Color.parseColor("#FFC83D")); textSize = 14f }
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            addView(confirmText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("✅ Yes") { send("yes") })
            addView(button("❌ No") { send("no") })
        }
        root.addView(confirmBar)

        input = EditText(this).apply {
            hint = "Ask Jarvis..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> sendInput(); true }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Send") { sendInput() })
        root.addView(row)

        hfBtn = button("🎧 Hands-free: OFF") { toggleHandsFree() }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(hfBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.4f))
        bar.addView(button("🎤") { listener.start(true) }, lp())
        bar.addView(button("⏹") { speaker.stop(); brain.cancel() }, lp())
        bar.addView(button("🌌") { showGalaxy() }, lp())
        bar.addView(button("⚙") { showSettings() }, lp())
        root.addView(bar)
        return root
    }

    private fun addMsg(text: String, user: Boolean, small: Boolean = false): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (small) Color.parseColor("#7AB8CC") else Color.WHITE)
            textSize = if (small) 11f else 15f
            setBackgroundColor(if (user) Color.parseColor("#0F3347") else if (small) Color.TRANSPARENT else Color.parseColor("#13222E"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        lp.gravity = if (user) Gravity.END else Gravity.START
        log.addView(tv, lp)
        scrollEnd()
        return tv
    }

    private fun scrollEnd() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun tick() {
        reactor.mode = when {
            brain.busy -> 1
            listener.listening -> 2
            speaker.busy -> 3
            else -> 0
        }
        status.text = when {
            note.isNotEmpty() -> note
            !brain.ready -> "MODEL NOT LOADED"
            brain.busy -> "THINKING..."
            speaker.busy -> "SPEAKING"
            listener.listening -> "LISTENING"
            handsFree -> "HANDS-FREE · say 'Hey Jarvis'"
            else -> "ONLINE · ON-DEVICE"
        }
        ui.postDelayed({ tick() }, 300)
    }

    // ---------------- chat ----------------
    private fun sendInput() {
        val t = input.text.toString()
        input.setText("")
        send(t)
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        if (!brain.ready) { addMsg("The model isn't loaded yet. Open ⚙ Settings to download one.", false); return }
        if (brain.busy) return
        confirmBar.visibility = View.GONE
        speaker.stop()
        addMsg(text, true)
        bubble = addMsg("", false)
        brain.chat(text, object : Callbacks {
            override fun token(s: String) { ui.post { bubble?.append(s); scrollEnd() } }
            override fun sentence(s: String) { if (prefs.getBoolean("speak", true)) speaker.say(s) }
            override fun tool(name: String) {
                ui.post {
                    val b = bubble
                    if (b != null && b.text.isEmpty()) log.removeView(b)
                    addMsg("⚙ $name", false, true)
                    bubble = addMsg("", false)
                }
            }
            override fun confirm(desc: String) { ui.post { confirmText.text = "Confirm: $desc"; confirmBar.visibility = View.VISIBLE } }
            override fun done() {
                ui.post {
                    val b = bubble
                    if (b != null && b.text.isEmpty()) log.removeView(b)
                    awakeUntil = System.currentTimeMillis() + 12000
                }
            }
        })
    }

    // ---------------- voice ----------------
    private fun toggleHandsFree() {
        handsFree = !handsFree
        hfBtn.text = if (handsFree) "🎧 Hands-free: ON" else "🎧 Hands-free: OFF"
        if (handsFree) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            listener.start(false)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            listener.stop()
        }
    }

    private fun onVoice(t: String, oneShot: Boolean) {
        if (oneShot || brain.pending != null) { send(t); return }
        val (woke, cmd) = Wake.split(t)
        val now = System.currentTimeMillis()
        if (woke) awakeUntil = now + 12000
        if ((woke || now < awakeUntil) && cmd.isNotBlank()) send(cmd)
    }

    // ---------------- model ----------------
    private fun loadModel() {
        if (!brain.modelFile.exists()) {
            note = "No model yet. Open ⚙ Settings to download one."
            addMsg("No model on this phone yet. Open ⚙ Settings and download Gemma 4, or pick a .litertlm file.", false)
            return
        }
        note = "Loading model..."
        brain.load { err ->
            ui.post {
                note = ""
                if (err == null) addMsg("Model loaded. Jarvis is online.", false)
                else addMsg("Could not load model: $err", false)
            }
        }
    }

    private fun startDownload(url: String) {
        val token = prefs.getString("hf", "") ?: ""
        note = "Downloading 0%"
        Thread {
            val err = ModelStore.download(url, token, brain.modelFile) { got, total ->
                ui.post { note = "Downloading " + (if (total > 0) "${got * 100 / total}%" else "") + " (${got / 1_000_000} MB)" }
            }
            ui.post {
                note = ""
                if (err == null) loadModel() else addMsg("Download failed: $err", false)
            }
        }.start()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == 10) {
            val uri = data.data ?: return
            note = "Copying model..."
            Thread {
                try { ModelStore.copyFrom(this, uri, brain.modelFile) { got, _ -> ui.post { note = "Copying model... ${got / 1_000_000} MB" } } }
                catch (e: Exception) { ui.post { addMsg("Copy failed: ${e.message}", false) } }
                ui.post { note = ""; loadModel() }
            }.start()
        } else if (req == 11) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
            data.data?.let { uris.add(it) }
            var n = 0
            for (u in uris) {
                try {
                    var name = "note$n.md"
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
                    contentResolver.openInputStream(u)?.use { i -> File(store.notesDir, name).outputStream().use { o -> i.copyTo(o) } }
                    n++
                } catch (e: Exception) { }
            }
            addMsg("Imported $n notes.", false)
        }
    }

    // ---------------- dialogs ----------------
    private fun showGalaxy() {
        fun pos(s: String): FloatArray {
            val h = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return FloatArray(3) { ((h[it].toInt() and 255) / 255f - 0.5f) * 2f }
        }
        val nodes = store.noteFiles().map { f -> pos(f.name).let { GNode(f.nameWithoutExtension, 0, it[0], it[1], it[2]) } } +
            store.memories().map { m -> pos(m).let { GNode(m, 1, it[0], it[1], it[2]) } }
        val v = GalaxyView(this, nodes)
        AlertDialog.Builder(this).setTitle("Memory galaxy").setView(v).setPositiveButton("Close", null).show()
            .window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, dp(480))
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun label(s: String) = TextView(this).apply { text = s; setPadding(0, dp(10), 0, 0) }.also { box.addView(it) }
        fun field(key: String, hint: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(key, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }.also { box.addView(it) }

        label("Model: " + if (brain.modelFile.exists()) "installed (${brain.modelFile.length() / 1_000_000} MB)" else "not installed")
        box.addView(Button(this).apply { text = "Download Gemma 4 E2B (2.6 GB, best)"; setOnClickListener { startDownload(modelUrlGemma) } })
        box.addView(Button(this).apply { text = "Download small test model (Qwen3 0.6B)"; setOnClickListener { startDownload(modelUrlSmall) } })
        box.addView(Button(this).apply {
            text = "Pick a .litertlm file"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 10) }
        })
        label("Hugging Face read token (for Gemma)")
        val hf = field("hf", "hf_...")
        label("ElevenLabs API key (optional, online)")
        val key = field("el_key", "key")
        label("ElevenLabs voice ID (Hindi voice)")
        val voice = field("el_voice", "voice id")
        label("Recognition language")
        val langs = listOf("hi-IN", "en-IN", "en-US")
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        sp.setSelection(langs.indexOf(prefs.getString("lang", "hi-IN")).coerceAtLeast(0))
        box.addView(sp)
        val speak = CheckBox(this).apply { text = "Speak replies"; isChecked = prefs.getBoolean("speak", true) }.also { box.addView(it) }
        val gpu = CheckBox(this).apply { text = "Use GPU (faster, may fail on some phones)"; isChecked = prefs.getBoolean("gpu", false) }.also { box.addView(it) }
        box.addView(Button(this).apply {
            text = "Import notes (.md / .txt)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 11)
            }
        })
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val gpuChanged = prefs.getBoolean("gpu", false) != gpu.isChecked
                prefs.edit().putString("hf", hf.text.toString().trim()).putString("el_key", key.text.toString().trim())
                    .putString("el_voice", voice.text.toString().trim()).putString("lang", langs[sp.selectedItemPosition])
                    .putBoolean("speak", speak.isChecked).putBoolean("gpu", gpu.isChecked).apply()
                if (gpuChanged && brain.modelFile.exists()) loadModel()
            }.setNegativeButton("Cancel", null).show()
    }
}
