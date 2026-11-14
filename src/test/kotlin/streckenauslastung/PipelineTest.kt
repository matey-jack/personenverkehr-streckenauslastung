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
        val page = withoutLayout(out.readText())
        assertContains(page, "1 touching no hub station")
        assertContains(
            page,
            "<td class=\"name\">RE1</td><td class=\"type\">Regional <small>106</small></td>" +
                "<td>Aheim Hbf</td><td>–</td><td>Cstadt</td>" +
                "<td class=\"stops\" title=\"Aheim Hbf\nBdorf\nCstadt\"><small>3 stops, 20 min</small></td>",
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
        val names = "ABCDEFGHIJKLMNOPQR".map { it.toString() }.associateWith { "Station $it" }
        val re1 = ListedRoute("r1", "RE1", 106, listOf("A", "B", "C"))
        val re2 = ListedRoute("r2", "RE2", 106, listOf("D", "A"))
        val ice = ListedRoute("r3", "ICE", 101, listOf("D", "A", "C"))
        // Both core routes of S1 end at E, which makes E no hub. S1a ends at F, where RB9 stops,
        // which makes F a hub.
        val s1a = ListedRoute("r4", "S1a", 109, listOf("E", "F"))
        val s1b = ListedRoute("r4", "S1b", 109, listOf("E", "F", "G"))
        val rb9 = ListedRoute("r5", "RB9", 106, listOf("I", "F", "J", "P"))
        // RB7 ends at B, where RE1 stops, which makes B a hub.
        val rb7 = ListedRoute("r6", "RB7", 106, listOf("H", "B"))
        // J has the same rank as F, so RB9 is still dangling there, which makes J a hub as well.
        val rb5 = ListedRoute("r7", "RB5", 106, listOf("J", "D"))
        // RB8 and RB6 only cross at M, where neither ends: a hub of rank 0.
        val rb8 = ListedRoute("r8", "RB8", 106, listOf("K", "M", "L"))
        val rb6 = ListedRoute("r9", "RB6", 106, listOf("N", "M", "O"))
        val rb4 = ListedRoute("r10", "RB4", 106, listOf("Q", "R"))
        val overview = hubOverview(listOf(re1, re2, ice, s1a, s1b, rb9, rb7, rb5, rb8, rb6, rb4), names)

        assertEquals(listOf("D", "A", "C", "F", "B", "J", "M"), overview.hubs.map { it.stationId })
        // Routes run from their end that comes first among the hubs.
        val rb5FromD = rb5.copy(stationIds = listOf("D", "J"))
        val s1aFromF = s1a.copy(stationIds = listOf("F", "E"))
        val rb7FromB = rb7.copy(stationIds = listOf("B", "H"))
        assertEquals(
            listOf(
                listOf(re2, ice, rb5FromD),
                listOf(re1, re2),
                listOf(re1, ice),
                listOf(s1aFromF),
                listOf(rb7FromB),
                listOf(rb5FromD),
                emptyList(),
            ),
            overview.hubs.map { it.ending },
        )
        assertEquals(
            listOf(emptyList(), listOf(ice), emptyList(), listOf(s1b, rb9), listOf(re1), listOf(rb9), listOf(rb8, rb6)),
            overview.hubs.map { it.stopping },
        )
        assertEquals(listOf(rb4), overview.isolated)

        val page = withoutLayout(hubStationsPage(overview, names))
        assertContains(page, "<section id=\"hub2\"><h2>Station A <small>2 routes starting or ending, 1 stopping")
        // Under hub A, A is not a link to itself; D is a link to its own section.
        assertContains(page, "<td><a href=\"#hub1\">Station D</a></td><td>–</td><td>Station A</td>")
        assertContains(page, "0 routes starting or ending, 2 stopping</small></h2><h3>Stopping here</h3>")
    }

    /** The page without the line breaks and indentation between its tags. */
    private fun withoutLayout(page: String) = page.replace(Regex(">\\s+<"), "><")
}
