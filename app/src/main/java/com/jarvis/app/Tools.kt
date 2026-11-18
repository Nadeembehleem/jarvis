package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun lev(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

fun sim(a: String, b: String): Double {
    val m = maxOf(a.length, b.length)
    return if (m == 0) 1.0 else 1.0 - lev(a, b).toDouble() / m
}

private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")
fun tok(s: String): List<String> = s.lowercase().split(TOKEN_SPLIT).filter { it.length > 2 }

/** Persistent memory + notes ("second brain"), stored in the app's private storage. */
class Store(ctx: Context) {
    private val memFile = File(ctx.filesDir, "memory.json")
    val notesDir = File(ctx.filesDir, "notes").apply { mkdirs() }

    fun memories(): List<String> = try {
        val a = JSONArray(memFile.readText())
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<String>) { memFile.writeText(JSONArray(l).toString()) }

    fun remember(fact: String): Boolean {
        val m = memories()
        val n = fact.trim()
        if (n.length < 4 || m.any { sim(it.lowercase(), n.lowercase()) > 0.8 }) return false
        save(m + n)
        return true
    }

    fun forget(k: String): Int {
        val m = memories()
        val keep = m.filter { !it.contains(k, true) }
        save(keep)
        return m.size - keep.size
    }

    fun noteFiles(): List<File> =
        notesDir.walkTopDown().filter { it.isFile && (it.extension == "md" || it.extension == "txt") }.toList()

    fun search(q: String): String {
        val words = tok(q)
        val hits = noteFiles().mapNotNull { f ->
            val text = f.readText()
            val low = text.lowercase()
            val score = words.sumOf { w -> low.split(w).size - 1 + (if (f.nameWithoutExtension.lowercase().contains(w)) 5 else 0) }
            if (score > 0) Triple(score, f.name, text) else null
        }.sortedByDescending { it.first }.take(3)
        if (hits.isEmpty()) return "No matching notes."
        return hits.joinToString("\n---\n") { (_, name, text) ->
            val i = words.mapNotNull { w -> text.lowercase().indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: 0
            "[$name]\n" + text.substring(maxOf(0, i - 100), minOf(text.length, i + 500))
        }
    }

    fun read(name: String): String =
        noteFiles().firstOrNull { it.nameWithoutExtension.contains(name, true) }?.readText()?.take(3000) ?: "Note not found."

    fun create(title: String, content: String): String {
        val f = File(notesDir, title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim() + ".md")
        f.writeText(content)
        return "Note saved: ${f.name}"
    }
}

const val TOOL_SPEC = """
search_notes(query) - search the user's notes
read_note(name) - read a note by title
create_note(title, content) - save a note
remember(fact) - save a lasting fact about the user
forget(keyword) - delete memories (needs confirmation)
get_time() - current date and time
web_search(query) - search the web (needs internet)
open_app(name) - open an installed app
open_url(url) - open a website
set_alarm(time_24h "HH:MM", label) - phone alarm
set_timer(minutes, label) - phone timer
remind_me(minutes, text) - timer that rings with the text
add_calendar_event(title, start "yyyy-MM-dd HH:mm", minutes)
battery() - battery level
location() - current location
flashlight(state "on" or "off")
set_volume(percent 0-100)
find_contact(name) - phone number of a contact
send_sms(number, text) - needs confirmation
make_call(number) - needs confirmation
"""

@Suppress("DEPRECATION")
class Tools(private val ctx: Context, private val store: Store) {
    val risky = setOf("send_sms", "make_call", "forget")

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun start(i: Intent): String { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); return "OK" }

    fun run(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "search_notes" -> store.search(a.optString("query"))
                "read_note" -> store.read(a.optString("name"))
                "create_note" -> store.create(a.optString("title", "Note"), a.optString("content"))
                "remember" -> if (store.remember(a.optString("fact"))) "Saved." else "Already known."
                "forget" -> "Removed ${store.forget(a.optString("keyword"))} memories."
                "get_time" -> SimpleDateFormat("EEEE dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
                "web_search" -> webSearch(a.optString("query"))
                "open_app" -> openApp(a.optString("name"))
                "open_url" -> {
                    var u = a.optString("url")
                    if (!u.startsWith("http")) u = "https://$u"
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(u))); "Opened."
                }
                "set_alarm" -> {
                    val t = a.optString("time_24h").split(":")
                    start(Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, t[0].trim().toInt())
                        .putExtra(AlarmClock.EXTRA_MINUTES, t[1].trim().toInt())
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Alarm set for ${a.optString("time_24h")}."
                }
                "set_timer", "remind_me" -> {
                    val m = a.optString("minutes").toDouble()
                    start(Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, (m * 60).toInt())
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", a.optString("text", "Jarvis")))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Timer set for $m minutes."
                }
                "add_calendar_event" -> {
                    val begin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(a.optString("start"))!!.time
                    val dur = a.optString("minutes", "60").toDouble().toLong()
                    start(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + dur * 60000))
                    "Calendar event ready to save."
                }
                "battery" -> battery()
                "location" -> location()
                "flashlight" -> flashlight(a.optString("state").lowercase() in listOf("on", "true", "1"))
                "set_volume" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val pct = a.optString("percent").toDouble().coerceIn(0.0, 100.0)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100).toInt(), 0)
                    "Volume set to ${pct.toInt()}%."
                }
                "find_contact" -> findContact(a.optString("name"))
                "send_sms" -> sendSms(a.optString("number"), a.optString("text"))
                "make_call" -> {
                    if (!has(Manifest.permission.CALL_PHONE)) "Call permission not granted."
                    else { start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(a.optString("number"))))); "Calling." }
                }
                else -> "Unknown tool $name."
            }
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
    }

    private fun webSearch(q: String): String = try {
        val c = URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        val html = c.inputStream.bufferedReader().readText()
        val re = Regex("class=\"result__a\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val strip = Regex("<.*?>|&\\w+;")
        re.findAll(html).take(5).joinToString("\n") { "- " + it.groupValues[1].replace(strip, "").trim() + ": " + it.groupValues[2].replace(strip, "").trim() }
            .ifBlank { "No results." }
    } catch (e: Exception) { "Search failed (needs internet): ${e.message}" }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0).firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return "No app named $name."
        val li = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "Cannot open $name."
        start(li)
        return "Opening ${hit.loadLabel(pm)}."
    }

    private fun battery(): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return "Battery ${lvl * 100 / scale}%" + if (charging) ", charging." else "."
    }

    private fun location(): String {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return "Location permission not granted."
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }
            .maxByOrNull { it.time } ?: return "No location fix yet. Open Maps once and try again."
        var s = "Latitude ${loc.latitude}, longitude ${loc.longitude}"
        try {
            val ad = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            if (!ad.isNullOrEmpty()) s += ". " + ad[0].getAddressLine(0)
        } catch (e: Exception) { }
        return s
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "No flashlight found."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun findContact(name: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return "Contacts permission not granted."
        val out = ArrayList<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
        )?.use { c -> while (c.moveToNext() && out.size < 5) out.add(c.getString(0) + ": " + c.getString(1)) }
        return if (out.isEmpty()) "No contact found." else out.joinToString("\n")
    }

    private fun sendSms(number: String, text: String): String {
        if (!has(Manifest.permission.SEND_SMS)) return "SMS permission not granted."
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
        return "SMS sent."
    }
}
