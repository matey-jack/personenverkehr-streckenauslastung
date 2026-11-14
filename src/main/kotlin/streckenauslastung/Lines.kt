@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import kotlinx.html.TABLE
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.math.sign

/** The core routes used for testing (see spec.md, "Reconstructing the line"), by their route_id. */
internal val testRouteIds = listOf(
    "2233941_106",
    "7149172_106",
    "de:VBB:12070424|R-Bahn|RE6:_106:a",
    "4740564_106",
    "de:VBB:11000000|R-Bahn|RE2:DB_106",
)

/** The seconds since midnight at which one trip arrives at and departs from each of its stops, null where unknown. */
internal class TripTimes(val arrivals: List<Long?>, val departures: List<Long?>)

/**
 * All trips of the route [routeId] that stop at the same [stations] in the same order. [arrivals]
 * and [departures] are the seconds at each stop of the fastest of these trips, null where the data
 * has no time. [medianTrip] is their median trip (see spec.md, "Reconstructing the line").
 */
internal class Run(
    val routeId: String,
    val stations: List<String>,
    val arrivals: List<Long?>,
    val departures: List<Long?>,
    val medianTrip: TripTimes,
) {
    /** The seconds from leaving stop [from] to reaching the later stop [to], both given by index. */
    fun secs(from: Int, to: Int): Long? {
        val departure = departures[from] ?: return null
        return arrivals[to]?.minus(departure)
    }

    /** The share of the travel time from stop [from] to stop [to] that lies before the stop [at] between them. */
    fun share(from: Int, at: Int, to: Int): Double? {
        val before = secs(from, at) ?: return null
        val after = secs(at, to) ?: return null
        return if (before + after > 0) before.toDouble() / (before + after) else null
    }

    /**
     * The same trips seen in the other direction of the line: time runs backwards, so it is negated.
     * The [medianTrip] is only meant to be read in the direction of travel and stays as it is.
     */
    fun reversed() = Run(
        routeId,
        stations.reversed(),
        departures.reversed().map { it?.unaryMinus() },
        arrivals.reversed().map { it?.unaryMinus() },
        medianTrip,
    )
}

/** The numbers in the rule for detours (see spec.md, "Reconstructing the line"). */
class DetourRule(val factor: Double = 1.5, val stopAllowanceSecs: Long = 180)

/** The [runs] in both directions, to look up by station. */
internal class RunIndex(val runs: List<Run>, private val rule: DetourRule) {
    /** For each station, the runs stopping there, in either direction, with the index of that stop. */
    private val stoppingAt = HashMap<String, MutableList<Pair<Run, Int>>>().also { map ->
        for (run in runs + runs.map { it.reversed() }) {
            run.stations.forEachIndexed { i, station -> map.getOrPut(station) { ArrayList() }.add(run to i) }
        }
    }
    private val fastest = HashMap<Pair<String, String>, Long?>()

    /** Calls [action] with each run that stops at [a] and later at [b], and the indices of these two stops. */
    fun forEachRun(a: String, b: String, action: (run: Run, i: Int, j: Int) -> Unit) {
        for ((run, i) in stoppingAt[a].orEmpty()) {
            val j = i + 1 + run.stations.subList(i + 1, run.stations.size).indexOf(b)
            if (j > i) action(run, i, j)
        }
    }

    /** The seconds of the fastest trip from [a] to [b], null if no trip between them has times. */
    fun fastestSecs(a: String, b: String): Long? = fastest.getOrPut(a to b) {
        var best: Long? = null
        forEachRun(a, b) { run, i, j ->
            val secs = run.secs(i, j)
            if (secs != null && (best == null || secs < best!!)) best = secs
        }
        best
    }

    /** Whether [run] takes a detour between its stops [i] and [j]. Without times it counts as none. */
    fun isDetour(run: Run, i: Int, j: Int): Boolean {
        val secs = run.secs(i, j) ?: return false
        val fastest = fastestSecs(run.stations[i], run.stations[j]) ?: return false
        return secs > rule.factor * fastest + rule.stopAllowanceSecs * (j - i)
    }
}

/** From this divergence on, the warning about two stations ordered by travel time only is a strong one. */
internal const val STRONG_DIVERGENCE = 0.1

/**
 * Two neighbouring stations [first] and [second] of a line that are ordered by travel time only
 * (see spec.md, "Reconstructing the line"). They were inserted between [from] and [to], at the
 * shares [firstShare] and [secondShare] of the way. [divergence] is null if it is unknown.
 */
