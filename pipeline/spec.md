# Pipeline spec

How the pipeline derives the data of this project. The aim, the architecture and the terms used here are in
[../spec.md](../spec.md).

# Basic data filtering

We filter all routes by the route_type, keeping only types 100 to 199, see
[Extended GTFS Route Types.md](Extended%20GTFS%20Route%20Types.md).
We then filter all trips, keeping only ones relating to the retained route_ids.
And then similarly filter stop_times, stops, and so on.

Fun fact: I really had to run this only one time. Thereafter I worked from the filtered data only.

# Heuristics to derive infrastructure data from timetable information

- A **hop** is a trip segment with exactly two stops, usually referred to as A and B.
- A **span** is a trip segment with more than two stops.

Note that from a single trip or even from all the trips of a single route, we won't know if a hop actually corresponds
to a line section.

For all the derivations that follow, first apply the station deduplication and the station fixes that are also used for 
the `hub_stations.html` report.

## Derivation from regional train data only

One of the basic tasks of the data derivation is to determine whether a hop corresponds to a line section or not.
Unlike what we do for long-distance trains, we include all regional rail trips to make sure we don't miss a single
station. Sometimes only a train in the very early or late hours stops at all stations and thus testifies to their
order.

Heuristics:

- Start by collecting all hops of regional train trips and note how many trip-days are having that hop and the median 
  travel time for the hop. 
- A hop of a regional train is not a line section if there is another trip (of any route) which has a span between the
  hop's end points and the travel times are consistent: the median travel time of the span is at most 1.33 times
  that of the hop, plus 3 minutes per extra stop, plus 2 minutes. (With these values, most of the remaining warnings
  are real detours or real pairs of lines; they may be tuned further.)
  + Print warnings for anything suspicious during this round; print the median travel time and occurrence count (in 
    trip-days) for all the hops involved.
- All other hops define line sections.
- From the set of those line sections, we can tell which stations are junctions and terminals.
- Finally, from the set of junctions and terminals we can join line sections to lines!

Since we do all of this from GTFS data alone, all junctions so far will also be stations. A line is thus defined as a
sequence of stations, with the ends always being junctions or terminals and the intermediates always being stopping
stations.

In some rare cases, even two different regional train trips might take two different paths between two junctions. If
there is at least one intermediate stop in both paths, we can tell them apart. If the travel time matches one route 
better, we can derive the path from that.
Otherwise, we need some more heuristics or a manually added hint. This is partly what the network graph is for: we 
don't have to get it right on the first try. We can tune the rules or add rules for specific lines (aka manual hints).  

The lack of alternative train paths on short routes also means that we do not expect to have to use the "travel time is
consistent" rule much.

## Derivation of the long-distance network

To keep everything easier to follow, we first filter all the long-distance trips: group them by stopping pattern and 
only include groups with at least N trips on at least M days.

### Step 1: derive the super-network of long-distance stations only.

This uses a similar algorithm as above: fill in stations between hops using other trips. But we use trips of
long-distance trains only. Unlike the regional network, trains will often have alternate paths through the network,
which is why the travel-time rule becomes important here: There might be a path A-B-C-D and additionally another path
A-D which is another direct line section with no intermediate stations. (There might be junctions, but we can't derive
them from the data.)

Even on this level, routing might not have an obvious solution. We need to find the edge cases, manually resolve them,
and define a data format to store those resolutions. In the simplest form, a mini-app in the HTML page just has a text
log of the decisions at the bottom of the page. And an upload/download button for those. Later the same data format
will be consumed by our data processing pipeline. That is, we commit the resolution file as part of the project.

A pathing problem for a very long hop usually resolves to a path using existing line sections. (So we need to save
which ones.)

A pathing problem for a short hop resolves to either a path or to defining its own line section.

### Step 2: map the super-network to the regional one

We take line sections from the super-network and map them to lines (or paths of lines) of the regional network.

Again, this is driven by the travel-time heuristic, this time with an added bonus for long-distance trains which have a
higher top speed in addition to having fewer stops.

The two kinds of resolutions are the same as above:

