package streckenauslastung

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.toPath
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectLinesTest {
    private val gtfs: Path = javaClass.getResource("/mini_gtfs/routes.txt")!!.toURI().toPath().parent

    /** A main line from Aheim to Cstadt with a spur to Bdorf that can only be reached from Aheim. */
    private val yaml = """
        stations:
          Aheim: A
          Bdorf: B
          Cstadt: C
        junctions:
          Fork: {lat: 52.05, lon: 13.05}
        segments:
          - {from: Aheim, to: Fork, km: 10}
          - {from: Fork, to: Cstadt, km: 5.5}
          - {from: Bdorf, to: Fork, km: 2.25}
        direct_lines:
          - name: Main line
            nodes: [Aheim, Fork, Cstadt]
          - nodes: [Aheim, Fork, Bdorf]
    """.trimIndent()

    @Test
    fun `parseDirectLines reads stations, junctions, segments and lines`() {
        val directLines = parseDirectLines(yaml)
        assertEquals(mapOf("Aheim" to "A", "Bdorf" to "B", "Cstadt" to "C"), directLines.stations)
        assertEquals(listOf("Fork"), directLines.junctions.map { it.name })
        assertEquals(52.05, directLines.junctions[0].lat)
        assertEquals(listOf("Main line", "Aheim – Bdorf"), directLines.lines.map { it.label })
        assertEquals(15.5, directLines.km(directLines.lines[0]))
        assertEquals(2.25, directLines.km("Fork", "Bdorf"))
        assertNull(directLines.km("Aheim", "Cstadt"))
    }

    @Test
    fun `a junction can only be passed along a line`() {
        val directLines = parseDirectLines(yaml)
        assertTrue(directLines.mayPass("Aheim", "Fork", "Bdorf"))
        assertTrue(directLines.mayPass("Bdorf", "Fork", "Aheim"))
        assertTrue(directLines.mayPass("Cstadt", "Fork", "Aheim"))
        assertFalse(directLines.mayPass("Bdorf", "Fork", "Cstadt"))
    }

    @Test
    fun `parseDirectLines lists what is inconsistent`() {
        val broken = yaml
            .replace("km: 5.5", "km: 0")
            .replace("nodes: [Aheim, Fork, Bdorf]", "nodes: [Aheim, Fork, Dtal]")
            .replace("Main line\n    nodes: [Aheim, Fork, Cstadt]", "Main line\n    nodes: [Aheim, Cstadt, Fork]")
        val message = assertFailsWith<IllegalArgumentException> { parseDirectLines(broken) }.message!!
        for (problem in listOf(
            "segment Fork – Cstadt: km must be greater than 0",
            "line Aheim – Dtal: \"Dtal\" is neither a station nor a junction",
            "line Aheim – Dtal: there is no segment Fork – Dtal",
            "line Main line: ends at the junction \"Fork\", but must end at a station",
            "line Main line: there is no segment Aheim – Cstadt",
            "segment Bdorf – Fork is on no line",
            "\"Bdorf\" is on no line",
        )) {
            assertContains(message, problem)
        }
    }

    @Test
    fun `parseDirectLines rejects another structure`() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { parseDirectLines(text) }.message!!
        assertContains(message(yaml.replace("km: 10", "length: 10")), "segment 1: unknown keys [length]")
        assertContains(message(yaml.replace("km: 10", "km: long")), "segment 1: \"km\" must be a number")
        assertContains(message(yaml.replace("nodes: [Aheim, Fork, Bdorf]", "nodes: [Aheim]")), "line 2: needs at least")
        assertContains(message("stations: ["), "not a YAML file")
    }

    @Test
    fun `directLines checks the station IDs and writes the station list`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val file = tmp.resolve("direct_lines.yaml")
        val out = tmp.resolve("stations.csv")
        file.writeText(yaml)
        directLines(tmp.resolve("rail"), file, out)
        assertEquals(listOf("station_id,station_name", "A,Aheim Hbf", "B,Bdorf", "C,Cstadt"), out.readLines())

        file.writeText(yaml.replace("Bdorf: B", "Bdorf: Z"))
        val message = assertFailsWith<IllegalArgumentException> {
            directLines(tmp.resolve("rail"), file, out)
        }.message!!
        assertContains(message, "station IDs that are not in the data: Bdorf (Z)")
    }

    @Test
    fun `the direct lines of the repository are consistent`() {
        val directLines = parseDirectLines(Files.readString(Path.of("data/manual/direct_lines.yaml")))
        assertTrue(directLines.mayPass("Erfurt Hbf", "Halle-Planena", "Halle (Saale) Hbf"))
        assertFalse(directLines.mayPass("Halle (Saale) Hbf", "Halle-Planena", "Gröbers"))
    }
}
