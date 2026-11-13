@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Step 1 – basic data filtering (see spec.txt, "Basic data filtering").
 *
 * Keeps only routes with route_type 100..199 (rail, see "Extended GTFS Route Types.md"),
 * then the trips, stop_times, stops, calendar data and agencies that belong to them.
 * Output: one Parquet file per GTFS table in [outDir].
 *
 * All CSV columns are read as text (all_varchar) because GTFS IDs may have leading zeros and
 * times may exceed 24:00:00 (trips past midnight). Selected columns are cast explicitly, and
 * stop_times additionally gets arrival_secs/departure_secs (seconds since midnight of the service day).
 */
fun filterRail(gtfsDir: Path, outDir: Path) {
    Files.createDirectories(outDir)
    fun csv(name: String) = "read_csv(${sqlPath(gtfsDir.resolve("$name.txt"))}, header = true, all_varchar = true)"
    fun out(name: String) = sqlPath(outDir.resolve("$name.parquet"))
    fun copy(name: String, select: String) {
        val started = System.nanoTime()
        openDuckDb().use { db ->
            db.exec("COPY ($select) TO ${out(name)} (FORMAT parquet, COMPRESSION zstd)")
            val rows = db.queryLong("SELECT count(*) FROM ${out(name)}")
            println("%-15s %,12d rows  (%.1f s)".format(name, rows, (System.nanoTime() - started) / 1e9))
        }
    }
    fun hasFile(name: String) = gtfsDir.resolve("$name.txt").exists()

    copy("routes", """
        SELECT * REPLACE (CAST(route_type AS INTEGER) AS route_type)
        FROM ${csv("routes")}
        WHERE CAST(route_type AS INTEGER) BETWEEN 100 AND 199
    """)
    copy("trips", """
        SELECT t.* FROM ${csv("trips")} t
        WHERE t.route_id IN (SELECT route_id FROM ${out("routes")})
    """)
    copy("stop_times", """
        SELECT st.* REPLACE (CAST(st.stop_sequence AS INTEGER) AS stop_sequence),
               ${secs("st.arrival_time")} AS arrival_secs,
               ${secs("st.departure_time")} AS departure_secs
        FROM ${csv("stop_times")} st
        WHERE st.trip_id IN (SELECT trip_id FROM ${out("trips")})
        ORDER BY st.trip_id, CAST(st.stop_sequence AS INTEGER)
    """)
    // Stops used by rail trips, plus their parent stations (so platforms can be mapped to stations).
    copy("stops", """
        WITH all_stops AS (SELECT * FROM ${csv("stops")}),
             used AS (SELECT DISTINCT stop_id FROM ${out("stop_times")})
        SELECT * FROM all_stops
        WHERE stop_id IN (SELECT stop_id FROM used)
           OR stop_id IN (SELECT parent_station FROM all_stops WHERE stop_id IN (SELECT stop_id FROM used))
    """)
    if (hasFile("calendar")) copy("calendar", """
        SELECT * FROM ${csv("calendar")}
        WHERE service_id IN (SELECT service_id FROM ${out("trips")})
    """)
    if (hasFile("calendar_dates")) copy("calendar_dates", """
        SELECT * FROM ${csv("calendar_dates")}
        WHERE service_id IN (SELECT service_id FROM ${out("trips")})
    """)
    if (hasFile("agency")) copy("agency", """
        SELECT * FROM ${csv("agency")}
        WHERE agency_id IN (SELECT agency_id FROM ${out("routes")})
    """)
}

/** SQL expression converting a GTFS time "H:MM:SS" (hours may be >= 24) to seconds, NULL if empty. */
private fun secs(col: String) = """
    CASE WHEN coalesce(trim($col), '') = '' THEN NULL ELSE
        CAST(split_part(trim($col), ':', 1) AS INTEGER) * 3600 +
        CAST(split_part(trim($col), ':', 2) AS INTEGER) * 60 +
        CAST(split_part(trim($col), ':', 3) AS INTEGER) END"""

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: FilterRail <gtfs-dir> <out-dir>" }
    val gtfsDir = Path.of(args[0])
    require(gtfsDir.resolve("routes.txt").exists()) {
        "No routes.txt in '$gtfsDir'. Download and unpack the GTFS data set first (see ReadMe.md)."
    }
    filterRail(gtfsDir, Path.of(args[1]))
}
