#!/usr/bin/env python3
"""
JARVIS v2 - private AI assistant. Local brain (Ollama), optional ElevenLabs voice.

SETUP
  ollama pull qwen2.5:7b && ollama pull nomic-embed-text
  Put notes (.md/.txt) in ~/JarvisNotes
  Hands-free voice (offline Whisper):  pip install faster-whisper   (PC)
      Android/Termux: build whisper.cpp, then set WHISPER_CPP_BIN and WHISPER_CPP_MODEL
  Android (Termux): pkg install python ollama termux-api play-audio ffmpeg
  Optional: ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID (Hindi voice)
  Run: python3 jarvis.py  ->  http://localhost:8000
"""
import json, os, re, subprocess, sys, threading, time, hashlib, math, queue, difflib, itertools
import urllib.request, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
IS_ANDROID = "com.termux" in os.environ.get("PREFIX", "") or "ANDROID_ROOT" in os.environ
MODEL = os.environ.get("JARVIS_MODEL", "qwen2.5:7b")
EMBED_MODEL = os.environ.get("JARVIS_EMBED_MODEL", "nomic-embed-text")
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")
NOTES_DIR = Path(os.environ.get("NOTES_DIR", Path.home() / "JarvisNotes"))
MEM_FILE, IDX_FILE, VOICE_DIR = HERE / "jarvis_memory.json", HERE / "notes_index.json", HERE / ".voice"
ELEVEN_KEY = os.environ.get("ELEVENLABS_API_KEY", "")
ELEVEN_VOICE = os.environ.get("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")  # replace with a Hindi voice
ELEVEN_MODEL = os.environ.get("ELEVENLABS_MODEL", "eleven_multilingual_v2")
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")
PORT = int(os.environ.get("PORT", 8000))
AWAKE_SECS = 12
NOTES_DIR.mkdir(parents=True, exist_ok=True); VOICE_DIR.mkdir(exist_ok=True)
DEV = re.compile(r"[\u0900-\u097F]")

def post(path, payload, timeout=300):
    req = urllib.request.Request(OLLAMA + path, json.dumps(payload).encode(), {"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=timeout)

# ================= memory =================
def load_mem():
    try: return json.loads(MEM_FILE.read_text())
    except Exception: return []

def add_memory(fact):
    m, n = load_mem(), fact.lower().strip()
    if len(n) < 4 or any(difflib.SequenceMatcher(None, n, x["fact"].lower()).ratio() > .8 for x in m): return False
    m.append({"fact": fact.strip(), "t": time.strftime("%Y-%m-%d")}); MEM_FILE.write_text(json.dumps(m, indent=1))
    return True

def learn(user_text):
    """Auto-extract lasting facts in the background."""
    if len(user_text) < 12: return
    def run():
        try:
            sysm = ('Extract lasting personal facts or preferences about the USER worth remembering long-term '
                    '(name, job, family, goals, preferences, habits). Ignore small talk and temporary things. '
                    'Reply only JSON: {"facts":["short sentence"]}. Empty list if none.')
            r = json.loads(post("/api/chat", {"model": MODEL, "stream": False, "format": "json", "messages": [
                {"role": "system", "content": sysm}, {"role": "user", "content": user_text}]}).read())
            for f in json.loads(r["message"]["content"]).get("facts", [])[:3]: add_memory(str(f))
        except Exception: pass
    threading.Thread(target=run, daemon=True).start()

# ================= notes (embeddings search) =================
def note_files(): return [p for p in NOTES_DIR.rglob("*") if p.suffix in (".md", ".txt")]

def embed(texts, prefix):
    return json.loads(post("/api/embed", {"model": EMBED_MODEL, "input": [prefix + t for t in texts]}).read())["embeddings"]

def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)) + 1e-9)

def chunks(text):
    out, cur = [], ""
    for para in re.split(r"\n\s*\n", text):
        if len(cur) + len(para) > 800 and cur: out.append(cur.strip()); cur = ""
        cur += para + "\n\n"
    if cur.strip(): out.append(cur.strip())
    return out

