@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Data exploration, first task (see spec.md, "Data exploration"):
 * for each rail route, group its trips by the stations they serve and count the trips per group.
 *
 * Choices made here (to be reviewed):
 *  - Stops are mapped to their station, so that the same train using a different platform still
 *    falls into the same group. The station is the parent station if the stop has one. Many
 *    platform-level stops have none; their station is the station part of the stop ID, the first
 *    three components of IDs like `de:13076:4782:1:1` or `de:13076:4782_G`.
 *  - A group is defined by the *ordered sequence* of stations, so both directions of a line
 *    are separate groups.
 *
 * Output CSV columns: route_id, route_short_name, route_type, group_no, num_stops, num_trips,
 * first_station, last_station, stations (" > "-separated station names).
 *
 * Within a route, groups are numbered (group_no) by most trips first, then most stops; ties are
 * broken alphabetically by first station, then by the full station list (and finally by station
 * IDs, for same-named stations), so the numbering is the same on every run.
 * Rows are sorted by route_type, route_short_name, then route_id and group_no.
 *
 * [topFile] gets the same columns without group_no, but only the most frequent group
 * (group_no 1) of each route.
 */
fun tripGroups(railDir: Path, outFile: Path, topFile: Path) {
    for (f in listOf(outFile, topFile)) f.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        val order = "ORDER BY route_type, route_short_name, route_id, group_no"
        db.exec("""
            COPY (SELECT * EXCLUDE (station_ids, num_trip_days) FROM trip_groups $order)
            TO ${sqlPath(outFile)} (FORMAT csv, HEADER)
        """)
        db.exec("""
            COPY (SELECT * EXCLUDE (station_ids, num_trip_days, group_no) FROM trip_groups WHERE group_no = 1 $order)
            TO ${sqlPath(topFile)} (FORMAT csv, HEADER)
        """)
        val routes = db.queryLong("SELECT count(DISTINCT route_id) FROM trip_groups")
        val groups = db.queryLong("SELECT count(*) FROM trip_groups")
        println("%,d routes, %,d trip groups -> %s, %s".format(routes, groups, outFile, topFile))
    }
}

/**
 * Creates the table `trip_groups` with the columns of the trip groups CSV plus
 *  - `station_ids`: the list of station IDs behind the `stations` names;
 *  - `num_trip_days`: the trips of the group, each counted once per day it runs;
 * and the table `station_names` (station_id, station_name).
 */
fun createTripGroupTable(db: Connection, railDir: Path) {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))
    db.exec("""
        CREATE TABLE station_of AS
        SELECT stop_id, stop_name,
               coalesce(nullif(regexp_extract(parent_or_self, '^[a-z]{2}:[0-9]+:[0-9]+'), ''), parent_or_self) AS station_id
        FROM (SELECT *, coalesce(nullif(parent_station, ''), stop_id) AS parent_or_self FROM ${p("stops")})
    """)
    db.exec("""
        CREATE TABLE station_names AS
        SELECT station_id, coalesce(min(stop_name) FILTER (stop_id = station_id), min(stop_name)) AS station_name
        FROM station_of GROUP BY station_id
    """)
    db.exec("""
        CREATE TABLE trip_seq AS
        SELECT st.trip_id,
               list(so.station_id ORDER BY st.stop_sequence) AS stations
        FROM ${p("stop_times")} st JOIN station_of so USING (stop_id)
        GROUP BY st.trip_id
    """)
    // The days a service runs: its weekdays between start_date and end_date, minus the removed
    // dates (exception_type 2), plus the added ones (exception_type 1).
    db.exec("""
        CREATE TABLE service_days AS
        WITH regular AS (
            SELECT service_id, weekdays, unnest(generate_series(first_day, last_day, INTERVAL 1 DAY))::DATE AS day
            FROM (
                SELECT service_id,
                       [monday, tuesday, wednesday, thursday, friday, saturday, sunday] AS weekdays,
                       strptime(start_date, '%Y%m%d') AS first_day,
                       strptime(end_date, '%Y%m%d') AS last_day
                FROM ${p("calendar")}
            )
        ), exceptions AS (
            SELECT service_id, strptime(date, '%Y%m%d')::DATE AS day, exception_type::INTEGER AS exception_type
            FROM ${p("calendar_dates")}
        ), days AS (
            (
                SELECT service_id, day FROM regular WHERE weekdays[isodow(day)]::INTEGER = 1
                EXCEPT
                SELECT service_id, day FROM exceptions WHERE exception_type = 2
            )
            UNION
            SELECT service_id, day FROM exceptions WHERE exception_type = 1
        )
        SELECT service_id, count(*) AS num_days FROM days GROUP BY service_id
    """)
    db.exec("""
        CREATE TABLE groups AS
        SELECT row_number() OVER () AS gid, t.route_id, ts.stations, count(*) AS num_trips,
               coalesce(sum(sd.num_days), 0)::BIGINT AS num_trip_days
        FROM ${p("trips")} t JOIN trip_seq ts USING (trip_id) LEFT JOIN service_days sd USING (service_id)
        GROUP BY t.route_id, ts.stations
    """)
    db.exec("""
        CREATE TABLE group_names AS
        WITH u AS (
            SELECT gid, unnest(stations) AS station_id, generate_subscripts(stations, 1) AS pos FROM groups
        )
        SELECT u.gid,
               string_agg(s.station_name, ' > ' ORDER BY u.pos) AS stations,
               arg_min(s.station_name, u.pos) AS first_station,
               arg_max(s.station_name, u.pos) AS last_station
        FROM u JOIN station_names s USING (station_id)
        GROUP BY u.gid
    """)
    db.exec("""
        CREATE TABLE trip_groups AS
        SELECT g.route_id, r.route_short_name, r.route_type,
               row_number() OVER (
                   PARTITION BY g.route_id
                   -- name and id tie-breakers make group_no deterministic (e.g. both directions of a line)
                   ORDER BY g.num_trips DESC, len(g.stations) DESC, n.first_station, n.stations, g.stations
               ) AS group_no,
               len(g.stations) AS num_stops,
               g.num_trips,
               n.first_station, n.last_station, n.stations,
               g.stations AS station_ids, g.num_trip_days
        FROM groups g
        JOIN ${p("routes")} r USING (route_id)
        JOIN group_names n USING (gid)
    """)
}

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: TripGroups <rail-parquet-dir> <out-csv> <top-out-csv>" }
    tripGroups(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
}
