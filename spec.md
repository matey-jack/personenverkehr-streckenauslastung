Aim of the project: estimate the operational timetables of railway lines and stations from the published timetable. Then
use this data to visualize the timetable and estimate capacity usage.
Caveat: most lines also carry freight trains which cannot be estimated from the published timetables. But the
operational timetables might show gaps that hint at scheduled paths for freight trains.

For the purpose of this app, the difference between the published and operational timetable is that the operational one
has the passing times of all trains, even the ones that don't stop in a station. Estimating those needs two steps:

- knowing which path a train takes
- estimating the passing times at the intermediate stations using a simplified physical model of trains: first using
  acceleration/decelaration and max speeds of stopping trains to estimate distances between stops; then using the
  acc/dec and max speeds of the fast train with those distances to get the passing times. Both calculations also need to
  include buffer times that need to be estimated or assumed with pre-defined constants.

The estimations of the physical train parameters and buffer times need to be fitted to match the published timetable.

For selected lines, exact distance and max speed information can be given and will replace the estimated distances. It
will also make our estimates of train properties more accurate by fitting against this data.

# Inputs

The basic input data is a GTFS data set for all of Germany. From this relevant train data has to be extracted. Possibly
be stored in a binary format to make subsequent steps faster.

Other inputs can be given optionally:

- hints for the path of trains that are not obvious from the timetable
- max speeds and acceleration/deceleration for specific trains (by type and line)
- exact distance and max speed information between stations of specific lines

All of those are given in text files whose format we need to define.

# Program architecture

There are several separately runnable programs to prepare the data, especially the parts where its just filtering (and
possibly storing in a binary format).
Intermediate results are written into files, so that most of the development and running can be done from intermediate
files without the need to read the full GTFS data set.

# Basic data filtering

We filter all routes by the route_type, keeping only types 100 to 199 see `Extended GTFS Route Types.md`.
We then filter all trips, keeping only ones relating to the retained route_ids.
And then similarly filter stop_times, stops, and so on.

# Data exploration

GTFS has a mix of very regular services (like every hour of every day with the exact same stops and travel times) and
irregular services (only on specific days or specific times of day, and individual parts of a route or even a longer
route or a different set of stops).

We are interested of getting data for the most typical kind of situation – when all the regular trains run – and
additionally for the most crowded of hours of the workday (commuter time) or the year (around public holidays or start
and end of school vacations).

The data exploration should help us find those times and do a data pre-selection so that all other data won't be used
in most of the app.

## Regular trips vs. one-offs

GTFS gives the days a trip runs through its service: a weekly pattern between a start and an end date (`calendar`),
plus single dates that are added or removed (`calendar_dates`).

In the Delfi data set of 2026-11-09 every service covers the same seven weeks, 2026-10-24 to 2026-12-12. Of its 9,422
rail services, 3,505 have no weekly pattern at all and run only on the dates added for them. Such services are
one-offs and construction timetables, but also regular trips that Delfi publishes as a list of dates.

So the number of trips says little about how typical a kind of trip is; the number of days they run does (see
"trip-day" under "Core Routes").

# Extracting meaningful "typical" routes and trips

## "Core Routes"

A route often has a few trips that are prolonged on either or both ends, often during early or late hours of the day.
A "core route" is a set of stations that is representative for the trips of the route.

Provisional definition:

- A "trip-day" is one trip on one day it runs. All frequencies are counted in trip-days, so that a trip which runs on
  a single day weighs less than one that runs daily.
- A "pattern" is the set of stations served by a trip, regardless of direction and order.
- The "majority stations" of a route are the stations served on more than half of its trip-days.
- The "difference" of a pattern is the number of its stations that are not majority stations, plus the number of
  majority stations it lacks.
- Each route has one or two core routes: its most frequent pattern, and the pattern with the smallest difference
  (the most frequent one of those, if several have it). Often both are the same pattern, which then is the only
  core route. A route without any majority station only has its most frequent pattern as core route.
- Two core routes of the same route get the suffixes `:a` (most frequent) and `:b` (smallest difference) on the
  route_id, and `a` and `b` on the route_short_name. If the route_short_name ends with a letter, the suffixes on it
  are `-1` and `-2` instead.

## Ideas on meaningfully grouping the Core Routes

The two core routes of a route mostly share their ends. So below, "several routes" means core routes of more than one
route.

- A core route is "connected" to the stations where it starts, stops or ends.
- The "rank" of a station is the number of core routes starting or ending there.
  Berlin Hauptbahnhof gets a capital bonus: its rank is 20.
- A station is a "hub station" if
  - core routes of several routes start or end there, or
  - core routes of several routes are connected to it, and one of them is connected to no hub station of a higher
    rank.
- A core route is "dangling" as long as it is connected to no hub station, while the hub stations are found from the
  highest rank down. A core route that is still dangling in the end is an "isolated core route". It shares no station
  with a core route of another route.

Only hub stations of a higher rank count in the second rule, so that stations of similar size are treated alike: if a
dangling core route runs from A to B, where another route stops but no other route ends, both A and B are hub stations.

Hub stations are ordered by rank, then by the number of core routes stopping there, then by name. Each core route is
given the direction from its end that comes first in this order to its other end, where a station that is no hub
station comes after all hub stations.