def build_index():
    try: idx = json.loads(IDX_FILE.read_text())
    except Exception: idx = {}
    live, changed = {}, False
    for p in note_files():
        k, mt = str(p), p.stat().st_mtime
        if k in idx and idx[k]["mt"] == mt: live[k] = idx[k]; continue
        cs = chunks(p.read_text(errors="ignore"))
        live[k] = {"mt": mt, "chunks": cs, "vecs": embed(cs, "search_document: ") if cs else []}; changed = True
    if changed or len(live) != len(idx): IDX_FILE.write_text(json.dumps(live))
    return live

def keyword_search(query):
    words = [w for w in re.findall(r"\w+", query.lower()) if len(w) > 2]; hits = []
    for p in note_files():
        text = p.read_text(errors="ignore"); low = text.lower()
        score = sum(low.count(w) + 5 * (w in p.stem.lower()) for w in words)
        if score: hits.append((score, p.name, text[:600]))
    hits.sort(reverse=True)
    return "\n---\n".join(f"[{n}]\n{s}" for _, n, s in hits[:3]) or "No matching notes."

def search_notes(query):
    try:
        idx, q = build_index(), None
        q = embed([query], "search_query: ")[0]
        sc = sorted(((cos(q, v), Path(k).name, c) for k, d in idx.items() for c, v in zip(d["chunks"], d["vecs"])), reverse=True)
        top = [s for s in sc[:4] if s[0] > 0.4]
        return "\n---\n".join(f"[{n}]\n{c[:700]}" for _, n, c in top) or "No relevant notes found."
    except Exception: return keyword_search(query)

def read_note(name):
    for p in note_files():
        if name.lower() in p.stem.lower(): return p.read_text(errors="ignore")[:3000]
    return "Note not found."

def create_note(title, content):
    p = NOTES_DIR / (re.sub(r"[^\w\- ]", "", title).strip() + ".md"); p.write_text(content); return f"Note saved: {p.name}"

# ================= tools =================
def sh(cmd, timeout=20):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or r.stderr).strip() or "OK"
    except Exception as e: return f"Failed: {e}"

def android_only(fn):
    return lambda *a, **k: fn(*a, **k) if IS_ANDROID else "This works only in the Android (Termux) version."

def num(x): return int(float(x))
def get_time(): return time.strftime("%A %d %B %Y, %H:%M")
def remember(fact): return "Saved to memory." if add_memory(fact) else "Already known."
def forget(keyword):
    m = load_mem(); keep = [x for x in m if keyword.lower() not in x["fact"].lower()]
    MEM_FILE.write_text(json.dumps(keep, indent=1)); return f"Removed {len(m) - len(keep)} memories."

def open_app(name):
    apps = {"youtube": "com.google.android.youtube", "chrome": "com.android.chrome", "camera": "com.android.camera",
            "maps": "com.google.android.apps.maps", "spotify": "com.spotify.music", "whatsapp": "com.whatsapp",
            "settings": "com.android.settings", "gmail": "com.google.android.gm", "photos": "com.google.android.apps.photos"}
    if IS_ANDROID:
        pkg = apps.get(name.lower().strip())
        return sh(["monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"]) and f"Opening {name}." if pkg else f"Unknown app {name}; add it to apps in open_app."
    return sh({"darwin": ["open", "-a", name], "win32": ["cmd", "/c", "start", "", name]}.get(sys.platform, [name.lower()])) and f"Opening {name}."

def open_url(url):
    url = url if url.startswith("http") else "https://" + url
    if IS_ANDROID: sh(["termux-open-url", url])
    else:
        import webbrowser; webbrowser.open(url)
    return "Opened."

