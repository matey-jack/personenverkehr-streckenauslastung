Aim of the project: estimate the operational timetables of railway lines and stations from the published timetable.
Then use this data to visualize the timetable and estimate capacity usage.
Caveat: most lines also carry freight trains which cannot be estimated from the published timetables.
But the operational timetables might show gaps that hint at scheduled paths for freight trains.

For the purpose of this app, the difference between the published and operational timetable is that the operational one
has the passing times of all trains, even the ones that don't stop in a station. Estimating those needs two steps:

- knowing which path a train takes. We build a simplified data model of the rail network to do this.
- estimating the passing times at the intermediate stations using a simplified physical model of trains: first using
  acceleration/decelaration and max speeds of stopping trains to estimate distances between stops; then using the
  acc/dec and max speeds of the fast train with those distances to get the passing times. Both calculations also need to
  include buffer times that need to be estimated or assumed with pre-defined constants.

The estimations of the physical train parameters and buffer times need to be fitted to match the published timetable.

For selected lines, exact distance and max speed information can be given and will replace the estimated distances.
It will also make our estimates of train properties more accurate by fitting against this data.

# Inputs

The basic input data is a GTFS data set for all of Germany. We discard all transit modes that are not heavy rail.

Other inputs can be given optionally:

- hints for the path of trains that are not obvious from the timetable
- max speeds and acceleration/deceleration for specific trains (by type and line)
- exact distance and max speed information between stations of specific lines

All of those are given in text files whose format we need to define.

# Program architecture

We follow an incremental approach using a lot of separately running programs (all accessing the same data model).

The first set of programs visualizes the input data in different ways, so we can verify small cleanups, like
station-groupings or name fixes.

The second set derives the infrastructure data and again visualizes it for verification.
Later this will also allow us to incorporate and edit any manual additions to infrastructure and train pathing.
Network diagrams are already a nice intermediate result to explore and enjoy.

And the final set creates the actual timetables that we are after.

# Basic data filtering

We filter all routes by the route_type, keeping only types 100 to 199 see `Extended GTFS Route Types.md`.
We then filter all trips, keeping only ones relating to the retained route_ids.
And then similarly filter stop_times, stops, and so on.

Fun fact: I really had to run this only one time. Thereafter working from the filtered data only.

# Data Model and Term Definitions

TODO: this partially supersedes information in later level 1 sections and those need to be adapted.

## Infrastructure terms

- A **station** is a node in the network where at least one trip stops.
- A **line** is a physical connection between two junctions with optionally some stops in between.
- A **line section** is the part of a line atomically connecting two stations with no intermediate stations.
- A **junction** is a node in the network that connects to at least three line sections.
- A **terminal** is a node that connects only to one line section.
- A **station** is a **junction station** when it is also a **junction**.
- Stations that are neither terminal nor a junction are called a **stopping station** or, if there is no confusion with
  homonymous timetable term, simply a **stop**.

Note that definition of line section and junction is circular, which is why it is non-trivial to reconstruct the
infrastructure data from the GTFS trips.

(Our internal model of lines differs from the numbering of railway lines in that our lines always end at junctions or
junction stations. Whereas official railway line numbers usually have the main line keeping its number on both sides
of the junction and only the branch line having a different number. We are approximating this instead by basing
operational timetables on routes, not lines.)

## Timetable terms

Route and Trip are defined as in GTFS:

- A **trip** is a sequence of stations with times. It represents a train running once per day for a number of days
  specified by its calendar properties.
- A **trip day** is the number of days each trip runs on in the calendar.
- A **stop** is the fact that the trip stops at a station.
- A **path** is the sequence of stations a trip is passing between its end points. Given the end points, this is
  equivalent to knowing the sequence of junctions or the sequence of line sections that the train is passing. (This
  assumes that there are never two different lines between the same two junction stations.)
- A **route** is a collection of related trips. Ideally a route would have all trips with the same stopping part, set
  apart by an hourly rhythm or an integer fraction of an hour (30 mins, 20 mins, 15 min, 10 mins, etc). In practice,
  routes contain many odd trips, especially in the early or late hours of the day when trips are cut short. This
  includes serving only part of a route, prolonging the route, or serving more or less intermediate stops.

In order to visualize routes and use them as part of timetables, we define the term **core part of a route** or **route
core** to be the set of all trips between the most frequent end points of all trips, weighted by trip days.
The intermediate stops of those trips as well as the travel times might be different, so the route core only carries
statistics on them: min, median, and max for each.

While a trip has a direction, we consider routes to be directionless. We normalize all route cores simply by using the
alphabetically earlier station name as the start and the other as the end. (When displaying them to the user we might
normalize in a different way, for example putting the current station first, when showing all routes starting or ending
at specific station. In user-facing communication we also sometimes just use "route" when displaying the route core
information. )