internal class TimeOrdered(
    val first: String,
    val second: String,
    val from: String,
    val to: String,
    val firstShare: Double,
    val secondShare: Double,
    val divergence: Double?,
) {
    val isStrong = divergence != null && divergence > STRONG_DIVERGENCE
}

/**
 * The [stations] of a line in their order, with their [positions] in seconds from the first station,
 * and its pairs of stations ordered by travel time only (see spec.md for the terms).
 */
internal class Line(val stations: List<String>, val positions: List<Double>, val timeOrdered: List<TimeOrdered>)

/**
 * A filler of a hop: the stations it stops at [between] the ends of the hop, the share of the way
 * at which each of them lies, and its seconds over the whole hop. Both are null without times.
 */
private class Filler(val between: List<String>, val shares: List<Double?>, val secs: Long?)

/** The in-between stations of the [fillers] of the hop from [from] to [to], put in their order. */
private class FilledHop(from: String, to: String, fillers: List<Filler>) {
    /** For each in-between station its share of the way: the median over the fillers stopping there. */
    private val shares: Map<String, Double> = run {
        val all = HashMap<String, MutableList<Double>>()
        for (filler in fillers) {
            filler.between.forEachIndexed { i, station ->
                val known = all.getOrPut(station) { ArrayList() }
                filler.shares[i]?.let { known += it }
            }
        }
        // A station that no filler has times for counts as lying in the middle.
        all.mapValues { (_, known) -> known.sorted().getOrNull(known.size / 2) ?: 0.5 }
    }

    /** The pairs of stations that some filler stops at in this order. */
    private val inOrder = HashSet<Pair<String, String>>().also { pairs ->
        for (filler in fillers) {
            filler.between.forEachIndexed { i, station ->
                for (later in filler.between.drop(i + 1)) pairs += station to later
            }
        }
    }

    /** Whether the fillers agree that [first] comes before [second]. */
    private fun isBefore(first: String, second: String) = (first to second) in inOrder && (second to first) !in inOrder

    /** The in-between stations: in the order of the fillers and, where they give none, of the shares. */
    val stations: List<String> = run {
        val remaining = shares.keys.toHashSet()
        val ordered = ArrayList<String>()
        while (remaining.isNotEmpty()) {
            // If the fillers contradict each other all around, the shares decide alone.
            val first = remaining.filter { station -> remaining.none { isBefore(it, station) } }.ifEmpty { remaining }
            // The station ID as tie-breaker makes the result the same on every run.
            val next = first.minWith(compareBy({ shares.getValue(it) }, { it }))
            ordered += next
            remaining -= next
        }
        ordered
    }

    val timeOrdered: List<TimeOrdered> = stations.zipWithNext().mapNotNull { (first, second) ->
        if ((first to second) in inOrder || (second to first) in inOrder) return@mapNotNull null

        fun fastestOnlyAt(station: String, other: String) =
            fillers.filter { station in it.between && other !in it.between }.mapNotNull { it.secs }.minOrNull()
        val secs = listOfNotNull(fastestOnlyAt(first, second), fastestOnlyAt(second, first)).sorted()
        val divergence = if (secs.size == 2 && secs[0] > 0) (secs[1] - secs[0]).toDouble() / secs[0] else null
        TimeOrdered(first, second, from, to, share(first), share(second), divergence)
    }

    fun share(station: String) = shares.getValue(station)
}

/** Reconstructs the line of a core route with the stations [core] (see spec.md, "Reconstructing the line"). */
internal fun reconstructLine(core: List<String>, index: RunIndex): Line {
    var line = core.distinct()
    val hopSecs = line.zipWithNext { a, b -> index.fastestSecs(a, b) ?: 0L }
    var positions = hopSecs.runningFold(0.0) { sum, secs -> sum + secs }
    val onLine = line.toHashSet()
    val timeOrdered = ArrayList<TimeOrdered>()
    do {
        val longer = ArrayList<String>()
        val longerPositions = ArrayList<Double>()
        for (hop in 0 until line.size - 1) {
            val (a, b) = line[hop] to line[hop + 1]
            longer += a
            longerPositions += positions[hop]
            val fillers = ArrayList<Filler>()
            index.forEachRun(a, b) { run, i, j ->
                val between = run.stations.subList(i + 1, j)
                val hasRepeats = between.toSet().size < between.size
                val isNew = between.isNotEmpty() && !hasRepeats && between.none { it in onLine }
                if (isNew && !index.isDetour(run, i, j)) {
                    fillers += Filler(between, (i + 1 until j).map { run.share(i, it, j) }, run.secs(i, j))
                }
            }
            val filled = FilledHop(a, b, fillers)
            for (station in filled.stations) {
                longer += station
                val position = positions[hop] + filled.share(station) * (positions[hop + 1] - positions[hop])
                // The order of the fillers wins over the shares, so a position never lies before the previous one.
                longerPositions += maxOf(position, longerPositions.last())
            }
            onLine += filled.stations
            timeOrdered += filled.timeOrdered
        }
        longer += line.last()
        longerPositions += positions.last()
        val grown = longer.size > line.size
        line = longer
        positions = longerPositions
    } while (grown)
    return Line(line, positions, timeOrdered)
}