An overview page lists the hub stations in this order.
With each hub station we list the core routes starting or ending there, and below those the core routes merely stopping
at that station.
The core route is listed by route short name, route type, start and end station.
As a navigation aid, the station names in routes are clickable.

And below all the hub stations we list all isolated core routes.

# Visualizations

Graphical timetable for lines between two major stations. Those will include all passenger trains which pass there, even
if they don't stop on any station on the line. (Although we'll add the latter only in a later version.)

Standard German timetable pattern: stations on the horizontal axis; time on the vertical passing from top down.
On double-tracked lines we only show trains in one direction (buttons to select the direction), whereas for
single-tracked lines we show trains in both directions, because the train meets of both directions are crucial here.

In the visualizations critical points are marked: places where the train interval (Zugfolgezeit) is smallest. A summary
beneath the visualization lists all the critical points below a given threshold and thus gives an impression of the line
capacity. (A lot of short critical points means the line is used over capacity, because a delay of one train would
affect one or more other trains.)

## Data Model for the Graphical Timetable

(Für die deutschen Leser: 'line' ist eine Strecke, nicht eine Linie. Letztere heißen 'route' im GTFS.)

The line is the actual physical infrastructure which has all the stations where trains can stop, whether a trip does 
stop there or not. Our timetables are line-based and since the GTFS dataset doesn't give us any line information, we'll 
derive that from the trip data. 

Graphical Timetable:
 - the scope is one core route: start and end of that core route, and the line (or sequence of lines if the route 
   passes through junctions) it runs on. For routes that we list twice with a/b core routes, those count as different 
   ones: the user chooses which one sets the scope.
 - the timetable includes all other routes running on those lines or parts of them. 
 - for now, we start with routes that don't share track with long-distance trains, so there is much less guessing about 
   the behavior of trains between stops. 
 - the shape of the time-position diagram shows which stations a train stops at.
 - use green graphs for RB, RSB, and S-Bahn trains; red graphs for RE and IRE trains; black graphs for others.
 - label each graph with the route_short_name.
 - routes that branch on or off the line are drawn starting from the station they first or last stop on that line, 
   labeled with an arrow and the route's terminal in that direction. 
 - while the core route defines the scope in the space-dimension, the time-dimension will use the exact data of trips 
   of this route and others that use the line.
 - the first only needs to work for single- and double-tracked lines. We'll allow-list selectable routes manually at 
   first. 

First task: design the algorithm / ruleset to reconstruct the line data (sequence of all possible stops, at least all
that GTFS allows us to see) from the trip data given one core route that defines the scope of this specific timetable.
This includes finding all the other relevant routes, including those that have only non-core trips running on that line.
(That is trips whose pattern is not a core route of their route.)

### Reconstructing the line (provisional)

Stations are those of "Core Routes", frequencies are counted in trip-days. For this task we ignore the direction: a 
trip running the other way counts with its stations in reverse order.

- A "hop" is a pair of neighbouring stations A, B of the line. The line starts as the stations of the core route, in
  the order of its most frequent trip.
- A "filler" of a hop is a trip of any route that stops at A, later at B, and at other stations in between, none of
  which is on the line yet.
- A filler is a "detour", and ignored, if it takes longer from A to B than 1.5 times the fastest trip from A to B plus
  3 minutes for each of its stops after A up to and including B.
- Each hop that has fillers gets the in-between stations of some of them inserted, in their order: fillers with the
  same in-between stations count together, and those with the most trip-days win. This is repeated until no hop has a
  filler left. Later rounds add the stations of the other fillers, and those of trips that stop at only one of A
  and B.
- A station of a filler that never gets inserted is "unplaced": no trip shows where it lies among the stations of the
  line. It hints at a second line between A and B.

A trip "uses the line" between two of its consecutive stops if both are stations of the line, and it is no detour
between them. A route is relevant for the timetable if one of its trips uses the line.

What this cannot see:
- a trip that stops at only one station of the line (it counts as crossing the line),
- a trip that passes the whole line without stopping,
- whether trips run on separate tracks beside the line, like an S-Bahn beside a main line: their stations become
  stations of the line.

Routes we use for testing, with the route_id of their core route:
 - RE50 Erfurt – Meiningen: `2233941_106`
 - RE21 Erfurt – Gera: `7149172_106`
 - RE6a Hennigsdorf – Wittenberge: `de:VBB:12070424|R-Bahn|RE6:_106:a`
 - RB33 Potsdam – Jüterbog: `4740564_106`
 - RE2 Cottbus – Nauen: `de:VBB:11000000|R-Bahn|RE2:DB_106`

A test page lists the reconstructed line of each of them, and at each station what stops there: the core routes with
a trip using the line there, and the routes where only other trips do. Below the line it lists all these core routes,
and then the additional routes: those of which only other trips use the line. Both lists are sorted by name. Tabs
switch between the agencies of the testing routes.

Relevant routes of one line that share their route_short_name get the suffixes `-1`, `-2`, … on it, in the order of
their route_id. The suffix of a core route follows it: `S8-2a`.

Things to be designed:
 - does it make sense to make a timetable for an entire day, so the user can scroll through the time axis? This would 
   spare us the work of deciding which time to pick as a representative! 
 - it still leaves us the work of picking a representative day... unless we prepare the data in a way that the user can 
   pick. But the date picker should show at least a color code for how many trips there are on the line compared to the 
   median over all days. 
