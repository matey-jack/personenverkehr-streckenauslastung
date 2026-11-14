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
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
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
import kotlinx.html.unsafe
import java.nio.file.Files
import java.nio.file.Path

/** The core routes used for testing (see spec.md, "Reconstructing the line"), by their route_id. */
internal val testRouteIds = listOf(
    "2233941_106",
    "7149172_106",
    "de:VBB:12070424|R-Bahn|RE6:_106:a",
    "4740564_106",
    "de:VBB:11000000|R-Bahn|RE2:DB_106",
)

/**
 * All trips of the route [routeId] that stop at the same [stations] in the same order, counted in
 * [tripDays]. [arrivals] and [departures] are the seconds at each stop of the fastest of these trips,
 * null where the data has no time.
 */
internal class Run(
    val routeId: String,
    val stations: List<String>,
    val arrivals: List<Long?>,
    val departures: List<Long?>,
    val tripDays: Long,
) {
    /** The seconds from leaving stop [from] to reaching the later stop [to], both given by index. */
    fun secs(from: Int, to: Int): Long? {
        val departure = departures[from] ?: return null
        return arrivals[to]?.minus(departure)
    }

    /** The same trips seen in the other direction of the line: time runs backwards, so it is negated. */
    fun reversed() = Run(
        routeId,
        stations.reversed(),
        departures.reversed().map { it?.unaryMinus() },
        arrivals.reversed().map { it?.unaryMinus() },
        tripDays,
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

    private fun fastestSecs(a: String, b: String): Long? = fastest.getOrPut(a to b) {
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

/** The [stations] of a line in their order, and the [unplaced] stations (see spec.md for the terms). */
internal class Line(val stations: List<String>, val unplaced: Set<String>)

/** Reconstructs the line of a core route with the stations [core] (see spec.md, "Reconstructing the line"). */
internal fun reconstructLine(core: List<String>, index: RunIndex): Line {
    var line = core.distinct()
    val onLine = line.toHashSet()
    val inFillers = HashSet<String>()
    do {
        val longer = ArrayList<String>()
        for ((a, b) in line.zipWithNext()) {
            longer += a
            val fillers = HashMap<List<String>, Long>()
            index.forEachRun(a, b) { run, i, j ->
                val between = run.stations.subList(i + 1, j)
                val hasRepeats = between.toSet().size < between.size
                val isNew = between.isNotEmpty() && !hasRepeats && between.none { it in onLine }
                if (isNew && !index.isDetour(run, i, j)) {
                    fillers.merge(between, run.tripDays, Long::plus)
                    inFillers += between
                }
            }
            // The tie-breakers make the result the same on every run.
            val order =
                compareBy<Map.Entry<List<String>, Long>>({ it.value }, { it.key.size }, { it.key.joinToString() })
            val best = fillers.entries.maxWithOrNull(order)
            if (best != null) {
                longer += best.key
                onLine += best.key
            }
        }
        longer += line.last()
        val grown = longer.size > line.size
        line = longer
    } while (grown)
    return Line(line, inFillers - onLine)
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
 * The reconstructed line of the core route [core]: its [stops] and [unplaced] stations.
 * [routeOf] gives the route of each core route.
 */
internal class LineReport(
    val core: CoreRoute,
    val stops: List<LineStop>,
    val unplaced: Set<String>,
    val routeOf: Map<CoreRoute, Route>,
) {
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
    return LineReport(core, stops, line.unplaced, routeOf)
}

/** The runs of all rail routes, and the agency names of the routes by route_id. */
internal class RailRuns(val runs: List<Run>, val agencies: Map<String, String>)

/** Loads the runs of the trips that run on at least one day. */
internal fun loadRuns(railDir: Path): RailRuns {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))

    fun secsList(array: java.sql.Array?, size: Int): List<Long?> =
        (array?.array as Array<*>?)?.map { (it as Number?)?.toLong() } ?: List(size) { null }

    val runs = ArrayList<Run>()
    val agencies = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        db.forEachRow("""
            SELECT t.route_id, s.stations, arg_min(s.arrivals, s.travel_secs), arg_min(s.departures, s.travel_secs),
                   sum(sd.num_days)::BIGINT
            FROM (
                SELECT st.trip_id,
                       list(so.station_id ORDER BY st.stop_sequence) AS stations,
                       list(st.arrival_secs ORDER BY st.stop_sequence) AS arrivals,
                       list(st.departure_secs ORDER BY st.stop_sequence) AS departures,
                       max(st.arrival_secs) - min(st.departure_secs) AS travel_secs
                FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
                GROUP BY st.trip_id
            ) s JOIN ${p("trips")} t USING (trip_id) JOIN service_days sd USING (service_id)
            GROUP BY t.route_id, s.stations
            ORDER BY t.route_id, s.stations
        """) { rs ->
            val stations = (rs.getArray(2).array as Array<*>).map { it.toString() }
            val arrivals = secsList(rs.getArray(3), stations.size)
            val departures = secsList(rs.getArray(4), stations.size)
            // Two stops in a row at the same station are one stop, from the first arrival to the last departure.
            val firsts = stations.indices.filter { it == 0 || stations[it] != stations[it - 1] }
            val lasts = stations.indices.filter { it == stations.lastIndex || stations[it] != stations[it + 1] }
            runs += Run(
                rs.getString(1),
                firsts.map { stations[it] },
                firsts.map { arrivals[it] },
                lasts.map { departures[it] },
                rs.getLong(5),
            )
        }
        db.forEachRow("""
            SELECT r.route_id, a.agency_name
            FROM ${p("routes")} r JOIN ${p("agency")} a USING (agency_id)
            WHERE a.agency_name IS NOT NULL
        """) { rs -> agencies[rs.getString(1)] = rs.getString(2) }
    }
    return RailRuns(runs, agencies)
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
                            th { +"Routes with a core stop" }
                            th { +"Routes with other trips stopping" }
                        }
                        for (stop in report.stops) {
                            tr(if (stop.stationId in coreStations) "core" else null) {
                                td("name") { +stationNames.getValue(stop.stationId) }
                                td { +names(stop.core.map(report::label)) }
                                td { +names(stop.other.map(report::label)) }
                            }
                        }
                    }
                    if (report.unplaced.isNotEmpty()) {
                        p {
                            +"Unplaced stations: "
                            +report.unplaced.map { stationNames.getValue(it) }.sorted().joinToString(", ")
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
            "  %-6s %3d stations (%d in the core route), %d unplaced, %d + %d routes".format(
                report.core.shortName,
                report.stops.size,
                report.core.pattern.stations.size,
                report.unplaced.size,
                report.coreRoutes.size,
                report.additionalRoutes.size,
            ),
        )
    }
}

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: Lines <rail-parquet-dir> <out-html> <stop-allowance-secs> <detour-factor>" }
    lines(Path.of(args[0]), Path.of(args[1]), testRouteIds, DetourRule(args[3].toDouble(), args[2].toLong()))
}
