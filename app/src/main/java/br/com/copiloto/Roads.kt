package br.com.copiloto

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Reconhecimento de rua ("map matching") sem Android — testável no computador.
 *
 * Ideia: com as vias do OpenStreetMap, descobrimos em qual rua o carro está e em que sentido,
 * "andamos" pela via à frente (seguindo em frente nos cruzamentos) e só consideramos radares
 * que estão nesse caminho. Assim um radar numa rua paralela ou transversal não é avisado.
 */

/** Uma via do OSM. oneway: 0 = mão dupla, 1 = só no sentido dos nós, -1 = só no sentido contrário. */
class Way(
    val id: Long,
    val cls: Int,
    val maxFwd: Int?,
    val maxBwd: Int?,
    val oneway: Int,
    val name: String,
    val nodes: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray
) {
    fun limit(dir: Int): Int? = if (dir >= 0) maxFwd else maxBwd
    fun allows(dir: Int): Boolean = oneway == 0 || oneway == dir
}

/** Onde o carro está: via w, segmento s (entre os nós s e s+1), posição t (0..1) e sentido dir (+1/-1). */
data class Match(val w: Int, val s: Int, val t: Double, val dir: Int, val dist: Double)

/** Segmento alcançável à frente; distStart = distância pela via até o início do segmento (negativa no atual). */
data class Ahead(val w: Int, val s: Int, val dir: Int, val distStart: Double, val len: Double)

/** Ponto (radar) encaixado numa via. */
data class Snap(val w: Int, val s: Int, val t: Double, val dist: Double)

object Geo {
    const val MLAT = 110_574.0
    fun mlon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

    fun dist(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * MLAT
        val dx = (o2 - o1) * mlon((a1 + a2) / 2)
        return sqrt(dx * dx + dy * dy)
    }

    fun bearing(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * MLAT
        val dx = (o2 - o1) * mlon((a1 + a2) / 2)
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    fun angDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Ponto a "m" metros na direção "brg". */
    fun move(lat: Double, lon: Double, brg: Double, m: Double): Pair<Double, Double> {
        val r = Math.toRadians(brg)
        return (lat + m * cos(r) / MLAT) to (lon + m * sin(r) / mlon(lat))
    }
}

class RoadNet(list: Collection<Way>) {
    val ways: Array<Way> = list.toTypedArray()
    private val nodeRefs = HashMap<Long, LongArray>()
    private val grid = HashMap<Long, LongArray>()

    companion object {
        private const val CELL = 0.001          // ~110 m
        private fun ref(w: Int, i: Int) = (w.toLong() shl 20) or i.toLong()
        private fun rw(r: Long) = (r shr 20).toInt()
        private fun ri(r: Long) = (r and 0xFFFFF).toInt()
        private fun key(cy: Int, cx: Int) = (cy.toLong() shl 32) xor (cx.toLong() and 0xFFFFFFFFL)
    }

    init {
        val nodeTmp = HashMap<Long, ArrayList<Long>>()
        val gridTmp = HashMap<Long, ArrayList<Long>>()
        for ((w, way) in ways.withIndex()) {
            for (i in way.nodes.indices) nodeTmp.getOrPut(way.nodes[i]) { ArrayList(2) }.add(ref(w, i))
            for (s in 0 until way.nodes.size - 1) {
                val y0 = floor(min(way.lat[s], way.lat[s + 1]) / CELL).toInt()
                val y1 = floor(max(way.lat[s], way.lat[s + 1]) / CELL).toInt()
                val x0 = floor(min(way.lon[s], way.lon[s + 1]) / CELL).toInt()
                val x1 = floor(max(way.lon[s], way.lon[s + 1]) / CELL).toInt()
                if ((y1 - y0 + 1) * (x1 - x0 + 1) > 400) continue   // segmento absurdo
                for (cy in y0..y1) for (cx in x0..x1) gridTmp.getOrPut(key(cy, cx)) { ArrayList(4) }.add(ref(w, s))
            }
        }
        for ((k, v) in nodeTmp) if (v.size > 1) nodeRefs[k] = v.toLongArray()
        for ((k, v) in gridTmp) grid[k] = v.toLongArray()
    }

    val isEmpty get() = ways.isEmpty()

    fun segLen(w: Int, s: Int): Double {
        val a = ways[w]
        return Geo.dist(a.lat[s], a.lon[s], a.lat[s + 1], a.lon[s + 1])
    }

