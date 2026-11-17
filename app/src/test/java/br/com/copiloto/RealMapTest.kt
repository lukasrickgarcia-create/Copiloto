package br.com.copiloto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Teste com o mapa de verdade (OpenStreetMap) da região da Avenida Paulista.
 * Se não houver internet no teste, ele é pulado.
 */
class RealMapTest {

    private fun overpass(q: String): String? {
        val urls = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
        for (tentativa in 0 until 2) for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 90000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", "Copiloto-test")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                println("Overpass $u -> ${c.responseCode}")
                if (c.responseCode == 200) return c.inputStream.bufferedReader().readText()
            } catch (e: Exception) { println("Overpass $u -> erro ${e.message}") }
            Thread.sleep(3000)
        }
        return null
    }

    private fun load(): List<Way> {
        val hw = "^(trunk|primary|secondary|tertiary|unclassified|residential|living_street|primary_link|secondary_link|tertiary_link)$"
        val txt = overpass("[out:json][timeout:60];way[\"highway\"~\"$hw\"](-23.575,-46.668,-23.552,-46.640);out body geom qt;")
        Assume.assumeTrue("sem internet para baixar o mapa", txt != null)
        val els = JSONObject(txt!!).getJSONArray("elements")
        val out = ArrayList<Way>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: continue
            val g = e.optJSONArray("geometry") ?: continue
            val nd = e.optJSONArray("nodes") ?: continue
            if (g.length() != nd.length() || nd.length() < 2) continue
            val n = nd.length()
            val ow = t.optString("oneway")
            val oneway = when { ow == "-1" -> -1; ow == "yes" || ow == "1" -> 1; t.optString("junction") == "roundabout" -> 1; else -> 0 }
            val lim = RoadTiles.speed(t.optString("maxspeed"))
            out.add(Way(e.getLong("id"), 3, lim, lim, oneway, t.optString("name"),
                LongArray(n) { nd.getLong(it) },
                DoubleArray(n) { g.getJSONObject(it).getDouble("lat") },
                DoubleArray(n) { g.getJSONObject(it).getDouble("lon") }))
        }
        return out
    }

    @Test fun paulistaNaoAvisaRuaParalelaNemOutraPista() {
        val ways = load()
        val net = RoadNet(ways)
        val paulista = ways.filter { it.name == "Avenida Paulista" && it.oneway != 0 }.maxByOrNull { it.nodes.size }
        println("Mapa: ${ways.size} vias; Paulista mão única: ${ways.count { it.name == "Avenida Paulista" && it.oneway != 0 }}")
        Assume.assumeTrue("Avenida Paulista não encontrada", paulista != null)
        val p = paulista!!
        val dir = p.oneway
        val i = p.nodes.size / 2
        val j = i + dir
        val hdg = Geo.bearing(p.lat[i], p.lon[i], p.lat[j], p.lon[j])
        // carro 3 m ao lado do eixo da via (erro normal de GPS)
        val (lat, lon) = Geo.move(p.lat[i], p.lon[i], (hdg + 90) % 360, 3.0)

        val m = net.match(lat, lon, hdg, null, 6.0)!!
        assertEquals("deveria reconhecer a Paulista", "Avenida Paulista", net.roadName(m))
        val ahead = net.walk(m, 900.0)

        // 1) pontos da própria pista, à frente, são encontrados
        val proprios = ahead.filter { net.ways[it.w].name == "Avenida Paulista" && it.distStart in 100.0..700.0 }
        assertTrue("deveria seguir pela Paulista", proprios.isNotEmpty())
        val a = proprios.first()
        val w = net.ways[a.w]
        val k = if (a.dir > 0) a.s else a.s + 1
        val dPropria = net.routeDistance(ahead, net.snap(w.lat[k], w.lon[k]))
        assertTrue("radar na mesma pista deveria contar", dPropria != null)

        // 2) nada da Alameda Santos (paralela) nem da pista do sentido contrário
        var falsos = 0; var testados = 0
        for (o in ways) {
            val alvo = o.name == "Alameda Santos" || (o.name == "Avenida Paulista" && o.oneway != 0 && o.id != p.id &&
                Geo.angDiff(Geo.bearing(o.lat[0], o.lon[0], o.lat[o.nodes.size - 1], o.lon[o.nodes.size - 1]) +
                    (if (o.oneway < 0) 180.0 else 0.0), hdg) > 120)
            if (!alvo) continue
            for (q in o.nodes.indices) {
                val d0 = Geo.dist(lat, lon, o.lat[q], o.lon[q])
                if (d0 < 30 || d0 > 800) continue
                testados++
                if (net.routeDistance(ahead, net.snap(o.lat[q], o.lon[q])) != null) falsos++
            }
        }
        println("Paulista: testados $testados pontos de rua paralela/outra pista, falsos $falsos")
        assertTrue("deveria testar algum ponto", testados > 0)
        assertEquals("radar de rua paralela ou da outra pista não pode ser avisado", 0, falsos)
    }
}
