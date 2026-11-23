# Development

Notes on how we work on the code, in the whole repository. Each part adds its own: [pipeline/ReadMe-development.md](pipeline/ReadMe-development.md).

## Git: no merges

This project keeps a linear history: we don't do merges, and there are no merge commits.

- To bring a branch up to date with `main`, rebase it onto `main` instead of merging `main` into it:
  `git fetch origin && git rebase origin/main`
- After rebasing a branch that was already pushed, push it with `git push --force-with-lease`. This refuses to overwrite commits on the remote that you haven't fetched yet. Only rewrite a branch that nobody else has commits on.
- Pull requests go into `main` by rebasing (or squashing), not with a merge commit.

This applies to Claude Code sessions as well.

## Dependency versions

- Libraries and tools: always the latest stable version. Dependabot (`.github/dependabot.yml`) opens a weekly PR with all minor and patch updates of each part, and a separate PR for each major update.
- GitHub Actions: also kept up to date by Dependabot, in one weekly PR.

## Specs

- A definition enters a spec when Robert asks for it, concisely; it may be labelled provisional.
