package br.com.copiloto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.floor

/**
 * Baixa (do OpenStreetMap) e guarda no celular as vias da região onde o carro está,
 * em quadrados de ~2 km. Depois de baixado, funciona offline por 30 dias.
 */
class RoadTiles(private val c: Context) {
    companion object {
        const val CELL = 0.02          // ~2,2 km
        private const val MAX_AGE = 30L * 86_400_000L
        private val HW = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
        private val CLS = mapOf(
            "motorway" to 0, "trunk" to 1, "primary" to 2, "secondary" to 3, "tertiary" to 4,
            "unclassified" to 5, "residential" to 6, "living_street" to 8, "road" to 9
        )
        private val URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        fun speed(v: String?): Int? {
            if (v.isNullOrBlank()) return null
            val n = v.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            return n.takeIf { it in 5..150 }
        }
    }

    @Volatile var net: RoadNet = RoadNet(emptyList())
        private set
    @Volatile var status: String = "sem mapa"
        private set

    private val dir = File(c.filesDir, "vias").apply { mkdirs() }
    private val loaded = LinkedHashMap<String, List<Way>>()
    private val failedUntil = HashMap<String, Long>()
    @Volatile private var busy = false

    private fun cellOf(lat: Double, lon: Double) = "${floor(lat / CELL).toInt()}_${floor(lon / CELL).toInt()}"

    /** Chamado a cada posição: garante as vias de onde o carro está e ~1,5 km à frente. */
    fun ensure(lat: Double, lon: Double, heading: Double?, online: Boolean) {
        if (busy) return
        val need = LinkedHashSet<String>()
        need.add(cellOf(lat, lon))
        for (b in listOf(0.0, 90.0, 180.0, 270.0)) {
            val (a, o) = Geo.move(lat, lon, b, 500.0)
            need.add(cellOf(a, o))
        }
        if (heading != null) for (m in listOf(800.0, 1600.0)) {
            val (a, o) = Geo.move(lat, lon, heading, m)
            need.add(cellOf(a, o))
        }
        val now = System.currentTimeMillis()
        val missing = need.filter { it !in loaded && (failedUntil[it] ?: 0L) < now }
        if (missing.isEmpty()) return
        busy = true
        Thread {
            var changed = false
            for (k in missing) {
                val ways = readCache(k) ?: if (online) fetch(k) else null
                if (ways != null) { loaded[k] = ways; changed = true }
                else failedUntil[k] = System.currentTimeMillis() + 90_000
            }
            // libera quadrados longe daqui (memória)
            val (cy, cx) = cellOf(lat, lon).split("_").map { it.toInt() }
            loaded.keys.filter { val (y, x) = it.split("_").map { n -> n.toInt() }; kotlin.math.abs(y - cy) > 2 || kotlin.math.abs(x - cx) > 2 }
                .forEach { loaded.remove(it); changed = true }
            if (changed) {
                val all = LinkedHashMap<Long, Way>()
                for (l in loaded.values) for (w in l) all[w.id] = w
                net = RoadNet(all.values)
                status = if (all.isEmpty()) "sem vias na região" else "${all.size} vias"
            }
            busy = false
        }.start()
    }

    private fun file(k: String) = File(dir, "$k.json")

    private fun readCache(k: String): List<Way>? {
        val f = file(k)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) return null
        return try { decode(JSONArray(f.readText())) } catch (e: Exception) { null }
    }

    private fun fetch(k: String): List<Way>? {
        val (cy, cx) = k.split("_").map { it.toInt() }
        val s = cy * CELL; val w = cx * CELL
        val bb = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", s, w, s + CELL, w + CELL)
        val q = "[out:json][timeout:40];way[\"highway\"~\"$HW\"]($bb);out body geom qt;"
        for (u in URLS) {
            try {
                val (code, txt) = Http.request("POST", u, "data=" + URLEncoder.encode(q, "UTF-8"), "application/x-www-form-urlencoded")
                if (code != 200) continue
                val ways = parse(JSONObject(txt).getJSONArray("elements"))
                file(k).writeText(encode(ways).toString())
                return ways
            } catch (e: Exception) { }
        }
        status = "não consegui baixar o mapa (sem internet?)"
        return null
    }

    private fun parse(els: JSONArray): List<Way> {
        val out = ArrayList<Way>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val t = e.optJSONObject("tags") ?: continue
            val hw = t.optString("highway")
            val geom = e.optJSONArray("geometry") ?: continue
            val nd = e.optJSONArray("nodes") ?: continue
            if (geom.length() != nd.length() || nd.length() < 2) continue
            val n = nd.length()
            val nodes = LongArray(n); val la = DoubleArray(n); val lo = DoubleArray(n)
            var ok = true
            for (j in 0 until n) {
                val g = geom.optJSONObject(j)
                if (g == null) { ok = false; break }
                nodes[j] = nd.getLong(j); la[j] = g.getDouble("lat"); lo[j] = g.getDouble("lon")
            }
            if (!ok) continue
            val ow = t.optString("oneway")
            val oneway = when {
                ow == "-1" || ow == "reverse" -> -1
                ow == "yes" || ow == "1" || ow == "true" -> 1
                ow == "no" -> 0
                hw == "motorway" || t.optString("junction") == "roundabout" -> 1
                else -> 0
            }
            val base = speed(t.optString("maxspeed"))
            val f = speed(t.optString("maxspeed:forward")) ?: base
            val b = speed(t.optString("maxspeed:backward")) ?: base
            val cls = CLS[hw] ?: 7
            val name = t.optString("name", t.optString("ref", ""))
            out.add(Way(e.getLong("id"), cls, f, b, oneway, name, nodes, la, lo))
        }
        return out
    }

    private fun encode(ways: List<Way>): JSONArray {
        val a = JSONArray()
        for (w in ways) {
            val nodes = JSONArray(); val pts = JSONArray()
            for (j in w.nodes.indices) {
                nodes.put(w.nodes[j])
                pts.put(Math.round(w.lat[j] * 1e6) / 1e6); pts.put(Math.round(w.lon[j] * 1e6) / 1e6)
            }
            a.put(JSONArray().put(w.id).put(w.cls).put(w.maxFwd ?: 0).put(w.maxBwd ?: 0).put(w.oneway).put(w.name).put(nodes).put(pts))
        }
        return a
    }

    private fun decode(a: JSONArray): List<Way> {
        val out = ArrayList<Way>(a.length())
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            val nd = r.getJSONArray(6); val pts = r.getJSONArray(7)
            val n = nd.length()
            val nodes = LongArray(n); val la = DoubleArray(n); val lo = DoubleArray(n)
            for (j in 0 until n) { nodes[j] = nd.getLong(j); la[j] = pts.getDouble(2 * j); lo[j] = pts.getDouble(2 * j + 1) }
            out.add(Way(r.getLong(0), r.getInt(1), r.getInt(2).takeIf { it > 0 }, r.getInt(3).takeIf { it > 0 },
                r.getInt(4), r.getString(5), nodes, la, lo))
        }
        return out
    }
}