def web_search(query):
    try:
        req = urllib.request.Request("https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(query), headers={"User-Agent": "Mozilla/5.0"})
        html = urllib.request.urlopen(req, timeout=15).read().decode("utf8", "ignore")
        res = re.findall(r'class="result__a"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</a>', html, re.S)[:5]
        cl = lambda s: re.sub(r"<.*?>|&\w+;", "", s).strip()
        return "\n".join(f"- {cl(t)}: {cl(s)}" for t, s in res) or "No results."
    except Exception as e: return f"Search failed (needs internet): {e}"

def remind_me(minutes, text):
    def fire():
        if IS_ANDROID: sh(["termux-notification", "--title", "Jarvis", "--content", text])
        enqueue("Reminder: " + text)
    threading.Timer(num(minutes) * 60, fire).start(); return f"I'll remind you in {num(minutes)} minutes (while Jarvis is running)."

@android_only
def set_alarm(time_24h, label):
    h, m = time_24h.split(":")
    return sh(["am", "start", "-a", "android.intent.action.SET_ALARM", "--ei", "android.intent.extra.alarm.HOUR", str(num(h)),
               "--ei", "android.intent.extra.alarm.MINUTES", str(num(m)), "--es", "android.intent.extra.alarm.MESSAGE", label,
               "--ez", "android.intent.extra.alarm.SKIP_UI", "true"]) and f"Alarm set for {time_24h}."

@android_only
def set_timer(minutes):
    return sh(["am", "start", "-a", "android.intent.action.SET_TIMER", "--ei", "android.intent.extra.alarm.LENGTH",
               str(num(minutes) * 60), "--ez", "android.intent.extra.alarm.SKIP_UI", "true"]) and f"Timer set for {num(minutes)} minutes."

@android_only
def battery(): return sh(["termux-battery-status"])
@android_only
def location(): return sh(["termux-location", "-p", "network"], 40)
@android_only
def flashlight(state): return sh(["termux-torch", "on" if state.lower() in ("on", "1", "true") else "off"])
@android_only
def set_volume(level): return sh(["termux-volume", "music", str(num(level))])

@android_only
def find_contact(name):
    try: cs = json.loads(sh(["termux-contact-list"], 30))
    except Exception: return "Could not read contacts (grant Termux:API contacts permission)."
    hits = [f"{c.get('name')}: {c.get('number')}" for c in cs if name.lower() in str(c.get("name", "")).lower()]
    return "\n".join(hits[:5]) or "No contact found."

@android_only
def send_sms(number, text): return sh(["termux-sms-send", "-n", number, text]) and "SMS sent."
@android_only
def make_call(number): return sh(["termux-telephony-call", number]) and "Calling."

def schema(name, desc, /, **props):
    return {"type": "function", "function": {"name": name, "description": desc, "parameters": {
        "type": "object", "properties": {k: {"type": "string", "description": v} for k, v in props.items()}, "required": list(props)}}}

TOOLS = [
    schema("search_notes", "Search the user's personal notes (second brain).", query="what to look for"),
    schema("read_note", "Read a note by (part of) its title.", name="note title"),
    schema("create_note", "Create a markdown note.", title="title", content="body"),
    schema("remember", "Store a lasting fact about the user.", fact="the fact"),
    schema("forget", "Delete memories containing a keyword (asks user to confirm).", keyword="keyword"),
    schema("get_time", "Current date and time."),
    schema("web_search", "Search the web (online).", query="search query"),
    schema("open_app", "Open an app.", name="app name"),
    schema("open_url", "Open a website.", url="URL"),
    schema("remind_me", "Remind the user later.", minutes="minutes from now", text="reminder text"),
    schema("set_alarm", "Set a phone alarm.", time_24h="HH:MM 24-hour", label="label"),
    schema("set_timer", "Start a phone timer.", minutes="minutes"),
    schema("battery", "Phone battery status."), schema("location", "Phone GPS location."),
    schema("flashlight", "Flashlight on/off.", state="on or off"),
    schema("set_volume", "Set media volume 0-15.", level="0-15"),
    schema("find_contact", "Find a phone number by contact name.", name="name"),
    schema("send_sms", "Send an SMS (asks user to confirm).", number="phone number", text="message"),
    schema("make_call", "Call a number (asks user to confirm).", number="phone number"),
]
FUNCS = {t["function"]["name"]: globals()[t["function"]["name"]] for t in TOOLS}
RISKY = {"send_sms", "make_call", "forget"}

def call_tool(name, args):
    try: return str(FUNCS[name](**{k: str(v) for k, v in args.items()}))
    except Exception as e: return f"Tool error: {e}"

# ================= voice output (queued, sentence by sentence) =================
gen, player, counter = 0, None, itertools.count()
tts_q, play_q = queue.Queue(), queue.Queue()
_kokoro = None

def clean_speech(s):
    s = re.sub(r"\[.*?\]\(.*?\)", "", s); s = re.sub(r"[*_`#>~|]", "", s)
    return re.sub(r"[\U00010000-\U0010ffff\u2600-\u27bf]", "", s).strip()

def eleven_tts(text, path):
    req = urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/{ELEVEN_VOICE}?output_format=mp3_44100_128",
        json.dumps({"text": text, "model_id": ELEVEN_MODEL}).encode(), {"Content-Type": "application/json", "xi-api-key": ELEVEN_KEY})
    path.write_bytes(urllib.request.urlopen(req, timeout=60).read())

