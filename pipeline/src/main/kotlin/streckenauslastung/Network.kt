@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

/** The route types of the regional network (see todo.md, "Reconstructing lines, regional part"). */
internal val regionalRouteTypes = listOf(106, 109)

/** The seconds at which one trip arrives at and departs from each of its stops, and its days. */
internal class TimedTrip(val arrivals: List<Long>, val departures: List<Long>, val days: Long) {
    /** The seconds from leaving stop [from] to reaching stop [to], both given by index, in either order. */
    fun secs(from: Int, to: Int) = arrivals[maxOf(from, to)] - departures[minOf(from, to)]
}

/**
 * A trip of a regional route as loaded: its [stations], [times], whether its service has a weekly
 * pattern, and the [dates] added to its service (yyyyMMdd).
 */
internal class LoadedTrip(
    val routeId: String,
    val stations: List<String>,
    val times: TimedTrip,
    val weekly: Boolean,
    val dates: Set<String>,
)

/** The trips of the route [routeId] that stop at the same [stations] in the same order. */
internal class StopPattern(val routeId: String, val stations: List<String>, val trips: List<TimedTrip>)

/**
 * The stop patterns of [trips] that the 10-date rule keeps (see todo.md, "Reconstructing lines,
 * regional part"): those with a trip that has a weekly pattern, and those whose trips together run
 * on at least [minDates] dates.
 */
internal fun stopPatterns(trips: List<LoadedTrip>, minDates: Int): List<StopPattern> =
    trips.groupBy { it.routeId to it.stations }
        .filter { (_, same) -> same.any { it.weekly } || same.flatMap { it.dates }.toSet().size >= minDates }
        .map { (key, same) -> StopPattern(key.first, key.second, same.map { it.times }) }

/**
 * The rule for consistent spans (see pipeline/spec.md, "Derivation from regional train data only"): a span
 * between the two ends of a hop is consistent if its median travel time is at most [factor] times
 * that of the hop plus [stopAllowanceSecs] for each stop between the ends, plus [extraSecs].
 */
class SpanRule(val factor: Double = 1.33, val stopAllowanceSecs: Long = 180, val extraSecs: Long = 120) {
    fun isConsistent(spanSecs: Long, hopSecs: Long, stopsBetween: Int) =
        spanSecs <= factor * hopSecs + stopAllowanceSecs * stopsBetween + extraSecs

    /**
     * Whether a span is faster than the hop, although it has more stops: the hop takes longer than
     * [factor] times the span plus [extraSecs].
     */
    fun isFaster(spanSecs: Long, hopSecs: Long) = hopSecs > factor * spanSecs + extraSecs
}

/** The two stations of a hop or line section, without a direction: [a] is the one with the smaller ID. */
internal data class Hop(val a: String, val b: String)

internal fun hop(x: String, y: String) = if (x < y) Hop(x, y) else Hop(y, x)

/** The travel times of some trips over the same stretch, each with its trip days; at least one trip once in use. */
internal class TravelTimes {
    private val times = ArrayList<Pair<Long, Long>>()

    /** The trip days of all the trips. */
    var tripDays = 0L
        private set

    fun add(secs: Long, days: Long) {
        tripDays += days
        times += secs to days
    }

    /** The median of the travel times, counted in trip days. */
    fun median(): Long {
        var sum = 0L
        for ((secs, days) in times.sortedBy { it.first }) {
            sum += days
            if (2 * sum >= tripDays) return secs
        }
        error("no travel times")
    }
}

/** A sequence of [stations] that some warning is about, with the travel times of each of its [parts]. */
internal class Involved(val stations: List<String>, val parts: List<TravelTimes>)

