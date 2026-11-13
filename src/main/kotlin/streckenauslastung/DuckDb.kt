package streckenauslastung

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Opens an in-memory DuckDB database. All persistent data lives in Parquet/CSV files, not in a database file. */
fun openDuckDb(): Connection = DriverManager.getConnection("jdbc:duckdb:")

/** Quotes a path as an SQL string literal. */
fun sqlPath(path: Path): String = "'" + path.toAbsolutePath().normalize().toString().replace("'", "''") + "'"

fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

fun Connection.queryLong(sql: String): Long = createStatement().use { st ->
    st.executeQuery(sql).use { rs ->
        rs.next()
        rs.getLong(1)
    }
}
