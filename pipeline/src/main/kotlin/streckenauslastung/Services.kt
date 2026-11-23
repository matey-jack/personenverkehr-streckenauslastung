package streckenauslastung

import kotlinx.serialization.Serializable
import java.util.Collections
import java.util.EnumMap
import java.util.TreeMap

/** A route of the regional network with its [coverage]: for each line it uses, the range of positions, from < to. */
internal class ServiceRoute(val id: String, val info: RouteInfo, val coverage: List<LineRun>)

/** A route core of the route at index [route], with its [stations] (typical stop sequence) and [path] (null if unconnectable). */
internal class ServiceCore(
    val route: Int,
    val suffix: String?,
    val tripDays: Long,
    val stations: List<String>,
    val path: List<LineRun>?,
)

/**
 * The services on the regional network: its [routes], their route [cores], the regional [hubs], the
 * warnings about the paths and, for each outcome, the number of distinct hops pathed with it.
 */
internal class Services(
    val routes: List<ServiceRoute>,
    val cores: List<ServiceCore>,
    val hubs: List<Hub>,
    val warnings: List<NetworkWarning>,
    val outcomes: Map<PathOutcome, Int>,
)

/**
 * Finds the paths of the stop [patterns] and of the route cores on the [network] (see
 * plan-regional-network.md, "Derivation"), the coverage of the routes and the regional hub stations.
 * The routes are those with a stop pattern; their route cores are computed from all their trips of [input].
 */