/** The kinds of warnings, with the text that explains them in the report. */
internal enum class WarningKind(val label: String, val text: String) {
    INCONSISTENT_SPANS("inconsistent-spans", "a hop has only spans that take too long, so it stays a line section"),
    FASTER_SPAN("faster-span", "a span with more stops is faster than the hop it fills"),
    PARALLEL_LINES("parallel-lines", "two or more lines connect the same two stations"),
    LOOP("loop", "a line starts and ends at the same junction"),
    RING("ring", "a line forms a ring without any junction"),
    UNCONNECTABLE("unconnectable", "no way through the network connects the two stations of a hop"),
    AMBIGUOUS("ambiguous", "two ways on different lines match the travel time of a hop about equally well"),
    DETOUR("detour", "no way between the two stations of a hop is allowed by the span rule; the shortest is taken"),
    REVERSAL("reversal", "the path of a stop pattern or route core uses a line section twice"),
    CORE_LEFT_OUT(
        "route-core-left-out",
        "the typical stop sequence of a route core is in no stop pattern kept by the 10-date rule",
    ),
}

/** A warning of the kind [kind] about the [stations] (the ends of the hop or lines), with what is [involved]. */
internal class NetworkWarning(val kind: WarningKind, val stations: List<String>, val involved: List<Involved>)

/**
 * A line: its [stations] in order, from an end to an end, and the travel times of each of its
 * sections. A ring repeats its first station at the end.
 */
internal class NetLine(val stations: List<String>, val sections: List<TravelTimes>) {
    val isRing get() = stations.first() == stations.last() && stations.size > 2
}

/**
 * The derived network: all [hops] and its line [sections] with their travel times, its lines and the
 * warnings. [passSavingSecs] is the time a train saves for each station it passes without stopping:
 * the median of (span − hop) / stops between over the consistent spans, counted in trip days.
 */
internal class Network(
    val hops: Map<Hop, TravelTimes>,
    val sections: Map<Hop, TravelTimes>,
    val lines: List<NetLine>,
    val warnings: List<NetworkWarning>,
    val passSavingSecs: Long,
) {
    val degree: Map<String, Int> = HashMap<String, Int>().also { degree ->
        for (section in sections.keys) for (station in listOf(section.a, section.b)) degree.merge(station, 1, Int::plus)
    }
}

/**
 * Derives the line sections and lines from the stop [patterns] (see pipeline/spec.md, "Derivation from
 * regional train data only"). [name] gives the name of a station, which orders lines and stations.
 */
internal fun deriveNetwork(patterns: List<StopPattern>, name: (String) -> String, rule: SpanRule): Network {
    val hops = LinkedHashMap<Hop, TravelTimes>()
    val at = HashMap<String, MutableList<Pair<StopPattern, Int>>>()
    for (pattern in patterns) {
        pattern.stations.forEachIndexed { i, station -> at.getOrPut(station) { ArrayList() } += pattern to i }
        for (i in 0 until pattern.stations.size - 1) {
            val times = hops.getOrPut(hop(pattern.stations[i], pattern.stations[i + 1])) { TravelTimes() }
            for (trip in pattern.trips) times.add(trip.secs(i, i + 1), trip.days)
        }
    }
    val warnings = ArrayList<NetworkWarning>()
    val sections = LinkedHashMap<Hop, TravelTimes>()
    val savings = TravelTimes()
    for ((hop, hopTimes) in hops) {
        val spans = spans(hop, at)
        val hopSecs = hopTimes.median()
        val consistent = spans.filter { (stations, times) ->
            rule.isConsistent(
                times.median(),
                hopSecs,
                stations.size - 2,
            )
        }
        val hopInvolved = Involved(listOf(hop.a, hop.b), listOf(hopTimes))

        fun warn(kind: WarningKind, shown: Map<List<String>, TravelTimes>) {
            val involved = listOf(hopInvolved) + shown.map { (stations, times) -> Involved(stations, listOf(times)) }
            warnings += NetworkWarning(kind, listOf(hop.a, hop.b), involved)
        }
        if (consistent.isEmpty()) {
            sections[hop] = hopTimes
            if (spans.isNotEmpty()) warn(WarningKind.INCONSISTENT_SPANS, spans)
        } else {
            for ((stations, times) in consistent) {
                savings.add((times.median() - hopSecs) / (stations.size - 2), times.tripDays)
            }
            val faster = consistent.filter { rule.isFaster(it.value.median(), hopSecs) }
            if (faster.isNotEmpty()) warn(WarningKind.FASTER_SPAN, faster)
        }
    }
    val lines = joinLines(sections, name)
    warnings += lineWarnings(lines)
    val order = compareBy<NetworkWarning> { it.kind }.thenBy(stationOrder(name)) { it.stations }
    val passSavingSecs = if (savings.tripDays > 0) savings.median() else 0
    return Network(hops, sections, lines, warnings.sortedWith(order), passSavingSecs)
}

