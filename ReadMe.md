# Personenverkehr Streckenauslastung

This project uses German [GTFS data from Delfi]. Since the dataset is 40 GB large, I did not commit it to the repository. Unpack it into the folder `20261109_fahrplaene_gesamtdeutschland_gtfs` to run the project.

[GTFS data from Delfi]: https://www.opendata-oepnv.de/ht/de/organisation/delfi/startseite?tx_vrrkit_view%5Baction%5D=details&tx_vrrkit_view%5Bcontroller%5D=View&tx_vrrkit_view%5Bdataset_name%5D=deutschlandweite-sollfahrplandaten-gtfs&cHash=af4be4c0a9de59953fb9ee2325ef818f

A condensed and compressed view of the data, however is part of the repository, so that a lot of the code can be run without the download.

## Way of working

I am always writing the spec and docs first, often using present tense even for things that are not implemented yet. There's `todo.md` which breaks the things to implement into increments that I'll test and review individually.

## Technology

- **Kotlin** (on the JVM, Java 21) for all programs: data preparation, the train-physics model, fitting, and later serving the visualizations.
- **[DuckDB](https://duckdb.org/)** (embedded, via JDBC) for filtering the GTFS CSV files and for exploration queries. It runs in memory; there is no database file.
- **[Parquet](https://parquet.apache.org/)** files for intermediate results (compressed, columnar, readable by DuckDB, Python/Polars, pandas, …). Small human-facing reports are CSV.
- **Gradle** with the wrapper (`./gradlew`), so no separate Gradle installation is needed. If no JDK 21 is installed, Gradle downloads one automatically.

Why not H2: it is row-oriented and built for transactional workloads, slow at bulk imports and large aggregations, and its file format changes between major versions, which is bad for data files committed to the repository.

## Running

Each pipeline step is a separate Gradle task (`./gradlew tasks --group pipeline` lists them). On Windows use `gradlew.bat` instead of `./gradlew`.

| Task | Input | Output |
|---|---|---|
| `./gradlew filterRail` | `20261109_fahrplaene_gesamtdeutschland_gtfs/*.txt` | `data/rail/*.parquet`: routes with `route_type` 100–199 and their trips, stop_times, stops (incl. parent stations), calendar, calendar_dates, agency |
| `./gradlew tripGroups` | `data/rail/*.parquet` | `data/reports/trip_groups.csv`: for each route, the groups of trips with the same sequence of stations |

Paths can be overridden, e.g. `./gradlew filterRail -Pgtfs=/path/to/gtfs -Pout=data/rail`.

`./gradlew test` runs the tests against a tiny hand-made GTFS data set in `src/test/resources/mini_gtfs`.

Data conventions in the Parquet files:
- All GTFS columns are kept as text, so IDs keep any leading zeros. Exceptions: `route_type` and `stop_sequence` are integers.
- `stop_times` gets two extra columns `arrival_secs` / `departure_secs`: seconds since midnight of the service day. GTFS times can be 24:00:00 or later for trips running past midnight, so these can exceed 86400.

GitHub rejects files over 100 MB. Check the size of `data/rail/*.parquet` before committing it.