/**
 * Three consecutive [stations] at which the trips of the routes [routeIds] stop while using the line,
 * without keeping one direction along it.
 */
internal class AgainstOrder(val stations: List<String>, val routeIds: Set<String>)

/** The stops of runs that go against the order of [line], each listed in one of its two directions. */
internal fun againstOrder(line: Line, index: RunIndex): List<AgainstOrder> {
    val place = line.stations.withIndex().associate { (i, station) -> station to i }
    val found = LinkedHashMap<List<String>, MutableSet<String>>()
    for (run in index.runs) {
        for (i in 0 until run.stations.size - 2) {
            val stops = run.stations.subList(i, i + 3)
            val places = stops.map { place[it] ?: continue }
            val turns = (places[1] - places[0]) * (places[2] - places[1]) < 0
            if (turns && !index.isDetour(run, i, i + 1) && !index.isDetour(run, i + 1, i + 2)) {
                val sameWay = minOf(stops, stops.reversed(), compareBy { it.joinToString() })
                found.getOrPut(sameWay) { LinkedHashSet() } += run.routeId
            }
        }
    }
    return found.map { (stations, routeIds) -> AgainstOrder(stations, routeIds) }
}

/** For each station of [line], the runs that use the line there (see spec.md), in the order of the runs. */
internal fun runsUsing(line: Line, index: RunIndex): Map<String, List<Run>> {
    val onLine = line.stations.toSet()
    val using = line.stations.associateWith { ArrayList<Run>() }
    for (run in index.runs) {
        val used = HashSet<String>()
        for (i in 0 until run.stations.size - 1) {
            val (a, b) = run.stations[i] to run.stations[i + 1]
            if (a in onLine && b in onLine && !index.isDetour(run, i, i + 1)) used += listOf(a, b)
        }
        for (station in used) using.getValue(station) += run
    }
    return using
}

/**
 * The stops [from] to [to] of [run], given by index, between which the trips use the line and keep
 * one direction along it: [forward] if that is the direction in which the line is listed.
 */
internal class Stretch(val run: Run, val from: Int, val to: Int, val forward: Boolean) {
    val stations: List<String> get() = run.stations.subList(from, to + 1)
}

/** The longest stretch of [run] on the line, the first one of several; null if it never uses the line. */
internal fun longestStretch(run: Run, place: Map<String, Int>, index: RunIndex): Stretch? {
    var best: Stretch? = null
    var from = 0
    var direction = 0
    for (i in run.stations.indices) {
        // The direction of the step to the next stop, 0 if the trips do not use the line on it.
        val step = run.stations.getOrNull(i + 1)?.let { next ->
            val here = place[run.stations[i]]
            val there = place[next]
            if (here == null || there == null || index.isDetour(run, i, i + 1)) 0 else (there - here).sign
        } ?: 0
        if (step != 0 && (direction == 0 || step == direction)) {
            direction = step
            continue
        }
        if (i > from && (best == null || i - from > best.to - best.from)) best = Stretch(run, from, i, direction > 0)
        // Where the trips turn around, the next stretch starts at this stop.
        from = if (step != 0) i else i + 1
        direction = step
    }
    return best
}

/** At most this many trains are timed on a line besides that of its core route. */
internal const val ADDITIONAL_TRAINS = 3

/**
 * A train whose times are shown along the line (see spec.md, "Reconstructing the line"): the median
 * trip of the run of [stretch]. [core] is the core route its trips belong to, if any.
 */
