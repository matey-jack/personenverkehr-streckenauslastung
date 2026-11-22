@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** A route core candidate needs at least this share of the trip days of the route core (see spec.md, "Timetable terms"). */
internal const val CANDIDATE_SHARE = 0.6

/** The trips of a route that stop at the same [stationIds] in the same order, with their [tripDays]. */
internal class TripGroup(val stationIds: List<String>, val tripDays: Long, val travelSecs: Map<Long, Long> = emptyMap())

/** Values counted in trip days: for each value, its trip days. All three are null if there are no values. */
internal class Distribution(counts: Map<Long, Long>) {
    private val values = counts.keys.sorted()
    val min: Long? = values.firstOrNull()
    val max: Long? = values.lastOrNull()

    /** The value that half of the trip days do not exceed. */
    val median: Long? = run {
        var daysLeft = (counts.values.sum() + 1) / 2
        values.firstOrNull { value ->
            daysLeft -= counts.getValue(value)
            daysLeft <= 0
        }
    }
}

/**
 * A route core or route core candidate (see spec.md, "Timetable terms"), with its own [id] and [shortName].
 * [stationIds] is its typical stop sequence, from its start to its end, and [stationNames] are their names.
 * [intermediateStops] and [travelSecs] are counted over the trips between its two ends.
 */
internal class RouteCore(
    val id: String,
    val shortName: String,
    val stationIds: List<String>,
    val stationNames: List<String>,
    val tripDays: Long,
    val intermediateStops: Distribution = Distribution(emptyMap()),
    val travelSecs: Distribution = Distribution(emptyMap()),
) {
    val stations = stationIds.toSet()
    val firstStation get() = stationNames.first()
    val lastStation get() = stationNames.last()
}

/**
 * A GTFS route with its trip [groups], all of which run on at least one day. [stationNames] gives the
 * name of each station ID; the start of a route core is the end with the alphabetically earlier name.
 */
internal class Route(
    val id: String,
    val shortName: String,
    val type: Int,
    val groups: List<TripGroup>,
    stationNames: Map<String, String>,
) {
    val tripDays = groups.sumOf { it.tripDays }

    /** The route core and its candidates, in this order. */
    val cores: List<RouteCore> = run {
        fun name(station: String) = stationNames[station] ?: station
        val byName = compareBy(::name).thenBy { it }

        /** The two ends of a trip group, start first. */
        fun ends(group: TripGroup): Pair<String, String> {
            val (a, b) = group.stationIds.first() to group.stationIds.last()
            return if (byName.compare(a, b) <= 0) a to b else b to a
        }
        val byEnds = groups.groupBy(::ends)
        val tripDaysOf = byEnds.mapValues { (_, groups) -> groups.sumOf { it.tripDays } }
        val ranked = byEnds.keys.sortedWith(
            compareByDescending<Pair<String, String>> { tripDaysOf.getValue(it) }
                .thenBy(byName) { it.first }
                .thenBy(byName) { it.second },
        )
        val best = tripDaysOf.getValue(ranked.first())
        val chosen = ranked.filter { tripDaysOf.getValue(it) >= CANDIDATE_SHARE * best }
        val suffixes = if (shortName.lastOrNull()?.isLetter() == true) {
            chosen.indices.map { "-${it + 1}" }
        } else {
            chosen.indices.map { "${'a' + it}" }
        }
        chosen.mapIndexed { i, ends ->
            val own = byEnds.getValue(ends)
            val stops = HashMap<Long, Long>()
            val travel = HashMap<Long, Long>()
            for (group in own) {
                stops.merge(maxOf(group.stationIds.size - 2, 0).toLong(), group.tripDays, Long::plus)
                for ((secs, days) in group.travelSecs) travel.merge(secs, days, Long::plus)
            }
            val sequence = typicalStopSequence(ends.first, ends.second, if (ends.first == ends.second) own else groups)
            RouteCore(
                id = if (chosen.size == 1) id else "$id:${'a' + i}",
                shortName = if (chosen.size == 1) shortName else shortName + suffixes[i],
                stationIds = sequence,
                stationNames = sequence.map(::name),
                tripDays = tripDaysOf.getValue(ends),
                intermediateStops = Distribution(stops),
                travelSecs = Distribution(travel),
            )
        }
    }
}

/**
 * The typical stop sequence from [start] to [end] (see spec.md, "Timetable terms") among [groups]. If
 * [start] and [end] are the same station, the [groups] are those of the route core itself, taken whole.
 */