internal fun deriveServices(
    input: RegionalInput,
    patterns: List<StopPattern>,
    network: Network,
    finder: PathFinder,
): Services {
    val allHops = HashMap<Hop, TravelTimes>()
    val routeHops = HashMap<String, HashMap<Hop, TravelTimes>>()
    for (trip in input.trips) {
        for (i in 0 until trip.stations.size - 1) {
            val h = hop(trip.stations[i], trip.stations[i + 1])
            val secs = trip.times.secs(i, i + 1)
            allHops.getOrPut(h) { TravelTimes() }.add(secs, trip.times.days)
            routeHops.getOrPut(trip.routeId) { HashMap() }.getOrPut(h) { TravelTimes() }.add(secs, trip.times.days)
        }
    }
    val warnings = LinkedHashMap<Pair<WarningKind, List<String>>, NetworkWarning>()
    val outcomes = EnumMap<PathOutcome, MutableSet<Hop>>(PathOutcome::class.java)
    val sectionsUsed = HashSet<Hop>()

    fun warn(kind: WarningKind, stations: List<String>, involved: () -> List<Involved>) {
        warnings.getOrPut(kind to stations) { NetworkWarning(kind, stations, involved()) }
    }

    fun hopsInvolved(stations: List<String>) =
        Involved(stations, stations.zipWithNext { a, b -> allHops.getValue(hop(a, b)) })

    fun wayInvolved(way: Way) =
        Involved(way.stations, way.stations.zipWithNext { a, b -> network.sections.getValue(hop(a, b)) })

    /** The path along [stations], and whether all of its hops are connected; [hopSecs] gives the median of a hop. */
    fun pathOf(stations: List<String>, hopSecs: (Hop) -> Long): Pair<List<LineRun>, Boolean> {
        val runs = ArrayList<LineRun>()
        var connected = true
        for ((x, y) in stations.zipWithNext()) {
            val h = hop(x, y)
            val found = finder.path(x, y, hopSecs(h))
            outcomes.getOrPut(found.outcome) { HashSet() } += h
            if (found.outcome == PathOutcome.SECTION) sectionsUsed += h
            val kind = when (found.outcome) {
                PathOutcome.AMBIGUOUS -> WarningKind.AMBIGUOUS
                PathOutcome.DETOUR -> WarningKind.DETOUR
                PathOutcome.UNCONNECTABLE -> WarningKind.UNCONNECTABLE
                else -> null
            }
            if (kind != null) {
                warn(kind, listOf(h.a, h.b)) {
                    listOf(hopsInvolved(listOf(h.a, h.b))) +
                        found.candidates.map(::wayInvolved)
                }
            }
            val way = found.way
            if (way == null) connected = false else runs += way.runs
        }
        val path = joinRuns(runs)
        val sections = finder.sections(path)
        val twice = sections.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()
        if (twice != null) {
            warn(WarningKind.REVERSAL, listOf(twice.a, twice.b)) { listOf(hopsInvolved(stations)) }
        }
        return path to connected
    }

    val coverage = HashMap<String, TreeMap<Int, LineRun>>()
    for (pattern in patterns) {
        val times = HashMap<Hop, TravelTimes>()
        for (trip in pattern.trips) {
            for (i in 0 until pattern.stations.size - 1) {
                times.getOrPut(hop(pattern.stations[i], pattern.stations[i + 1])) { TravelTimes() }
                    .add(trip.secs(i, i + 1), trip.days)
            }
        }
        val (path, _) = pathOf(pattern.stations) { times.getValue(it).median() }
        val ofRoute = coverage.getOrPut(pattern.routeId) { TreeMap() }
        for (run in path) {
            val (low, high) = minOf(run.from, run.to) to maxOf(run.from, run.to)
            val known = ofRoute[run.line]
            ofRoute[run.line] = if (known == null) {
                LineRun(run.line, low, high)
            } else {
                LineRun(run.line, minOf(known.from, low), maxOf(known.to, high))
            }
        }
    }
    val unused = network.sections.keys - sectionsUsed
    check(unused.isEmpty()) { "line sections that are the path of no hop: $unused" }

    val stationNames = input.stations.mapValues { it.value.name }
    val keptByRoute = patterns.groupBy { it.routeId }
    val tripsByRoute = input.trips.groupBy { it.routeId }
    val routeIds = keptByRoute.keys.sortedWith(
        compareBy({ input.routes.getValue(it).type }, { input.routes.getValue(it).shortName }, { it }),
    )
    val routes = ArrayList<ServiceRoute>()
    val gtfsRoutes = ArrayList<Route>()
    val cores = ArrayList<ServiceCore>()
    for ((index, id) in routeIds.withIndex()) {
        val info = input.routes.getValue(id)
        routes += ServiceRoute(id, info, coverage.getValue(id).values.toList())
        val groups = tripsByRoute.getValue(id).groupBy { it.stations }.map { (stations, trips) ->
            val travelSecs = HashMap<Long, Long>()
            for (trip in trips) {
                travelSecs.merge(trip.times.secs(0, stations.lastIndex), trip.times.days, Long::plus)
            }
            TripGroup(stations, trips.sumOf { it.times.days }, travelSecs)
        }
        val route = Route(id, info.shortName, info.type, groups, stationNames)
        gtfsRoutes += route
        val hopsOfRoute = routeHops.getValue(id)
        for (core in route.cores) {
            val (path, connected) = pathOf(core.stationIds) { hopsOfRoute.getValue(it).median() }
            val kept = keptByRoute.getValue(id).any { pattern ->
                listOf(core.stationIds, core.stationIds.reversed()).any {
                    Collections.indexOfSubList(pattern.stations, it) >= 0
                }
            }
            if (!kept) {
                warn(WarningKind.CORE_LEFT_OUT, listOf(core.stationIds.first(), core.stationIds.last())) {
                    listOf(hopsInvolved(core.stationIds))
                }
            }
            val suffix = core.shortName.removePrefix(info.shortName).ifEmpty { null }
            cores += ServiceCore(index, suffix, core.tripDays, core.stationIds, path.takeIf { connected })
        }
    }
    val hubs = hubOverview(listedRoutes(gtfsRoutes), stationNames).hubs
    return Services(routes, cores, hubs, warnings.values.toList(), outcomes.mapValues { it.value.size })
}