    /** Rumo do segmento no sentido de viagem. */
    fun segBearing(w: Int, s: Int, dir: Int): Double {
        val a = ways[w]
        val b = Geo.bearing(a.lat[s], a.lon[s], a.lat[s + 1], a.lon[s + 1])
        return if (dir >= 0) b else (b + 180) % 360
    }

    /** Distância do ponto ao segmento e posição t (0..1). */
    fun project(lat: Double, lon: Double, w: Int, s: Int): Pair<Double, Double> {
        val a = ways[w]
        val k = Geo.mlon(lat)
        val ax = (a.lon[s] - lon) * k; val ay = (a.lat[s] - lat) * Geo.MLAT
        val bx = (a.lon[s + 1] - lon) * k; val by = (a.lat[s + 1] - lat) * Geo.MLAT
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py) to t
    }

    private fun candidates(lat: Double, lon: Double, r: Double): LongArray {
        val dLat = r / Geo.MLAT
        val dLon = r / Geo.mlon(lat)
        val out = HashSet<Long>()
        for (cy in floor((lat - dLat) / CELL).toInt()..floor((lat + dLat) / CELL).toInt())
            for (cx in floor((lon - dLon) / CELL).toInt()..floor((lon + dLon) / CELL).toInt())
                grid[key(cy, cx)]?.let { for (x in it) out.add(x) }
        return out.toLongArray()
    }