internal class TimedTrain(val stretch: Stretch, val route: Route, val core: CoreRoute?) {
    private val times = stretch.run.medianTrip

    /** The seconds since midnight at which the train leaves its first station on the line. */
    val departureSecs: Long? = times.departures[stretch.from]

    /**
     * The minutes after [departureSecs] at which the train arrives at [station], with those at
     * which it leaves again if it dwells for two minutes or more; empty if it does not stop there.
     */
    fun minutes(station: String): String {
        val stop = stretch.from + stretch.stations.indexOf(station)
        val start = departureSecs
        if (stop < stretch.from || start == null) return ""
        if (stop == stretch.from) return "0"

        fun minute(secs: Long) = ((secs - start) / 60.0).roundToInt()
        val arrival = times.arrivals[stop] ?: return ""
        val departure = times.departures[stop]
        val dwells = stop < stretch.to && departure != null && departure - arrival >= 120
        return if (dwells) "${minute(arrival)}–${minute(departure)}" else minute(arrival).toString()
    }
}

/**
 * The trains to time on [line]: that of the core route [scope] first, if its trips have times, then
 * up to [ADDITIONAL_TRAINS] more. [coreOf] gives the core route that the trips of a run belong to.
 */
internal fun timedTrains(
    line: Line,
    scope: CoreRoute,
    index: RunIndex,
    routeById: Map<String, Route>,
    coreOf: (Run) -> CoreRoute?,
): List<TimedTrain> {
    val place = line.stations.withIndex().associate { (i, station) -> station to i }
    val stretches = index.runs
        .mapNotNull { longestStretch(it, place, index) }
        .filter { it.run.medianTrip.departures[it.from] != null && it.run.routeId in routeById }

    fun train(stretch: Stretch) = TimedTrain(stretch, routeById.getValue(stretch.run.routeId), coreOf(stretch.run))
    val trains = ArrayList<TimedTrain>()
    val timed = HashSet<String>()

    fun add(stretch: Stretch) {
        trains += train(stretch)
        timed += stretch.stations
    }
    stretches.filter { it.forward && coreOf(it.run) === scope }.maxByOrNull { it.to - it.from }?.let(::add)
    val ofCore = trains.size
    while (trains.size < ofCore + ADDITIONAL_TRAINS && !timed.containsAll(line.stations)) {
        fun gain(stretch: Stretch) = stretch.stations.count { it !in timed }

        fun stops(stretch: Stretch) = stretch.to - stretch.from
        // The first train is the one with the most stops, the later ones fill what is left.
        val mostStops = trains.size == ofCore
        val best = compareByDescending<Stretch> { if (mostStops) stops(it) else gain(it) }
            .thenByDescending { if (mostStops) gain(it) else stops(it) }
            .thenBy(naturalOrder) { routeById.getValue(it.run.routeId).shortName }
            // The IDs as tie-breakers make the result the same on every run.
            .thenBy { it.run.routeId }
            .thenBy { it.run.stations.joinToString() }
        // Trains of core routes come first: their trips are the typical ones. Among those and among
        // the others, a train against the listed direction only gets its turn if no other one helps.
        val useful = stretches.filter { gain(it) > 0 }.groupBy { (coreOf(it.run) != null) to it.forward }
        val turns = listOf(true to true, true to false, false to true, false to false)
        add(turns.firstNotNullOfOrNull { useful[it]?.minWithOrNull(best) } ?: break)
    }
    return trains
}

/**
 * A station of a line with what stops there: [core] are the core routes with a trip using the line
 * here, [other] the routes where only trips of no such core route do. Both are in the order of the
 * routes.
 */
internal class LineStop(val stationId: String, val core: List<CoreRoute>, val other: List<Route>)

/** Sorts names with numbers by the value of the numbers: RE3 comes before RE16. */
internal val naturalOrder: Comparator<String> = compareBy { name ->
    Regex("[0-9]+").replace(name) { it.value.padStart(9, '0') }
}

/**
 * The reconstructed line of the core route [core]: its [stops], the [trains] timed along it and
 * the warnings about it. [routeOf] gives the route of each core route.
 */
