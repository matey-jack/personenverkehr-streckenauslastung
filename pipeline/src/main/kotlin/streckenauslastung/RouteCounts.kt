@file:Suppress("ktlint:standard:wrapping") // keeps inline SQL strings compact, see .editorconfig

package streckenauslastung

import java.nio.file.Path

/**
 * Counts the rail routes by the prefix of their short name (everything before the first digit,
 * e.g. "RE" for "RE 1"), by route_type and by agency name, in the combinations given below.
 * Returns one tab-separated table per combination, each row with its route count, most first.
 */
internal fun routeCounts(railDir: Path): String {
    fun p(name: String) = sqlPath(railDir.resolve("$name.parquet"))
    val groupings = listOf(
        listOf("prefix", "route_type"),
        listOf("prefix"),
        listOf("route_type"),
        listOf("prefix", "route_type", "agency"),
        listOf("agency"),
    )
    return openDuckDb().use { db ->
        db.exec("""
            CREATE TABLE r AS
            SELECT trim(regexp_extract(coalesce(route_short_name, ''), '^[^0-9]*')) AS prefix, route_type,
                   coalesce(a.agency_name, r.agency_id::VARCHAR, '') AS agency
            FROM ${p("routes")} r LEFT JOIN ${p("agency")} a USING (agency_id)
        """)
        groupings.joinToString("\n") { columns ->
            val cols = columns.joinToString()
            buildString {
                appendLine((columns + "routes").joinToString("\t"))
                db.forEachRow("SELECT $cols, count(*) AS n FROM r GROUP BY ALL ORDER BY n DESC, $cols") { rs ->
                    appendLine((1..columns.size + 1).joinToString("\t") { rs.getString(it) })
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "usage: RouteCounts <rail-parquet-dir>" }
    print(routeCounts(Path.of(args[0])))
}
