@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** "Most trips" of a route means more than this share of them. */
private const val MAJORITY = 0.5

private class TripGroup(
    val numTrips: Long,
    val numTripDays: Long,
    val stationIds: Set<String>,
    val firstStation: String,
    val lastStation: String,
)

private class Route(
    val id: String,
    val shortName: String,
    val type: Int,
    val groups: MutableList<TripGroup> = mutableListOf(),
)

/** How the most frequent trip group of a route relates to the stations that most of its trips serve. */
private class Check(groups: List<TripGroup>, weight: (TripGroup) -> Long) {
    private val weighted = groups.filter { weight(it) > 0 }.map { it to weight(it) }
    val total = weighted.sumOf { it.second }.toDouble()

    /** The first of the most frequent groups, so ties are broken by the order of [groups]. */
    val top = weighted.maxBy { it.second }.first
    val topShare = weighted.maxOf { it.second } / total
    val numGroups = weighted.size

    private val serving = HashMap<String, Long>().also { serving ->
        for ((group, w) in weighted) for (station in group.stationIds) serving.merge(station, w, Long::plus)
    }
    val majorityStations = serving.filterValues { it / total > MAJORITY }.keys
    val minTopStationShare = top.stationIds.minOf { serving.getValue(it) } / total

    val verdict = when {
        majorityStations.isEmpty() -> "no_majority"
        top.stationIds == majorityStations -> "equal"
        top.stationIds.containsAll(majorityStations) -> "top_longer"
        majorityStations.containsAll(top.stationIds) -> "top_shorter"
        else -> "different"
    }

    fun share(matches: (Set<String>) -> Boolean) =
        weighted.filter { matches(it.first.stationIds) }.sumOf { it.second } / total
}

/**
 * Data exploration (see spec.md, "Core Routes"): does the most frequent trip group of a route qualify
 * as its core route?
 *
 * A trip counts once for each day it runs (a *trip-day*), so that a trip running on a single day,
 * as in a construction timetable, weighs less than one running daily. The most frequent trip group
 * is the one with the most trip-days.
 *
 * The check compares two sets of stations per route, ignoring direction and order:
 *  - the *top stations*: those of the most frequent trip group;
 *  - the *majority stations*: those served on most trip-days of the route (more than [MAJORITY]).
 *
 * `verdict` column of the output CSV:
 *  - `equal`: both sets are the same; the top group is a core route by any reading of the definition.
 *  - `top_longer`: the top group serves all majority stations plus some that most trips don't serve.
 *  - `top_shorter`: most trips serve all top stations and further ones that the top group doesn't.
 *  - `different`: each set has stations that the other lacks.
 *  - `no_majority`: no station is served by most trips, so the route has no common core at all.
 *
 * `verdict_by_trips` is the same verdict when every trip counts once, however many days it runs
 * (as in the trip groups CSV, with group_no 1 as the top group).
 *
 * The remaining columns: route_id, route_short_name, route_type, then num_trip_days and num_groups of
 * the route, then shares of the route's trip-days (0 to 1):
 *  - `top_share`: the top group;
 *  - `same_stations_share`: trips serving exactly the top stations (the top group, its opposite
 *    direction, and any reordering);
 *  - `prolonged_share`: trips serving all top stations and more;
 *  - `partial_share`: trips serving only top stations, but not all of them (short runs, expresses);
 *  - `min_top_station_share`: trips serving the least served top station;
 * then num_top_stations, num_majority_stations, first_station and last_station of the top group,
 * `top_only` (top stations that are not majority stations) and `majority_only` (the reverse),
 * both as " | "-separated station names.
 *
 * Rows are sorted by route_type, route_short_name, then route_id. Routes without any trip-day are left out.
 */
fun coreRouteCheck(railDir: Path, outFile: Path) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val routes = LinkedHashMap<String, Route>()
    val stationNames = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        db.forEachRow("""
            SELECT route_id, route_short_name, route_type, num_trips, num_trip_days, station_ids,
                   first_station, last_station
            FROM trip_groups ORDER BY route_type, route_short_name, route_id, group_no
        """) { rs ->
            val route = routes.getOrPut(rs.getString(1)) { Route(rs.getString(1), rs.getString(2) ?: "", rs.getInt(3)) }
            val stationIds = (rs.getArray(6).array as Array<*>).map { it.toString() }.toSet()
            route.groups += TripGroup(rs.getLong(4), rs.getLong(5), stationIds, rs.getString(7), rs.getString(8))
        }
        db.forEachRow("SELECT station_id, station_name FROM station_names") { rs ->
            stationNames[rs.getString(1)] = rs.getString(2)
        }
    }

    fun names(ids: Set<String>) = ids.map { stationNames.getValue(it) }.sorted().joinToString(" | ")
    val verdicts = sortedMapOf<String, Int>()
    val verdictsByTrips = sortedMapOf<String, Int>()
    var written = 0
    Files.newBufferedWriter(outFile).use { out ->
        out.write(
            "route_id,route_short_name,route_type,verdict,verdict_by_trips,num_trip_days,num_groups,top_share," +
                "same_stations_share,prolonged_share,partial_share,min_top_station_share,num_top_stations," +
                "num_majority_stations,first_station,last_station,top_only,majority_only\n",
        )
        for (route in routes.values) {
            if (route.groups.none { it.numTripDays > 0 }) continue
            val check = Check(route.groups) { it.numTripDays }
            val byTrips = Check(route.groups) { it.numTrips }
            val top = check.top.stationIds
            verdicts.merge(check.verdict, 1, Int::plus)
            verdictsByTrips.merge(byTrips.verdict, 1, Int::plus)
            written++
            out.write(
                listOf(
                    route.id, route.shortName, route.type, check.verdict, byTrips.verdict,
                    check.total.toLong(), check.numGroups,
                    fraction(check.topShare),
                    fraction(check.share { it == top }),
                    fraction(check.share { it.size > top.size && it.containsAll(top) }),
                    fraction(check.share { it.size < top.size && top.containsAll(it) }),
                    fraction(check.minTopStationShare),
                    top.size, check.majorityStations.size,
                    check.top.firstStation, check.top.lastStation,
                    names(top - check.majorityStations), names(check.majorityStations - top),
                ).joinToString(",") { csvField(it.toString()) } + "\n",
            )
        }
    }
    println("%,d of %,d routes -> %s".format(written, routes.size, outFile))
    println("  %-12s %8s %8s".format("verdict", "by days", "by trips"))
    for (verdict in verdicts.keys + verdictsByTrips.keys) {
        println("  %-12s %,8d %,8d".format(verdict, verdicts[verdict] ?: 0, verdictsByTrips[verdict] ?: 0))
    }
}

private fun fraction(value: Double) = "%.2f".format(Locale.ROOT, value)

private fun csvField(value: String) =
    if (value.any { it in ",\"\n\r" }) "\"" + value.replace("\"", "\"\"") + "\"" else value

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: CoreRouteCheck <rail-parquet-dir> <out-csv>" }
    coreRouteCheck(Path.of(args[0]), Path.of(args[1]))
}
