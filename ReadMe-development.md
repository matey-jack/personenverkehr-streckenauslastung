# Development

Notes on how we work on the code. For what the project does and how to run it, see [ReadMe.md](ReadMe.md).

## Git: no merges

This project keeps a linear history: we don't do merges, and there are no merge commits.

- To bring a branch up to date with `main`, rebase it onto `main` instead of merging `main` into it:
  `git fetch origin && git rebase origin/main`
- After rebasing a branch that was already pushed, push it with `git push --force-with-lease`. This refuses to overwrite commits on the remote that you haven't fetched yet. Only rewrite a branch that nobody else has commits on.
- Pull requests go into `main` by rebasing (or squashing), not with a merge commit.

This applies to Claude Code sessions as well.

## Dependency versions

- Libraries, Gradle plugins, Kotlin and Gradle itself: always the latest stable version. Dependabot (`.github/dependabot.yml`) opens a weekly PR with all minor and patch updates, and a separate PR for each major update. The ktlint version is set in `gradle/libs.versions.toml`, the one place where Dependabot can see it.
- GitHub Actions: also kept up to date by Dependabot, in one weekly PR.
- JDK: the latest LTS release. Dependabot doesn't touch it; upgrade it by hand every few years, when a new LTS comes out (`jvmToolchain(...)` in `build.gradle.kts`, `java-version` in `.github/workflows/tests.yml`, and the ReadMe).

## Checks

`./gradlew check` runs the tests plus static checks:
- [ktlint](https://pinterest.github.io/ktlint/) for formatting and style (configured in `.editorconfig`). `./gradlew ktlintFormat` fixes most findings automatically.
- The Kotlin compiler treats all warnings as errors, with its extra checks (such as unused variables) switched on.

GitHub Actions runs `./gradlew check` on every push and pull request (`.github/workflows/tests.yml`). When Claude Code finishes a turn in which Kotlin or Gradle files changed, a Stop hook (`.claude/hooks/gradle-check.sh`) runs it too and has Claude fix any failure.
