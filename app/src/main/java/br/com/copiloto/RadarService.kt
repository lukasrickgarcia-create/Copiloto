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
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
 * Serviço em primeiro plano: lê o GPS, avisa radares, radar de trecho e excesso de velocidade
 * com a tela apagada ou com outros apps (Waze, Spotify) abertos. Também registra a viagem.
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
    private lateinit var lm: LocationManager
    private var lastGpsAt = 0L
    private lateinit var am: AudioManager
    private lateinit var bubble: Bubble
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var tone: ToneGenerator? = null
    private var radars: List<Radar> = emptyList()
    private var trechos: List<Trecho> = emptyList()
    private var cfg = JSONObject()
    private var started = false

    private var prev: Location? = null
    private var anchor: Location? = null
    private var heading: Double? = null
    private var lastLoc: Location? = null
    private var lastKmh = 0.0
    private val alerted = HashMap<String, Long>()
    private val overSpoken = HashMap<String, Long>()
    private var lastAlert: Radar? = null
    private var lastAlertAt = 0L
    private var lastBeep = 0L

    // Alarme de excesso: bip contínuo e rápido + vibração até reduzir ou sair do raio do radar.
    private var alarmOn = false
    private var alarmStep = 0
    private val alarm = object : Runnable {
        override fun run() {
            if (!alarmOn) return
            if (cfg.optBoolean("bip", true)) try {
                tone?.startTone(if (alarmStep % 2 == 0) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_MED_L, 180)
            } catch (e: Exception) { }
            if (alarmStep % 3 == 0) vibrate(longArrayOf(0, 250))
            alarmStep++
            main.postDelayed(this, 300)
        }
    }
    private fun startAlarm() {
        if (alarmOn) return
        alarmOn = true; alarmStep = 0
        main.post(alarm)
    }
    private fun stopAlarm() {
        alarmOn = false
        main.removeCallbacks(alarm)
    }
    private fun vibrate(p: LongArray) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            v.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
        } catch (e: Exception) { }
    }
    private var lastMoving = 0L
    private var exitReq = false
    private var slowSince = 0L
    private var lastNotif = 0L
    private var lastOver = false
    private var lastOsmCheck = 0L
    private var osmBusy = false

    // viagem
    private var tripStart = 0L
    private var tripDist = 0.0
    private var tripMovingMs = 0L
    private var tripMax = 0.0
    private var tripExcessos = 0

    // radar de trecho
    private var tAtivo: Trecho? = null
    private var tStart = 0L
    private var tDist = 0.0
    private var tWarned = false
    private val tCooldown = HashMap<String, Long>()

    private val focusReq: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs())
            .build()
    }

    // Reserva: só usada se o GPS puro ficar sem sinal (túnel, garagem).
    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            if (SystemClock.elapsedRealtime() - lastGpsAt < 2500) return
            r.lastLocation?.let { onLoc(it) }
        }
    }

    // Principal: GPS do aparelho direto, sem a suavização do Google (que atrasa 1–2 s).
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastGpsAt = SystemClock.elapsedRealtime()
            onLoc(l)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    fun post(r: () -> Unit) { main.post(r) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fused = LocationServices.getFusedLocationProviderClient(this)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        bubble = Bubble(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "MARK" -> { if (started) mark(null, "fixo"); return START_NOT_STICKY }
            "IGNORE" -> { if (started) ignoreLast(); return START_NOT_STICKY }
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
        tripStart = System.currentTimeMillis()
        lastMoving = tripStart
        reload()
        Voz.create(this) { t ->
            if (t == null) return@create
            if (!started) { t.shutdown(); return@create }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { am.abandonAudioFocusRequest(focusReq) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { am.abandonAudioFocusRequest(focusReq) }
            })
            tts = t
            ttsOk = true
        }
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper())
        } catch (e: Exception) { }
        try {
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf(); return
        }
        publish(0.0, -1.0, null, false, null)
    }

    override fun onDestroy() {
        try { fused.removeLocationUpdates(cb) } catch (e: Exception) { }
        try { lm.removeUpdates(gpsListener) } catch (e: Exception) { }
        try { am.abandonAudioFocusRequest(focusReq) } catch (e: Exception) { }
        stopAlarm()
        bubble.remove()
        if (started) saveTrip()
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
        trechos = Radars.loadTrechos(this)
    }

    fun onVehicleExit() { exitReq = true }

    /* ---------------- som e voz ---------------- */
    private fun speechAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun speak(t: String, fila: Boolean = false) {
        if (t.isBlank() || !cfg.optBoolean("voz", true) || !ttsOk) return
        am.requestAudioFocus(focusReq)
        tts?.speak(t, if (fila) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun beep(type: Int, ms: Int) {
        if (!cfg.optBoolean("bip", true)) return
        try { tone?.startTone(type, ms) } catch (e: Exception) { }
    }

    /* ---------------- GPS ---------------- */
    private fun onLoc(l: Location) {
        prev?.let { if (l.elapsedRealtimeNanos <= it.elapsedRealtimeNanos) return }
        val now = System.currentTimeMillis()
        var kmh = if (l.hasSpeed()) l.speed * 3.6 else -1.0
        val p = prev
        var step = 0.0
        if (p != null) {
            val dt = (l.time - p.time) / 1000.0
            step = p.distanceTo(l).toDouble()
            if (kmh < 0 && dt > 0.5) kmh = step / dt * 3.6
            if (kmh > 5 && dt in 0.0..10.0 && l.accuracy < 35) {
                tripDist += step
                tripMovingMs += (dt * 1000).toLong()
            }
        }
        prev = l
        val a = anchor
        if (a == null) anchor = l
        else if (a.distanceTo(l) > 12) { heading = ((a.bearingTo(l).toDouble()) + 360) % 360; anchor = l }
        if (l.hasBearing() && l.hasSpeed() && l.speed > 2) heading = l.bearing.toDouble()
        if (kmh < 2) kmh = 0.0
        if (l.accuracy < 25 && kmh < 250) tripMax = max(tripMax, kmh)
        lastLoc = l
        lastKmh = kmh

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
        checkTrecho(l, step, kmh)
        // Compensa o atraso do GPS: o fix chega ~0,5–1 s "velho"; projeta onde o carro está agora.
        val age = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9).coerceIn(0.0, 3.0)
        val adv = if (kmh > 10) kmh / 3.6 * (age + 0.4) else 0.0
        checkRadars(l.latitude, l.longitude, kmh, l.accuracy, adv)
    }

    private fun rid(r: Radar) = String.format(Locale.US, "%.5f,%.5f", r.lat, r.lon)

    private fun checkRadars(lat: Double, lon: Double, kmh: Double, acc: Float, adv: Double = 0.0) {
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
            val de = max(0.0, d - adv)
            if (de < bestD) { best = r; bestD = de }
        }

        val r = best
        if (r == null || bestD > dAviso) {
            stopAlarm()
            publish(kmh, if (r == null) -1.0 else bestD, null, false, null, acc)
            return
        }
        val id = rid(r)
        val lim = r.lim
        lastAlert = r
        lastAlertAt = now
        if (!alerted.containsKey(id)) {
            alerted[id] = now
            beep(ToneGenerator.TONE_PROP_BEEP2, 300)
            val m = max(50, ((bestD / 50).roundToInt() * 50))
            val key = when (r.tipo) { "lombada" -> "lombada"; "semaforo" -> "semaforo"; else -> "radar" }
            speak(Frases.get(cfg, key, dist = m, lim = lim))
        }
        val over = lim != null && kmh > lim + tol
        if (!over) stopAlarm()
        if (over) {
            startAlarm()
            if (!overSpoken.containsKey(id)) {
                overSpoken[id] = now
                tripExcessos++
                speak(Frases.get(cfg, "excesso", lim = lim), fila = true)
            }
        }
        publish(kmh, bestD, lim, over, r, acc)
    }

    /* ---------------- radar de trecho (velocidade média) ---------------- */
    private fun checkTrecho(l: Location, step: Double, kmh: Double) {
        val now = System.currentTimeMillis()
        val tol = cfg.optInt("tol", 0)
        val t = tAtivo
        if (t == null) {
            if (kmh < 10) return
            for (c in trechos) {
                val k = "${c.aLat},${c.aLon}"
                if ((tCooldown[k] ?: 0L) > now) continue
                if (Radars.dist(l.latitude, l.longitude, c.aLat, c.aLon) > 60) continue
                if (Radars.dist(c.aLat, c.aLon, c.bLat, c.bLon) < 150) continue
                tAtivo = c; tStart = now; tDist = 0.0; tWarned = false
                tCooldown[k] = now + 120_000
                beep(ToneGenerator.TONE_PROP_BEEP2, 300)
                speak(Frases.get(cfg, "trechoInicio", lim = c.lim))
                return
            }
            return
        }
        tDist += step
        val el = (now - tStart) / 1000.0
        val media = if (el > 5) tDist / el * 3.6 else kmh
        val lim = t.lim
        val toB = Radars.dist(l.latitude, l.longitude, t.bLat, t.bLon)
        if (toB < 60) {
            tAtivo = null
            val acima = lim != null && media > lim + tol
            speak(Frases.get(cfg, "trechoFim", lim = lim, media = media.roundToInt()))
            if (acima) {
                tripExcessos++
                speak(Frases.get(cfg, "excesso", lim = lim), fila = true)
            }
            return
        }
        val total = Radars.dist(t.aLat, t.aLon, t.bLat, t.bLon)
        if (el > 3600 || toB > total * 2 + 2000) { tAtivo = null; return }   // saiu do trecho
        if (lim != null && el > 20) {
            if (!tWarned && media > lim + tol) {
                tWarned = true
                beep(ToneGenerator.TONE_CDMA_HIGH_L, 250)
                speak(Frases.get(cfg, "trechoExcesso", lim = lim))
            } else if (tWarned && media < lim - 3) tWarned = false
        }
    }

    /* ---------------- estado para a tela + notificação + bolha ---------------- */
    private fun publish(kmh: Double, dist: Double, lim: Int?, over: Boolean, r: Radar?, acc: Float = -1f) {
        val show = if (cfg.optBoolean("painel", false)) kmh * 1.03 else kmh
        val t = tAtivo
        val tJson: Any = if (t == null) JSONObject.NULL else {
            val el = (System.currentTimeMillis() - tStart) / 1000.0
            val media = if (el > 5) tDist / el * 3.6 else kmh
            JSONObject().put("media", media.roundToInt()).put("lim", t.lim ?: JSONObject.NULL)
                .put("falta", Radars.dist(lastLoc?.latitude ?: t.aLat, lastLoc?.longitude ?: t.aLon, t.bLat, t.bLon).roundToInt())
        }
        state = JSONObject()
            .put("running", true).put("auto", auto)
            .put("kmh", show.roundToInt()).put("acc", acc.roundToInt())
            .put("dist", dist.roundToInt()).put("lim", lim ?: JSONObject.NULL)
            .put("over", over).put("desc", r?.desc ?: "").put("tipo", r?.tipo ?: "")
            .put("count", radars.size).put("trechos", trechos.size)
            .put("aviso", cfg.optInt("dist", 500))
            .put("gps", lastLoc != null)
            .put("trecho", tJson)
            .put("viagem", JSONObject().put("km", Math.round(tripDist / 100) / 10.0).put("max", tripMax.roundToInt()))
            .toString()

        MainActivity.current?.let { a ->
            if (MainActivity.visible) try { a.web.evaluateJavascript("window.pollNative&&pollNative()", null) } catch (e: Exception) { }
        }
        val inRange = dist >= 0 && dist <= cfg.optInt("dist", 500)
        bubble.update(cfg.optBoolean("bolha", true) && !MainActivity.visible, show.roundToInt(), over, if (inRange) lim else (t?.lim))

        val now = System.currentTimeMillis()
        if (now - lastNotif < 900 && over == lastOver) return
        lastNotif = now
        lastOver = over
        val title = when {
            over -> "⚠ ACIMA DO LIMITE — ${show.roundToInt()} km/h (limite $lim)"
            lastLoc == null -> "Copiloto ativo"
            else -> "${show.roundToInt()} km/h"
        }
        val nome = when (r?.tipo) { "lombada" -> "Lombada"; "semaforo" -> "Semáforo c/ câmera"; else -> "Radar" }
        val text = when {
            lastLoc == null -> "Procurando sinal do GPS…"
            t != null -> {
                val el = (now - tStart) / 1000.0
                "Radar de trecho · média ${(if (el > 5) tDist / el * 3.6 else kmh).roundToInt()} km/h" + (t.lim?.let { " · limite $it" } ?: "")
            }
            inRange -> "$nome a ${dist.roundToInt()} m" + (if (lim != null) " · limite $lim km/h" else "")
            else -> "Nenhum radar à frente · ${radars.size} radares na base"
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
        val ign = PendingIntent.getService(this, 3, Intent(this, RadarService::class.java).setAction("IGNORE"), fl)
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
            .addAction(0, "Marcar radar", mark)
            .addAction(0, "Não existe", ign)
            .addAction(0, "Parar", stop)
            .build()
    }

    /* ---------------- marcar radar / trecho ---------------- */
    fun mark(lim: Int?, tipo: String): Boolean {
        val l = lastLoc ?: run { speak("Sem sinal de GPS."); return false }
        val lat = Math.round(l.latitude * 1e6) / 1e6
        val lon = Math.round(l.longitude * 1e6) / 1e6
        when (tipo) {
            "trecho_ini" -> {
                Store.set(this, "trechoPend", JSONObject().put("lat", lat).put("lon", lon).put("lim", lim ?: JSONObject.NULL).toString())
                beep(ToneGenerator.TONE_PROP_ACK, 200)
                speak("Início do trecho salvo. Marque o fim quando passar pelo último radar.")
                return true
            }
            "trecho_fim" -> {
                val pend = try { JSONObject(Store.get(this, "trechoPend") ?: "") } catch (e: Exception) { null }
                if (pend == null) { speak("Marque o início do trecho primeiro."); return false }
                val arr = try { JSONArray(Store.get(this, "meusTrechos") ?: "[]") } catch (e: Exception) { JSONArray() }
                val l2 = lim ?: (if (pend.isNull("lim")) null else pend.optInt("lim"))
                arr.put(JSONObject().put("aLat", pend.getDouble("lat")).put("aLon", pend.getDouble("lon"))
                    .put("bLat", lat).put("bLon", lon).put("lim", l2 ?: JSONObject.NULL).put("desc", "marcado por você"))
                Store.set(this, "meusTrechos", arr.toString())
                Store.set(this, "trechoPend", null)
                reload()
                beep(ToneGenerator.TONE_PROP_ACK, 200)
                speak("Radar de trecho salvo.")
                return true
            }
        }
        val arr = try { JSONArray(Store.get(this, "radMeus") ?: "[]") } catch (e: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("lat", lat).put("lon", lon)
                .put("lim", lim ?: JSONObject.NULL)
                .put("desc", "marcado por você")
                .put("tipo", tipo)
                .put("dir", heading?.let { Math.round(it).toDouble() } ?: JSONObject.NULL)
        )
        Store.set(this, "radMeus", arr.toString())
        reload()
        beep(ToneGenerator.TONE_PROP_ACK, 200)
        speak("Radar salvo.")
        return true
    }

    /** "Esse radar não existe": some da base e não avisa mais. */
    fun ignoreLast(): Boolean {
        val r = lastAlert
        if (r == null || System.currentTimeMillis() - lastAlertAt > 180_000) { speak("Nenhum radar recente para remover."); return false }
        val arr = try { JSONArray(Store.get(this, "radIgnorados") ?: "[]") } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("lat", r.lat).put("lon", r.lon).put("desc", r.desc).put("t", System.currentTimeMillis()))
        Store.set(this, "radIgnorados", arr.toString())
        lastAlert = null
        reload()
        beep(ToneGenerator.TONE_PROP_ACK, 200)
        speak("Ok, esse radar não vai mais ser avisado.")
        return true
    }

    /* ---------------- histórico de viagens ---------------- */
    private fun saveTrip() {
        if (tripDist < 500) return
        val fim = System.currentTimeMillis()
        val horas = tripMovingMs / 3_600_000.0
        val v = JSONObject()
            .put("ini", tripStart).put("fim", fim)
            .put("km", Math.round(tripDist / 100) / 10.0)
            .put("max", tripMax.roundToInt())
            .put("media", if (horas > 0) (tripDist / 1000 / horas).roundToInt() else 0)
            .put("min", ((fim - tripStart) / 60_000).toInt())
            .put("excessos", tripExcessos)
            .put("auto", auto)
        val arr = try { JSONArray(Store.get(this, "viagens") ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(v)
        for (i in 0 until minOf(arr.length(), 299)) out.put(arr.get(i))
        Store.set(this, "viagens", out.toString())
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