def synth(text):
    global _kokoro
    n = next(counter)
    if ELEVEN_KEY:
        try: p = VOICE_DIR / f"{n}.mp3"; eleven_tts(text, p); return "file", str(p)
        except Exception as e: print("ElevenLabs failed, local voice:", e)
    if not DEV.search(text) and Path("kokoro-v1.0.onnx").exists():
        try:
            from kokoro_onnx import Kokoro; import soundfile as sf
            _kokoro = _kokoro or Kokoro("kokoro-v1.0.onnx", "voices-v1.0.bin")
            samples, sr = _kokoro.create(text, voice="bm_george", speed=1.05, lang="en-gb")
            p = VOICE_DIR / f"{n}.wav"; sf.write(str(p), samples, sr); return "file", str(p)
        except Exception: pass
    return "say", text

def play_cmd(kind, val):
    if kind == "say":
        if IS_ANDROID: return ["termux-tts-speak", val]
        if sys.platform == "darwin": return ["say", "-v", "Lekha" if DEV.search(val) else "Daniel", val]
        return ["espeak-ng", val]
    if IS_ANDROID: return ["play-audio", val]
    if sys.platform == "darwin": return ["afplay", val]
    return ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", val]

def tts_worker():
    while True:
        g, text = tts_q.get()
        try:
            if g == gen: k, v = synth(text); play_q.put((g, k, v))
        except Exception as e: print("tts error:", e)
        finally: tts_q.task_done()

def play_worker():
    global player
    while True:
        g, kind, val = play_q.get()
        try:
            if g == gen: player = subprocess.Popen(play_cmd(kind, val)); player.wait()
        except Exception as e: print("play error:", e)
        finally:
            if kind == "file": Path(val).unlink(missing_ok=True)
            play_q.task_done()

def enqueue(text):
    text = clean_speech(text)
    if text: tts_q.put((gen, text))

def speaking(): return tts_q.unfinished_tasks > 0 or play_q.unfinished_tasks > 0

def stop_speaking():
    global gen
    gen += 1
    for q in (tts_q, play_q):
        while True:
            try: q.get_nowait(); q.task_done()
            except queue.Empty: break
    if player and player.poll() is None: player.terminate()

for w in (tts_worker, play_worker): threading.Thread(target=w, daemon=True).start()

# ================= voice input: offline Whisper + wake word =================
_whisper, awake_until = None, 0.0
WAKE = ["jarvis", "जार्विस", "जारविस", "जार्विश", "जर्विस", "जरविस"]
NW = re.compile(r"[^\w\u0900-\u097F\s]")
def norm(t): return NW.sub("", t.lower()).strip()