internal class LineReport(
    val core: CoreRoute,
    val stops: List<LineStop>,
    val trains: List<TimedTrain>,
    val timeOrdered: List<TimeOrdered>,
    val againstOrder: List<AgainstOrder>,
    val routeOf: Map<CoreRoute, Route>,
) {
    /** The routes of the trips [againstOrder]. */
    fun routes(againstOrder: AgainstOrder) = routeOf.values.distinct().filter { it.id in againstOrder.routeIds }

    /** The names of the relevant routes on this line (see spec.md, "Reconstructing the line"). */
    private val routeLabels: Map<Route, String> = run {
        val stopping = stops.flatMap { stop -> stop.core.map(routeOf::getValue) + stop.other }
        val relevant = (stopping + routeOf.getValue(core)).distinct()
        val labels = HashMap<Route, String>()
        for ((name, sameName) in relevant.groupBy { it.shortName }) {
            for ((i, route) in sameName.sortedBy { it.id }.withIndex()) {
                labels[route] = when {
                    name.isEmpty() -> route.id
                    sameName.size > 1 -> "$name-${i + 1}"
                    else -> name
                }
            }
        }
        labels
    }

    /** The name of the route [route] on this line. */
    fun label(route: Route) = routeLabels.getValue(route)

    /** The name of [train] on this line: that of its core route or, if its trips belong to none, of its route. */
    fun label(train: TimedTrain) = train.core?.let(::label) ?: label(train.route)

    /** The name of the core route [core] on this line. */
    fun label(core: CoreRoute): String {
        val route = routeOf.getValue(core)
        val label = label(route)
        return when {
            label == route.shortName -> core.shortName
            route.coreRoutes.size == 1 -> label
            else -> label + "ab"[route.coreRoutes.indexOf(core)]
        }
    }

    /** The core routes with a core stop on the line, sorted by name. */
    val coreRoutes = stops.flatMap { it.core }.distinct().sortedWith(compareBy(naturalOrder, ::label))

    /** The routes with other trips stopping on the line and no core route among the [coreRoutes], sorted by name. */
    val additionalRoutes = (stops.flatMap { it.other }.toSet() - coreRoutes.map(routeOf::getValue).toSet())
        .sortedWith(compareBy(naturalOrder, ::label))
}

/** Reconstructs the line of the core route with the ID [coreRouteId] and finds the routes relevant for it. */
internal fun lineReport(coreRouteId: String, routes: List<Route>, index: RunIndex): LineReport {
    val routeOf = LinkedHashMap<CoreRoute, Route>()
    for (route in routes) for (core in route.coreRoutes) routeOf[core] = route
    val core = routeOf.keys.firstOrNull { it.id == coreRouteId } ?: error("No core route '$coreRouteId'")
    val line = reconstructLine(core.pattern.stationIds, index)
    val routeById = routes.associateBy { it.id }
    val using = runsUsing(line, index)

    /** The core route that the trips of the run belong to, if any. */
    fun Run.coreRoute() = routeById[routeId]?.coreRoutes?.firstOrNull { it.pattern.stations == stations.toSet() }
    val stops = line.stations.map { station ->
        val runs = using.getValue(station)
        val cores = runs.mapNotNull { it.coreRoute() }.toSet()
        val otherIds = runs.map { it.routeId }.toSet() - cores.map { routeOf.getValue(it).id }.toSet()
        // Filtering keeps the order of the routes.
        LineStop(station, routeOf.keys.filter { it in cores }, routes.filter { it.id in otherIds })
    }
    val trains = timedTrains(line, core, index, routeById) { it.coreRoute() }
    return LineReport(core, stops, trains, line.timeOrdered, againstOrder(line, index), routeOf)
}

/** The runs of rail routes, and the agency names of the routes by route_id. */
internal class RailRuns(val runs: List<Run>, val agencies: Map<String, String>)

/** The agencies whose trips are left out of the lines (see spec.md, "Data Model for the Graphical Timetable"). */
private val agenciesOnOwnTracks = setOf("S-Bahn Berlin GmbH", "S-Bahn Hamburg")