/**
 * The spans of the patterns in [at] between the two ends of [hop], listed from [Hop.a] to
 * [Hop.b], with their travel times. A span stops at each of its stations once.
 */
private fun spans(hop: Hop, at: Map<String, List<Pair<StopPattern, Int>>>): Map<List<String>, TravelTimes> {
    val spans = LinkedHashMap<List<String>, TravelTimes>()
    for ((pattern, i) in at[hop.a].orEmpty()) {
        val stations = pattern.stations
        for (step in listOf(1, -1)) {
            var k = i + step
            while (k in stations.indices && stations[k] != hop.a && stations[k] != hop.b) k += step
            if (k !in stations.indices || stations[k] != hop.b || (k - i) * step < 2) continue
            val span = if (step > 0) stations.subList(i, k + 1) else stations.subList(k, i + 1).reversed()
            if (span.toSet().size < span.size) continue
            val times = spans.getOrPut(span) { TravelTimes() }
            for (trip in pattern.trips) times.add(trip.secs(i, k), trip.days)
        }
    }
    return spans
}

/** Orders lists of stations by their names, then by their IDs. */
internal fun stationOrder(name: (String) -> String): Comparator<List<String>> = Comparator { x, y ->
    for ((a, b) in x.zip(y)) {
        val byName = name(a).compareTo(name(b))
        if (byName != 0) return@Comparator byName
        val byId = a.compareTo(b)
        if (byId != 0) return@Comparator byId
    }
    x.size - y.size
}

/**
 * Joins the [sections] to lines between junctions and terminals; the sections left over form
 * rings. Each line is listed in the direction in which its stations come first by [stationOrder].
 */
private fun joinLines(sections: Map<Hop, TravelTimes>, name: (String) -> String): List<NetLine> {
    val order = stationOrder(name)
    val neighbours = HashMap<String, MutableList<String>>()
    for (section in sections.keys) {
        neighbours.getOrPut(section.a) { ArrayList() } += section.b
        neighbours.getOrPut(section.b) { ArrayList() } += section.a
    }
    val used = HashSet<Hop>()

    /** Follows the sections from [start] over [first] until a station that is no stopping station, or [start]. */
    fun walk(start: String, first: String): List<String> {
        val stations = arrayListOf(start, first)
        used += hop(start, first)
        while (stations.last() != start && neighbours.getValue(stations.last()).size == 2) {
            val here = stations.last()
            val next = neighbours.getValue(here).firstOrNull { hop(here, it) !in used } ?: break
            used += hop(here, next)
            stations += next
        }
        return stations
    }
    val byName = neighbours.keys.sortedWith(compareBy(order) { listOf(it) })
    val found = ArrayList<List<String>>()
    for (end in byName.filter { neighbours.getValue(it).size != 2 }) {
        for (next in neighbours.getValue(end)) if (hop(end, next) !in used) found += walk(end, next)
    }
    for (start in byName) {
        for (next in neighbours.getValue(start)) if (hop(start, next) !in used) found += walk(start, next)
    }
    return found
        .map { minOf(it, it.reversed(), order) }
        .sortedWith(order)
        .map { stations -> NetLine(stations, stations.zipWithNext { a, b -> sections.getValue(hop(a, b)) }) }
}