def transcribe(path):
    global _whisper
    try:
        if _whisper is None:
            from faster_whisper import WhisperModel
            _whisper = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
        segs, _ = _whisper.transcribe(path, vad_filter=True, beam_size=1, initial_prompt="Hey Jarvis, हे जार्विस")
        return " ".join(s.text for s in segs).strip()
    except ImportError:
        b, m = os.environ.get("WHISPER_CPP_BIN"), os.environ.get("WHISPER_CPP_MODEL")
        if not (b and m): raise RuntimeError("Install faster-whisper, or set WHISPER_CPP_BIN and WHISPER_CPP_MODEL")
        wav = path + ".wav"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "quiet", "-i", path, "-ar", "16000", "-ac", "1", wav], check=True)
        return subprocess.run([b, "-m", m, "-f", wav, "-l", "auto", "-nt"], capture_output=True, text=True).stdout.strip()

def wake_split(text):
    words = text.split()
    for i, w in enumerate(words[:4]):
        wl = norm(w)
        if wl in WAKE or difflib.SequenceMatcher(None, wl, "jarvis").ratio() >= .75:
            return True, " ".join(words[i + 1:]).strip(" ,.!?।")
    return False, text.strip()

def voice(raw):
    global awake_until
    p = VOICE_DIR / "in.webm"; p.write_bytes(raw)
    try: text = transcribe(str(p))
    except Exception as e: return {"error": str(e)}
    finally: p.unlink(missing_ok=True)
    woke, cmd = wake_split(text)
    if woke: awake_until = time.time() + AWAKE_SECS
    if (woke or time.time() < awake_until) and cmd: return {"text": text, "command": cmd}
    return {"text": text, "command": None, "awake": woke}

# ================= brain =================
history, pending = [], None
YES = {"yes", "yeah", "yep", "confirm", "ok", "okay", "sure", "haan", "han", "ha", "haa", "हाँ", "हां", "हा", "कर दो", "ठीक है", "kar do"}
NO = {"no", "nope", "cancel", "nahi", "nahin", "na", "नहीं", "मत करो", "stop"}
chat_lock = threading.Lock()
SENT = re.compile(r"(.+?[.!?।\n])\s+", re.S)

def system_prompt():
    mem = "\n".join(f"- {m['fact']}" for m in load_mem()[-40:]) or "(nothing yet)"
    return ("You are JARVIS, a concise, witty, loyal personal AI assistant. Answer in 1-3 short sentences unless asked for more. "
            "Reply in the user's language: Hindi in Devanagari, or Hinglish (Roman letters) if they write Hinglish. "
            "No markdown or emojis (replies are spoken). For multi-step requests, call tools one after another until the task is done. "
            "Use search_notes for questions about the user's notes/work. Use find_contact before messaging or calling a name. "
            "send_sms, make_call and forget need user confirmation: call the tool, then ask the user to confirm, stating exactly what will happen.\n"
            f"Now: {get_time()}\nWhat you remember about the user:\n{mem}")

def llm_stream(msgs):
    resp = post("/api/chat", {"model": MODEL, "messages": msgs, "tools": TOOLS, "stream": True, "keep_alive": "30m"})
    calls = []
    for line in resp:
        if not line.strip(): continue
        m = json.loads(line).get("message", {})
        if m.get("content"): yield "text", m["content"]
        calls += m.get("tool_calls") or []
    yield "calls", calls

def flush(buf):
    sents = []
    while (m := SENT.match(buf)): sents.append(m.group(1)); buf = buf[m.end():]
    return sents, buf

