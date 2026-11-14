@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** "Most trips" of a route means more than this share of them. */
internal const val MAJORITY = 0.5

/**
 * All trips of a route serving the same set of stations, in either direction and any order.
 * [stationIds], [numStops], [firstStation], [lastStation] and [stationNames] are those of its most
 * frequent trip group.
 */
internal class Pattern(
    val stationIds: List<String>,
    val numStops: Int,
    val firstStation: String,
    val lastStation: String,
    val stationNames: String,
) {
    val stations = stationIds.toSet()

    /** The trips of the pattern, each counted once per day it runs. */
    var tripDays = 0L
}

/** How a pattern differs from the majority stations of its route. */
internal class Difference(pattern: Pattern, majorityStations: Set<String>) {
    val extra = pattern.stations - majorityStations
    val lacking = majorityStations - pattern.stations
    val size = extra.size + lacking.size

    val verdict = when {
        majorityStations.isEmpty() -> "no_majority"
        size == 0 -> "equal"
        lacking.isEmpty() -> "longer +${extra.size}"
        extra.isEmpty() -> "shorter -${lacking.size}"
        else -> "different +${extra.size} -${lacking.size}"
    }
}

/** A core route of a GTFS route (see spec.md, "Core Routes"), with its own ID and short name. */
internal class CoreRoute(val id: String, val shortName: String, val pattern: Pattern, val difference: Difference)

/** A GTFS route with its [patterns], most frequent first. */
internal class Route(val id: String, val shortName: String, val type: Int, val patterns: List<Pattern>) {
    val tripDays = patterns.sumOf { it.tripDays }.toDouble()

    /** The stations served on most trip-days of the route. */
    val majorityStations: Set<String> = HashMap<String, Long>().also { serving ->
        for (p in patterns) for (station in p.stations) serving.merge(station, p.tripDays, Long::plus)
    }.filterValues { it / tripDays > MAJORITY }.keys

    /** For each of the [patterns], its difference from the [majorityStations]. */
    val differences = patterns.map { Difference(it, majorityStations) }

    /** Index of the pattern with the smallest difference; the most frequent one, if several have it. */
    val closest = differences.indices.minBy { differences[it].size }

    /**
     * The most frequent pattern and, if it is another one, the closest pattern. A route without
     * majority stations has nothing to be close to, so only its most frequent pattern is a core route.
     */
    val coreRoutes: List<CoreRoute> = run {
        if (closest == 0 || majorityStations.isEmpty()) {
            return@run listOf(CoreRoute(id, shortName, patterns[0], differences[0]))
        }
        val nameSuffixes = if (shortName.lastOrNull()?.isLetter() == true) listOf("-1", "-2") else listOf("a", "b")
        listOf(0, closest).mapIndexed { i, p ->
            CoreRoute("$id:${"ab"[i]}", shortName + nameSuffixes[i], patterns[p], differences[p])
        }
    }
}

/** The rail routes and the names of their stations by station ID. */
internal class Routes(val routes: List<Route>, val stationNames: Map<String, String>)

/**
 * Loads the routes with their patterns, sorted by route_type, route_short_name, then route_id.
 * Trips running on fewer than [minDays] days are ignored, as are routes without any trip left.
 */
internal fun loadRoutes(railDir: Path, minDays: Int = 1): Routes {
    class Row(val id: String, val shortName: String, val type: Int, val patterns: LinkedHashMap<Set<String>, Pattern>)

    val rows = LinkedHashMap<String, Row>()
    val stationNames = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir, minDays)
        db.forEachRow("""
            SELECT route_id, route_short_name, route_type, num_trip_days, station_ids, num_stops,
                   first_station, last_station, stations
            FROM trip_groups WHERE num_trip_days > 0
            ORDER BY route_type, route_short_name, route_id, num_trip_days DESC, group_no
        """) { rs ->
            val row = rows.getOrPut(rs.getString(1)) {
                Row(rs.getString(1), rs.getString(2) ?: "", rs.getInt(3), LinkedHashMap())
            }
            val stationIds = (rs.getArray(5).array as Array<*>).map { it.toString() }
            val pattern = row.patterns.getOrPut(stationIds.toSet()) {
                Pattern(stationIds, rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9))
            }
            pattern.tripDays += rs.getLong(4)
        }
        db.forEachRow("SELECT station_id, station_name FROM station_names") { rs ->
            stationNames[rs.getString(1)] = rs.getString(2)
        }
    }
    val routes = rows.values.map { row ->
        // The sort is stable, so patterns with equally many trip-days keep the order of their trip groups.
        Route(row.id, row.shortName, row.type, row.patterns.values.sortedByDescending { it.tripDays })
    }
    return Routes(routes, stationNames)
}

/**
 * Writes the core routes of all rail routes (see spec.md, "Core Routes").
 *
 * Output CSV columns: route_id and route_short_name (with the suffixes of the core route, if the
 * route has two), route_type, then for the core route's pattern:
 *  - `share`: its share of the route's trip-days (0 to 1);
 *  - `verdict`: how it compares with the majority stations of the route:
 *    `equal`; `longer +N` (N extra stations); `shorter -N` (N majority stations lacking);
 *    `different +N -M` (N extra, M lacking); `no_majority` (the route has no majority stations);
 *  - num_stops, first_station, last_station, stations (" > "-separated station names): of its most
 *    frequent trip group, so in one of the two directions.
 *
 * Rows are sorted by route_type, route_short_name, then route_id.
 */
fun coreRoutes(railDir: Path, outFile: Path) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val routes = loadRoutes(railDir).routes
    Files.newBufferedWriter(outFile).use { out ->
        out.write("route_id,route_short_name,route_type,share,verdict,num_stops,first_station,last_station,stations\n")
        for (route in routes) {
            for (core in route.coreRoutes) {
                val p = core.pattern
                val share = fraction(p.tripDays / route.tripDays)
                val stationColumns = listOf(p.numStops, p.firstStation, p.lastStation, p.stationNames)
                val row = listOf(core.id, core.shortName, route.type, share, core.difference.verdict) + stationColumns
                out.write(row.joinToString(",") { csvField(it.toString()) } + "\n")
            }
        }
    }
    val two = routes.count { it.coreRoutes.size == 2 }
    println("%,d routes, %,d of them with two core routes -> %s".format(routes.size, two, outFile))
}

internal fun fraction(value: Double) = "%.2f".format(Locale.ROOT, value)

internal fun csvField(value: String) =
    if (value.any { it in ",\"\n\r" }) "\"" + value.replace("\"", "\"\"") + "\"" else value

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: CoreRoutes <rail-parquet-dir> <out-csv>" }
    coreRoutes(Path.of(args[0]), Path.of(args[1]))
}