- either map a hop onto a path
- or define a new line section by the hop

# Extracting meaningful "typical" routes and trips

## Ideas on meaningfully grouping the Routes (via their route cores)

- A route (core) is "connected" to the stations where it starts, stops or ends.
- The "rank" of a station is the number of routes starting or ending there.
  Berlin Hauptbahnhof gets a capital bonus: its rank is 20. (This is to signify its importance despite having mostly
  through traffic, as part of Berlin's multi-station through-running route concept.)
- A station is a "hub station" if
    - route cores of several routes start or end there, or
    - route cores of several routes are connected to it, and one of them is connected to no hub station of a higher
      rank.
- A route is "dangling" as long as it is connected to no hub station, while the hub stations are found from the
  highest rank down. A route that is still dangling in the end is an "isolated route". It shares no station
  with a route core of another route.

Only hub stations of a higher rank count in the second rule, so that stations of similar size are treated alike: if a
dangling route runs from A to B, where another route stops but no other route ends, both A and B are hub stations.

Hub stations are ordered by rank, then by the number of routes stopping there, then by name. Each route is
given the direction from its end that comes first in this order to its other end, where a station that is no hub
station comes after all hub stations.

**Regional hub stations** are found by the same rules, but only from the route cores of the routes in the regional
network: the routes of its route types and agencies that keep at least one trip under its 10-date rule (see
[../todo.md](../todo.md), "Reconstructing lines, regional part").

An overview page lists the hub stations in this order.
With each hub station we list the routes starting or ending there, and below those the routes merely stopping
at that station.
The route is listed by route short name, route type, start and end station.
As a navigation aid, the station names in routes are clickable.

And below all the hub stations we list all isolated routes.

# The old test page for showing the derived infrastructure data

NOTE: this section only describes the "lines.html" report.

Routes we use for testing, with the ID of their route core:

- RE50 Erfurt – Meiningen: `2233941_106`
- RE21 Erfurt – Gera: `7149172_106`
- RE6 Hennigsdorf – Wittenberge: `de:VBB:12070424|R-Bahn|RE6:_106`
- RB33 Potsdam – Jüterbog: `4740564_106`
- RE2 Cottbus – Nauen: `de:VBB:11000000|R-Bahn|RE2:DB_106`

A test page lists the reconstructed line of each of them, and at each station the times of the timed trains and what
stops there: the route cores with a trip using the line there, and the routes where only other trips do. Below the
line it lists the warnings, with the strong ones set apart, then all these route cores, and then the additional
routes: those of which only other trips use the line. Both lists are sorted by name. Tabs switch between the agencies
of the testing routes.

The "timed trains" show how long real trains take along the line. Each is the "median trip" of some trips of one
route with the same stops in the same order: the fastest of them that at least half of their trip days do not exceed
in travel time. A train is timed on its longest stretch of stops between which it uses the line and keeps one
direction. Its column is headed by its name and the time of day at which it leaves its first station of the stretch,
and gives the minutes after that at which it arrives at each station, and also those at which it leaves again if it
stands there for two minutes or more.

- The first timed train is that of the route core, in the direction in which the line is listed.
- If it leaves stations untimed, the next one is the train with the most stops on the line that stops at one of them.
- Up to two more follow as long as stations are untimed: each time the train that stops at the most of them.
- Trains of route cores are picked first, because their trips are the typical ones: another train is only picked if
  no train of a route core stops at an untimed station.
- Among the trains of route cores, and among the others, a train running against the listed direction is only picked
  if no train in the listed direction stops at an untimed station.
- Among equal trains, the one with the smaller route name comes first.

Relevant routes of one line that share their route_short_name get the suffixes `-1`, `-2`, … on it, in the order of
their route_id. The suffix of a route core follows it: `S8-2a`.

Things to be designed:

- does it make sense to make a timetable for an entire day, so the user can scroll through the time axis? This would
  spare us the work of deciding which time to pick as a representative!
- it still leaves us the work of picking a representative day... unless we prepare the data in a way that the user can
  pick. But the date picker should show at least a color code for how many trips there are on the line compared to the
  median over all days. 
