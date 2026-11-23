package streckenauslastung

import java.util.PriorityQueue
import kotlin.math.abs

/** A run along the line [line] from its node at position [from] to that at [to]; from > to runs against its listing. */
internal data class LineRun(val line: Int, val from: Int, val to: Int)

/**
 * A way between two stations: its [runs], the [stations] along it including both ends, and [secs], the
 * sum of the median travel times of its sections.
 */
internal class Way(val runs: List<LineRun>, val stations: List<String>, val secs: Long) {
    /** The number of stations passed between the two ends. */
    val passed get() = stations.size - 2
}

/** How the path of a hop was found. */
internal enum class PathOutcome(val text: String) {
    SECTION("the hop is a line section"),
    MATCHED("one way matches the travel time best"),
    AMBIGUOUS("two ways match the travel time about equally well"),
    DETOUR("no way is allowed by the span rule; the shortest is taken"),
    UNCONNECTABLE("no way connects the two stations"),
}

/**
 * The path of a hop: its [outcome] and the [way] taken (null if unconnectable). [candidates] are the ways
 * that the span rule allows, best match first, or all ways searched if it allows none.
 */
internal class HopPath(val outcome: PathOutcome, val way: Way?, val candidates: List<Way>)

/**
 * Finds the paths of hops through the lines of [network] (see plan-regional-network.md, "Derivation"):
 * a hop that is a line section takes it; for any other hop, the [maxCandidates] shortest simple ways
 * are searched on the graph of the line ends and the two ends of the hop, and of those that [rule]
 * allows, the one whose estimated time matches the hop best is taken.
 */
internal class PathFinder(private val network: Network, private val rule: SpanRule, private val maxCandidates: Int) {
    private val lines = network.lines

    /** For each line, the sum of the medians of its sections up to each position. */
    private val cumulative: List<LongArray> = lines.map { line ->
        val sums = LongArray(line.stations.size)
        line.sections.forEachIndexed { i, times -> sums[i + 1] = sums[i] + times.median() }
        sums
    }

    /** For each station, its places on the lines: (line, position). */
    private val places = HashMap<String, MutableList<Pair<Int, Int>>>().also { places ->
        lines.forEachIndexed { l, line ->
            line.stations.forEachIndexed { p, station -> places.getOrPut(station) { ArrayList() } += l to p }
        }
    }

    /** For each line section, its line and the position of its first node there. */
    private val sectionPlaces = HashMap<Hop, Pair<Int, Int>>().also { at ->
        lines.forEachIndexed { l, line ->
            for (i in 0 until line.stations.size - 1) at[hop(line.stations[i], line.stations[i + 1])] = l to i
        }
    }

    private val candidateCache = HashMap<Pair<String, String>, List<Way>>()

    /** The node at position [p] of line [l]. */
    private fun station(l: Int, p: Int) = lines[l].stations[p]

    private fun secs(run: LineRun) = abs(cumulative[run.line][run.to] - cumulative[run.line][run.from])

    /** The stations along [runs], starting at [start]. */
    private fun stationsAlong(start: String, runs: List<LineRun>): List<String> {
        val stations = arrayListOf(start)
        for (run in runs) {
            val step = if (run.to > run.from) 1 else -1
            var p = run.from
            while (p != run.to) {
                p += step
                stations += station(run.line, p)
            }
        }
        return stations
    }

    private data class Edge(val to: String, val run: LineRun, val secs: Long)

    /**
     * The edges from [node] of the search from [x] to [y]: along each line through it, in both directions,
     * to the next line end or to [y]. An edge that would pass [x] is left out.
     */
    private fun edges(node: String, x: String, y: String): List<Edge> {
        val edges = ArrayList<Edge>()
        for ((l, p) in places[node].orEmpty()) {
            val last = lines[l].stations.lastIndex
            for (step in listOf(1, -1)) {
                var q = p + step
                while (q in 0..last) {
                    val s = station(l, q)
                    if (s == x) break
                    if (s == y || q == 0 || q == last) {
                        val run = LineRun(l, p, q)
                        edges += Edge(s, run, secs(run))
                        break
                    }
                    q += step
                }
            }
        }
        return edges
    }

    /** The shortest way from [source] to [y] in the search from [x], avoiding [bannedNodes] and starting with no run of [bannedRuns]. */
    private fun shortest(
        source: String,
        x: String,
        y: String,
        bannedNodes: Set<String>,
        bannedRuns: Set<LineRun>,
    ): List<Edge>? {
        val dist = HashMap<String, Long>()
        val previous = HashMap<String, Pair<String, Edge>>()
        val queue = PriorityQueue<Pair<Long, String>>(compareBy({ it.first }, { it.second }))
        dist[source] = 0
        queue += 0L to source
        while (queue.isNotEmpty()) {
            val (d, node) = queue.poll()
            if (d > dist.getValue(node)) continue
            if (node == y) {
                val edges = ArrayList<Edge>()
                var at = y
                while (at != source) {
                    val (from, edge) = previous.getValue(at)
                    edges += edge
                    at = from
                }
                return edges.reversed()
            }
            for (edge in edges(node, x, y)) {
                if (edge.to in bannedNodes || (node == source && edge.run in bannedRuns)) continue
                val next = d + edge.secs
                if (next < (dist[edge.to] ?: Long.MAX_VALUE)) {
                    dist[edge.to] = next
                    previous[edge.to] = node to edge
                    queue += next to edge.to
                }
            }
        }
        return null
    }