    /** Em que via e sentido o carro está. heading = rumo do GPS (null se parado). */
    fun match(lat: Double, lon: Double, heading: Double?, prev: Match?, accuracy: Double): Match? {
        val r = (accuracy * 1.5).coerceIn(22.0, 45.0)
        var best: Match? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates(lat, lon, r)) {
            val w = rw(c); val s = ri(c)
            val (d, t) = project(lat, lon, w, s)
            if (d > r) continue
            val way = ways[w]
            for (dir in intArrayOf(1, -1)) {
                if (!way.allows(dir)) continue
                var score = d
                if (heading != null) {
                    val ang = Geo.angDiff(heading, segBearing(w, s, dir))
                    if (ang > 65) continue
                    score += ang * 0.35
                } else if (prev != null && !(prev.w == w && prev.dir == dir)) {
                    score += 15.0                  // parado: mantém o que já sabia
                }
                if (prev != null) {
                    if (prev.w == w && prev.dir == dir) score -= 10.0
                    else if (touches(prev, w, s)) score -= 5.0
                }
                if (score < bestScore) { bestScore = score; best = Match(w, s, t, dir, d) }
            }
        }
        return best
    }

    private fun touches(p: Match, w: Int, s: Int): Boolean {
        val a = ways[p.w].nodes; val b = ways[w].nodes
        val n1 = a[p.s]; val n2 = a[p.s + 1]
        return b[s] == n1 || b[s] == n2 || b[s + 1] == n1 || b[s + 1] == n2
    }

    /** Rumo médio dos próximos ~20 m saindo do nó "i" da via w no sentido dir. */
    private fun outBearing(w: Int, i: Int, dir: Int): Double {
        val a = ways[w]
        var j = i
        var acc = 0.0
        while (acc < 20) {
            val nj = j + dir
            if (nj < 0 || nj >= a.nodes.size) break
            acc += Geo.dist(a.lat[j], a.lon[j], a.lat[nj], a.lon[nj])
            j = nj
        }
        if (j == i) return 0.0
        return Geo.bearing(a.lat[i], a.lon[i], a.lat[j], a.lon[j])
    }

    /** Rumo médio dos últimos ~20 m chegando ao nó "i" da via w vindo no sentido dir. */
    private fun inBearing(w: Int, i: Int, dir: Int): Double {
        val a = ways[w]
        var j = i
        var acc = 0.0
        while (acc < 20) {
            val pj = j - dir
            if (pj < 0 || pj >= a.nodes.size) break
            acc += Geo.dist(a.lat[j], a.lon[j], a.lat[pj], a.lon[pj])
            j = pj
        }
        if (j == i) return 0.0
        return Geo.bearing(a.lat[j], a.lon[j], a.lat[i], a.lon[i])
    }

    /**
     * Caminha pela via à frente até maxDist metros, seguindo "em frente" nos cruzamentos
     * (desvio de até maxTurn graus). Se a via só tem um caminho, segue mesmo com curva.
     */
    fun walk(m: Match, maxDist: Double, maxTurn: Double = 50.0): List<Ahead> {
        val out = ArrayList<Ahead>()
        val seen = HashSet<Long>()
        val len0 = segLen(m.w, m.s)
        val start0 = -(if (m.dir > 0) m.t else 1 - m.t) * len0
        val stack = ArrayDeque<Ahead>()
        stack.add(Ahead(m.w, m.s, m.dir, start0, len0))
        while (stack.isNotEmpty() && out.size < 600) {
            val cur = stack.removeLast()
            val k = (ref(cur.w, cur.s) shl 1) or (if (cur.dir > 0) 1L else 0L)
            if (!seen.add(k)) continue
            out.add(cur)
            val end = cur.distStart + cur.len
            if (end >= maxDist) continue
            val way = ways[cur.w]
            val endIdx = if (cur.dir > 0) cur.s + 1 else cur.s
            val node = way.nodes[endIdx]
            val inB = inBearing(cur.w, endIdx, cur.dir)

            // opções a partir do nó final
            data class Opt(val w: Int, val s: Int, val dir: Int, val turn: Double, val same: Boolean)
            val opts = ArrayList<Opt>()
            fun addFrom(w: Int, i: Int) {
                val wy = ways[w]
                if (i < wy.nodes.size - 1 && wy.allows(1) && !(w == cur.w && i == cur.s && cur.dir < 0))
                    opts.add(Opt(w, i, 1, Geo.angDiff(inB, outBearing(w, i, 1)), w == cur.w))
                if (i > 0 && wy.allows(-1) && !(w == cur.w && i - 1 == cur.s && cur.dir > 0))
                    opts.add(Opt(w, i - 1, -1, Geo.angDiff(inB, outBearing(w, i, -1)), w == cur.w))
            }
            val refs = nodeRefs[node]
            if (refs == null) addFrom(cur.w, endIdx) else for (r in refs) addFrom(rw(r), ri(r))
            // descarta retorno (meia-volta)
            val valid = opts.filter { it.turn < 150 }
            if (valid.isEmpty()) continue
            val straight = valid.filter { it.turn <= maxTurn }
            val follow = when {
                valid.size == 1 -> valid
                straight.isNotEmpty() -> straight
                else -> valid.filter { it.same }.take(1)      // a própria via faz a curva
            }
            for (o in follow) stack.add(Ahead(o.w, o.s, o.dir, end, segLen(o.w, o.s)))
        }
        return out
    }

    /** Encaixa um ponto (radar) nas vias próximas. */
    fun snap(lat: Double, lon: Double, maxD: Double = 40.0): List<Snap> {
        val all = ArrayList<Snap>()
        for (c in candidates(lat, lon, maxD)) {
            val (d, t) = project(lat, lon, rw(c), ri(c))
            if (d <= maxD) all.add(Snap(rw(c), ri(c), t, d))
        }
        if (all.isEmpty()) return all
        val best = all.minOf { it.dist }
        // fica com as vias praticamente tão perto quanto a melhor (radar em cruzamento)
        return all.filter { it.dist <= best + 6 }.sortedBy { it.dist }.take(4)
    }

    /**
     * Distância pela via até o ponto, se ele estiver no caminho à frente; null se não estiver.
     * pointDir = sentido que o radar fiscaliza (null = qualquer).
     */
    fun routeDistance(ahead: List<Ahead>, snaps: List<Snap>, pointDir: Double? = null, behind: Double = 8.0): Double? {
        var best: Double? = null
        for (sn in snaps) for (a in ahead) {
            if (a.w != sn.w || a.s != sn.s) continue
            if (pointDir != null && Geo.angDiff(pointDir, segBearing(a.w, a.s, a.dir)) > 60) continue
            val along = (if (a.dir > 0) sn.t else 1 - sn.t) * a.len
            val d = a.distStart + along
            if (d < -behind) continue               // já passou
            if (best == null || d < best) best = d
        }
        return best
    }

    fun roadLimit(m: Match): Int? = ways[m.w].limit(m.dir)
    fun roadName(m: Match): String = ways[m.w].name
}

/** Sem mapa da região: corredor estreito na direção em que o carro vai. */
object Corridor {
    fun ahead(lat: Double, lon: Double, heading: Double, rLat: Double, rLon: Double, d: Double): Boolean {
        if (d < 25) return true
        val ang = Geo.angDiff(Geo.bearing(lat, lon, rLat, rLon), heading)
        if (ang > 30) return false
        val lateral = d * sin(Math.toRadians(ang))
        return lateral <= 22 + d * 0.04
    }
}
