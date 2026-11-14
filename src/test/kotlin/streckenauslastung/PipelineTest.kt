package streckenauslastung

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PipelineTest {
    private val gtfs: Path = javaClass.getResource("/mini_gtfs/routes.txt")!!.toURI().toPath().parent

    @Test
    fun `filterRail keeps only rail data and computes seconds past midnight`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp)
        openDuckDb().use { db ->
            fun count(table: String) = db.queryLong("SELECT count(*) FROM ${sqlPath(tmp.resolve("$table.parquet"))}")
            assertEquals(1, count("routes"))
            assertEquals(3, count("trips"))
            assertEquals(8, count("stop_times"))
            assertEquals(5, count("stops")) // A, A:1, A:2, B, C – not the bus stop X
            assertEquals(1, count("calendar"))
            assertEquals(1, count("calendar_dates"))
            assertEquals(1, count("agency"))
            val st = sqlPath(tmp.resolve("stop_times.parquet"))
            assertEquals(
                24 * 3600L + 600,
                db.queryLong("SELECT arrival_secs FROM $st WHERE trip_id = 'T1' AND stop_sequence = 3"),
            )
        }
    }

    @Test
    fun `tripGroups groups trips by station sequence`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("trip_groups.csv")
        val top = tmp.resolve("trip_groups_top.csv")
        tripGroups(tmp.resolve("rail"), out, top)
        val lines = out.readLines()
        assertEquals(
            "route_id,route_short_name,route_type,group_no,num_stops,num_trips,first_station,last_station,stations",
            lines[0],
        )
        // T1 and T2 use different platforms at Aheim but form one group; T3 runs the other way.
        assertEquals("R1,RE1,106,1,3,2,Aheim Hbf,Cstadt,Aheim Hbf > Bdorf > Cstadt", lines[1])
        assertEquals("R1,RE1,106,2,2,1,Cstadt,Aheim Hbf,Cstadt > Aheim Hbf", lines[2])
        assertEquals(3, lines.size)
        assertEquals(
            listOf(
                "route_id,route_short_name,route_type,num_stops,num_trips,first_station,last_station,stations",
                "R1,RE1,106,3,2,Aheim Hbf,Cstadt,Aheim Hbf > Bdorf > Cstadt",
            ),
            top.readLines(),
        )
    }

    @Test
    fun `coreRoutes finds the most frequent pattern`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("core_routes.csv")
        coreRoutes(tmp.resolve("rail"), out)
        assertEquals(
            listOf(
                "route_id,route_short_name,route_type,share,verdict,num_stops,first_station,last_station,stations",
                "R1,RE1,106,0.67,equal,3,Aheim Hbf,Cstadt,Aheim Hbf > Bdorf > Cstadt",
            ),
            out.readLines(),
        )
    }

    @Test
    fun `coreRouteCheck compares the most frequent patterns with the stations most trips serve`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("core_route_check.csv")
        coreRouteCheck(tmp.resolve("rail"), out, minDays = 1)
        // Two of three trips form the top pattern; the third skips Bdorf, which still is a majority station.
        assertEquals(
            "R1,RE1,106,867,2,3,equal,0.67,Aheim Hbf,Cstadt,shorter -1,0.33,Cstadt,Aheim Hbf,1,equal,,",
            out.readLines()[1],
        )
    }

    @Test
    fun `hubStations lists a single route as isolated`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("hub_stations.html")
        hubStations(tmp.resolve("rail"), out)
        val page = out.readText()
        assertContains(page, "1 touching no hub station")
        assertContains(
            page,
            "<td class=\"name\">RE1</td><td class=\"type\">Regional <small>106</small></td>" +
                "<td>Aheim Hbf</td><td>–</td><td>Cstadt</td>",
        )
    }

    @Test
    fun `listedRoutes lists both core routes of a route`() {
        fun pattern(stations: String, tripDays: Long) =
            Pattern(stations.map { it.toString() }, stations.length, "", "", "").also { it.tripDays = tripDays }
        // ABC is the most frequent pattern, but A, B, C and D are all served on most trip-days.
        val route = Route("R", "RE1", 106, listOf(pattern("ABC", 40), pattern("ABCD", 35), pattern("BCD", 25)))
        assertEquals(
            listOf("RE1a" to listOf("A", "B", "C"), "RE1b" to listOf("A", "B", "C", "D")),
            listedRoutes(listOf(route)).map { it.shortName to it.stationIds },
        )
    }

    @Test
    fun `hubOverview finds hubs, stopping routes and isolated routes`() {
        val names = listOf("A", "B", "C", "D", "E", "F", "G").associateWith { "Station $it" }
        val re1 = ListedRoute("r1", "RE1", 106, listOf("A", "B", "C"))
        val re2 = ListedRoute("r2", "RE2", 106, listOf("D", "A"))
        val ice = ListedRoute("r3", "ICE", 101, listOf("D", "A", "C"))
        // Both core routes of S1 end at E, which makes E no hub.
        val s1a = ListedRoute("r4", "S1a", 109, listOf("E", "F"))
        val s1b = ListedRoute("r4", "S1b", 109, listOf("E", "F", "G"))
        val overview = hubOverview(listOf(re1, re2, ice, s1a, s1b), names)

        assertEquals(listOf("A", "C", "D"), overview.hubs.map { it.stationId })
        assertEquals(listOf(listOf(re1, re2), listOf(re1, ice), listOf(re2, ice)), overview.hubs.map { it.ending })
        assertEquals(listOf(listOf(ice), emptyList(), emptyList()), overview.hubs.map { it.stopping })
        assertEquals(listOf(s1a, s1b), overview.isolated)

        val page = hubStationsPage(overview, names)
        assertContains(page, "<details id=\"hub1\">\n<summary>Station A <small>2 routes starting or ending, 1 stopping")
        // Under hub A, RE2 starts at A, which is not a link to itself; D is a link to its own section.
        assertContains(page, "<td>Station A</td><td>–</td><td><a href=\"#hub3\">Station D</a></td>")
    }
}
