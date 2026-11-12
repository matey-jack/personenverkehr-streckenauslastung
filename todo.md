# Todo

Increments to implement, test and review one at a time.

## 1. Project setup ✅
- Kotlin/Gradle project with DuckDB, Parquet as the intermediate format.
- Test fixture `src/test/resources/mini_gtfs`.

## 2. Basic data filtering (implemented, not yet run on the full data set)
- [ ] Run `./gradlew filterRail` on the full Delfi data set. Note the run time and output sizes.
- [ ] Check that `route_type` really is 100–199 for all trains in the Delfi data. Some data sets use the basic GTFS type 2 (rail) instead; if so, include it.
- [ ] Decide whether the resulting Parquet files are small enough to commit (GitHub limit: 100 MB per file). If not: further condense (e.g. drop unused columns, pre-select the time period) or use Git LFS.
- [ ] Commit the condensed data in `data/rail/`.

## 3. Data exploration: trip groups (implemented, not yet run on the full data set)
- [ ] Run `./gradlew tripGroups` and review `data/reports/trip_groups.csv`.
- [ ] Review the two choices made in `TripGroups.kt`:
  - stops are mapped to their parent station (so a different platform doesn't create a new group);
  - groups are defined by the *ordered* station sequence (each direction is a separate group), as in the spec ✅

## 4. Calendar: regular vs. one-off trips
- [ ] Understand `calendar` / `calendar_dates` in the Delfi data (open question in the spec).
- [ ] For each trip, count the days it runs. Find a "typical" weekday and the busiest days and hours.

## Later
- Path finding for trains (which stations are passed without stopping).
- Train-physics model and fitting of acceleration, braking, maximum speed and buffer times.
- Input file formats for path hints, train parameters and exact line data.
- Graphical timetable visualization and critical-point (Zugfolgezeit) summary.
