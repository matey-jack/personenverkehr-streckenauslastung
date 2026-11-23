# Development of the pipeline

Notes on how we work on the Kotlin code, in addition to the project-wide ones in [../ReadMe-development.md](../ReadMe-development.md). For what the pipeline does and how to run it, see [ReadMe.md](ReadMe.md).

## Dependency versions

- The ktlint version is set in `gradle/libs.versions.toml`, the one place where Dependabot can see it.
- JDK: the latest LTS release. Dependabot doesn't touch it; upgrade it by hand every few years, when a new LTS comes out (`jvmToolchain(...)` in `build.gradle.kts`, `java-version` in the workflows in `../.github/workflows/`, and the ReadMe).

## Data exploration

Questions about the data are answered with Kotlin code in the repository, not with throwaway scripts, so that everyone sees the same result: a step in the `pipeline` task group that writes a report to `data/reports/` and prints a short summary. A report is a CSV file, or an HTML page for browsing: one self-contained file written with kotlinx.html, with its style and script in `src/main/resources`. Each step gets a row in the task table of [ReadMe.md](ReadMe.md) and a test against `mini_gtfs`.

- On an HTML page with headings and tables, decide what has to stay visible while scrolling (page heading, navigation, section heading, table header) and make it stick to the top.
- Concepts are refined by comparing variants ("how would the rate change if…"). Make variants cheap to compare, with summary lines or a `-P` option, instead of hard-wiring one.
- Before validating a concept against the data, check what the data means by "the same station" and "how often": both changed the route core results more than the definition itself did.
- For a quick SQL query that is not worth keeping, use the DuckDB JDBC driver that Gradle has downloaded. DuckDB 2 only accepts the lambda syntax `lambda x: ...`, not `x -> ...`.

## Checks

`./gradlew check` runs the tests plus static checks:
- [ktlint](https://pinterest.github.io/ktlint/) for formatting and style (configured in `../.editorconfig`). 
  * `./gradlew ktlintFormat` fixes most findings automatically. Two it can't fix: 
  * a condition mixing `&&` and `||` needs named sub-conditions or parentheses, and an argument list that doesn't fit 
    on one line needs every argument on its own line. 
  * It also wraps a line that is too long in awkward places, and may need a second run to finish; shorten such a line 
    by hand first, with a named value or a helper function.
- The Kotlin compiler treats all warnings as errors, with its extra checks (such as unused variables) switched on.

GitHub Actions runs `./gradlew check` on every push and pull request (`../.github/workflows/tests.yml`). When Claude Code finishes a turn in which files of the pipeline changed, a Stop hook (`../.claude/hooks/gradle-check.sh`) runs it too and has Claude fix any failure.