/** The warnings about [lines]: parallel lines, loops and rings. */
private fun lineWarnings(lines: List<NetLine>): List<NetworkWarning> {
    fun involved(line: NetLine) = Involved(line.stations, line.sections)
    val warnings = ArrayList<NetworkWarning>()
    for (line in lines) {
        val ends = listOf(line.stations.first(), line.stations.last())
        if (line.isRing) {
            warnings += NetworkWarning(WarningKind.RING, ends.take(1), listOf(involved(line)))
        } else if (ends[0] == ends[1]) {
            warnings += NetworkWarning(WarningKind.LOOP, ends.take(1), listOf(involved(line)))
        }
    }
    val withEnds = lines.filter { it.stations.first() != it.stations.last() }
    for (parallel in withEnds.groupBy { hop(it.stations.first(), it.stations.last()) }.values) {
        if (parallel.size < 2) continue
        val stations = parallel.first().stations.let { listOf(it.first(), it.last()) }
        warnings += NetworkWarning(WarningKind.PARALLEL_LINES, stations, parallel.map(::involved))
    }
    return warnings
}

/** The name and coordinates of a station; the coordinates are null if the data has none. */
internal class StationInfo(val name: String, val lat: Double?, val lon: Double?)

/** The short name (after `routeNameFixes`), route type and agency name of a route. */
internal class RouteInfo(val shortName: String, val type: Int, val agency: String?)

/** The regional trips, routes and the stations of the rail data, and the number of [untimed] trips left out. */
internal class RegionalInput(
    val trips: List<LoadedTrip>,
    val routes: Map<String, RouteInfo>,
    val stations: Map<String, StationInfo>,
    val untimed: Int,
)

/**
 * Loads the trips of the regional routes that run on at least one day (see todo.md,
 * "Reconstructing lines, regional part"), with their stops mapped to stations as for the hub stations.
 * Trips without an arrival or departure time at some stop are left out.
 */