## Heuristics to derive infrastructure data from timetable information

- A **hop** is a trip segment with exactly two stops, usually referred to as A and B.
- A **span** is a trip segment with more than two stops.

Note that from a single trip or even from all the trips of a single route, we won't know if a hop actually corresponds
to a

### Derivation from regional train data only

One of the basic tasks of the data derivation is to determine whether a hop corresponds to a line section or not.
Unlike what we do for long-distance trains, we include all regional rail trips to make sure we don't miss a single
station. Sometimes only a very earyl or late hours trains stops at all stations and thus testifies about their
ordering.

Heuristics:

- A hop of a regional train is not a line section if there is another trip (of any route) which has a span between the
  hop's end points and the travel times are consistent. (For instance, a maximum of 2 extra minutes per extra stop.)
- All other hops define line sections.
- From the set of those line sections, we can tell which stations are junctions and terminals.
- Finally, from the set of junctions and terminals we can join line sections to lines!

Since we do all of this from GTFS data alone, all junctions so far will also be stations. A line is thus defined as a
sequence of stations, with the ends always being junctions or terminals and the intermediates always being simple
stops.

In some rare cases, even two different regional train trips might take two different paths between two junctions. If
there is at least one intermediate stop in both paths, we can tell them apart. Otherwise, we need some more
heuristics or a manually added hint.

The lack of alternative train paths on short routes also means that we do not expect to have to use the "travel time is
consistent" rule much.

### Derivation of the long-distance network

To keep everything easier to follow, we first filter all the long-distance trips: include only trips that run at least
N times a day on at least M days.

#### Step 1: derive the super-network of long-distance stations only.

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

#### Step 2: map the super-network to the regional one

We take line sections from the super-network and map them to lines (or paths of lines) of the regional network.

Again, this is driven by the travel-time heuristic, this time with an added bonus for long-distance trains which have a
higher top speed in addition to having less stops.

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
    - routes of several routes start or end there, or
    - routes of several routes are connected to it, and one of them is connected to no hub station of a higher
      rank.
- A route is "dangling" as long as it is connected to no hub station, while the hub stations are found from the
  highest rank down. A route that is still dangling in the end is an "isolated route". It shares no station
  with a route of another route.

Only hub stations of a higher rank count in the second rule, so that stations of similar size are treated alike: if a
dangling route runs from A to B, where another route stops but no other route ends, both A and B are hub stations.

Hub stations are ordered by rank, then by the number of routes stopping there, then by name. Each route is
given the direction from its end that comes first in this order to its other end, where a station that is no hub
station comes after all hub stations.

An overview page lists the hub stations in this order.
With each hub station we list the routes starting or ending there, and below those the routes merely stopping
at that station.
The route is listed by route short name, route type, start and end station.
As a navigation aid, the station names in routes are clickable.

And below all the hub stations we list all isolated routes.

## Deducing the long-distance train network from the GTFS data (plus minimal manually added data)

Everything here is clipped at the border with only a select few foreign stations to be included: Basel Bad, Basel SBB,
Salzburg. All other eligible long-distance trains are capped at the border with an arrow and labeled there with the
final destination without showing that on the map. (And there will later be no operational graph timetables on the
foreign sections.)

Most parts of the rail network observe the rule "all direct trains between two stops A and B take the same route, only
with different stopping patterns". And for many of remaining cases, the two alternative routes can be disambiguated by
mentioning an intermediate station on one of the possible alternative routes. (If the trip doesn't stop there, mention
the station in parentheses or explicitly "non-stop via X".) There's only very few direct high-speed lines with no
intermediate stop, and we can refer to those by saying "via NBS".

In order to visually verify our work and maybe also to find routing path ambiguities more easily in code, let's build a
network graph for the German long-distance train network. We include every hop by a 101 or 102 type route that has a
minimal number of daily trips for a minimum number of days in the timetable period. (My suggestion: at least 3 days per
number of months in the timetable period that have at least 6 trips each.)

The first step needs to be a filtering of all long-distance stations to be in scope and a grouping of stations from the
GTFS data into logical stations, manually giving priority to several stations in the same city. From that we'll cap all
the trips to just those stations and only signal further intermediate stops symbolically and further stops past the
last in-scope station with a labeled arrow. This should reduce the manual work and also make the diagrams more legible.
And the number of different trips by route should be reduced.

Then we build the network from those hops with the following assumptions:

- first use a manually entered list of NBS lines and mark those hops as going via the NBS.Those NBS will also need
  information on junction points that are not stations. We need to define a data model for that. We'll probably include
  distance information in that model, since it's an easy addition.
- then for all hops that match a regional train route, assume that the long-distance train hops via that route's line.
- then for the remaining larger hops, route them via the line sections added before, using the hop times of stopping
  long-distance trains to validate the travel time.
