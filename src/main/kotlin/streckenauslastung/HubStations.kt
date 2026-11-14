package streckenauslastung

import java.nio.file.Files
import java.nio.file.Path

/**
 * A core route of the route [routeId] as listed on the page. It has no direction: both of its ends
 * are a start and an end.
 */
internal class ListedRoute(
    val routeId: String,
    val shortName: String,
    val type: Int,
    val stationIds: List<String>,
) {
    val ends = setOf(stationIds.first(), stationIds.last())
}

/**
 * A station where core routes of more than one route start or end. The two core routes of one
 * route mostly share their ends, so they count once.
 */
internal class Hub(
    val stationId: String,
    val ending: List<ListedRoute>,
    val stopping: List<ListedRoute>,
) {
    /** The number of routes with a core route starting or ending here. */
    val numRoutes = ending.distinctBy { it.routeId }.size
}

/** [hubs] with the most routes starting or ending there first; [isolated] routes touch no hub. */
internal class HubOverview(val hubs: List<Hub>, val isolated: List<ListedRoute>)

/** Groups [routes] by hub station (see spec.md, "Ideas on meaningfully grouping the Core Routes"). */
internal fun hubOverview(routes: List<ListedRoute>, stationNames: Map<String, String>): HubOverview {
    val ending = LinkedHashMap<String, MutableList<ListedRoute>>()
    for (route in routes) for (end in route.ends) ending.getOrPut(end) { ArrayList() }.add(route)
    val hubIds = ending.filterValues { ends -> ends.distinctBy { it.routeId }.size > 1 }.keys
    val stopping = HashMap<String, MutableList<ListedRoute>>()
    for (route in routes) {
        for (station in route.stationIds.toSet() - route.ends) {
            if (station in hubIds) stopping.getOrPut(station) { ArrayList() }.add(route)
        }
    }
    val hubs = hubIds
        .map { Hub(it, ending.getValue(it), stopping[it].orEmpty()) }
        .sortedWith(compareBy({ -it.numRoutes }, { stationNames.getValue(it.stationId) }, { it.stationId }))
    return HubOverview(hubs, routes.filter { route -> route.stationIds.none { it in hubIds } })
}

/**
 * The core routes to list, in the order of [routes]. Core routes with the same route type, short
 * name and stations are listed once.
 *
 * This is the only place that decides which core routes the page shows.
 */