def run_chat(text, speak_on, emit):
    global pending, awake_until
    def finish(reply):
        emit("token", reply)
        if speak_on: enqueue(reply)
        history.extend([{"role": "user", "content": text}, {"role": "assistant", "content": reply}])
    stop_speaking()
    if pending:
        p, pending = pending, None
        if norm(text) in YES: return finish("Done. " + call_tool(p["name"], p["args"]))
        if norm(text) in NO: return finish("Cancelled.")
    history.append({"role": "user", "content": text}); del history[:-24]
    msgs, full, used = [{"role": "system", "content": system_prompt()}] + history, "", []
    for _ in range(8):
        buf, step, calls = "", "", []
        for kind, val in llm_stream(msgs):
            if kind == "text":
                step += val; buf += val; emit("token", val)
                sents, buf = flush(buf)
                if speak_on: [enqueue(s) for s in sents]
            else: calls = val
        if speak_on and buf.strip(): enqueue(buf)
        full += step
        msgs.append({"role": "assistant", "content": step, **({"tool_calls": calls} if calls else {})})
        if not calls: break
        for c in calls:
            name, args = c["function"]["name"], c["function"].get("arguments") or {}
            used.append(name); emit("tool", name)
            if name in RISKY:
                pending = {"name": name, "args": args}
                msgs.append({"role": "tool", "content": "NOT executed yet. Ask the user to confirm, stating exactly what will happen."})
                emit("confirm", f"{name}({', '.join(map(str, args.values()))})")
            else: msgs.append({"role": "tool", "content": call_tool(name, args)})
    history.append({"role": "assistant", "content": full.strip() or "Done."})
    awake_until = time.time() + AWAKE_SECS
    if "remember" not in used: learn(text)

def galaxy():
    def pos(s): h = hashlib.md5(s.encode()).digest(); return [(h[i] / 255 - .5) * 2 for i in range(3)]
    return ([{"label": p.stem, "kind": "note", "p": pos(p.name)} for p in note_files()] +
            [{"label": m["fact"][:60], "kind": "memory", "p": pos(m["fact"])} for m in load_mem()])