- finally, there will only be a very few cases where a train does very large hops and could route them via different
  paths that both would roughly match its travel time. We can list those cases in a CSV file, then I'll go ride the
  trains to check the path and write that in the file.

We can build a test page from this which shows the list of all long-distance routes in a sidebar and an SVG of the
network on the main page. Use the station coordinates to place stations in the SVG. Show only one station by city and
label it with only the name of the city. When a route is selected and shown on the map, show the actual names of the
stations in each city, but only one per city, preferring Hbf if there are more than one. When that doesn't break all
ties, I'll give override rules later.
Lines of the map can be visually differentiated between mixed line (with regional traffic, this actually includes
some high-speed lines, so we don't need to manually add those), pure high-speed line (no regional stops), and yet
unclassified "large" hops of "Sprinter" type trains.

On the sidebar, include a filter asking for a minimal number of trips on a minimal number of days. This way we can start
from the major routes and dial it up over time.

TODOs:

- define data format in which the NBS list is to be provided. Actually, that is so little data, it could be a constant
  in
  the code. But still, we need to define how the stations are identified which are connected via NBS'.
- define data format for the manual routing of trains when paths are ambiguous.

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

- the scope is one route between its core end stations.
- the timetable includes all other routes running on those lines or parts of them.
- for now, we start with routes that don't share track with long-distance trains, so there is much less guessing about
  the behavior of trains between stops.
- the shape of the time-position diagram shows which stations a train stops at.
- use green graphs for RB, RSB, and S-Bahn trains; red graphs for RE and IRE trains; black graphs for others.
- label each graph with the route_short_name.
- routes that branch on or off the line are drawn starting from the station they first or last stop on that line,
  labeled with an arrow and the route's terminal in that direction.
- while the route defines the scope in the space-dimension, the time-dimension will use the exact data of trips
  of this route and others that use the line.
- the first only needs to work for single- and double-tracked lines. We'll allow-list selectable routes manually at
  first. For now, the trips of the S-Bahn in Berlin and Hamburg are left out entirely: they run on tracks of their
  own beside the main lines.

First task: design the algorithm / ruleset to reconstruct the line data (sequence of all possible stops, at least all
that GTFS allows us to see) from the trip data given one route that defines the scope of this specific timetable.
This includes finding all the other relevant routes, including those that have only non-core trips running on that line.
(That is trips which end in other stations as the route core.)


## The old test page for showing the derived infrastructure data

NOTE: this section uses old terminology and is only left here as a description of the "lines.html" report.

Routes we use for testing, with the route_id of their core route:

- RE50 Erfurt – Meiningen: `2233941_106`
- RE21 Erfurt – Gera: `7149172_106`
- RE6a Hennigsdorf – Wittenberge: `de:VBB:12070424|R-Bahn|RE6:_106:a`
- RB33 Potsdam – Jüterbog: `4740564_106`
- RE2 Cottbus – Nauen: `de:VBB:11000000|R-Bahn|RE2:DB_106`

A test page lists the reconstructed line of each of them, and at each station the times of the timed trains and what
stops there: the core routes with a trip using the line there, and the routes where only other trips do. Below the
line it lists the warnings, with the strong ones set apart, then all these core routes, and then the additional
routes: those of which only other trips use the line. Both lists are sorted by name. Tabs switch between the agencies
of the testing routes.

The "timed trains" show how long real trains take along the line. Each is the "median trip" of some trips of one
route with the same stops in the same order: the fastest of them that at least half of their trip-days do not exceed
in travel time. A train is timed on its longest stretch of stops between which it uses the line and keeps one
direction. Its column is headed by its name and the time of day at which it leaves its first station of the stretch,
and gives the minutes after that at which it arrives at each station, and also those at which it leaves again if it
stands there for two minutes or more.

- The first timed train is that of the core route, in the direction in which the line is listed.
- If it leaves stations untimed, the next one is the train with the most stops on the line that stops at one of them.
- Up to two more follow as long as stations are untimed: each time the train that stops at the most of them.
- Trains of core routes are picked first, because their trips are the typical ones: another train is only picked if
  no train of a core route stops at an untimed station.
- Among the trains of core routes, and among the others, a train running against the listed direction is only picked
  if no train in the listed direction stops at an untimed station.
- Among equal trains, the one with the smaller route name comes first.

Relevant routes of one line that share their route_short_name get the suffixes `-1`, `-2`, … on it, in the order of
their route_id. The suffix of a core route follows it: `S8-2a`.

Things to be designed:

- does it make sense to make a timetable for an entire day, so the user can scroll through the time axis? This would
  spare us the work of deciding which time to pick as a representative!
- it still leaves us the work of picking a representative day... unless we prepare the data in a way that the user can
  pick. But the date picker should show at least a color code for how many trips there are on the line compared to the
  median over all days. 