internal fun loadRegionalTrips(railDir: Path): RegionalInput {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))

    fun secsList(array: java.sql.Array): List<Long>? =
        (array.array as Array<*>).map { (it as Number?)?.toLong() ?: return null }

    val trips = ArrayList<LoadedTrip>()
    var untimed = 0
    val stations = HashMap<String, StationInfo>()
    val routes = HashMap<String, RouteInfo>()
    val excluded = agenciesOnOwnTracks.joinToString { "'${it.replace("'", "''")}'" }
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        db.forEachRow("""
            WITH weekly AS (
                SELECT service_id FROM ${p("calendar")}
                WHERE '1' IN (monday::VARCHAR, tuesday::VARCHAR, wednesday::VARCHAR, thursday::VARCHAR,
                              friday::VARCHAR, saturday::VARCHAR, sunday::VARCHAR)
            ), added AS (
                SELECT service_id, list(DISTINCT date) AS dates
                FROM ${p("calendar_dates")} WHERE exception_type::INTEGER = 1 GROUP BY service_id
            ), stops AS (
                SELECT st.trip_id,
                       list(so.station_id ORDER BY st.stop_sequence) AS stations,
                       list(st.arrival_secs ORDER BY st.stop_sequence) AS arrivals,
                       list(st.departure_secs ORDER BY st.stop_sequence) AS departures
                FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
                GROUP BY st.trip_id
            )
            SELECT t.route_id, s.stations, s.arrivals, s.departures, sd.num_days,
                   w.service_id IS NOT NULL AS weekly, coalesce(a.dates, []) AS dates
            FROM ${p("trips")} t
            JOIN stops s USING (trip_id)
            JOIN service_days sd USING (service_id)
            JOIN ${p("routes")} r USING (route_id)
            LEFT JOIN ${p("agency")} ag USING (agency_id)
            LEFT JOIN weekly w USING (service_id)
            LEFT JOIN added a USING (service_id)
            WHERE r.route_type IN (${regionalRouteTypes.joinToString()})
              AND coalesce(ag.agency_name, '') NOT IN ($excluded)
            ORDER BY t.route_id, t.trip_id
        """) { rs ->
            val all = (rs.getArray(2).array as Array<*>).map { it.toString() }
            val arrivals = secsList(rs.getArray(3))
            val departures = secsList(rs.getArray(4))
            if (arrivals == null || departures == null) {
                untimed++
                return@forEachRow
            }
            // Two stops in a row at the same station are one stop, from the first arrival to the last departure.
            val firsts = all.indices.filter { it == 0 || all[it] != all[it - 1] }
            val lasts = all.indices.filter { it == all.lastIndex || all[it] != all[it + 1] }
            trips += LoadedTrip(
                rs.getString(1),
                firsts.map { all[it] },
                TimedTrip(firsts.map { arrivals[it] }, lasts.map { departures[it] }, rs.getLong(5)),
                rs.getBoolean(6),
                (rs.getArray(7).array as Array<*>).map { it.toString() }.toSet(),
            )
        }
        db.forEachRow("""
            SELECT r.route_id, r.route_short_name, r.route_type, ag.agency_name
            FROM ${p("routes")} r LEFT JOIN ${p("agency")} ag USING (agency_id)
        """) { rs ->
            val id = rs.getString(1)
            routes[id] = RouteInfo(routeNameFixes[id] ?: rs.getString(2) ?: "", rs.getInt(3), rs.getString(4))
        }
        // The coordinates of a station are those of its own stop or, if the data has none, the mean of its parts.
        db.forEachRow("""
            SELECT so.station_id, coalesce(any_value(n.station_name), so.station_id),
                   coalesce(avg(TRY_CAST(s.stop_lat AS DOUBLE)) FILTER (s.stop_id = so.station_id), avg(TRY_CAST(s.stop_lat AS DOUBLE))),
                   coalesce(avg(TRY_CAST(s.stop_lon AS DOUBLE)) FILTER (s.stop_id = so.station_id), avg(TRY_CAST(s.stop_lon AS DOUBLE)))
            FROM station_of so
            JOIN ${p("stops")} s USING (stop_id)
            LEFT JOIN station_names n ON n.station_id = so.station_id
            GROUP BY so.station_id
        """) { rs ->
            fun double(column: Int) = rs.getDouble(column).takeUnless { rs.wasNull() }
            stations[rs.getString(1)] = StationInfo(rs.getString(2), double(3), double(4))
        }
    }
    val regional = trips.map { it.routeId }.toSet()
    return RegionalInput(trips, routes.filterKeys { it in regional }, stations, untimed)
}

/** The parameters the network is derived with. */
@Serializable
internal class NetworkSource(
    val gtfs: JsonObject?,
    val routeTypes: List<Int>,
    val excludedAgencies: List<String>,
    val minDates: Int,
    val detourFactor: Double,
    val stopAllowanceSecs: Long,
    val extraSecs: Long,
)

/** The nodes of the network, as columns: one entry per node. */
@Serializable
internal class NetworkNodes(
    val id: List<String>,
    val name: List<String>,
    val lat: List<Int?>,
    val lon: List<Int?>,
    val isStation: List<Boolean>,
)

/** The lines of the network, as columns: one entry per line. */
@Serializable
internal class NetworkLines(
    val nodes: List<List<Int>>,
    val medianSecs: List<List<Long>>,
    val tripDays: List<List<Long>>,
)

/**
 * The file `network.json`: the regional rail network.
 *
 * - [format] is always "streckenauslastung-network"; [version] is raised with every change that
 *   readers have to adapt to.
 * - [source]: the GTFS data set as described by `data/rail/metadata.json5` (null without that file) and
 *   the parameters the network is derived with (see the `network` step in pipeline/ReadMe.md).
 * - Tables are objects of columns of equal length; other tables refer to a row by its index.
 * - [nodes]: the GTFS station ID (after the joins of `stationParts` in `TripGroups.kt`), name,
 *   latitude and longitude in units of 10⁻⁵ degrees (null where the data has none) and whether the
 *   node is a station. Nodes are sorted by name, then ID. Whether a node is a junction, terminal or
 *   stopping station follows from the number of line sections it connects to.
 * - [lines]: the nodes of each line in order, from an end to an end; a ring repeats its first node at
 *   the end. A line is listed in the direction in which its node names come first alphabetically.
 *   For each section, from one node to the next, `medianSecs` is the median travel time of the
 *   trips over the hop, counted in trip days, and `tripDays` their trip days.
 */
