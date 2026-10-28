package br.com.copiloto

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/* ================= Armazenamento (compartilhado entre tela e serviço) ================= */
object Store {
    private fun file(c: Context, k: String): File {
        val d = File(c.filesDir, "kv")
        d.mkdirs()
        return File(d, k.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    }

    @Synchronized
    fun get(c: Context, k: String): String? {
        val f = file(c, k)
        return if (f.exists()) f.readText() else null
    }

    @Synchronized
    fun set(c: Context, k: String, v: String?) {
        val f = file(c, k)
        if (v == null) { f.delete(); return }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(v)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun cfg(c: Context): JSONObject =
        try { JSONObject(get(c, "cfg") ?: "{}") } catch (e: Exception) { JSONObject() }
}

/* ================= Radares ================= */
data class Radar(val lat: Double, val lon: Double, val lim: Int?, val desc: String, val dir: Double?)

object Radars {
    private val KEYS = listOf("radAntt", "radOsm", "radImp", "radMeus")

    fun load(c: Context): List<Radar> {
        val out = ArrayList<Radar>()
        for (k in KEYS) {
            val s = Store.get(c, k) ?: continue
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val lat = o.optDouble("lat", Double.NaN)
                    val lon = o.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val lim = if (o.isNull("lim")) null else o.optInt("lim", 0).takeIf { it in 1..199 }
                    val dir = if (o.isNull("dir")) null else o.optDouble("dir", Double.NaN).takeIf { !it.isNaN() }
                    out.add(Radar(lat, lon, lim, o.optString("desc", "radar"), dir))
                }
            } catch (e: Exception) { }
        }
        return out
    }

    private fun rad(d: Double) = d * Math.PI / 180

    fun dist(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dA = rad(a2 - a1)
        val dO = rad(o2 - o1)
        val h = sin(dA / 2) * sin(dA / 2) + cos(rad(a1)) * cos(rad(a2)) * sin(dO / 2) * sin(dO / 2)
        return 2 * 6371000.0 * asin(sqrt(h))
    }

    fun bearing(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val y = sin(rad(o2 - o1)) * cos(rad(a2))
        val x = cos(rad(a1)) * sin(rad(a2)) - sin(rad(a1)) * cos(rad(a2)) * cos(rad(o2 - o1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

/* ================= HTTP (sem bloqueio de CORS) ================= */
object Http {
    fun request(method: String, url: String, body: String?, type: String?): Pair<Int, String> {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 20000
            con.readTimeout = 45000
            con.requestMethod = method
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", "Copiloto/1.0 (Android)")
            if (!body.isNullOrEmpty() && method != "GET") {
                con.doOutput = true
                if (!type.isNullOrEmpty()) con.setRequestProperty("Content-Type", type)
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return code to decode(bytes)
        } finally {
            con.disconnect()
        }
    }

    fun decode(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8)
        val t = if (s.contains('�')) String(b, Charsets.ISO_8859_1) else s
        return t.removePrefix("﻿")
    }
}

/* ================= OpenStreetMap (radares da região) ================= */
object Osm {
    private val CARD = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5,
        "SE" to 135.0, "SSE" to 157.5, "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5
    )
    private val URLS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private fun dirOf(v: String): Double? {
        val s = v.split(';', ',').first().trim().uppercase(Locale.ROOT)
        if (s.isEmpty()) return null
        CARD[s]?.let { return it }
        return s.toDoubleOrNull()?.let { ((it % 360) + 360) % 360 }
    }

    fun fetch(c: Context, lat: Double, lon: Double): Int {
        val r = 0.4
        val bb = listOf(lat - r, lon - r, lat + r, lon + r).joinToString(",") { String.format(Locale.US, "%.4f", it) }
        val q = "[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"]($bb);node[\"enforcement\"=\"maxspeed\"]($bb););out body;"
        var txt: String? = null
        for (u in URLS) {
            try {
                val (code, t) = Http.request("POST", u, "data=" + URLEncoder.encode(q, "UTF-8"), "application/x-www-form-urlencoded")
                if (code == 200) { txt = t; break }
            } catch (e: Exception) { }
        }
        if (txt == null) throw IOException("OpenStreetMap sem resposta")
        val els = JSONObject(txt).getJSONArray("elements")
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (!e.has("lat")) continue
            val la = e.getDouble("lat")
            val lo = e.getDouble("lon")
            if (!seen.add(String.format(Locale.US, "%.4f,%.4f", la, lo))) continue
            val t = e.optJSONObject("tags") ?: JSONObject()
            val lim = t.optString("maxspeed", "").trim().takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..199 }
            val name = t.optString("name", t.optString("ref", ""))
            out.put(
                JSONObject()
                    .put("lat", la).put("lon", lo)
                    .put("lim", lim ?: JSONObject.NULL)
                    .put("desc", "OSM $name".trim())
                    .put("dir", dirOf(t.optString("direction", "")) ?: JSONObject.NULL)
            )
        }
        Store.set(c, "radOsm", out.toString())
        Store.set(c, "osmArea", JSONObject().put("lat", lat).put("lon", lon).put("t", System.currentTimeMillis()).toString())
        return out.length()
    }
}

/* ================= Detecção de "estou de carro" ================= */
object Detector {
    private fun pi(c: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val i = Intent(c, TransitionReceiver::class.java).setAction("br.com.copiloto.TRANSITION")
        return PendingIntent.getBroadcast(c, 0, i, flags)
    }

    fun canRun(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!canRun(c)) return
        val ts = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(it)
                .build()
        }
        try {
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(ts), pi(c))
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun unregister(c: Context) {
        try { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pi(c)) } catch (e: Exception) { }
    }

    fun syncWithCfg(c: Context) {
        if (Store.cfg(c).optBoolean("auto", true)) register(c) else unregister(c)
    }
}

class TransitionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!ActivityTransitionResult.hasResult(i)) return
        val r = ActivityTransitionResult.extractResult(i) ?: return
        if (!Store.cfg(c).optBoolean("auto", true)) return
        for (e in r.transitionEvents) {
            if (e.activityType != DetectedActivity.IN_VEHICLE) continue
            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) RadarService.start(c, true)
            else RadarService.inst?.let { s -> s.post { s.onVehicleExit() } }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Detector.syncWithCfg(c)
    }
}

/* ================= Notificações ================= */
object Notifs {
    const val CH_RADAR = "radar"
    const val CH_AVISO = "aviso"

    fun channels(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_RADAR, "Radar em viagem", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AVISO, "Viagem detectada", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Usado quando o Android não deixa iniciar sozinho: um toque liga o radar. */
    fun askToStart(c: Context) {
        channels(c)
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("start", true)
        val pi = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH_AVISO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Viagem detectada")
            .setContentText("Toque para ligar o radar")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(8, n)
        } catch (e: Exception) { }
    }
}
