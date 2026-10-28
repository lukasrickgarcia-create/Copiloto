package br.com.copiloto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano: lê o GPS, avisa radares e excesso de velocidade
 * com a tela apagada ou com outros apps (Waze, Spotify) abertos.
 */
class RadarService : Service() {

    companion object {
        const val NID = 7
        @Volatile var inst: RadarService? = null
        @Volatile var state: String = "{\"running\":false}"

        fun start(c: Context, auto: Boolean) {
            inst?.let { s -> if (!auto) s.post { s.auto = false }; return }
            val i = Intent(c, RadarService::class.java).setAction("START").putExtra("auto", auto)
            try {
                ContextCompat.startForegroundService(c, i)
            } catch (e: Exception) {
                Notifs.askToStart(c)
            }
        }
    }

    var auto = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var am: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var tone: ToneGenerator? = null
    private var radars: List<Radar> = emptyList()
    private var cfg = JSONObject()
    private var started = false

    private var prev: Location? = null
    private var anchor: Location? = null
    private var heading: Double? = null
    private var lastLoc: Location? = null
    private val alerted = HashMap<String, Long>()
    private val overSpoken = HashMap<String, Long>()
    private var lastBeep = 0L
    private var lastMoving = 0L
    private var exitReq = false
    private var slowSince = 0L
    private var lastNotif = 0L
    private var lastOver = false
    private var lastOsmCheck = 0L
    private var osmBusy = false

