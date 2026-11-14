package streckenauslastung

import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.ol
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.nio.file.Files
import java.nio.file.Path

/**
 * A core route of the route [routeId] as listed on the page, where it is simply called a "route".
 * [stationIds] run in the direction given by [hubOverview]. [travelSecs] is the median travel time
 * between both ends, counted in trip-days over the trips of the core route in both directions.
 */
internal data class ListedRoute(
    val routeId: String,
    val shortName: String,
    val type: Int,
    val stationIds: List<String>,
    val travelSecs: Long? = null,
) {
    val ends = setOf(stationIds.first(), stationIds.last())

    /** The stations where the route stops without starting or ending there. */
    val stops = stationIds.toSet() - ends
}

/** Stations with a rank of their own (see spec.md), by station ID. This one is Berlin Hauptbahnhof. */
private val assignedRanks = mapOf("de:11000:900003201" to 20)

/** A hub station with the core routes [ending] and [stopping] there (see spec.md for the terms). */
internal class Hub(
    val stationId: String,
    val rank: Int,
    val ending: List<ListedRoute>,
    val stopping: List<ListedRoute>,
)

/** The [hubs] in their order and the [isolated] core routes, all in their direction as listed. */
internal class HubOverview(val hubs: List<Hub>, val isolated: List<ListedRoute>)

/** Groups [routes] by hub station (see spec.md, "Ideas on meaningfully grouping the Core Routes"). */
internal fun hubOverview(routes: List<ListedRoute>, stationNames: Map<String, String>): HubOverview {
    fun byStation(routes: List<ListedRoute>, stations: (ListedRoute) -> Set<String>) =
        HashMap<String, MutableList<ListedRoute>>().also { map ->
            for (route in routes) for (station in stations(route)) map.getOrPut(station) { ArrayList() }.add(route)
        }

    fun List<ListedRoute>.ofSeveralRoutes() = distinctBy { it.routeId }.size > 1

    val ending = byStation(routes) { it.ends }
    val stopping = byStation(routes) { it.stops }
    val calling = byStation(routes) { it.stationIds.toSet() }

    fun rank(station: String) = assignedRanks[station] ?: ending[station].orEmpty().size
    val bySize = calling.keys.sortedWith(
        compareBy(
            { -rank(it) },
            { -stopping[it].orEmpty().size },
            { stationNames.getValue(it) },
            { it },
        ),
    )
    val hubIds = ArrayList<String>()
    val connected = HashSet<ListedRoute>()
    // All stations of one rank are decided together, before any of their routes count as
    // connected, so that stations of similar size are treated alike: if a dangling route runs
    // from A to B, where another route stops but no other route ends, both A and B become hubs.
    // Deciding station by station would make only the first of them a hub.
    for (stationsOfRank in bySize.groupBy(::rank).values) {
        val hubsOfRank = stationsOfRank.filter { station ->
            val callingHere = calling.getValue(station)
            val linksDangling = callingHere.ofSeveralRoutes() && callingHere.any { it !in connected }
            ending[station].orEmpty().ofSeveralRoutes() || linksDangling
        }
        hubIds += hubsOfRank
        for (hub in hubsOfRank) connected += calling.getValue(hub)
    }
    val positions = hubIds.withIndex().associate { (i, id) -> id to i }

    fun position(stationId: String) = positions[stationId] ?: hubIds.size
    val directed = routes.map { route ->
        val backwards = position(route.stationIds.last()) < position(route.stationIds.first())
        if (backwards) route.copy(stationIds = route.stationIds.reversed()) else route
    }
    val directedEnding = byStation(directed) { it.ends }
    val directedStopping = byStation(directed) { it.stops }
    val hubs = hubIds.map { Hub(it, rank(it), directedEnding[it].orEmpty(), directedStopping[it].orEmpty()) }
    return HubOverview(hubs, directed.filter { route -> route.stationIds.none { it in positions } })
}

/**
 * The core routes to list, in the order of [routes]. Core routes with the same route type, short
 * name and stations are listed once.
 *
 * This is the only place that decides which core routes the page shows.
 */
internal fun listedRoutes(routes: List<Route>): List<ListedRoute> = routes
    .flatMap { route ->
        route.coreRoutes.map {
            ListedRoute(route.id, it.shortName, route.type, it.pattern.stationIds, it.pattern.medianTravelSecs)
        }
    }
    .distinctBy { Triple(it.type, it.shortName, it.stationIds.toSet()) }

/** Names of the route types occurring in the German data, see `Extended GTFS Route Types.md`. */
private val routeTypeNames = mapOf(
    100 to "Railway",
    101 to "High Speed",
    102 to "Long Distance",
    103 to "Inter Regional",
    106 to "Regional",
    109 to "Suburban",
)

