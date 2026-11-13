package streckenauslastung

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.toPath
import kotlin.test.Test
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
            assertEquals(24 * 3600L + 600, db.queryLong("SELECT arrival_secs FROM $st WHERE trip_id = 'T1' AND stop_sequence = 3"))
        }
    }

    @Test
    fun `tripGroups groups trips by station sequence`(@TempDir tmp: Path) {
        filterRail(gtfs, tmp.resolve("rail"))
        val out = tmp.resolve("trip_groups.csv")
        tripGroups(tmp.resolve("rail"), out)
        val lines = out.readLines()
        assertEquals("route_id,route_short_name,route_type,group_no,num_stops,num_trips,first_station,last_station,stations", lines[0])
        // T1 and T2 use different platforms at Aheim but form one group; T3 runs the other way.
        assertEquals("R1,RE1,106,1,3,2,Aheim Hbf,Cstadt,Aheim Hbf > Bdorf > Cstadt", lines[1])
        assertEquals("R1,RE1,106,2,2,1,Cstadt,Aheim Hbf,Cstadt > Aheim Hbf", lines[2])
        assertEquals(3, lines.size)
    }
}
