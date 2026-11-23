plugins {
    kotlin("jvm") version "2.4.21"
    // @Serializable classes for the JSON files of the network (see Network.kt).
    kotlin("plugin.serialization") version "2.4.21"
    // Formatter and linter; `./gradlew ktlintFormat` fixes most findings, `check` runs `ktlintCheck`.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.duckdb:duckdb_jdbc:2.0.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors.set(true)
        extraWarnings.set(true) // additional checks such as unused variables and redundant code
    }
}

ktlint {
    // Set in gradle/libs.versions.toml, where Dependabot can update it.
    version.set(libs.versions.ktlint)
}

// DuckDB's JDBC driver loads a native library. Since Java 24 that prints a warning unless native
// access is enabled, and future Java releases will block it (https://openjdk.org/jeps/472).
val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.test {
    useJUnitPlatform()
    jvmArgs(enableNativeAccess)
}

// Each step of the pipeline is a separately runnable program (see spec.md, "Program architecture").
// Paths are relative to pipeline/; override with -Pgtfs=... / -Pout=... if needed.
fun registerStep(name: String, mainClass: String, description: String, args: () -> List<String>) =
    tasks.register<JavaExec>(name) {
        group = "pipeline"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        this.mainClass.set(mainClass)
        workingDir = rootDir
        maxHeapSize = "4g"
        jvmArgs(enableNativeAccess)
        args(args())
    }

registerStep(
    "filterRail",
    "streckenauslastung.FilterRailKt",
    "Step 1: filter the full GTFS data set down to rail routes and write Parquet files.",
) {
    listOf(
        (findProperty("gtfs") ?: "20261109_fahrplaene_gesamtdeutschland_gtfs").toString(),
        (findProperty("out") ?: "data/rail").toString(),
    )
}

registerStep(
    "tripGroups",
    "streckenauslastung.TripGroupsKt",
    "Exploration: group the trips of each rail route by their sequence of stations.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/reports/trip_groups.csv").toString(),
        (findProperty("topOut") ?: "data/reports/trip_groups_top.csv").toString(),
    )
}

registerStep(
    "routeCores",
    "streckenauslastung.RouteCoresKt",
    "Find the route core and route core candidates of each rail route.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/reports/route_cores.csv").toString(),
    )
}

registerStep(
    "hubStations",
    "streckenauslastung.HubStationsKt",
    "Exploration: write an HTML page of the stations where several route cores start or end.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/reports/hub_stations.html").toString(),
    )
}

registerStep(
    "longDistanceStations",
    "streckenauslastung.LongDistanceStationsKt",
    "Exploration: write an HTML page of the stations with the most long-distance trips.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/reports/long-distance_stations.html").toString(),
        (findProperty("top") ?: "150").toString(),
    )
}

registerStep(
    "lines",
    "streckenauslastung.LinesKt",
    "Exploration: write an HTML page of the reconstructed lines of the testing routes.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/reports/lines.html").toString(),
        (findProperty("stopAllowance") ?: "180").toString(),
        (findProperty("detourFactor") ?: "1.5").toString(),
    )
}

registerStep(
    "routeCounts",
    "streckenauslastung.RouteCountsKt",
    "Exploration: print the numbers of rail routes by short-name prefix, route_type and agency.",
) {
    listOf((findProperty("rail") ?: "data/rail").toString())
}

registerStep(
    "network",
    "streckenauslastung.NetworkKt",
    "Derive the regional rail network and write it as JSON, with a report of its warnings.",
) {
    listOf(
        (findProperty("rail") ?: "data/rail").toString(),
        (findProperty("out") ?: "data/network/network.json").toString(),
        (findProperty("warnings") ?: "data/reports/network_warnings.csv").toString(),
        (findProperty("stopAllowance") ?: "180").toString(),
        (findProperty("detourFactor") ?: "1.33").toString(),
        (findProperty("extraAllowance") ?: "120").toString(),
        (findProperty("minDates") ?: "10").toString(),
    )
}
