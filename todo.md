# Todo

Increments to implement, test and review one at a time.

## 1. Project setup ✅
- Kotlin/Gradle project with DuckDB, Parquet as the intermediate format.
- Test fixture `src/test/resources/mini_gtfs`.

## 2. Basic data filtering 

All done, the project can now work without the actual full GTFS files present.
The parquet files have everything we need and are committed in Git.

## 3. Data exploration: trip groups 

- [x] stops are mapped to their parent station (so a different platform doesn't create a new group);
- [x] groups are defined by the *ordered* station sequence (each direction is a separate group), as in the spec ✅

## 4. Calendar: regular vs. one-off trips
- [x] Understand `calendar` / `calendar_dates` in the Delfi data ✅
- [ ] For each trip, count the days it runs. Find a "typical" weekday and the busiest days and hours.

## 5. Reconstructing lines (implemented for the five testing routes)
- [x] Review `data/reports/lines.html`.
- [ ] Run it for more core routes. Neither kind of warning has occurred on real data yet.
- [ ] Decide what to do about trains on separate tracks other than the S-Bahn of Berlin and Hamburg: the other S-Bahn networks still fill lines.
- [x] RE2 shares its line with long-distance routes (17, 18) and the FEX. Keep it as a testing route? yes! ✅

## 6. direst_lines.yaml

- [ ] simplify the data format, so it doesn't repeat connections that can be seen from the GTFS data.
  It should also describe the infrastructure only: all connections that a junction offers; not just say which are the 
  paths taken by actual long-distance passenger trains. And make the information atomic, not end-to-end for highly 
  connected lines like Mannheim–Stuttgart (the most connected line of all).

## Later
- Path finding for trains (which stations are passed without stopping).
- Train-physics model and fitting of acceleration, braking, maximum speed and buffer times.
- Input file formats for path hints, train parameters.
- Graphical timetable visualization and critical-point (Zugfolgezeit) summary.