internal fun listedRoutes(routes: List<Route>): List<ListedRoute> = routes
    .flatMap { route ->
        route.coreRoutes.map { ListedRoute(route.id, it.shortName, route.type, it.pattern.stationIds) }
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

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val STYLE = """
:root { color-scheme: light dark; }
body { font-family: system-ui, sans-serif; font-size: 14px; margin: 0; }
main { padding: 0.5em 1.5em 50vh; }
header label { margin-right: 1em; }
nav { display: none; }
body.sidebar nav {
  display: block; position: fixed; top: 0; bottom: 0; left: 0; width: 19em;
  overflow-y: auto; padding: 0.5em; box-sizing: border-box; border-right: 1px solid #8886;
}
body.sidebar main { margin-left: 19em; }
nav input { width: 100%; box-sizing: border-box; }
nav ol { list-style: none; padding: 0; }
nav li { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
nav a { text-decoration: none; }
small { opacity: 0.65; }
details { border-top: 1px solid #8886; padding: 0.3em 0; }
summary { font-size: 1.15em; font-weight: 600; cursor: pointer; }
details:target > summary { background: #fc06; }
h3 { font-size: 1em; margin: 0.6em 0 0.2em 1em; }
table { border-collapse: collapse; margin-left: 1em; }
td { padding: 1px 1em 1px 0; }
td.name { font-weight: 600; min-width: 5em; }
td.type { min-width: 10em; }
"""

/**
 * Both navigation variants are one document: `body.sidebar` shows the station list beside fully
 * expanded sections, `body.collapsible` hides it and starts with all sections closed.
 */
private const val SCRIPT = """
const sections = document.querySelectorAll('details');
function openTarget() {
  const target = document.getElementById(location.hash.slice(1));
  if (target) { target.open = true; target.scrollIntoView(); }
}
function setNavigation(mode) {
  document.body.className = mode;
  sections.forEach(s => { s.open = mode === 'sidebar'; });
  openTarget();
}
document.querySelectorAll('input[name=navigation]').forEach(radio => {
  radio.addEventListener('change', () => setNavigation(radio.value));
});
document.getElementById('filter').addEventListener('input', event => {
  const text = event.target.value.toLowerCase();
  document.querySelectorAll('nav li').forEach(li => {
    li.hidden = !li.textContent.toLowerCase().includes(text);
  });
});
addEventListener('hashchange', openTarget);
addEventListener('click', event => { if (event.target.closest('a[href^="#"]')) setTimeout(openTarget); });
setNavigation('sidebar');
"""

/** Renders the overview as a self-contained HTML page. */
internal fun hubStationsPage(overview: HubOverview, stationNames: Map<String, String>): String {
    val anchors = overview.hubs.withIndex().associate { (i, hub) -> hub.stationId to "hub${i + 1}" }

    fun station(id: String, link: Boolean = true): String {
        val name = html(stationNames.getValue(id))
        val anchor = anchors[id]
        return if (link && anchor != null) "<a href=\"#$anchor\">$name</a>" else name
    }

    /** One table row per route; a route ending at [hubId] is shown starting there. */
    fun StringBuilder.routeTable(routes: List<ListedRoute>, hubId: String? = null) {
        append("<table>\n")
        for (route in routes) {
            val (first, last) = route.stationIds.first() to route.stationIds.last()
            val (start, end) = if (last == hubId) last to first else first to last
            val type = routeTypeNames[route.type]?.let { "$it <small>${route.type}</small>" } ?: route.type.toString()
            append("<tr><td class=\"name\">${html(route.shortName)}</td><td class=\"type\">$type</td>")
            append("<td>${station(start, link = start != hubId)}</td><td>–</td>")
            append("<td>${station(end, link = end != hubId)}</td>")
            append("<td><small>${route.stationIds.size} stops</small></td></tr>\n")
        }
        append("</table>\n")
    }

    return buildString {
        append("<!DOCTYPE html>\n<html lang=\"de\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<title>Hub stations and their core routes</title>\n<style>$STYLE</style>\n</head>\n<body>\n")
        append("<nav>\n<input id=\"filter\" type=\"search\" placeholder=\"Filter stations\">\n<ol>\n")
        for (hub in overview.hubs) {
            append("<li><a href=\"#${anchors[hub.stationId]}\">${station(hub.stationId, link = false)}</a>")
            append(" <small>${hub.numRoutes}</small></li>\n")
        }
        append("<li><a href=\"#isolated\">Isolated core routes</a> <small>${overview.isolated.size}</small></li>\n")
        append("</ol>\n</nav>\n<main>\n<header>\n<h1>Hub stations and their core routes</h1>\n<p>Navigation:\n")
        append("<label><input type=\"radio\" name=\"navigation\" value=\"sidebar\" checked> station sidebar</label>\n")
        append("<label><input type=\"radio\" name=\"navigation\" value=\"collapsible\"> collapsible stations</label>\n")
        append("</p>\n</header>\n")
        for (hub in overview.hubs) {
            append("<details id=\"${anchors[hub.stationId]}\">\n<summary>${station(hub.stationId, link = false)} ")
            append(
                "<small>${hub.numRoutes} routes starting or ending, ${hub.stopping.size} stopping</small></summary>\n",
            )
            append("<h3>Starting or ending here</h3>\n")
            routeTable(hub.ending, hub.stationId)
            if (hub.stopping.isNotEmpty()) {
                append("<h3>Stopping here</h3>\n")
                routeTable(hub.stopping)
            }
            append("</details>\n")
        }
        append("<details id=\"isolated\">\n<summary>Isolated core routes ")
        append("<small>${overview.isolated.size} touching no hub station</small></summary>\n")
        routeTable(overview.isolated)
        append("</details>\n</main>\n<script>$SCRIPT</script>\n</body>\n</html>\n")
    }
}

/**
 * Data exploration (see spec.md, "Ideas on meaningfully grouping the Core Routes"): writes an HTML
 * page of the hub stations with their core routes, followed by the isolated core routes.
 * The core routes are those of spec.md, "Core Routes", in the direction of their most frequent trip group.
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
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: HubStations <rail-parquet-dir> <out-html>" }
    hubStations(Path.of(args[0]), Path.of(args[1]))
}
