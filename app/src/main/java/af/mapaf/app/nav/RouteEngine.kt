package af.mapaf.app.nav

/**
 * Route engine — fully independent: real road routes via the on-device GraphRouter (A*)
 * over map.af's own street data, with turn-by-turn maneuvers generated from the actual
 * geometry (bearing changes) and mapped to the bundled voice prompts. No external API.
 */
class RouteEngine(private val graph: GraphRouter) {

    data class Maneuver(val promptKey: String, val distanceMeters: Int, val text: String, val pointIdx: Int)
    data class Route(
        val points: List<DoubleArray>,      // [lat, lng]
        val maneuvers: List<Maneuver>,
        val totalMeters: Double,
        val approximate: Boolean = false
    )

    fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, foot: Boolean = false): Route? {
        val r = graph.route(fromLat, fromLng, toLat, toLng, foot) ?: return null
        return buildRoute(r.points, r.totalMeters)
    }

    /** straight-line fallback only when the graph is unavailable (clearly labeled in UI) */
    fun straightLine(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route {
        val points = ArrayList<DoubleArray>()
        val steps = 24
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            points.add(doubleArrayOf(fromLat + (toLat - fromLat) * f, fromLng + (toLng - fromLng) * f))
        }
        val d = GraphRouterBearing.haversine(fromLat, fromLng, toLat, toLng)
        val man = listOf(
            Maneuver("start", 0, "●", 0),
            Maneuver("straight", (d * 0.6).toInt(), "↑", 4),
            Maneuver("arrival", 0, "◎", points.size - 1)
        )
        return Route(points, man, d, approximate = true)
    }

    /** derive maneuvers from real geometry: bearing changes between segments */
    private fun buildRoute(points: List<DoubleArray>, totalMeters: Double): Route {
        val maneuvers = ArrayList<Maneuver>()
        maneuvers.add(Maneuver("start", 0, "●", 0))
        var lastTurnIdx = 0

        fun distanceSince(from: Int, to: Int): Int {
            var m = 0.0
            for (i in from until minOf(to, points.size - 1)) {
                m += GraphRouterBearing.haversine(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1])
            }
            return m.toInt()
        }

        for (i in 1 until points.size - 1) {
            val b1 = GraphRouterBearing.bearing(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
            val b2 = GraphRouterBearing.bearing(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1])
            val delta = ((b2 - b1 + 540.0) % 360.0) - 180.0
            val seg = distanceSince(lastTurnIdx, i)
            val nextSeg = GraphRouterBearing.haversine(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1])
            if (Math.abs(delta) >= 25.0 && seg >= 25 && nextSeg >= 20) {
                val key = when {
                    Math.abs(delta) >= 150.0 -> "uturn"
                    Math.abs(delta) >= 110.0 -> if (delta > 0) "sharp_right" else "sharp_left"
                    Math.abs(delta) >= 55.0 -> if (delta > 0) "right" else "left"
                    else -> if (delta > 0) "slight_right" else "slight_left"
                }
                val arrow = when (key) {
                    "uturn" -> "U"; "sharp_right" -> "⇲"; "sharp_left" -> "⇱"
                    "right" -> "→"; "left" -> "←"
                    "slight_right" -> "↗"; else -> "↰"
                }
                maneuvers.add(Maneuver(key, seg, arrow, i))
                lastTurnIdx = i
            }
        }
        val tail = distanceSince(lastTurnIdx, points.size - 1)
        if (points.size >= 2) {
            maneuvers.add(Maneuver("approaching", tail, "◎", points.size - 2))
            maneuvers.add(Maneuver("arrival", 0, "◎", points.size - 1))
        }
        return Route(points, maneuvers, totalMeters)
    }

    // ---- progress helpers used by NavigationService ----
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double) =
        GraphRouterBearing.haversine(lat1, lng1, lat2, lng2)

    fun distanceToRoute(route: Route, lat: Double, lng: Double): Pair<Double, Int> {
        var best = Double.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until route.points.size - 1) {
            val d = pointToSegment(lat, lng, route.points[i], route.points[i + 1])
            if (d < best) { best = d; bestIdx = i }
        }
        return best to bestIdx
    }

    private fun pointToSegment(lat: Double, lng: Double, a: DoubleArray, b: DoubleArray): Double {
        val k = Math.cos(Math.toRadians(lat))
        val ax = a[0]; val ay = a[1] * k
        val bx = b[0]; val by = b[1] * k
        val px = lat; val py = lng * k
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return haversine(lat, lng, ax, ay / k)
        var t = ((px - ax) * dx + (py - ay) * dy) / l2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return haversine(lat, lng, cx, cy / k)
    }

    fun remainingMeters(route: Route, nearestIdx: Int, lat: Double, lng: Double): Double {
        var rem = haversine(lat, lng, route.points[nearestIdx][0], route.points[nearestIdx][1])
        for (i in nearestIdx until route.points.size - 1) {
            rem += haversine(route.points[i][0], route.points[i][1], route.points[i + 1][0], route.points[i + 1][1])
        }
        return rem
    }

    fun nextManeuver(route: Route, nearestIdx: Int, posLat: Double, posLng: Double): Maneuver? {
        val next = route.maneuvers.filter { it.pointIdx > nearestIdx }.minByOrNull { it.pointIdx }
        if (next == null) return null
        val target = route.points.getOrNull(next.pointIdx) ?: return next
        val d = haversine(posLat, posLng, target[0], target[1])
        return next.copy(distanceMeters = d.toInt())
    }
}

/** shared math helpers */
object GraphRouterBearing {
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val dL = Math.toRadians(lng2 - lng1)
        val y = Math.sin(dL) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dL)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