    /**
     * The [maxCandidates] shortest ways from [x] to [y] that pass no station twice, shortest first (Yen's
     * algorithm on the graph of [edges]).
     */
    fun candidates(x: String, y: String): List<Way> = candidateCache.getOrPut(x to y) {
        val first = shortest(x, x, y, emptySet(), emptySet()) ?: return@getOrPut emptyList()
        val found = arrayListOf(first)
        val seen = hashSetOf(first)
        val queue = PriorityQueue<List<Edge>>(compareBy({ path -> path.sumOf { it.secs } }, { it.toString() }))
        while (found.size < maxCandidates) {
            val previous = found.last()
            for (i in previous.indices) {
                val root = previous.subList(0, i)
                val spur = if (i == 0) x else root.last().to
                val bannedRuns = found.filter { it.size > i && it.subList(0, i) == root }.map { it[i].run }.toSet()
                val bannedNodes = (listOf(x) + root.map { it.to }).dropLast(1).toSet()
                val rest = shortest(spur, x, y, bannedNodes, bannedRuns) ?: continue
                val path = root + rest
                if (seen.add(path)) queue += path
            }
            found += queue.poll() ?: break
        }
        found
            .map { edges -> edges.map { it.run } }
            .map { runs -> Way(runs, stationsAlong(x, runs), runs.sumOf(::secs)) }
            .filter { it.stations.toSet().size == it.stations.size }
    }

    /** The path of the hop from [x] to [y] whose trips take [hopSecs] (their median). */
    fun path(x: String, y: String, hopSecs: Long): HopPath {
        sectionPlaces[hop(x, y)]?.let { (l, i) ->
            val run = if (station(l, i) == x) LineRun(l, i, i + 1) else LineRun(l, i + 1, i)
            return HopPath(PathOutcome.SECTION, Way(listOf(run), listOf(x, y), secs(run)), emptyList())
        }
        val candidates = candidates(x, y)
        if (candidates.isEmpty()) return HopPath(PathOutcome.UNCONNECTABLE, null, candidates)
        val allowed = candidates.filter { rule.isConsistent(it.secs, hopSecs, it.passed) }
        if (allowed.isEmpty()) return HopPath(PathOutcome.DETOUR, candidates.first(), candidates)

        // Passing stations saves at most half of the time: on ways of many short sections, the saving
        // per station would otherwise make them about as fast as a direct section.
        fun estimate(way: Way) = maxOf(way.secs - network.passSavingSecs * way.passed, way.secs / 2)
        fun mismatch(way: Way) = abs(estimate(way) - hopSecs)
        val ranked = allowed.sortedBy(::mismatch)
        val (best, second) = ranked[0] to ranked.getOrNull(1)
        val ambiguous = second != null &&
            best.runs.map { it.line }.toSet() != second.runs.map { it.line }.toSet() &&
            mismatch(second) - mismatch(best) <= maxOf(AMBIGUITY_SECS, hopSecs / AMBIGUITY_DIVISOR)
        return HopPath(if (ambiguous) PathOutcome.AMBIGUOUS else PathOutcome.MATCHED, best, ranked)
    }

    /** The two ends of each line section along [path]. */
    fun sections(path: List<LineRun>): List<Hop> = path.flatMap { run ->
        val step = if (run.to > run.from) 1 else -1
        generateSequence(run.from) { it + step }.takeWhile { it != run.to }
            .map { hop(station(run.line, it), station(run.line, it + step)) }.toList()
    }

    companion object {
        /** Two ways are ambiguous if they match the hop equally well within this many seconds… */
        const val AMBIGUITY_SECS = 60L

        /** …or within this fraction of the hop's time (1/10). */
        const val AMBIGUITY_DIVISOR = 10L
    }
}

/** [runs] with neighbouring runs along the same line in the same direction merged into one. */
internal fun joinRuns(runs: List<LineRun>): List<LineRun> {
    val joined = ArrayList<LineRun>()
    for (run in runs) {
        val last = joined.lastOrNull()
        val sameWay = last != null && last.line == run.line && last.to == run.from &&
            (last.to > last.from) == (run.to > run.from)
        if (sameWay) joined[joined.lastIndex] = LineRun(run.line, last.from, run.to) else joined += run
    }
    return joined
}