internal fun typicalStopSequence(start: String, end: String, groups: List<TripGroup>): List<String> {
    val tripDays = LinkedHashMap<List<String>, Long>()
    for (group in groups) {
        val stations = group.stationIds
        val i = stations.indexOf(start)
        val j = stations.indexOf(end)
        if (i < 0 || j < 0) continue
        val part = when {
            start == end -> stations
            i < j -> stations.subList(i, j + 1)
            else -> stations.subList(j, i + 1).reversed()
        }
        tripDays.merge(part, group.tripDays, Long::plus)
    }
    // The station IDs as tie-breaker make the result the same on every run.
    return tripDays.entries
        .sortedWith(compareByDescending<Map.Entry<List<String>, Long>> { it.value }.thenBy { it.key.joinToString() })
        .first().key
}

/** The rail routes and the names of their stations by station ID. */
internal class Routes(val routes: List<Route>, val stationNames: Map<String, String>)

/** Loads the routes with the trip groups that run on at least one day, sorted by route_type, route_short_name, then route_id. */
internal fun loadRoutes(railDir: Path): Routes {
    class Row(val id: String, val shortName: String, val type: Int, val groups: MutableList<TripGroup>)

    val rows = LinkedHashMap<String, Row>()
    val stationNames = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir, minDays = 1)
        db.forEachRow("""
            SELECT route_id, route_short_name, route_type, num_trip_days, station_ids, trip_travel_secs, trip_days
            FROM trip_groups WHERE num_trip_days > 0
            ORDER BY route_type, route_short_name, route_id, group_no
        """) { rs ->
            val row = rows.getOrPut(rs.getString(1)) {
                Row(rs.getString(1), rs.getString(2) ?: "", rs.getInt(3), ArrayList())
            }
            val stationIds = (rs.getArray(5).array as Array<*>).map { it.toString() }
            val tripDays = (rs.getArray(7).array as Array<*>).map { (it as Number).toLong() }
            val travelSecs = HashMap<Long, Long>()
            (rs.getArray(6).array as Array<*>).forEachIndexed { i, secs ->
                if (secs != null) travelSecs.merge((secs as Number).toLong(), tripDays[i], Long::plus)
            }
            row.groups += TripGroup(stationIds, rs.getLong(4), travelSecs)
        }
        db.forEachRow("SELECT station_id, station_name FROM station_names") { rs ->
            stationNames[rs.getString(1)] = rs.getString(2)
        }
    }
    val routes = rows.values.map { Route(it.id, it.shortName, it.type, it.groups, stationNames) }
    return Routes(routes, stationNames)
}

/**
 * Writes the route cores and route core candidates of all rail routes (see spec.md, "Timetable terms").
 *
 * Output CSV columns: route_id and route_short_name (with the suffixes of the route core, if the
 * route has candidates), route_type, then for the route core:
 *  - `share`: its share of the route's trip days (0 to 1);
 *  - `num_trip_days`;
 *  - `min_stops_between`, `median_stops_between`, `max_stops_between`: of its trips;
 *  - `min_travel_mins`, `median_travel_mins`, `max_travel_mins`: of its trips, empty if they have no times;
 *  - first_station, last_station, stations (" > "-separated station names): of its typical stop sequence.
 *
 * Rows are sorted by route_type, route_short_name, then route_id.
 */
fun routeCores(railDir: Path, outFile: Path) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val routes = loadRoutes(railDir).routes
    Files.newBufferedWriter(outFile).use { out ->
        out.write(
            "route_id,route_short_name,route_type,share,num_trip_days," +
                "min_stops_between,median_stops_between,max_stops_between," +
                "min_travel_mins,median_travel_mins,max_travel_mins,first_station,last_station,stations\n",
        )
        for (route in routes) {
            for (core in route.cores) {
                val share = fraction(core.tripDays.toDouble() / route.tripDays)
                val stops = core.intermediateStops.let { listOf(it.min, it.median, it.max) }
                val secs = core.travelSecs.let { listOf(it.min, it.median, it.max) }
                val mins = secs.map { it?.let(::roundedMinutes) }
                val stations = listOf(core.firstStation, core.lastStation, core.stationNames.joinToString(" > "))
                val row = listOf(core.id, core.shortName, route.type, share, core.tripDays) + stops + mins + stations
                out.write(row.joinToString(",") { csvField(it?.toString() ?: "") } + "\n")
            }
        }
    }
    val withCandidates = routes.count { it.cores.size > 1 }
    println("%,d routes, %,d of them with route core candidates -> %s".format(routes.size, withCandidates, outFile))
}

private fun roundedMinutes(secs: Long) = (secs + 30) / 60

internal fun fraction(value: Double) = "%.2f".format(Locale.ROOT, value)

internal fun csvField(value: String) =
    if (value.any { it in ",\"\n\r" }) "\"" + value.replace("\"", "\"\"") + "\"" else value

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: RouteCores <rail-parquet-dir> <out-csv>" }
    routeCores(Path.of(args[0]), Path.of(args[1]))
}
