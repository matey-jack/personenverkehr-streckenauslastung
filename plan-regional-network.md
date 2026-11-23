# Plan: Reconstructing lines, regional part

A working document for the increment in `todo.md`. Once it is settled, fold what is left of it into `spec.md` and
`todo.md` and delete this file.

The input is defined by `todo.md` ("Pragmatic definitions") and the derivation by `pipeline/spec.md` ("Derivation from regional
train data only"). Numbers below come from throwaway DuckDB prototypes on `pipeline/data/rail`, with the station IDs of
`station_of` in `TripGroups.kt` but without `stationParts`:

- 88,800 regional trips (route types 106 and 109 without the agencies "S-Bahn Berlin GmbH" and "S-Bahn Hamburg"),
  6,691 stations.
- The prototype of the section rule (without the travel-time part): about 7,200 line sections, 800 junctions,
  300 terminals. The whole network is well under 1 MB of JSON.

## 1. Open questions

None blocking.

The TODO at "Data Model and Term Definitions" in `spec.md` still stands: the per-route reconstruction of `lines.html`
is described there. That is increment 8 below.

## 2. Derivation

My reading of the spec, as the `network` step will implement it:

- **Input**: the trips of `todo.md`, stations deduplicated and fixed as for `hub_stations.html`. Trips without an
  arrival or departure time at some stop are left out (none in the data so far), so every hop and span has times.
- **Hops**: every pair of consecutive stops of an input trip, directionless, with its trip days and its median travel
  time (weighted by trip days, from departure to arrival).
- **Consistent span**: by the rule in `pipeline/spec.md`, whose three numbers are `-P` options.
- **Sections**: the hops without a consistent span. A hop with only inconsistent spans stays a section and gets a
  warning; so does a hop that some span with more stops runs faster than the hop itself.
- **Lines**: chains of sections between junctions and terminals. A ring without any junction is one line, starting
  and ending at its alphabetically first station. Lines are listed from the end with the alphabetically earlier name.
- **Paths**: for each two consecutive stops of a trip, the paths through the sections between them that the detour
  rule allows (a search that stops at that time limit); of these, the one whose time matches the hop best. No path:
  a warning. Two paths about equally good: a warning.
- **Warnings** are printed as a summary and written to `data/reports/network_warnings.csv`, each with the median
  travel times and trip days of the hops involved. Kinds: inconsistent spans, faster spans, two lines between the
  same junctions (usually duplicate station IDs, see `pipeline/gtfs_findings.md`), loops, rings, unconnectable or
  ambiguous paths.
- A **loop** is a line whose two ends are the same junction: it leaves the junction and comes back to it, so it
  hangs on the rest of the network at that one node. A **ring** is a closed chain of stopping stations without any
  junction: it does not touch the rest of the network at all, and its start is arbitrary. Both can be real (a
  balloon loop, an isolated circular line), but more likely come from a missing section or a station split into
  two IDs. (We had neither loops nor rings in the first regional data set that we processed. Note that something like 
  Berlin's Ringbahn has several junctions splitting the loop into several plain lines.)

## 3. Data format

### Logical model

Two files, because the infrastructure and the services change for different reasons, and the timetable later extends
only the second.

**Network**:

- **Node**: ID, name, coordinates, whether it is a station. Today every node is a station; the junctions that are not
  stations (`todo.md`) become nodes without one, so the model says "node" from the start. Whether a node is a junction,
  terminal or stopping station follows from the lines and is not stored.
- **Line**: its nodes in order. Its sections are the pairs of neighbouring nodes; each has its median travel time and
  trip days.
- The derivation parameters and the GTFS data set it was made from.

**Services** (refers to the network by index):

- **Route**: ID, short name (after `routeNameFixes`), route type, agency.
- **Route core**: its route, suffix, trip days, typical stop sequence and path.
- **Stop pattern** (the trip group of `TripGroups.kt`, after the 10-date rule): its route, stops, number of trips,
  trip days and path. These are the untimed trips of this iteration; timed trips later each refer to a stop pattern.
- **Hub station**: node and rank of each regional hub station, in hub order.

A **path** is a list of runs along lines: (line, from position, to position), where from > to means against the
direction in which the line is listed. The stops are a subsequence of the nodes along the path.

The frontend builds the reverse index itself (section → route cores and routes passing it): a few hundred thousand
entries, cheaper to compute than to download.

### JSON representation

- **Tables as columns**: each table is an object of equally long arrays, `{"id": [...], "name": [...]}`. No key is
  repeated per row, and one generic decoder turns any table into rows.
- **References are array indices**, never IDs. GTFS IDs like `de:VBB:11000000|R-Bahn|RE2:DB_106` appear once.
- **Coordinates as integers** in units of 10⁻⁵ degrees (about 1 m).
- **Paths as flat integer arrays**: `[line, from, to, line, from, to, ...]`.
- **Absent values as `null`**.
- A header with `format` and `version` (raised on every incompatible change), the GTFS data set and the derivation
  parameters, so the frontend can refuse a file it does not understand.
- No compression of our own: GitHub Pages serves JSON gzipped. Expected sizes: network about 0.5 MB, services about
  2 MB before gzip.

```json
{
  "format": "streckenauslastung-network", "version": 1,
  "source": {"routeTypes": [106, 109], "excludedAgencies": ["…"], "minDates": 10, "detourFactor": 1.33, "stopAllowanceSecs": 180, "extraSecs": 120},
  "nodes": {"id": ["de:12054:900230999", "…"], "name": ["Potsdam Hbf", "…"], "lat": [5239172, "…"], "lon": [1306692, "…"], "isStation": [true, "…"]},
  "lines": {"nodes": [[0, 17, 18, 4], "…"], "medianSecs": [[120, 95, 240], "…"], "tripDays": [[812, 790, 790], "…"]}
}
```

```json
{
  "format": "streckenauslastung-services", "version": 1, "network": {"version": 1},
  "routes": {"id": ["…"], "shortName": ["RE2", "…"], "type": [106, "…"], "agency": ["DB Regio", "…"]},
  "routeCores": {"route": [0, "…"], "suffix": ["a", "…"], "tripDays": [1230, "…"], "stops": [[0, 17, 4], "…"], "path": [[3, 0, 3, 8, 5, 0], "…"]},
  "patterns": {"route": [0, "…"], "stops": [[0, 4], "…"], "trips": [12, "…"], "tripDays": [240, "…"], "path": [[3, 0, 3], "…"]},
  "hubs": {"node": [4, "…"], "rank": [20, "…"]}
}
```

The format is documented in the KDoc of the Kotlin classes that write it, as `RouteCores.kt` documents the columns of
`route_cores.csv`; the TypeScript types mirror them without repeating the prose. The files go to `pipeline/data/network/` and
are committed like the reports. They are written with `kotlinx.serialization` (a new dependency), which the later
Kotlin timetable steps also use to read them.

## 4. Frontend project

Where it lives and its stack: see `todo.md`, "Frontend project".

### Structure

- `web/src/data/`: the types of the two files, the column decoder, the reverse indexes; shared by all later pages.
- `web/src/geo/`: projection, equirectangular with the x axis scaled by cos 51°, into kilometres, once on loading.
- `web/src/network/`: the network viewer.
- Later `web/src/timetable/`, with hash routing between the pages (`#/network`, `#/timetable/…`).
- In development, Vite serves `pipeline/data/network/` as its public folder; the build copies it into the site.

### The network viewer

- Sidebar: the regional hub stations (`pipeline/spec.md`), searchable, multi-select.
- Shown: the regional route cores connected to a selected hub, their paths, the line runs these use.
- Nodes: the junctions and terminals on the shown lines, labelled. A path that ends at a stopping station just ends
  there, unlabelled.
- Fitting: the viewBox is the bounding box of the shown nodes and path ends plus a margin, with a short animated
  transition whenever these change.
- Mode toggle: straight lines between consecutive junctions, terminals and path ends, or one segment per section
  with the stops as small dots.
- Route list in natural order of the names; clicking one highlights its path and dims the rest.
- Tooltip on a line piece (the section in segment mode): the route cores passing it, a separator, the other routes
  with a stop pattern passing it.

### CI and docs

- `tests.yml`: a job running `npm ci`, `npm run check` (types, lint) and `npm test` in `web/`.
- `pages.yml`: also run the `network` step and `npm run build`; the app goes to `site/app/`.
- A Stop hook like `gradle-check.sh` for changes under `web/`.
- `pipeline/ReadMe.md`: the new step in the task table. `web/ReadMe.md`: how to start the frontend.
  `web/ReadMe-development.md`: the frontend checks.

## 5. Increments

1. **`network` step** (Kotlin): input filter, hops, sections, lines, `network.json`, the warnings report and a
   printed summary. Done; the rule for consistent spans is `SpanRule`, separate from the `DetourRule` of `Lines.kt`,
   which compares with the fastest trip and counts the stop at the end as well.
2. **Review the warnings** on the real data: duplicate station IDs into `stationParts`, tune the two numbers of the
   rule. Findings go to `pipeline/gtfs_findings.md`.
3. **Services**: route cores, stop patterns, paths, regional hub stations in `services.json`; tests for the path
   search and the regional hub stations.
4. **Frontend scaffold**: checks, CI, Pages, Dependabot; decoder and indexes
   with tests against the JSON written from `mini_gtfs`.
5. **Viewer**: hub selection, drawing, fitting, labels.
6. **Viewer**: route list with highlighting, tooltip, mode toggle.
7. **Docs**: ReadMe task table and frontend notes.
8. **`lines.html`**: base it on the network (the path of the route core) or retire it; update the spec sections it
   is described in.
