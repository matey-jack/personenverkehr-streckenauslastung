@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** A junction of direct lines: a place without a station where lines meet. */
internal class Junction(val name: String, val lat: Double, val lon: Double)

/** Two neighbouring nodes, in the order of the file, and the length of the track between them. */
internal class Segment(val from: String, val to: String, val km: Double) {
    val ends = setOf(from, to)
}

/** A direct line: the names of its nodes in order. It runs in both directions. */
internal class DirectLine(val name: String?, val nodes: List<String>) {
    /** The [name], or the two ends of a line without one. */
    val label = name ?: "${nodes.first()} – ${nodes.last()}"
}

/**
 * The manually entered direct lines; `direct_lines.md` describes the model and the file.
 * [stations] maps the names used in the file to station IDs.
 */
internal class DirectLines(
    val stations: Map<String, String>,
    val junctions: List<Junction>,
    val segments: List<Segment>,
    val lines: List<DirectLine>,
) {
    private val kmByEnds = segments.associate { it.ends to it.km }

    /** The length of the segment between two neighbouring nodes; null if there is none. */
    fun km(a: String, b: String): Double? = kmByEnds[setOf(a, b)]

    /** The length of [line]: the sum of its segments. */
    fun km(line: DirectLine): Double = line.nodes.zipWithNext { a, b -> kmByEnds.getValue(setOf(a, b)) }.sum()

    /** Whether a train may run from [from] through [junction] on to [to]: some line has the three in a row. */
    fun mayPass(from: String, junction: String, to: String): Boolean = lines.any { line ->
        line.nodes.windowed(3).any { it == listOf(from, junction, to) || it == listOf(to, junction, from) }
    }

    /** What is wrong with the direct lines, one message each; empty if they are consistent. */
    fun problems(): List<String> {
        val problems = ArrayList<String>()
        val junctionNames = junctions.map { it.name }
        val nodes = stations.keys + junctionNames
        fun unknown(node: String, where: String) {
            if (node !in nodes) problems += "$where: \"$node\" is neither a station nor a junction"
        }
        for (name in junctionNames.filter { it in stations }) problems += "\"$name\" is both a station and a junction"
        for (name in junctionNames.groupBy { it }.filterValues { it.size > 1 }.keys) {
            problems += "junction \"$name\" is listed twice"
        }
        for ((id, names) in stations.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }) {
            problems += "station ID $id has several names: ${names.joinToString()}"
        }
        for (segment in segments) {
            val where = "segment ${segment.from} – ${segment.to}"
            unknown(segment.from, where)
            unknown(segment.to, where)
            if (segment.from == segment.to) problems += "$where: both ends are the same node"
            if (segment.km <= 0) problems += "$where: km must be greater than 0"
        }
        for (ends in segments.groupBy { it.ends }.filterValues { it.size > 1 }.keys) {
            problems += "segment ${ends.joinToString(" – ")} is listed twice"
        }
        for (line in lines) {
            val where = "line ${line.label}"
            line.nodes.forEach { unknown(it, where) }
            if (line.nodes.distinct().size < line.nodes.size) problems += "$where: a node occurs twice"
            for (end in setOf(line.nodes.first(), line.nodes.last()).filter { it in junctionNames }) {
                problems += "$where: ends at the junction \"$end\", but must end at a station"
            }
            line.nodes.zipWithNext { a, b ->
                if (a != b && km(a, b) == null) problems += "$where: there is no segment $a – $b"
            }
        }
        val used = lines.flatMap { line -> line.nodes.zipWithNext { a, b -> setOf(a, b) } }.toSet()
        for (segment in segments.filter { it.ends !in used }) {
            problems += "segment ${segment.from} – ${segment.to} is on no line"
        }
        for (node in nodes - lines.flatMap { it.nodes }.toSet()) problems += "\"$node\" is on no line"
        return problems
    }
}

/** A YAML mapping with the name of its place in the file, for messages. */
private class Entry(val where: String, map: Any?, vararg keys: String) {
    val map: Map<*, *> = map as? Map<*, *> ?: throw IllegalArgumentException("$where must be a mapping")

    init {
        val unknown = this.map.keys.filter { it !in keys }
        require(unknown.isEmpty()) { "$where: unknown keys $unknown; known are ${keys.toList()}" }
    }

    fun text(key: String): String = textOrNull(key) ?: throw IllegalArgumentException("$where: \"$key\" is missing")

    fun textOrNull(key: String): String? = map[key]?.toString()

