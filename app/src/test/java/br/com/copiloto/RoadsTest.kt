package br.com.copiloto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cenários de rua montados em metros a partir de um ponto em São Paulo. */
class RoadsTest {
    private val lat0 = -23.55
    private val lon0 = -46.63
    private fun la(y: Double) = lat0 + y / Geo.MLAT
    private fun lo(x: Double) = lon0 + x / Geo.mlon(lat0)

    private var nextId = 1000L
    /** pts: pares (x, y) em metros; ids opcionais para ligar vias pelo mesmo nó. */
    private fun way(id: Long, pts: List<Pair<Double, Double>>, ids: List<Long>? = null, oneway: Int = 0, lim: Int? = null, name: String = "") =
        Way(id, 3, lim, lim, oneway, name,
            LongArray(pts.size) { ids?.get(it) ?: nextId++ },
            DoubleArray(pts.size) { la(pts[it].second) },
            DoubleArray(pts.size) { lo(pts[it].first) })

    private fun distTo(net: RoadNet, carX: Double, carY: Double, hdg: Double, rx: Double, ry: Double, rdir: Double? = null): Double? {
        val m = net.match(la(carY), lo(carX), hdg, null, 5.0) ?: return null
        val ahead = net.walk(m, 900.0)
        val sn = net.snap(la(ry), lo(rx))
        if (sn.isEmpty()) return null
        return net.routeDistance(ahead, sn, rdir)
    }

    /** Rua A (x=0) e rua B paralela (x=100), ligadas por uma transversal em y=500. */
    private fun paralelas(): RoadNet {
        val a = way(1, listOf(0.0 to 0.0, 0.0 to 500.0, 0.0 to 1000.0), listOf(1, 2, 3), name = "Rua A", lim = 40)
        val b = way(2, listOf(100.0 to 0.0, 100.0 to 500.0, 100.0 to 1000.0), listOf(11, 12, 13), name = "Rua B")
        val c = way(3, listOf(0.0 to 500.0, 100.0 to 500.0), listOf(2, 12), name = "Transversal")
        return RoadNet(listOf(a, b, c))
    }

    @Test fun radarNaMesmaRuaAFrente() {
        val d = distTo(paralelas(), 0.0, 100.0, 0.0, 0.0, 600.0)
        assertNotNull(d)
        assertEquals(500.0, d!!, 5.0)
    }

    @Test fun radarNaRuaParalelaNaoAvisa() {
        assertNull(distTo(paralelas(), 0.0, 100.0, 0.0, 100.0, 600.0))
    }

    @Test fun radarNaTransversalNaoAvisa() {
        assertNull(distTo(paralelas(), 0.0, 100.0, 0.0, 60.0, 500.0))
    }

    @Test fun radarQueJaPassouSome() {
        assertNull(distTo(paralelas(), 0.0, 650.0, 0.0, 0.0, 600.0))
    }

    @Test fun sentidoContrarioNaMesmaRua() {
        val net = paralelas()
        // indo para o sul, radar em y=600 está à frente
        assertEquals(300.0, distTo(net, 0.0, 900.0, 180.0, 0.0, 600.0)!!, 5.0)
        // radar que só fiscaliza quem vai para o norte não vale para quem vai para o sul
        assertNull(distTo(net, 0.0, 900.0, 180.0, 0.0, 600.0, rdir = 0.0))
    }

    @Test fun limiteENomeDaVia() {
        val net = paralelas()
        val m = net.match(la(100.0), lo(2.0), 0.0, null, 5.0)!!
        assertEquals(40, net.roadLimit(m))
        assertEquals("Rua A", net.roadName(m))
    }

    @Test fun curvaDaPropriaViaContinua() {
        // vai 300 m para o norte e faz uma curva de 90° para leste
        val w = way(1, listOf(0.0 to 0.0, 0.0 to 300.0, 20.0 to 320.0, 40.0 to 325.0, 400.0 to 325.0))
        val net = RoadNet(listOf(w))
        val d = distTo(net, 0.0, 50.0, 0.0, 200.0, 325.0)
        assertNotNull(d)
        assertTrue("distância pela via deveria ser ~460 m, foi $d", d!! in 440.0..480.0)
    }

    @Test fun pistaDuplaNaoAvisaRadarDoOutroLado() {
        // pista norte (x=0, mão única para o norte) e pista sul (x=18, mão única para o sul)
        val norte = way(1, listOf(0.0 to 0.0, 0.0 to 1000.0), oneway = 1)
        val sul = way(2, listOf(18.0 to 1000.0, 18.0 to 0.0), oneway = 1)
        val net = RoadNet(listOf(norte, sul))
        val m = net.match(la(100.0), lo(4.0), 0.0, null, 8.0)!!
        assertEquals(1L, net.ways[m.w].id)
        assertNull(distTo(net, 4.0, 100.0, 0.0, 18.0, 500.0))
        assertNotNull(distTo(net, 4.0, 100.0, 0.0, 0.0, 500.0))
    }

    @Test fun ruaDivididaEmVariasViasContinua() {
        val a = way(1, listOf(0.0 to 0.0, 0.0 to 300.0), listOf(1, 2))
        val b = way(2, listOf(0.0 to 300.0, 10.0 to 700.0), listOf(2, 3))
        val net = RoadNet(listOf(a, b))
        assertEquals(500.0, distTo(net, 0.0, 100.0, 0.0, 7.5, 600.0)!!, 8.0)
    }

    @Test fun naBifurcacaoSegueAsDuas() {
        // estrada que se divide em duas saídas suaves (±25°)
        val a = way(1, listOf(0.0 to 0.0, 0.0 to 300.0), listOf(1, 2))
        val e = way(2, listOf(0.0 to 300.0, -100.0 to 515.0), listOf(2, 3))
        val d = way(3, listOf(0.0 to 300.0, 100.0 to 515.0), listOf(2, 4))
        val net = RoadNet(listOf(a, e, d))
        assertNotNull(distTo(net, 0.0, 100.0, 0.0, -50.0, 407.0))
        assertNotNull(distTo(net, 0.0, 100.0, 0.0, 50.0, 407.0))
    }

    @Test fun paradoMantemARua() {
        val net = paralelas()
        val m1 = net.match(la(500.0), lo(1.0), 0.0, null, 5.0)!!
        assertEquals(1L, net.ways[m1.w].id)
        // parado bem no cruzamento, sem rumo: continua na Rua A
        val m2 = net.match(la(500.0), lo(3.0), null, m1, 5.0)!!
        assertEquals(1L, net.ways[m2.w].id)
        assertEquals(m1.dir, m2.dir)
    }

    @Test fun corredorSemMapa() {
        assertTrue(Corridor.ahead(la(0.0), lo(0.0), 0.0, la(400.0), lo(5.0), 400.0))
        assertTrue(!Corridor.ahead(la(0.0), lo(0.0), 0.0, la(400.0), lo(100.0), 412.0))
    }
}
