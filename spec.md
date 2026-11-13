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

# Visualizations

Graphical timetable for lines between two major stations. Those will include all passenger trains which pass there, even
if they don't stop on any station on the line.

Standard German timetable pattern: stations on the horizontal axis; time on the vertical passing from top down.
On double-tracked lines we only show trains in one direction (buttons to select the direction), whereas for
single-tracked lines we show trains in both directions, because the train meets of both directions are crucial here.

In the visualizations critical points are marked: places where the train interval (Zugfolgezeit) is smallest. A summary
beneath the visualization lists all the critical points below a given threshold and thus gives an impression of the line
capacity. (A lot of short critical points means the line is used over capacity, because a delay of one train would
affect one or more other trains.)

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

# Extracting meaningful "typical" routes and trips

## "Core Routes"

A route often has a few trips that are prolonged on either or both ends, often during early or late hours of the day.
We define a "core route" to mean the sequence of stations that usually is the most frequent trip on that route and that
contains stations that are also contained in most other trips on the route.

## Ideas on meaningfully grouping the Core Routes

For each station, collect all core routes that end or start there. 
If a station has more than one core route added that way, call the station a "hub station".

Then we can create an overview page which lists hub stations by number of associated core routes.
With each hub station we list those core routes, and below those also list core routes merely stopping at that station.
The core route is listed by route short name (maybe type and route long name), start and end station.
As a navigation aid, the station names in routes should be clickable.

And below all the hub stations we list all "isolated core routes" – those which do not start, intermediate stop, or end 
at any hub station.

# Open questions

- understand data model for calendar dates, especially: regular trips vs one-offs.