/** Loads the runs of the trips that run on at least one day, except those of the [agenciesOnOwnTracks]. */
internal fun loadRuns(railDir: Path): RailRuns {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))

    fun secsList(array: java.sql.Array?, size: Int): List<Long?> =
        (array?.array as Array<*>?)?.map { (it as Number?)?.toLong() } ?: List(size) { null }

    val runs = ArrayList<Run>()
    val agencies = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        // fast_rank 1 marks the fastest trip of a run, median_rank 1 its median trip: the fastest
        // one that at least half of the trip-days do not exceed in travel time.
        db.forEachRow("""
            WITH trip AS (
                SELECT t.route_id, t.trip_id, s.stations, s.arrivals, s.departures, s.travel_secs, sd.num_days
                FROM (
                    SELECT st.trip_id,
                           list(so.station_id ORDER BY st.stop_sequence) AS stations,
                           list(st.arrival_secs ORDER BY st.stop_sequence) AS arrivals,
                           list(st.departure_secs ORDER BY st.stop_sequence) AS departures,
                           max(st.arrival_secs) - min(st.departure_secs) AS travel_secs
                    FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
                    GROUP BY st.trip_id
                ) s JOIN ${p("trips")} t USING (trip_id) JOIN service_days sd USING (service_id)
            ), summed AS (
                SELECT *,
                       sum(num_days) OVER fastest_first AS days_so_far,
                       sum(num_days) OVER (PARTITION BY route_id, stations) AS days,
                       row_number() OVER fastest_first AS fast_rank
                FROM trip
                WINDOW fastest_first AS (PARTITION BY route_id, stations ORDER BY travel_secs, trip_id)
            ), ranked AS (
                SELECT *, row_number() OVER (
                           PARTITION BY route_id, stations
                           ORDER BY days_so_far < (days + 1) // 2, travel_secs, trip_id
                       ) AS median_rank
                FROM summed
            )
            SELECT route_id, stations,
                   any_value(arrivals) FILTER (fast_rank = 1), any_value(departures) FILTER (fast_rank = 1),
                   any_value(arrivals) FILTER (median_rank = 1), any_value(departures) FILTER (median_rank = 1)
            FROM ranked
            GROUP BY route_id, stations
            ORDER BY route_id, stations
        """) { rs ->
            val stations = (rs.getArray(2).array as Array<*>).map { it.toString() }
            // Two stops in a row at the same station are one stop, from the first arrival to the last departure.
            val firsts = stations.indices.filter { it == 0 || stations[it] != stations[it - 1] }
            val lasts = stations.indices.filter { it == stations.lastIndex || stations[it] != stations[it + 1] }

            fun arrivals(column: Int) = secsList(rs.getArray(column), stations.size).let { all ->
                firsts.map { all[it] }
            }

            fun departures(column: Int) = secsList(rs.getArray(column), stations.size).let { all ->
                lasts.map { all[it] }
            }
            runs += Run(
                rs.getString(1),
                firsts.map { stations[it] },
                arrivals(3),
                departures(4),
                TripTimes(arrivals(5), departures(6)),
            )
        }
        db.forEachRow("""
            SELECT r.route_id, a.agency_name
            FROM ${p("routes")} r JOIN ${p("agency")} a USING (agency_id)
            WHERE a.agency_name IS NOT NULL
        """) { rs -> agencies[rs.getString(1)] = rs.getString(2) }
    }
    return RailRuns(runs.filter { agencies[it.routeId] !in agenciesOnOwnTracks }, agencies)
}

private const val NO_AGENCY = "No agency"

/**
 * Renders the lines as a self-contained HTML page; its style and script are in `src/main/resources`.
 * [agencies] are the agency names by route_id.
 */
