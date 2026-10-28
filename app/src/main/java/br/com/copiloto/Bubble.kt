package br.com.copiloto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/** Bolinha de velocidade que fica por cima do Waze/Maps. Arraste para mover, toque para abrir o app. */
class Bubble(private val c: Context) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var v: TextView? = null
    private val size = (64 * c.resources.displayMetrics.density).toInt()
    private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var lastText = ""
    private var lastColor = 0

    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val pos = try { JSONObject(Store.get(c, "bolhaPos") ?: "{}") } catch (e: Exception) { JSONObject() }
        x = pos.optInt("x", 24)
        y = pos.optInt("y", 300)
    }

    fun update(show: Boolean, kmh: Int, over: Boolean, lim: Int?) {
        if (!show || !Settings.canDrawOverlays(c)) { remove(); return }
        if (v == null && !add()) return
        val color = when {
            over -> 0xEEE53935.toInt()
            lim != null -> 0xEE1B2430.toInt()
            else -> 0xDD0B0F14.toInt()
        }
        if (color != lastColor) {
            lastColor = color
            bg.setColor(color)
            bg.setStroke((3 * c.resources.displayMetrics.density).toInt(), if (lim != null) 0xFFFF3B3B.toInt() else 0xFF3AA0FF.toInt())
            v?.background = bg
        }
        val txt = "$kmh\n" + (if (lim != null) "lim $lim" else "km/h")
        if (txt != lastText) {
            lastText = txt
            val s = SpannableString(txt)
            s.setSpan(RelativeSizeSpan(0.42f), txt.indexOf('\n'), txt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v?.text = s
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(): Boolean {
        val t = TextView(c).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 0.85f)
            background = bg
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        t.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                    try { wm.updateViewLayout(t, lp) } catch (ex: Exception) { }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Store.set(c, "bolhaPos", JSONObject().put("x", lp.x).put("y", lp.y).toString())
                    else c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            true
        }
        return try { wm.addView(t, lp); v = t; lastText = ""; lastColor = 0; true } catch (e: Exception) { false }
    }

    fun remove() {
        v?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        v = null
    }
}