    fun number(key: String): Double =
        (map[key] as? Number)?.toDouble() ?: throw IllegalArgumentException("$where: \"$key\" must be a number")

    fun list(key: String): List<*> =
        map[key] as? List<*> ?: throw IllegalArgumentException("$where: \"$key\" must be a list")

    fun entries(key: String): Map<*, *> = when (val value = map[key]) {
        null -> emptyMap<Any, Any>()
        is Map<*, *> -> value
        else -> throw IllegalArgumentException("$where: \"$key\" must be a mapping")
    }
}

/**
 * Reads direct lines from the text of a YAML file. Throws an [IllegalArgumentException] if the
 * text has another structure than `direct_lines.md` describes, or if the lines are inconsistent.
 */
internal fun parseDirectLines(yaml: String): DirectLines {
    val root = try {
        Load(LoadSettings.builder().build()).loadFromString(yaml)
    } catch (e: YamlEngineException) {
        throw IllegalArgumentException("not a YAML file: ${e.message}", e)
    }
    val file = Entry("the file", root, "stations", "junctions", "segments", "direct_lines")
    val stations = file.entries("stations").entries.associate { (name, id) -> name.toString() to id.toString() }
    val junctions = file.entries("junctions").map { (name, position) ->
        val entry = Entry("junction $name", position, "lat", "lon")
        Junction(name.toString(), entry.number("lat"), entry.number("lon"))
    }
    val segments = file.list("segments").mapIndexed { i, segment ->
        val entry = Entry("segment ${i + 1}", segment, "from", "to", "km")
        Segment(entry.text("from"), entry.text("to"), entry.number("km"))
    }
    val lines = file.list("direct_lines").mapIndexed { i, line ->
        val entry = Entry("line ${i + 1}", line, "name", "nodes")
        val nodes = entry.list("nodes").map { it.toString() }
        require(nodes.size >= 2) { "line ${i + 1}: needs at least two nodes" }
        DirectLine(entry.textOrNull("name"), nodes)
    }
    val directLines = DirectLines(stations, junctions, segments, lines)
    val problems = directLines.problems()
    require(problems.isEmpty()) { "inconsistent direct lines:\n" + problems.joinToString("\n") { "  $it" } }
    return directLines
}

/**
 * Checks the direct lines in [yamlFile] against the stations of the rail data and writes
 * [stationsFile], the list to look up station IDs in: station_id and station_name of every
 * station as in [createTripGroupTable], sorted by name, then ID.
 *
 * Fails if a station ID of the direct lines is not in the data.
 */
fun directLines(railDir: Path, yamlFile: Path, stationsFile: Path) {
    stationsFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val stationNames = HashMap<String, String>()
    openDuckDb().use { db ->
        createTripGroupTable(db, railDir)
        // station_names also has the parts of stations, which are no stations of their own.
        db.exec("""
            CREATE TABLE stations AS
            SELECT station_id, station_name FROM station_names WHERE station_id IN (SELECT station_id FROM station_of)
        """)
        db.exec("""
            COPY (SELECT * FROM stations ORDER BY station_name, station_id)
            TO ${sqlPath(stationsFile)} (FORMAT csv, HEADER)
        """)
        db.forEachRow("SELECT station_id, station_name FROM stations") { rs ->
            stationNames[rs.getString(1)] = rs.getString(2)
        }
    }
    println("%,d stations -> %s".format(stationNames.size, stationsFile))

    val directLines = parseDirectLines(Files.readString(yamlFile))
    val missing = directLines.stations.filterValues { it !in stationNames }
    require(missing.isEmpty()) {
        "station IDs that are not in the data: " + missing.entries.joinToString { (name, id) -> "$name ($id)" }
    }
    println(
        "%,d direct lines with %,d stations, %,d junctions and %,d segments <- %s".format(
            directLines.lines.size,
            directLines.stations.size,
            directLines.junctions.size,
            directLines.segments.size,
            yamlFile,
        ),
    )
    for (line in directLines.lines) {
        val km = "%.1f km".format(Locale.ROOT, directLines.km(line))
        println("  %-40s %2d nodes, %8s".format(line.label, line.nodes.size, km))
    }
    val renamed = directLines.stations.filter { (name, id) -> stationNames.getValue(id) != name }
    if (renamed.isNotEmpty()) println("Stations with another name in the data:")
    for ((name, id) in renamed) println("  %-30s %s".format(name, stationNames.getValue(id)))
}

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: DirectLines <rail-parquet-dir> <direct-lines-yaml> <stations-out-csv>" }
    directLines(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
}