/** Rounded to minutes: "47 min", or "1:05 h" from an hour on. */
private fun duration(secs: Long): String {
    val minutes = (secs + 30) / 60
    return if (minutes < 60) "$minutes min" else "%d:%02d h".format(minutes / 60, minutes % 60)
}

internal fun resource(name: String) = ListedRoute::class.java.getResource(name)!!.readText()

/** Renders the overview as a self-contained HTML page; its style and script are in `src/main/resources`. */
internal fun hubStationsPage(overview: HubOverview, stationNames: Map<String, String>): String {
    val anchors = overview.hubs.withIndex().associate { (i, hub) -> hub.stationId to "hub${i + 1}" }

    /** The name of a station, as a link to its section unless it is no hub or the section is that of [hubId]. */
    fun FlowOrPhrasingContent.station(id: String, hubId: String? = null) {
        val name = stationNames.getValue(id)
        val anchor = anchors[id]
        if (anchor != null && id != hubId) a("#$anchor") { +name } else +name
    }

    /** One table row per route. */
    fun FlowContent.routeTable(routes: List<ListedRoute>, hubId: String? = null) = table {
        for (route in routes) {
            tr {
                td("name") { +route.shortName }
                td("type") {
                    routeTypeNames[route.type]?.let { +"$it " }
                    small { +route.type.toString() }
                }
                td { station(route.stationIds.first(), hubId) }
                td { +"–" }
                td { station(route.stationIds.last(), hubId) }
                td("stops") {
                    title = route.stationIds.joinToString("\n") { stationNames.getValue(it) }
                    val travelTime = route.travelSecs?.let { ", ${duration(it)}" } ?: ""
                    small { +"${route.stationIds.size} stops$travelTime" }
                }
            }
        }
    }

    val page = createHTML().html {
        lang = "de"
        head {
            meta(charset = "utf-8")
            title("Hub stations and their routes")
            style { unsafe { raw(resource("hub_stations.css")) } }
        }
        body {
            nav {
                input(InputType.search) {
                    id = "filter"
                    placeholder = "Filter stations"
                }
                ol {
                    for (hub in overview.hubs) {
                        li {
                            a("#${anchors[hub.stationId]}") { +stationNames.getValue(hub.stationId) }
                            +" "
                            small { +"${hub.ending.size} + ${hub.stopping.size}" }
                        }
                    }
                    li {
                        a("#isolated") { +"Isolated routes" }
                        +" "
                        small { +overview.isolated.size.toString() }
                    }
                }
            }
            main {
                h1 { +"Hub stations and their routes" }
                for (hub in overview.hubs) {
                    section {
                        id = anchors.getValue(hub.stationId)
                        h2 {
                            +"${stationNames.getValue(hub.stationId)} "
                            small { +"${hub.ending.size} routes starting or ending, ${hub.stopping.size} stopping" }
                        }
                        if (hub.ending.isNotEmpty()) {
                            h3 { +"Starting or ending here" }
                            routeTable(hub.ending, hub.stationId)
                        }
                        if (hub.stopping.isNotEmpty()) {
                            h3 { +"Stopping here" }
                            routeTable(hub.stopping)
                        }
                    }
                }
                section {
                    id = "isolated"
                    h2 {
                        +"Isolated routes "
                        small { +"${overview.isolated.size} touching no hub station" }
                    }
                    routeTable(overview.isolated)
                }
            }
            script { unsafe { raw(resource("hub_stations.js")) } }
        }
    }
    return "<!DOCTYPE html>\n$page"
}

/**
 * Data exploration (see spec.md, "Ideas on meaningfully grouping the Core Routes"): writes an HTML
 * page of the hub stations with their core routes, followed by the isolated core routes.
 * The core routes are those of spec.md, "Core Routes".
 */
fun hubStations(railDir: Path, outFile: Path) {
    outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val loaded = loadRoutes(railDir)
    val routes = listedRoutes(loaded.routes)
    val stationNames = loaded.stationNames
    val overview = hubOverview(routes, stationNames)
    Files.writeString(outFile, hubStationsPage(overview, stationNames))
    println(
        "%,d core routes, %,d hub stations, %,d isolated core routes -> %s"
            .format(routes.size, overview.hubs.size, overview.isolated.size, outFile),
    )
    for ((rank, hubs) in overview.hubs.groupBy { minOf(it.rank, 2) }.toSortedMap()) {
        println("  %,5d  hub stations of rank %s".format(hubs.size, if (rank == 2) "2 or higher" else "$rank"))
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: HubStations <rail-parquet-dir> <out-html>" }
    hubStations(Path.of(args[0]), Path.of(args[1]))
}
