@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.lang
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.nio.file.Files
import java.nio.file.Path

/** The route types of long-distance routes; all other rail routes count as regional. */
private val longDistanceTypes = setOf(101, 102)

/**
 * A station as in [createTripGroupTable]. [longDistanceRoutes] and [regionalRoutes] are the short
 * names of the routes with a trip stopping here, one per route_id. [longDistanceTripDays] counts
 * the long-distance trips stopping here, each once per day it runs.
 */
internal class RailStation(
    val id: String,
    val name: String,
    val longDistanceTripDays: Long,
    val longDistanceRoutes: List<String>,
    val regionalRoutes: List<String>,
)

/** The [top] stations with the most long-distance trip-days, most first. */
internal fun longDistanceStations(stations: List<RailStation>, top: Int): List<RailStation> = stations
    .filter { it.longDistanceTripDays > 0 }
    .sortedWith(compareBy({ -it.longDistanceTripDays }, { it.name }, { it.id }))
    .take(top)

/** Loads all stations where a trip running on at least one day stops, sorted by station ID. */
internal fun loadRailStations(railDir: Path): List<RailStation> {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))
    val stations = ArrayList<RailStation>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        db.exec("""
            CREATE TABLE calls AS
            SELECT DISTINCT so.station_id, st.trip_id FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
        """)
        db.exec("""
            CREATE TABLE station_routes AS
            SELECT c.station_id, r.route_id, r.route_type IN (${longDistanceTypes.joinToString()}) AS long_distance,
                   coalesce(r.route_short_name, '') AS short_name, sum(sd.num_days)::BIGINT AS trip_days
            FROM calls c
            JOIN ${p("trips")} t USING (trip_id)
            JOIN ${p("routes")} r USING (route_id)
            JOIN service_days sd USING (service_id)
            GROUP BY ALL
        """)
        db.forEachRow("""
            SELECT station_id, n.station_name,
                   coalesce(sum(sr.trip_days) FILTER (sr.long_distance), 0)::BIGINT,
                   coalesce(list(sr.short_name ORDER BY sr.short_name, sr.route_id) FILTER (sr.long_distance), []),
                   coalesce(list(sr.short_name ORDER BY sr.short_name, sr.route_id) FILTER (NOT sr.long_distance), [])
            FROM station_routes sr JOIN station_names n USING (station_id)
            GROUP BY ALL
            ORDER BY station_id
        """) { rs ->
            fun names(column: Int) = (rs.getArray(column).array as Array<*>).map { it.toString() }
            stations += RailStation(rs.getString(1), rs.getString(2), rs.getLong(3), names(4), names(5))
        }
    }
    return stations
}

/** Renders the stations as a self-contained HTML page; its style is in `src/main/resources`. */
internal fun longDistanceStationsPage(stations: List<RailStation>): String {
    val page = createHTML().html {
        lang = "de"
        head {
            meta(charset = "utf-8")
            title("Long-distance stations")
            style { unsafe { raw(resource("long-distance_stations.css")) } }
        }
        body {
            h1 { +"Long-distance stations" }
            p {
                +"The ${stations.size} stations with the most long-distance trip-days (route types "
                +"${longDistanceTypes.joinToString(" and ")})."
            }
            table {
                thead {
                    tr {
                        th(classes = "number") { +"#" }
                        th { +"Station" }
                        th(classes = "number") { +"Long-distance trip-days" }
                        th(classes = "number") { +"Long-distance routes" }
                        th(classes = "number") { +"Regional routes" }
                    }
                }
                tbody {
                    stations.forEachIndexed { i, station ->
                        tr {
                            td("number") { +"${i + 1}" }
                            td("name") {
                                +"${station.name} "
                                small { +station.id }
                            }
                            td("number") { +"%,d".format(station.longDistanceTripDays) }
                            for (routes in listOf(station.longDistanceRoutes, station.regionalRoutes)) {
                                td("number routes") {
                                    title = routes.distinct().joinToString(", ")
                                    +"${routes.size}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return "<!DOCTYPE html>\n$page"
}

/**
 * Data exploration (see pipeline/spec.md, "Derivation of the long-distance network"): writes an HTML page
 * of the [top] stations with the most long-distance trip-days.
 */
fun longDistanceStations(railDir: Path, outFile: Path, top: Int) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val all = loadRailStations(railDir)
    val stations = longDistanceStations(all, top)
    Files.writeString(outFile, longDistanceStationsPage(stations))
    println(
        "%,d stations with long-distance trips, the top %,d -> %s"
            .format(all.count { it.longDistanceTripDays > 0 }, stations.size, outFile),
    )
    println("  %,d of them without regional routes".format(stations.count { it.regionalRoutes.isEmpty() }))
}

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: LongDistanceStations <rail-parquet-dir> <out-html> <top>" }
    longDistanceStations(Path.of(args[0]), Path.of(args[1]), args[2].toInt())
}