@Serializable
internal class NetworkFile(
    val format: String,
    val version: Int,
    val source: NetworkSource,
    val nodes: NetworkNodes,
    val lines: NetworkLines,
)

internal const val NETWORK_FORMAT = "streckenauslastung-network"
internal const val NETWORK_VERSION = 1

/** The IDs of the nodes of [network] in their order in the network file: by name, then ID. */
internal fun nodeIds(network: Network, stations: Map<String, StationInfo>): List<String> =
    network.lines.flatMap { it.stations }.distinct().sortedWith(compareBy({ stations.getValue(it).name }, { it }))

/** The network file of [network]; [stations] gives the names and coordinates. */
internal fun networkFile(network: Network, stations: Map<String, StationInfo>, source: NetworkSource): NetworkFile {
    val ids = nodeIds(network, stations)
    val index = ids.withIndex().associate { (i, id) -> id to i }

    fun coordinate(degrees: Double?) = degrees?.let { (it * 100_000).roundToInt() }
    val nodes = NetworkNodes(
        ids,
        ids.map { stations.getValue(it).name },
        ids.map { coordinate(stations.getValue(it).lat) },
        ids.map { coordinate(stations.getValue(it).lon) },
        ids.map { true },
    )
    val lines = NetworkLines(
        network.lines.map { line -> line.stations.map(index::getValue) },
        network.lines.map { line -> line.sections.map { it.median() } },
        network.lines.map { line -> line.sections.map { it.tripDays } },
    )
    return NetworkFile(NETWORK_FORMAT, NETWORK_VERSION, source, nodes, lines)
}