# ================= dashboard =================
HTML = r"""<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>JARVIS</title><style>
:root{--c:#27d3ff;--bg:#04101a}*{box-sizing:border-box}
body{margin:0;background:radial-gradient(circle at 30% 20%,#0a2336,var(--bg));color:#cfeeff;font:15px ui-monospace,Menlo,monospace;height:100vh;display:grid;grid-template-columns:300px 1fr 320px;gap:14px;padding:14px}
.panel{border:1px solid #27d3ff44;border-radius:10px;background:#07182599;padding:12px;display:flex;flex-direction:column;min-height:0}
h3{margin:0 0 8px;color:var(--c);font-size:12px;letter-spacing:3px}
.reactor{width:160px;height:160px;margin:10px auto;position:relative}
.reactor i{position:absolute;inset:0;border:2px solid var(--c);border-radius:50%;border-top-color:transparent;animation:spin 6s linear infinite}
.reactor i:nth-child(2){inset:18px;animation-duration:4s;animation-direction:reverse}.reactor i:nth-child(3){inset:36px;animation-duration:2.5s}
.reactor b{position:absolute;inset:60px;border-radius:50%;background:var(--c);box-shadow:0 0 30px var(--c);animation:pulse 3s ease-in-out infinite}
.think i{animation-duration:1s!important}.think b{animation-duration:.5s}.hear b{background:#ffc83d;box-shadow:0 0 30px #ffc83d}
@keyframes spin{to{transform:rotate(360deg)}}@keyframes pulse{50%{opacity:.4;transform:scale(.85)}}
#log{flex:1;overflow:auto;display:flex;flex-direction:column;gap:8px}
.m{padding:8px 10px;border-radius:8px;max-width:90%;white-space:pre-wrap}.u{align-self:flex-end;background:#27d3ff22}.j{background:#ffffff0d}
.t{font-size:11px;color:#7ab}form{display:flex;gap:6px;margin-top:8px}
input[type=text]{flex:1;background:#00000055;border:1px solid #27d3ff55;color:#fff;padding:10px;border-radius:8px;font:inherit}
button,select{background:#27d3ff22;border:1px solid var(--c);color:var(--c);border-radius:8px;padding:8px 12px;cursor:pointer;font:inherit;margin:2px 0}
select{background:#000;color:#fff}canvas{width:100%;flex:1;min-height:0}
@media(max-width:900px){body{grid-template-columns:1fr;height:auto}#log{max-height:50vh}canvas{height:300px}}
</style></head><body>
<div class=panel><h3>NEURAL CORE</h3><div class=reactor id=r><i></i><i></i><i></i><b></b></div>
<div id=st style="text-align:center;color:#7ab">ONLINE · LOCAL</div><br>
<label><input type=checkbox id=sp checked> Speak replies</label>
<select id=lg><option value=hi-IN>हिन्दी / Hinglish</option><option value=en-IN>English (India)</option><option value=en-US>English (US)</option></select>
<button id=hf type=button>🎧 Hands-free: OFF</button><button id=mic type=button>🎤 Hold to talk</button>
<button id=stop type=button>⏹ Stop speaking</button></div>
<div class=panel><h3>CONSOLE</h3><div id=log></div>
<form id=f><input type=text id=q placeholder="Ask Jarvis... (or say 'Hey Jarvis')" autocomplete=off><button>SEND</button></form></div>
<div class=panel><h3>MEMORY GALAXY</h3><canvas id=g></canvas><div class=t>notes = cyan · memories = gold</div></div>
<script>
const $=id=>document.getElementById(id),log=$('log');let busy=false,hfOn=false;
function add(t,c){const d=document.createElement('div');d.className='m '+c;d.textContent=t;log.appendChild(d);log.scrollTop=1e9;return d}
function status(s,cls){$('st').textContent=s;$('r').className='reactor '+(cls||'')}
async function send(t){if(!t.trim())return;add(t,'u');const d=add('','j');busy=true;status('THINKING...','think');
 try{const r=await fetch('/api/chat',{method:'POST',body:JSON.stringify({message:t,speak:$('sp').checked})});
  const rd=r.body.getReader(),dec=new TextDecoder();let buf='';
  for(;;){const{value,done}=await rd.read();if(done)break;buf+=dec.decode(value,{stream:true});
   let i;while((i=buf.indexOf('\n\n'))>=0){const ev=JSON.parse(buf.slice(6,i));buf=buf.slice(i+2);
    if(ev.e=='token'){d.textContent+=ev.v}
    else if(ev.e=='tool'){const x=add('⚙ '+ev.v,'t');x.style.background='none'}
    else if(ev.e=='confirm'){const x=add('Confirm: '+ev.v,'j');
     for(const[l,v]of[['✅ Yes','yes'],['❌ No','no']]){const b=document.createElement('button');b.textContent=l;b.onclick=()=>{x.remove();send(v)};x.appendChild(b)}}
    log.scrollTop=1e9}}
 }catch(e){d.textContent='Is Ollama running? '+e}
 busy=false;status(hfOn?"LISTENING · say 'Hey Jarvis'":'ONLINE · LOCAL');load()}
$('f').onsubmit=e=>{e.preventDefault();const t=$('q').value;$('q').value='';send(t)};
$('stop').onclick=()=>fetch('/api/stop',{method:'POST'});
const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
if(SR){const rec=new SR();rec.onresult=e=>send(e.results[0][0].transcript);const m=$('mic');
 m.onmousedown=m.ontouchstart=e=>{e.preventDefault();rec.lang=$('lg').value;rec.start()};m.onmouseup=m.ontouchend=()=>rec.stop()}else $('mic').style.display='none';
// hands-free: offline Whisper on the server + wake word
let blocked=false,speech=false,lastLoud=0,startT=0,rec2,chunks=[],discard=false,stream;
setInterval(async()=>{if(!hfOn)return;try{blocked=busy||(await(await fetch('/api/state')).json()).speaking}catch(e){}},400);
$('hf').onclick=async()=>{if(hfOn){hfOn=false;$('hf').textContent='🎧 Hands-free: OFF';status('ONLINE · LOCAL');return}
 try{stream=stream||await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true}})}catch(e){add('Mic blocked: '+e,'j');return}
 if(!window.vadStarted){window.vadStarted=true;const ctx=new AudioContext(),an=ctx.createAnalyser();an.fftSize=1024;ctx.createMediaStreamSource(stream).connect(an);const b=new Float32Array(1024);
  setInterval(()=>{if(!hfOn)return;an.getFloatTimeDomainData(b);let s=0;for(const v of b)s+=v*v;const loud=Math.sqrt(s/b.length)>0.025,now=Date.now();
   if(blocked){if(speech){discard=true;speech=false;rec2.stop()}return}
   if(loud){lastLoud=now;if(!speech){discard=false;chunks=[];rec2=new MediaRecorder(stream);rec2.ondataavailable=e=>chunks.push(e.data);
     rec2.onstop=upload;rec2.start();startT=now;speech=true;status('HEARING...','hear')}}
   else if(speech&&(now-lastLoud>900||now-startT>15000)){speech=false;rec2.stop()}},50)}
 hfOn=true;$('hf').textContent='🎧 Hands-free: ON';status("LISTENING · say 'Hey Jarvis'")};
async function upload(){if(discard||Date.now()-startT-900<500){status("LISTENING · say 'Hey Jarvis'");return}
 status('TRANSCRIBING...','think');const r=await(await fetch('/api/voice',{method:'POST',body:new Blob(chunks,{type:rec2.mimeType})})).json();
 if(r.error){add(r.error,'j');status("LISTENING · say 'Hey Jarvis'");return}
 if(r.command)send(r.command);else status(r.awake?'AWAKE · go ahead...':"LISTENING · say 'Hey Jarvis'")}
// 3D memory galaxy
let nodes=[],a=0;const cv=$('g'),cx=cv.getContext('2d');
async function load(){nodes=await(await fetch('/api/galaxy')).json()}
function draw(){const w=cv.width=cv.clientWidth,h=cv.height=cv.clientHeight;cx.clearRect(0,0,w,h);a+=.004;
 const pr=nodes.map(n=>{const[x,y,z]=n.p,c=Math.cos(a),s=Math.sin(a),X=x*c-z*s,Z=x*s+z*c,k=1/(2.6-Z);return{X:w/2+X*k*w*.9,Y:h/2+y*k*h*.9,k,n}}).sort((p,q)=>p.k-q.k);
 for(const p of pr){cx.fillStyle=p.n.kind=='note'?'#27d3ff':'#ffc83d';cx.globalAlpha=Math.min(1,p.k*1.6);cx.shadowColor=cx.fillStyle;cx.shadowBlur=10;
  cx.beginPath();cx.arc(p.X,p.Y,p.k*6,0,7);cx.fill();if(p.k>.5){cx.shadowBlur=0;cx.font='10px monospace';cx.fillText(p.n.label.slice(0,18),p.X+8,p.Y+3)}}
 requestAnimationFrame(draw)}
load();draw();add("Jarvis online. Say hello, or turn on Hands-free.",'j');
</script></body></html>"""

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, body, ctype="application/json"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(200); self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path == "/api/galaxy": self._send(json.dumps(galaxy()))
        elif self.path == "/api/state": self._send(json.dumps({"speaking": speaking()}))
        else: self._send(HTML, "text/html")
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/api/voice": return self._send(json.dumps(voice(raw), ensure_ascii=False))
        if self.path == "/api/stop": stop_speaking(); return self._send("{}")
        d = json.loads(raw or b"{}")
        self.send_response(200); self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache"); self.end_headers()
        def emit(e, v):
            try: self.wfile.write(("data: " + json.dumps({"e": e, "v": v}, ensure_ascii=False) + "\n\n").encode()); self.wfile.flush()
            except Exception: pass
        with chat_lock:
            try: run_chat(d.get("message", ""), d.get("speak", True), emit)
            except Exception as e: emit("token", f"Brain offline: {e}. Is `ollama serve` running with {MODEL} pulled?")
        emit("done", "")

if __name__ == "__main__":
    print(f"JARVIS v2 -> http://localhost:{PORT} | model {MODEL} | notes {NOTES_DIR} | voice {'ElevenLabs' if ELEVEN_KEY else 'local'}")
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
