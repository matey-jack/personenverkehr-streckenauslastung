plugins {
    kotlin("jvm") version "2.4.21"
    // Formatter and linter; `./gradlew ktlintFormat` fixes most findings, `check` runs `ktlintCheck`.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.duckdb:duckdb_jdbc:2.0.0.1")

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
    version.set("1.8.0")
}

tasks.test {
    useJUnitPlatform()
}

// Each step of the pipeline is a separately runnable program (see ReadMe.md, "Program architecture").
// Paths are relative to the project root; override with -Pgtfs=... / -Pout=... if needed.
fun registerStep(name: String, mainClass: String, description: String, args: () -> List<String>) =
    tasks.register<JavaExec>(name) {
        group = "pipeline"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        this.mainClass.set(mainClass)
        workingDir = rootDir
        maxHeapSize = "4g"
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
    )
}
