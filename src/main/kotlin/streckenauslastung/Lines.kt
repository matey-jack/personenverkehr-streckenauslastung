@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import kotlinx.html.FlowContent
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
 * A station of a line with the relevant routes stopping there: [core] are those with a trip of
 * one of their core routes using the line here, [other] those where only other trips do.
 */
internal class LineStop(val stationId: String, val core: List<Route>, val other: List<Route>)

/** The reconstructed line of the core route [core] of [route]: its [stops] and [unplaced] stations. */
internal class LineReport(val route: Route, val core: CoreRoute, val stops: List<LineStop>, val unplaced: Set<String>) {
    /** The relevant routes with a core stop on the line. */
    val coreRoutes = stops.flatMap { it.core }.distinct()

    /** The relevant routes without a core stop on the line. */
    val otherRoutes = stops.flatMap { it.other }.distinct() - coreRoutes.toSet()
}

/** Reconstructs the line of the core route with the ID [coreRouteId] and finds the routes relevant for it. */
internal fun lineReport(coreRouteId: String, routes: List<Route>, index: RunIndex): LineReport {
    val route = routes.firstOrNull { route -> route.coreRoutes.any { it.id == coreRouteId } }
        ?: error("No core route '$coreRouteId'")
    val core = route.coreRoutes.first { it.id == coreRouteId }
    val line = reconstructLine(core.pattern.stationIds, index)
    val routeById = routes.associateBy { it.id }
    val using = runsUsing(line, index)

    fun Run.isCore() = routeById[routeId]?.coreRoutes?.any { it.pattern.stations == stations.toSet() } == true
    val stops = line.stations.map { station ->
        val runs = using.getValue(station)
        val coreIds = runs.filter { it.isCore() }.map { it.routeId }.toSet()
        val otherIds = runs.map { it.routeId }.toSet() - coreIds
        // Filtering the routes keeps their order.
        LineStop(station, routes.filter { it.id in coreIds }, routes.filter { it.id in otherIds })
    }
    return LineReport(route, core, stops, line.unplaced)
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

    fun name(route: Route) = route.shortName.ifEmpty { route.id }

    fun names(routes: List<Route>) = routes.map(::name).distinct().joinToString(", ")

    /** One table row per route, with the number of stations of the line where it stops. */
    fun FlowContent.routeTable(report: LineReport, routes: List<Route>) = table("routes") {
        for (route in routes) {
            val pattern = route.coreRoutes.first().pattern
            tr {
                td("name") { +name(route) }
                td { +"${pattern.firstStation} – ${pattern.lastStation}" }
                td { +agency(route) }
                td { small { +"${report.stops.count { route in it.core || route in it.other }} stations" } }
            }
        }
    }

    val tabs = reports.map { agency(it.route) }.distinct()
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
                    attributes["data-agency"] = agency(report.route)
                    h2 {
                        val pattern = report.core.pattern
                        +"${report.core.shortName} ${pattern.firstStation} – ${pattern.lastStation} "
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
                                td { +names(stop.core) }
                                // A short name can belong to several routes; it is not repeated here.
                                td { +names(stop.other.filter { other -> stop.core.none { name(it) == name(other) } }) }
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
                    routeTable(report, report.coreRoutes)
                    if (report.otherRoutes.isNotEmpty()) {
                        h3 { +"Routes with only other trips stopping on the line" }
                        routeTable(report, report.otherRoutes)
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
                report.otherRoutes.size,
            ),
        )
    }
}

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: Lines <rail-parquet-dir> <out-html> <stop-allowance-secs> <detour-factor>" }
    lines(Path.of(args[0]), Path.of(args[1]), testRouteIds, DetourRule(args[3].toDouble(), args[2].toLong()))
}
