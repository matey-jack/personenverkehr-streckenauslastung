# Personenverkehr Streckenauslastung

Estimates the operational timetables of German railway lines from the published timetable, to visualize them and the
capacity usage of the lines. [spec.md](spec.md) describes the aim and the terms in detail.

The project has two parts, each in a folder of its own:

- [pipeline/](pipeline/ReadMe.md): Kotlin programs that read the published timetable (GTFS), derive the network and
  timetables from it and write them as data files, plus HTML reports to check them.
- `web/`: the frontend that shows the data written by the pipeline: the network viewer and later the timetable pages.

## Way of working

I am always writing the spec and docs first, often using present tense even for things that are not implemented yet. There's `todo.md` which breaks the things to implement into increments that I'll test and review individually.

The rules for working on the code (for example: no merges) are described in [ReadMe-development.md](ReadMe-development.md).
