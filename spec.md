The parts of the project have specs of their own: [pipeline/spec.md](pipeline/spec.md) and
[web/spec.md](web/spec.md). This one has what concerns both: the aim, the architecture and the terms.

Aim of the project: estimate the operational timetables of railway lines and stations from the published timetable.
Then use this data to visualize the timetable and estimate capacity usage.
Caveat: most lines also carry freight trains which cannot be estimated from the published timetables.
But the operational timetables might show gaps that hint at scheduled paths for freight trains.

For the purpose of this app, the difference between the published and operational timetable is that the operational one
has the passing times of all trains, even the ones that don't stop in a station. Estimating those needs two steps:

- knowing which path a train takes. We build a simplified data model of the rail network to do this.
- estimating the passing times at the intermediate stations using a simplified physical model of trains: first using
  acceleration/deceleration and max speeds of stopping trains to estimate distances between stops; then using the
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

These programs are the steps of the pipeline (`pipeline/`). Their visualizations for verification are HTML reports;
the visualizations for exploring the results are the pages of the frontend (`web/`), which reads the data files the
pipeline writes.

# Data Model and Term Definitions

TODO: this partially supersedes information in later level 1 sections and those need to be adapted.

## Infrastructure terms

- A **station** is a node in the network where at least one trip stops.
- A **line** is a physical connection between two junctions or terminals with optionally some stops in between.
- A **line section** is the part of a line atomically connecting two stations with no intermediate stations.
- A **junction** is a node in the network that connects to at least three line sections.
- A **terminal** is a node that connects only to one line section.
- A **station** is a **junction station** when it is also a **junction**.
- A station that is neither a terminal nor a junction is called a **stopping station** or, if there is no confusion
  with the homonymous timetable term, simply a **stop**.

Note that the definitions of line section and junction are circular, which is why it is non-trivial to reconstruct the
infrastructure data from the GTFS trips. Also note that if two lines would connect in a single station, that station 
would not be a junction, but a stopping station, and the two lines would be just one.

(Our internal model of lines differs from the numbering of railway lines in that our lines always end at junctions or
junction stations. Whereas official railway line numbers usually have the main line keeping its number on both sides
of the junction and only the branch line having a different number. We are approximating this instead by basing
operational timetables on routes, not lines.)

## Timetable terms

Route and trip are defined as in GTFS:

- A **trip** is a sequence of stations with times. It represents a train running once per day for a number of days
  specified by its calendar properties.
- A **trip day** is one day on which a trip runs according to its calendar. Counting trip days weights each trip by
  the number of days it runs.
- A **stop** is the fact that the trip stops at a station.
- A **path** is the sequence of line sections a trip is passing between its end points, or equivalently the sequence
  of stations. The sequence of junctions alone is not always enough, because there can be two different lines between
  the same two junctions.
- A **route** is a collection of related trips. Ideally a route would have all trips with the same stopping part, set
  apart by an hourly rhythm or an integer fraction of an hour (30 mins, 20 mins, 15 min, 10 mins, etc). In practice,
  routes contain many odd trips, especially in the early or late hours of the day when trips are cut short. This
  includes serving only part of a route, prolonging the route, or serving more or fewer intermediate stops.

In order to visualize routes and use them as part of timetables, we define the term **core part of a route** or **route
core** to be the set of all trips between the most frequent pair of end points of all trips, counted in trip days.
The intermediate stops of those trips as well as the travel times might be different, so the route core only carries
statistics on them: min, median, and max for each, counted in trip days.

Other pairs of end points of a route whose trips have at least 60% of the trip days of the route core are **route core
candidates**. Everywhere a route core is used, its candidates are used as well, as route cores of their own. The route
core is named "a" and its candidates "b", "c", and so on, in the order of their trip days (or "-1", "-2", … when the
route's name already ends with a letter). If there are no such candidates, the route core will not have any suffix.

The **typical stop sequence** of a route core is the most frequent sequence of stops between its two ends, counted in
trip days over all trips of its route that stop at both ends, each cut down to the part between them. So it counts
longer trips as well, but not shorter ones. The stations of a route core are those of its typical stop sequence.

While a trip has a direction, we consider routes to be directionless. We normalize all route cores simply by using the
alphabetically earlier station name as the start and the other as the end. (When displaying them to the user we might
normalize in a different way, for example putting the current station first, when showing all routes starting or ending
at specific station. In user-facing communication we also sometimes just use "route" when displaying the route core
information.)

# Data Model for the Graphical Timetable

(Für die deutschen Leser: 'line' ist eine Strecke, nicht eine Linie. Letztere heißen 'route' im GTFS.)

The line is the actual physical infrastructure which has all the stations where trains can stop, whether a trip does
stop there or not. Our timetables are line-based and since the GTFS dataset doesn't give us any line information, we'll
derive that from the trip data.

Graphical Timetable:

- the scope is one route between the end stations of its route core.
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
(That is, trips which end at other stations than the route core.)
