@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path

/** Two patterns have a similar frequency if the rarer one has at least this share of the other's trip-days. */
private const val SIMILAR_FREQUENCY = 0.8

/**
 * Data exploration (see spec.md, "Core Routes"): how well do the most frequent patterns of a route
 * match its majority stations? Trips running on fewer than [minDays] days are ignored altogether.
 *
 * Verdicts are written as in the core routes CSV (see [coreRoutes]).
 *
 * Output CSV columns: route_id, route_short_name, route_type, num_trip_days and num_patterns of the
 * route, num_majority_stations, then for the most frequent pattern (`top1_`) and the second most
 * frequent one (`top2_`, empty for routes with a single pattern):
 *  - `verdict`;
 *  - `share`: its share of the route's trip-days (0 to 1);
 *  - `first_station`, `last_station`: of its most frequent trip group;
 * then
 *  - `best_rank`: frequency rank of the pattern with the smallest difference (the most frequent
 *    one of them, if several have it), and `best_verdict`, its verdict;
 *  - `top1_extra`, `top1_lacking`: the extra and lacking stations of the most frequent pattern,
 *    as " | "-separated station names.
 *
 * Rows are sorted by route_type, route_short_name, then route_id. Routes without any trip left are left out.
 */
fun coreRouteCheck(railDir: Path, outFile: Path, minDays: Int) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val (routes, stationNames) = loadRoutes(railDir, minDays).let { it.routes to it.stationNames }

    fun names(ids: Set<String>) = ids.map { stationNames.getValue(it) }.sorted().joinToString(" | ")
    val summary = LinkedHashMap<String, Int>()
    fun count(label: String, condition: Boolean) = summary.merge(label, if (condition) 1 else 0, Int::plus)
    Files.newBufferedWriter(outFile).use { out ->
        out.write(
            "route_id,route_short_name,route_type,num_trip_days,num_patterns,num_majority_stations," +
                "top1_verdict,top1_share,top1_first_station,top1_last_station," +
                "top2_verdict,top2_share,top2_first_station,top2_last_station," +
                "best_rank,best_verdict,top1_extra,top1_lacking\n",
        )
        for (route in routes) {
            val patterns = route.patterns
            val total = route.tripDays
            val majorityStations = route.majorityStations
            val differences = route.differences
            val top1 = differences[0]
            val top2 = differences.getOrNull(1)
            val bestRank = route.closest + 1
            val best = differences[route.closest]

            count("top 1 equals the majority stations", top1.size == 0)
            count("top 1 or top 2 equals them", top1.size == 0 || top2?.size == 0)
            count("some pattern equals them", best.size == 0)
            count("top 1 has the smallest difference of all patterns", top1.size == best.size)
            count("top 1 or top 2 has it", minOf(top1.size, top2?.size ?: top1.size) == best.size)
            count("top 2 is closer to the majority stations than top 1", top2 != null && top2.size < top1.size)
            val shorterThenLonger = top2 != null && top1.extra.isEmpty() && top2.lacking.isEmpty()
            val longerThenShorter = top2 != null && top1.lacking.isEmpty() && top2.extra.isEmpty()
            count(
                "neither top equals them, but one is shorter and the other longer",
                (shorterThenLonger || longerThenShorter) && top1.size > 0 && top2.size > 0,
            )
            count(
                "top 2 has a similar frequency as top 1 (at least ${(SIMILAR_FREQUENCY * 100).toInt()}%)",
                patterns.size > 1 && patterns[1].tripDays >= SIMILAR_FREQUENCY * patterns[0].tripDays,
            )

            fun top(rank: Int) = patterns.getOrNull(rank - 1)?.let {
                listOf(differences[rank - 1].verdict, fraction(it.tripDays / total), it.firstStation, it.lastStation)
            } ?: listOf("", "", "", "")
            val routeColumns = listOf(route.id, route.shortName, route.type, total.toLong(), patterns.size)
            val bestColumns = listOf(bestRank, best.verdict, names(top1.extra), names(top1.lacking))
            val row = routeColumns + majorityStations.size + top(1) + top(2) + bestColumns
            out.write(row.joinToString(",") { csvField(it.toString()) } + "\n")
        }
    }
    println("%,d routes -> %s (trips running on at least %d days)".format(routes.size, outFile, minDays))
    for ((label, n) in summary) println("  %,5d  %4.1f%%  %s".format(n, 100.0 * n / routes.size, label))
}

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: CoreRouteCheck <rail-parquet-dir> <out-csv> <min-days>" }
    coreRouteCheck(Path.of(args[0]), Path.of(args[1]), args[2].toInt())
}