internal fun linesPage(
    reports: List<LineReport>,
    stationNames: Map<String, String>,
    agencies: Map<String, String>,
): String {
    fun agency(route: Route) = agencies[route.id] ?: NO_AGENCY

    fun names(names: List<String>) = names.sortedWith(naturalOrder).joinToString(", ")

    fun station(id: String) = stationNames.getValue(id)

    fun percent(share: Double) = "${(share * 100).roundToInt()}%"

    /** The time of day as on a station clock, also for trips running past midnight. */
    fun clock(secs: Long?) = if (secs == null) "" else "%02d:%02d".format(secs / 3600 % 24, secs / 60 % 60)

    /** A table row for something named [name] that stops at [stations] stations of the line. */
    fun TABLE.routeRow(name: String, pattern: Pattern, route: Route, stations: Int) = tr {
        td("name") { +name }
        td { +"${pattern.firstStation} – ${pattern.lastStation}" }
        td { +agency(route) }
        td { small { +(if (stations == 1) "1 station" else "$stations stations") } }
    }

    val tabs = reports.map { agency(it.routeOf.getValue(it.core)) }.distinct()
    val page = createHTML().html {
        lang = "de"
        head {
            meta(charset = "utf-8")
            title("Reconstructed lines of the testing routes")
            style { unsafe { raw(resource("lines.css")) } }
        }
        body {
            header {
                h1 { +"Reconstructed lines of the testing routes" }
                nav {
                    for (tab in tabs) button { +tab }
                }
            }
            for (report in reports) {
                val coreStations = report.core.pattern.stations
                section {
                    attributes["data-agency"] = agency(report.routeOf.getValue(report.core))
                    h2 {
                        val pattern = report.core.pattern
                        +"${report.label(report.core)} ${pattern.firstStation} – ${pattern.lastStation} "
                        small { +"${report.stops.size} stations, ${coreStations.size} of them in the core route" }
                    }
                    table("line") {
                        tr {
                            th { +"Station" }
                            for (train in report.trains) {
                                th(classes = "time") {
                                    +"${report.label(train)} "
                                    small { +clock(train.departureSecs) }
                                }
                            }
                            th { +"Routes with a core stop" }
                            th { +"Routes with other trips stopping" }
                        }
                        for (stop in report.stops) {
                            tr(if (stop.stationId in coreStations) "core" else null) {
                                td("name") { +stationNames.getValue(stop.stationId) }
                                for (train in report.trains) td("time") { +train.minutes(stop.stationId) }
                                td { +names(stop.core.map(report::label)) }
                                td { +names(stop.other.map(report::label)) }
                            }
                        }
                    }
                    if (report.timeOrdered.isNotEmpty() || report.againstOrder.isNotEmpty()) {
                        h3 { +"Warnings" }
                        ul {
                            for (pair in report.timeOrdered) {
                                li(if (pair.isStrong) "strong" else null) {
                                    +"Ordered by travel time only: "
                                    +"${station(pair.first)} before ${station(pair.second)}, "
                                    +"at ${percent(pair.firstShare)} and ${percent(pair.secondShare)} of the way "
                                    +"from ${station(pair.from)} to ${station(pair.to)}."
                                    if (pair.divergence != null) {
                                        +" The trips stopping at only one of them differ by "
                                        +"${percent(pair.divergence)} in travel time."
                                    }
                                }
                            }
                            for (trips in report.againstOrder) {
                                li {
                                    val stops = trips.stations.joinToString(" > ", transform = ::station)
                                    val routes = names(report.routes(trips).map(report::label))
                                    +"Against the order of the line: $stops ($routes)"
                                }
                            }
                        }
                    }
                    h3 { +"Routes with core stops on the line" }
                    table("routes") {
                        for (core in report.coreRoutes) {
                            val stations = report.stops.count { core in it.core }
                            routeRow(report.label(core), core.pattern, report.routeOf.getValue(core), stations)
                        }
                    }
                    if (report.additionalRoutes.isNotEmpty()) {
                        h3 { +"Additional routes with other trips stopping on the line" }
                        table("routes") {
                            for (route in report.additionalRoutes) {
                                val stations = report.stops.count { route in it.other }
                                routeRow(report.label(route), route.coreRoutes.first().pattern, route, stations)
                            }
                        }
                    }
                }
            }
            script { unsafe { raw(resource("lines.js")) } }
        }
    }
    return "<!DOCTYPE html>\n$page"
}

/**
 * Writes the test page of spec.md, "Reconstructing the line", for the core routes [coreRouteIds].
 */
fun lines(railDir: Path, outFile: Path, coreRouteIds: List<String>, rule: DetourRule = DetourRule()) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val loaded = loadRoutes(railDir)
    val railRuns = loadRuns(railDir)
    val index = RunIndex(railRuns.runs, rule)
    val reports = coreRouteIds.map { lineReport(it, loaded.routes, index) }
    Files.writeString(outFile, linesPage(reports, loaded.stationNames, railRuns.agencies))
    println("%,d lines -> %s".format(reports.size, outFile))
    for (report in reports) {
        println(
            "  %-6s %3d stations (%d in the core route), %d + %d routes, %d + %d warnings (%d strong)".format(
                report.core.shortName,
                report.stops.size,
                report.core.pattern.stations.size,
                report.coreRoutes.size,
                report.additionalRoutes.size,
                report.timeOrdered.size,
                report.againstOrder.size,
                report.timeOrdered.count { it.isStrong },
            ),
        )
    }
}

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: Lines <rail-parquet-dir> <out-html> <stop-allowance-secs> <detour-factor>" }
    lines(Path.of(args[0]), Path.of(args[1]), testRouteIds, DetourRule(args[3].toDouble(), args[2].toLong()))
}