/** The parameters the paths are found with. */
@Serializable
internal class ServicesSource(val pathCandidates: Int, val passSavingSecs: Long)

/** The version of the network file that a services file refers to. */
@Serializable
internal class NetworkReference(val version: Int)

/** The routes, as columns: one entry per route. */
@Serializable
internal class ServiceRoutes(
    val id: List<String>,
    val shortName: List<String>,
    val type: List<Int>,
    val agency: List<String?>,
    val coverage: List<List<Int>>,
)

/** The route cores, as columns: one entry per route core. */
@Serializable
internal class ServiceRouteCores(
    val route: List<Int>,
    val suffix: List<String?>,
    val tripDays: List<Long>,
    val stops: List<List<Int?>>,
    val path: List<List<Int>?>,
)

/** The regional hub stations, as columns: one entry per hub station. */
@Serializable
internal class ServiceHubs(val node: List<Int>, val rank: List<Int>)

/**
 * The file `services.json`: the routes on the regional network (see [NetworkFile] for the
 * conventions of both files).
 *
 * - [format] is always "streckenauslastung-services"; [version] is raised with every change that
 *   readers have to adapt to; [network] is the version of the network file it refers to.
 * - [source]: the number of candidate ways searched per hop and the measured saving per passed station.
 * - A path or coverage is a flat list of runs along lines: `line, from, to, line, from, to, …`, with
 *   `from` and `to` the positions of nodes on the line; `from > to` runs against the direction in which
 *   the line is listed.
 * - [routes]: the GTFS route ID, short name (after the fixes of `TripGroups.kt`), route type and agency
 *   name of each route with a stop pattern kept by the 10-date rule, sorted by route type, short name,
 *   then ID. Its `coverage` has for each line used by the path of one of its stop patterns the range from
 *   the lowest to the highest position used, `from < to`, sorted by line.
 * - [routeCores]: each route core and route core candidate of these routes (see spec.md, "Timetable terms"),
 *   computed from all their trips: the index of its route, its suffix (null without candidates), its
 *   trip days, its typical stop sequence as nodes (null for a station that is no node) and its path
 *   (null if a hop is unconnectable).
 * - [hubs]: the regional hub stations (see pipeline/spec.md) that are nodes, in hub order, with their rank.
 */
@Serializable
internal class ServicesFile(
    val format: String,
    val version: Int,
    val network: NetworkReference,
    val source: ServicesSource,
    val routes: ServiceRoutes,
    val routeCores: ServiceRouteCores,
    val hubs: ServiceHubs,
)

internal const val SERVICES_FORMAT = "streckenauslastung-services"
internal const val SERVICES_VERSION = 1

/** The services file of [services]; [nodeIds] are the nodes in the order of the network file. */
internal fun servicesFile(services: Services, nodeIds: List<String>, source: ServicesSource): ServicesFile {
    val index = nodeIds.withIndex().associate { (i, id) -> id to i }

    fun flat(runs: List<LineRun>) = runs.flatMap { listOf(it.line, it.from, it.to) }
    val routes = services.routes
    val cores = services.cores
    val hubs = services.hubs.filter { it.stationId in index }
    return ServicesFile(
        SERVICES_FORMAT,
        SERVICES_VERSION,
        NetworkReference(NETWORK_VERSION),
        source,
        ServiceRoutes(
            routes.map { it.id },
            routes.map { it.info.shortName },
            routes.map { it.info.type },
            routes.map { it.info.agency },
            routes.map { flat(it.coverage) },
        ),
        ServiceRouteCores(
            cores.map { it.route },
            cores.map { it.suffix },
            cores.map { it.tripDays },
            cores.map { core -> core.stations.map { index[it] } },
            cores.map { core -> core.path?.let(::flat) },
        ),
        ServiceHubs(hubs.map { index.getValue(it.stationId) }, hubs.map { it.rank }),
    )
}
