package streckenauslastung

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.math.roundToInt
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
    fun `lines lists the stations of the line with the routes stopping there`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("lines.html")
        lines(tmp.resolve("rail"), out, listOf("R1"))
        val page = withoutLayout(out.readText())
        assertContains(page, "<nav><button>DB Regio</button></nav></header><section data-agency=\"DB Regio\">")
        assertContains(page, "<h2>RE1 Aheim Hbf – Cstadt <small>3 stations, 3 of them in the core route</small></h2>")
        assertContains(
            page,
            "<tr class=\"core\"><td class=\"name\">Bdorf</td><td class=\"time\">8</td><td>RE1</td><td></td></tr>",
        )
        assertContains(page, "<td class=\"name\">RE1</td><td>Aheim Hbf – Cstadt</td><td>DB Regio</td>")
    }

    @Test
    fun `longDistanceStations counts the routes stopping at each station`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val stations = loadRailStations(tmp.resolve("rail"))
        assertEquals(listOf("A", "B", "C"), stations.map { it.id })
        val aheim = stations.first()
        assertEquals("Aheim Hbf", aheim.name)
        assertEquals(listOf("RE1"), aheim.regionalRoutes)
        assertEquals(emptyList(), aheim.longDistanceRoutes)
        assertEquals(0, aheim.longDistanceTripDays)

        val out = tmp.resolve("long-distance_stations.html")
        longDistanceStations(tmp.resolve("rail"), out, top = 50)
        assertContains(out.readText(), "The 0 stations with the most long-distance trip-days")
    }

    @Test
    fun `longDistanceStations lists the stations with the most long-distance trip-days`() {
        fun station(id: String, tripDays: Long, longDistance: List<String>, regional: List<String>) =
            RailStation(id, "Station $id", tripDays, longDistance, regional)
        val stations = listOf(
            station("mixed", 50, listOf("ICE 1"), listOf("RE3", "RE3", "S1")),
            station("regional", 0, emptyList(), listOf("RE2")),
            station("pure", 100, listOf("ICE 1", "ICE 2"), emptyList()),
            station("rare", 10, listOf("IC 3"), emptyList()),
        )
        val listed = longDistanceStations(stations, top = 2)
        assertEquals(listOf("pure", "mixed"), listed.map { it.id })

        val page = withoutLayout(longDistanceStationsPage(listed))
        assertContains(
            page,
            "<tr><td class=\"number\">2</td>" +
                "<td class=\"name\">Station mixed <small>mixed</small></td><td class=\"number\">50</td>" +
                "<td class=\"number routes\" title=\"ICE 1\">1</td>" +
                "<td class=\"number routes\" title=\"RE3, S1\">3</td></tr>",
        )
    }

    /** A pattern of the stations named by the letters of [stations]. */
    private fun pattern(stations: String, tripDays: Long) =
        Pattern(stations.map { it.toString() }, stations.length, "", "", "").also { it.tripDays = tripDays }

    /**
     * A run stopping at the stations named by the letters of [stations] at the seconds [times], without
     * dwelling. Its median trip has the same times.
     */
    private fun run(routeId: String, stations: String, vararg times: Long): Run {
        val secs = times.toList()
        return Run(routeId, stations.map { it.toString() }, secs, secs, TripTimes(secs, secs))
    }

    @Test
    fun `reconstructLine inserts the stations of other routes`() {
        val re = run("re", "ACE", 0, 600, 1200)
        val rb = run("rb", "ABCDE", 0, 400, 800, 1300, 1700)
        // Runs the other way and adds F between D and E, once D is on the line.
        val s = run("s", "EFDC", 0, 300, 600, 1000)
        // Takes far longer from A to C than the others: a detour, so Z stays off the line.
        val detour = run("detour", "AZC", 0, 1500, 3000)
        // No trip stops at both B and U, so their travel times put them in an order.
        val alternate = run("alternate", "AUC", 0, 500, 760)
        val crossing = run("crossing", "QCR", 0, 300, 600)
        val index = RunIndex(listOf(re, rb, s, detour, alternate, crossing), DetourRule())

        val line = reconstructLine(listOf("A", "C", "E"), index)
        assertEquals("ABUCDFE".map { it.toString() }, line.stations)
        // A, C and E lie where the fastest trip reaches them, the others at their share of the way in between.
        assertEquals(listOf(0, 300, 395, 600, 933, 1020, 1200), line.positions.map { it.roundToInt() })
        val pair = line.timeOrdered.single()
        assertEquals(listOf("B", "U", "A", "C"), listOf(pair.first, pair.second, pair.from, pair.to))
        // The trips through B take 800 seconds from A to C, those through U 760.
        assertEquals(false, pair.isStrong)

        val using = runsUsing(line, index)
        // The next stop of the detour is not on the line.
        assertEquals(listOf(re, rb, alternate), using.getValue("A"))
        assertEquals(listOf(re, rb, s, alternate), using.getValue("C"))
        assertEquals(listOf(s), using.getValue("F"))
    }

    @Test
    fun `lineReport warns about an order by travel time only and about trips against the order`() {
        val routes = listOf("AD", "ABD", "ACD", "CBD").mapIndexed { i, stations ->
            Route("r${i + 1}", "R${i + 1}", 106, listOf(pattern(stations, 10)))
        }
        val runs = listOf(
            run("r1", "AD", 0, 500),
            // The trips through B take 600 seconds from A to D, those through C 800: a third longer.
            run("r2", "ABD", 0, 300, 600),
            run("r3", "ACD", 0, 480, 800),
            run("r4", "CBD", 0, 200, 500),
        )
        val report = lineReport("r1", routes, RunIndex(runs, DetourRule()))

        assertEquals("ABCD".map { it.toString() }, report.stops.map { it.stationId })
        assertEquals(true, report.timeOrdered.single().isStrong)
        assertEquals(listOf("C", "B", "D"), report.againstOrder.single().stations)

        val names = "ABCD".map { it.toString() }.associateWith { "Station $it" }
        val page = withoutLayout(linesPage(listOf(report), names, emptyMap()))
        // R1 passes B and C, so R2 and R3 are timed as well. R4 stops nowhere new.
        assertContains(
            page,
            "<th>Station</th><th class=\"time\">R1 <small>00:00</small></th>" +
                "<th class=\"time\">R2 <small>00:00</small></th><th class=\"time\">R3 <small>00:00</small></th>",
        )
        assertContains(
            page,
            "<td class=\"name\">Station C</td><td class=\"time\"></td><td class=\"time\"></td><td class=\"time\">8</td>",
        )
        assertContains(
            page,
            "<h3>Warnings</h3><ul><li class=\"strong\">Ordered by travel time only: Station B before Station C, " +
                "at 50% and 60% of the way from Station A to Station D. " +
                "The trips stopping at only one of them differ by 33% in travel time.</li>" +
                "<li>Against the order of the line: Station C &gt; Station B &gt; Station D (R4)</li></ul>",
        )
    }

    @Test
    fun `timedTrains times the core route first, then trains of core routes, then other trains`() {
        val line = Line("ABCDEF".map { it.toString() }, List(6) { 0.0 }, emptyList())
        val scope = CoreRoute("re", "RE1", pattern("AF", 10), Difference(pattern("AF", 10), emptySet()))
        val routes = listOf("re", "rb", "s", "back", "short").associateWith {
            Route(it, it.uppercase(), 106, listOf(pattern("AF", 1)))
        }
        // The train of the core route leaves A at 08:00 and stops only at both ends.
        val re =
            Run(
                "re",
                listOf("A", "F"),
                listOf<Long?>(null, 30000),
                listOf<Long?>(28800, null),
                TripTimes(listOf<Long?>(null, 30000), listOf<Long?>(28800, null)),
            )
        // Stops at the most stations, and waits at C for three minutes. Its trips belong to no core route.
        val rb = Run(
            "rb",
            "ABCD".map { it.toString() },
            listOf<Long?>(0, 300, 600, 1000),
            listOf<Long?>(0, 330, 780, 1000),
            TripTimes(listOf<Long?>(0, 300, 600, 1000), listOf<Long?>(0, 330, 780, 1000)),
        )
        // The only train stopping at E runs against the listed direction.
        val back = run("back", "FED", 0, 240, 480)
        // Stops nowhere that the others do not, so it is never picked.
        val short = run("short", "BCD", 0, 300, 700)
        // The trips of a core route: they go first, although RB stops at more stations.
        val s = run("s", "BC", 0, 300)
        val sCore = CoreRoute("s", "S1", pattern("BC", 10), Difference(pattern("BC", 10), emptySet()))
        val index = RunIndex(listOf(re, rb, back, short, s), DetourRule())

        val trains = timedTrains(line, scope, index, routes) { run ->
            when (run) {
                re -> scope
                s -> sCore
                else -> null
            }
        }
        assertEquals(listOf("re", "s", "rb", "back"), trains.map { it.route.id })
        assertEquals(listOf("0", "", "", "", "", "20"), line.stations.map { trains[0].minutes(it) })
        assertEquals(listOf("", "0", "5", "", "", ""), line.stations.map { trains[1].minutes(it) })
        assertEquals(listOf("0", "5", "10–13", "17", "", ""), line.stations.map { trains[2].minutes(it) })
        // It counts from its own first station on the line, F.
        assertEquals(listOf("", "", "", "8", "4", "0"), line.stations.map { trains[3].minutes(it) })
    }

    @Test
    fun `lineReport separates routes with core stops from routes with other trips`() {
        val re = Route("re", "RE1", 106, listOf(pattern("ACE", 10)))
        // RB1 has two core routes: RB1a from A to C is the most frequent, RB1b from A to E is what most trips serve.
        val rb = Route("rb", "RB1", 106, listOf(pattern("ABC", 40), pattern("ABCDE", 35), pattern("BCDE", 25)))
        // Another route called RB1. Its core route is elsewhere; one of its trips runs from D to E.
        val rb2 = Route("rb2", "RB1", 106, listOf(pattern("QR", 10), pattern("DE", 1)))
        val runs = listOf(
            run("re", "ACE", 0, 600, 1200),
            run("rb", "ABCDE", 0, 400, 800, 1300, 1700),
            run("rb", "ABC", 0, 400, 800),
            run("rb2", "DE", 0, 400),
            run("rb2", "AUQ", 0, 350, 700),
        )
        val report = lineReport("re", listOf(re, rb, rb2), RunIndex(runs, DetourRule()))

        assertEquals("ABCDE".map { it.toString() }, report.stops.map { it.stationId })
        // The two routes called RB1 are numbered; the core routes of the first one keep their own suffixes.
        assertEquals(
            listOf("RE1 RB1-1a RB1-1b", "RB1-1a RB1-1b", "RE1 RB1-1a RB1-1b", "RB1-1b", "RE1 RB1-1b"),
            report.stops.map { stop -> stop.core.joinToString(" ") { report.label(it) } },
        )
        // The second RB1 stops at A, but does not use the line there: its next stop U is not on the line.
        assertEquals(
            listOf(emptyList(), emptyList(), emptyList(), listOf(rb2), listOf(rb2)),
            report.stops.map { it.other },
        )
        assertEquals(listOf("RB1-1a", "RB1-1b", "RE1"), report.coreRoutes.map { report.label(it) })
        assertEquals(listOf("RB1-2"), report.additionalRoutes.map { report.label(it) })
        assertEquals(
            listOf("RE3", "RE16", "S1", "S1-2", "S10"),
            listOf("S10", "RE16", "S1-2", "RE3", "S1").sortedWith(naturalOrder),
        )

        val names = "ABCDEU".map { it.toString() }.associateWith { "Station $it" }
        val page = withoutLayout(linesPage(listOf(report), names, mapOf("re" to "Agency 1")))
        assertContains(
            page,
            "<tr><td class=\"name\">Station D</td><td class=\"time\"></td><td class=\"time\">22</td>" +
                "<td>RB1-1b</td><td>RB1-2</td></tr>",
        )
        assertContains(page, "<h3>Additional routes with other trips stopping on the line</h3>")
        assertContains(
            page,
            "<td class=\"name\">RB1-2</td><td> – </td><td>No agency</td><td><small>2 stations</small></td>",
        )
    }

    @Test
    fun `listedRoutes lists both core routes of a route`() {
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
