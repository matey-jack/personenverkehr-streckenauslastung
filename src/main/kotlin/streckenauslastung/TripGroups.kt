package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path

/**
 * Data exploration, first task (see spec.txt, "Data exploration"):
 * for each rail route, group its trips by the stations they serve and count the trips per group.
 *
 * Choices made here (to be reviewed):
 *  - Stops are mapped to their parent station if they have one, so that the same train using
 *    a different platform still falls into the same group.
 *  - A group is defined by the *ordered sequence* of stations, so both directions of a line
 *    are separate groups.
 *
 * Output CSV columns: route_id, route_short_name, route_type, group_no, num_stops, num_trips,
 * first_station, last_station, stations (" > "-separated station names).
 */
fun tripGroups(railDir: Path, outFile: Path) {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    openDuckDb().use { db ->
        db.exec("""
            CREATE TABLE station_of AS
            SELECT s.stop_id,
                   coalesce(nullif(s.parent_station, ''), s.stop_id) AS station_id
            FROM ${p("stops")} s
        """)
        db.exec("""
            CREATE TABLE trip_seq AS
            SELECT st.trip_id,
                   list(so.station_id ORDER BY st.stop_sequence) AS stations
            FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
            GROUP BY st.trip_id
        """)
        db.exec("""
            CREATE TABLE groups AS
            SELECT row_number() OVER () AS gid, t.route_id, ts.stations, count(*) AS num_trips
            FROM ${p("trips")} t JOIN trip_seq ts USING (trip_id)
            GROUP BY t.route_id, ts.stations
        """)
        db.exec("""
            CREATE TABLE group_names AS
            WITH u AS (
                SELECT gid, unnest(stations) AS station_id, generate_subscripts(stations, 1) AS pos FROM groups
            )
            SELECT u.gid,
                   string_agg(coalesce(s.stop_name, u.station_id), ' > ' ORDER BY u.pos) AS stations,
                   arg_min(coalesce(s.stop_name, u.station_id), u.pos) AS first_station,
                   arg_max(coalesce(s.stop_name, u.station_id), u.pos) AS last_station
            FROM u LEFT JOIN ${p("stops")} s ON s.stop_id = u.station_id
            GROUP BY u.gid
        """)
        db.exec("""
            COPY (
                SELECT g.route_id, r.route_short_name, r.route_type,
                       row_number() OVER (PARTITION BY g.route_id ORDER BY g.num_trips DESC, len(g.stations) DESC) AS group_no,
                       len(g.stations) AS num_stops,
                       g.num_trips,
                       n.first_station, n.last_station, n.stations
                FROM groups g
                JOIN ${p("routes")} r USING (route_id)
                JOIN group_names n USING (gid)
                ORDER BY g.route_id, group_no
            ) TO ${sqlPath(outFile)} (FORMAT csv, HEADER)
        """)
        val routes = db.queryLong("SELECT count(DISTINCT route_id) FROM groups")
        val groups = db.queryLong("SELECT count(*) FROM groups")
        println("%,d routes, %,d trip groups -> %s".format(routes, groups, outFile))
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: TripGroups <rail-parquet-dir> <out-csv>" }
    tripGroups(Path.of(args[0]), Path.of(args[1]))
}