    private val focusReq: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs())
            .build()
    }

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { onLoc(it) }
        }
    }

    fun post(r: () -> Unit) { main.post(r) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "MARK" -> { if (started) mark(null); return START_NOT_STICKY }
        }
        if (!started) {
            auto = intent?.getBooleanExtra("auto", false) ?: false
            if (!startFg()) { stopSelf(); return START_NOT_STICKY }
            begin()
        }
        return START_NOT_STICKY
    }

    private fun startFg(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NID, buildNotif("Copiloto ativo", "Procurando sinal do GPS…", false), type)
        true
    } catch (e: Exception) {
        Notifs.askToStart(this)
        false
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        started = true
        inst = this
        lastMoving = System.currentTimeMillis()
        reload()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setAudioAttributes(speechAttrs())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { am.abandonAudioFocusRequest(focusReq) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { am.abandonAudioFocusRequest(focusReq) }
                })
                ttsOk = true
            }
        }
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()
        try {
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf(); return
        }
        publish(0.0, -1.0, null, false, "")
    }

    override fun onDestroy() {
        try { fused.removeLocationUpdates(cb) } catch (e: Exception) { }
        try { am.abandonAudioFocusRequest(focusReq) } catch (e: Exception) { }
        tts?.shutdown()
        tone?.release()
        inst = null
        started = false
        state = "{\"running\":false}"
        super.onDestroy()
    }

    fun reload() {
        cfg = Store.cfg(this)
        radars = Radars.load(this)
    }

    fun onVehicleExit() { exitReq = true }

    /* ---------------- som e voz ---------------- */
    private fun speechAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun speak(t: String) {
        if (!cfg.optBoolean("voz", true) || !ttsOk) return
        am.requestAudioFocus(focusReq)
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun beep(type: Int, ms: Int) {
        if (!cfg.optBoolean("bip", true)) return
        try { tone?.startTone(type, ms) } catch (e: Exception) { }
    }

    /* ---------------- GPS ---------------- */
    private fun onLoc(l: Location) {
        val now = System.currentTimeMillis()
        var kmh = if (l.hasSpeed()) l.speed * 3.6 else -1.0
        prev?.let { p ->
            val dt = (l.time - p.time) / 1000.0
            if (kmh < 0 && dt > 0.5) kmh = p.distanceTo(l) / dt * 3.6
        }
        prev = l
        val a = anchor
        if (a == null) anchor = l
        else if (a.distanceTo(l) > 12) { heading = ((a.bearingTo(l).toDouble()) + 360) % 360; anchor = l }
        if (l.hasBearing() && l.hasSpeed() && l.speed > 2) heading = l.bearing.toDouble()
        if (kmh < 2) kmh = 0.0
        lastLoc = l

        // desliga sozinho quando estaciona
        if (kmh > 8) lastMoving = now
        if (auto && now - lastMoving > 5 * 60_000) { stopSelf(); return }
        if (exitReq) {
            if (kmh < 10) {
                if (slowSince == 0L) slowSince = now
                if (now - slowSince > 30_000) { stopSelf(); return }
            } else { slowSince = 0L; exitReq = false }
        }

        if (now - lastOsmCheck > 30_000) { lastOsmCheck = now; autoOsm(l) }
        checkRadars(l.latitude, l.longitude, kmh, l.accuracy)
    }

    private fun rid(r: Radar) = String.format(Locale.US, "%.5f,%.5f", r.lat, r.lon)

    private fun checkRadars(lat: Double, lon: Double, kmh: Double, acc: Float) {
        val now = System.currentTimeMillis()
        val dAviso = cfg.optInt("dist", 500).toDouble()
        val tol = cfg.optInt("tol", 0)
        val maxD = dAviso + 300
        alerted.entries.removeAll { now - it.value > 240_000 }
        overSpoken.entries.removeAll { now - it.value > 240_000 }

        val dLat = maxD / 111000.0
        val dLon = dLat / max(0.2, cos(Math.toRadians(lat)))
        val h = heading
        var best: Radar? = null
        var bestD = Double.MAX_VALUE
        for (r in radars) {
            if (Math.abs(r.lat - lat) > dLat || Math.abs(r.lon - lon) > dLon) continue
            val d = Radars.dist(lat, lon, r.lat, r.lon)
            if (d > maxD) continue
            if (h != null && kmh > 10) {
                if (d > 35 && Radars.angDiff(Radars.bearing(lat, lon, r.lat, r.lon), h) > 50) continue
                if (r.dir != null && Radars.angDiff(r.dir, h) > 60) continue
            }
            if (d < bestD) { best = r; bestD = d }
        }

        val r = best
        if (r == null || bestD > dAviso) {
            publish(kmh, if (r == null) -1.0 else bestD, null, false, "", acc)
            return
        }
        val id = rid(r)
        val lim = r.lim
        if (!alerted.containsKey(id)) {
            alerted[id] = now
            beep(ToneGenerator.TONE_PROP_BEEP2, 300)
            val m = max(50, ((bestD / 50).roundToInt() * 50))
            speak("Radar em $m metros." + (if (lim != null) " Limite $lim." else ""))
        }
        val over = lim != null && kmh > lim + tol
        if (over) {
            if (now - lastBeep > 1400) { beep(ToneGenerator.TONE_CDMA_HIGH_L, 250); lastBeep = now }
            if (!overSpoken.containsKey(id)) {
                overSpoken[id] = now
                main.postDelayed({ speak("Se ferrou! Passou acima da velocidade.") }, 1500)
            }
        }
        publish(kmh, bestD, lim, over, r.desc, acc)
    }

    /* ---------------- estado para a tela + notificação ---------------- */
    private fun publish(kmh: Double, dist: Double, lim: Int?, over: Boolean, desc: String, acc: Float = -1f) {
        val show = if (cfg.optBoolean("painel", false)) kmh * 1.03 else kmh
        state = JSONObject()
            .put("running", true).put("auto", auto)
            .put("kmh", show.roundToInt()).put("acc", acc.roundToInt())
            .put("dist", dist.roundToInt()).put("lim", lim ?: JSONObject.NULL)
            .put("over", over).put("desc", desc)
            .put("count", radars.size)
            .put("aviso", cfg.optInt("dist", 500))
            .put("gps", lastLoc != null)
            .toString()

        val now = System.currentTimeMillis()
        if (now - lastNotif < 1500 && over == lastOver) return
        lastNotif = now
        lastOver = over
        val title = when {
            over -> "⚠ ACIMA DO LIMITE — ${show.roundToInt()} km/h (limite $lim)"
            lastLoc == null -> "Copiloto ativo"
            else -> "${show.roundToInt()} km/h"
        }
        val text = when {
            lastLoc == null -> "Procurando sinal do GPS…"
            dist < 0 -> "Nenhum radar à frente · ${radars.size} radares na base"
            lim != null || dist <= cfg.optInt("dist", 500) ->
                "Radar a ${dist.roundToInt()} m" + (if (lim != null) " · limite $lim km/h" else "")
            else -> "Radar a ${dist.roundToInt()} m (fora do raio de aviso)"
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID, buildNotif(title, text, over))
        } catch (e: Exception) { }
    }

    private fun buildNotif(title: String, text: String, over: Boolean): Notification {
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, RadarService::class.java).setAction("STOP"), fl)
        val mark = PendingIntent.getService(this, 2, Intent(this, RadarService::class.java).setAction("MARK"), fl)
        return NotificationCompat.Builder(this, Notifs.CH_RADAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setColor(if (over) 0xFFFF3B3B.toInt() else 0xFF3AA0FF.toInt())
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Marcar radar aqui", mark)
            .addAction(0, "Parar", stop)
            .build()
    }

    /* ---------------- marcar radar ---------------- */
    fun mark(lim: Int?): Boolean {
        val l = lastLoc ?: run { speak("Sem sinal de GPS."); return false }
        val arr = try { JSONArray(Store.get(this, "radMeus") ?: "[]") } catch (e: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("lat", Math.round(l.latitude * 1e6) / 1e6)
                .put("lon", Math.round(l.longitude * 1e6) / 1e6)
                .put("lim", lim ?: JSONObject.NULL)
                .put("desc", "marcado por você")
                .put("dir", heading?.let { Math.round(it).toDouble() } ?: JSONObject.NULL)
        )
        Store.set(this, "radMeus", arr.toString())
        reload()
        beep(ToneGenerator.TONE_PROP_ACK, 200)
        speak("Radar salvo.")
        return true
    }

    /* ---------------- OpenStreetMap automático ---------------- */
    private fun autoOsm(l: Location) {
        if (osmBusy || !cfg.optBoolean("osm", true)) return
        val area = try { JSONObject(Store.get(this, "osmArea") ?: "") } catch (e: Exception) { null }
        val far = area == null ||
            Radars.dist(area.optDouble("lat"), area.optDouble("lon"), l.latitude, l.longitude) > 25_000
        val old = area != null && System.currentTimeMillis() - area.optLong("t") > 3 * 86_400_000L
        if (!far && !old) return
        osmBusy = true
        val la = l.latitude
        val lo = l.longitude
        Thread {
            try {
                Osm.fetch(this, la, lo)
                main.post { reload() }
            } catch (e: Exception) { }
            main.postDelayed({ osmBusy = false }, 60_000)
        }.start()
    }
}
