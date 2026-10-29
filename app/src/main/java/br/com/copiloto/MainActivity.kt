package br.com.copiloto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        @Volatile var visible = false
        @Volatile var current: MainActivity? = null
    }

    lateinit var web: WebView
    private var pendingBattery = false
    private var testTts: TextToSpeech? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF0B0F14.toInt())
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: Exception) { }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (hasAll()) Detector.syncWithCfg(this)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("start", false) == true) {
            i.removeExtra("start")
            RadarService.start(this, true)
        }
    }

    override fun onPause() {
        super.onPause()
        visible = false
        current = null
    }

    override fun onDestroy() {
        testTts?.shutdown()
        super.onDestroy()
    }

    /** Testar frases nos Ajustes. */
    fun testSpeak(t: String) {
        val cur = testTts
        if (cur != null) { cur.speak(t, TextToSpeech.QUEUE_FLUSH, null, "teste"); return }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                testTts = tts
                tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "teste")
            }
        }
    }

    fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun updateOsm(id: Int) {
        val cb = { ok: Boolean, msg: String ->
            runOnUiThread { web.evaluateJavascript("window.__osmCb && __osmCb($id,$ok,${JSONObject.quote(msg)})", null) }
        }
        if (!hasLoc()) { cb(false, "libere a localização"); return }
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { l ->
                if (l == null) cb(false, "sem sinal de localização")
                else Thread {
                    try { cb(true, Osm.fetch(this, l.latitude, l.longitude).toString()) }
                    catch (e: Exception) { cb(false, e.message ?: "erro") }
                    RadarService.inst?.let { s -> s.post { s.reload() } }
                }.start()
            }
            .addOnFailureListener { cb(false, "sem localização") }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        current = this
        if (pendingBattery) { pendingBattery = false; finishSetup() }
        refreshJs()
    }

    fun refreshJs() {
        if (::web.isInitialized) web.evaluateJavascript("window.onNativeResume && onNativeResume()", null)
    }

    /* ---------------- permissões ---------------- */
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun basePerms(): List<String> {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) l += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l
    }

    private fun hasBg() = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun hasLoc() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasAll() = basePerms().all { granted(it) } && hasBg()

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    fun permJson(): String = JSONObject()
        .put("loc", hasLoc())
        .put("bg", hasBg())
        .put("act", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION))
        .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        .put("battery", batteryOk())
        .put("overlay", Settings.canDrawOverlays(this))
        .toString()

    /** Passo 1: localização, atividade física e notificações. */
    fun setupPermissions() {
        val miss = basePerms().filter { !granted(it) }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 1) else stepBackground()
    }

    /** Passo 2: localização "o tempo todo". */
    private fun stepBackground() {
        if (hasBg()) { stepBattery(); return }
        AlertDialog.Builder(this)
            .setTitle("Localização o tempo todo")
            .setMessage("Na próxima tela escolha \"Permitir o tempo todo\". Sem isso o radar não liga sozinho com o app fechado.")
            .setPositiveButton("Continuar") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    /** Passo 3: tirar o app da economia de bateria (senão o Android mata o serviço). */
    @SuppressLint("BatteryLife")
    private fun stepBattery() {
        if (batteryOk()) { finishSetup(); return }
        pendingBattery = true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            pendingBattery = false
            finishSetup()
        }
    }

    private fun finishSetup() {
        Detector.syncWithCfg(this)
        refreshJs()
        if (hasAll()) toast("Pronto! O radar liga sozinho quando você estiver de carro.")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            1 -> if (hasLoc()) stepBackground() else toast("Sem localização o radar não funciona.")
            2 -> if (hasBg()) stepBattery() else {
                toast("Em Permissões > Localização, escolha \"Permitir o tempo todo\".")
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
        }
        refreshJs()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}

/** Ponte entre a tela (HTML) e o Android. */
class Bridge(private val a: MainActivity) {

    @JavascriptInterface
    fun getItem(k: String): String? = Store.get(a, k)

    @JavascriptInterface
    fun setItem(k: String, v: String) {
        Store.set(a, k, v)
        if (k == "cfg") Detector.syncWithCfg(a)
        RadarService.inst?.let { s -> s.post { s.reload() } }
    }

    @JavascriptInterface
    fun http(method: String, url: String, body: String, type: String, id: Int) {
        Thread {
            var code = 0
            var txt = ""
            try {
                val r = Http.request(method, url, body.ifEmpty { null }, type.ifEmpty { null })
                code = r.first
                txt = r.second
            } catch (e: Exception) { }
            val b64 = Base64.encodeToString(txt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            a.runOnUiThread { a.web.evaluateJavascript("window.__httpCb($id,$code,'$b64')", null) }
        }.start()
    }

    @JavascriptInterface
    fun status(): String = RadarService.state

    @JavascriptInterface
    fun startTrip() {
        a.runOnUiThread { if (a.hasLoc()) RadarService.start(a, false) else a.setupPermissions() }
    }

    @JavascriptInterface
    fun stopTrip() {
        RadarService.inst?.let { s -> s.post { s.stopSelf() } }
    }

    @JavascriptInterface
    fun markRadar(lim: Int, tipo: String) {
        RadarService.inst?.let { s -> s.post { s.mark(if (lim > 0) lim else null, tipo.ifEmpty { "fixo" }) } }
    }

    @JavascriptInterface
    fun ignoreRadar() {
        RadarService.inst?.let { s -> s.post { s.ignoreLast() } }
    }

    @JavascriptInterface
    fun updateOsm(id: Int) {
        a.runOnUiThread { a.updateOsm(id) }
    }

    @JavascriptInterface
    fun speak(t: String) {
        a.runOnUiThread { a.testSpeak(t) }
    }

    @JavascriptInterface
    fun requestOverlay() {
        a.runOnUiThread { a.requestOverlay() }
    }

    @JavascriptInterface
    fun permStatus(): String = a.permJson()

    @JavascriptInterface
    fun setupPermissions() {
        a.runOnUiThread { a.setupPermissions() }
    }
}