/** Quotes a CSV field if it needs it. */
private fun csv(field: String) =
    if (field.any { it == ',' || it == '"' || it == '\n' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

/**
 * The warnings report: one row per warning with its kind, the names and IDs of the stations it is
 * about, and for each sequence of stations involved the median travel times and trip days of its parts.
 */
internal fun warningsCsv(warnings: List<NetworkWarning>, name: (String) -> String): String = buildString {
    appendLine("kind,stations,station_ids,involved")
    for (warning in warnings) {
        val involved = warning.involved.joinToString("; ") { part ->
            val minutes = part.parts.joinToString(" + ") { times ->
                String.format(Locale.ROOT, "%.1f", times.median() / 60.0)
            }
            val days = part.parts.joinToString(" + ") { it.tripDays.toString() }
            "${part.stations.joinToString(" > ", transform = name)}: $minutes min, $days trip days"
        }
        val fields = listOf(
            warning.kind.label,
            warning.stations.joinToString(" – ", transform = name),
            warning.stations.joinToString(" "),
            involved,
        )
        appendLine(fields.joinToString(",", transform = ::csv))
    }
}

/** Reads the JSON5 file `metadata.json5` describing the GTFS data set of [railDir]; null if there is none. */
@OptIn(ExperimentalSerializationApi::class)
internal fun gtfsMetadata(railDir: Path): JsonObject? {
    val file = railDir.resolve("metadata.json5")
    if (!Files.exists(file)) return null
    val json5 = Json {
        isLenient = true
        allowComments = true
        allowTrailingComma = true
    }
    return json5.decodeFromString<JsonObject>(Files.readString(file))
}

/**
 * Derives the regional network from the rail data in [railDir] and writes it to [outFile] (see
 * [NetworkFile]), the routes on it to [servicesFile] (see [ServicesFile]) and the warnings about both to
 * [warningsFile] (see [warningsCsv]). [pathCandidates] is the number of ways searched per hop.
 */
fun network(
    railDir: Path,
    outFile: Path,
    servicesFile: Path,
    warningsFile: Path,
    rule: SpanRule = SpanRule(),
    minDates: Int = 10,
    pathCandidates: Int = 5,
) {
    for (f in listOf(outFile, servicesFile, warningsFile)) {
        f.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    }
    val input = loadRegionalTrips(railDir)
    val patterns = stopPatterns(input.trips, minDates)

    fun name(id: String) = input.stations[id]?.name ?: id
    val network = deriveNetwork(patterns, ::name, rule)
    val source = NetworkSource(
        gtfsMetadata(railDir),
        regionalRouteTypes,
        agenciesOnOwnTracks.toList(),
        minDates,
        rule.factor,
        rule.stopAllowanceSecs,
        rule.extraSecs,
    )
    val services = deriveServices(input, patterns, network, PathFinder(network, rule, pathCandidates))
    val warnings = (network.warnings + services.warnings)
        .sortedWith(compareBy<NetworkWarning> { it.kind }.thenBy(stationOrder(::name)) { it.stations })
    Files.writeString(outFile, Json.encodeToString(networkFile(network, input.stations, source)) + "\n")
    val servicesSource = ServicesSource(pathCandidates, network.passSavingSecs)
    val ids = nodeIds(network, input.stations)
    Files.writeString(servicesFile, Json.encodeToString(servicesFile(services, ids, servicesSource)) + "\n")
    Files.writeString(warningsFile, warningsCsv(warnings, ::name))

    val routes = input.trips.map { it.routeId }.toSet()
    val keptRoutes = patterns.map { it.routeId }.toSet()
    val keptTrips = patterns.sumOf { it.trips.size }
    val allPatterns = input.trips.map { it.routeId to it.stations }.toSet().size
    println("%,d regional trips left out for missing times".format(input.untimed))
    println(
        "%,d regional trips in %,d stop patterns of %,d routes; the 10-date rule leaves out %,d trips, %,d stop patterns and %,d routes"
            .format(
                input.trips.size,
                allPatterns,
                routes.size,
                input.trips.size - keptTrips,
                allPatterns - patterns.size,
                routes.size - keptRoutes.size,
            ),
    )
    val degrees = network.degree.values
    println(
        "%,d line sections between %,d stations: %,d junctions, %,d terminals, %,d stopping stations".format(
            network.sections.size,
            degrees.size,
            degrees.count { it >= 3 },
            degrees.count { it == 1 },
            degrees.count { it == 2 },
        ),
    )
    println("%,d lines -> %s".format(network.lines.size, outFile))
    println("%d s saved per station passed without stopping".format(network.passSavingSecs))
    println("hops of stop patterns and route cores by how their path was found:")
    for (outcome in PathOutcome.entries) {
        println("  %,6d %s".format(services.outcomes[outcome] ?: 0, outcome.text))
    }
    val nodes = ids.toSet()
    val summary = "%,d routes, %,d route cores (%,d without a path), %,d regional hub stations (%,d of them no node)"
    println(
        "$summary -> %s".format(
            services.routes.size,
            services.cores.size,
            services.cores.count { it.path == null },
            services.hubs.size,
            services.hubs.count { it.stationId !in nodes },
            servicesFile,
        ),
    )
    println("%,d warnings -> %s".format(warnings.size, warningsFile))
    for ((kind, ofKind) in warnings.groupBy { it.kind }) {
        println("  %,6d %s: %s".format(ofKind.size, kind.label, kind.text))
    }
}

fun main(args: Array<String>) {
    require(args.size == 9) {
        "usage: Network <rail-parquet-dir> <out-json> <services-json> <warnings-csv> " +
            "<stop-allowance-secs> <detour-factor> <extra-allowance-secs> <min-dates> <path-candidates>"
    }
    network(
        Path.of(args[0]),
        Path.of(args[1]),
        Path.of(args[2]),
        Path.of(args[3]),
        SpanRule(args[5].toDouble(), args[4].toLong(), args[6].toLong()),
        args[7].toInt(),
        args[8].toInt(),
    )
}
