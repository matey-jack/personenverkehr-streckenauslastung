# Frontend spec

The pages of the frontend. The aim, the architecture and the terms used here are in [../spec.md](../spec.md).

# Graphical timetable

Graphical timetable for lines between two major stations. Those will include all passenger trains which pass there, even
if they don't stop on any station on the line. (Although we'll add the latter only in a later version.)

Standard German timetable pattern: stations on the horizontal axis; time on the vertical passing from top down.
On double-tracked lines we only show trains in one direction (buttons to select the direction), whereas for
single-tracked lines we show trains in both directions, because the train meets of both directions are crucial here.

In the visualizations critical points are marked: places where the train interval (Zugfolgezeit) is smallest. A summary
beneath the visualization lists all the critical points below a given threshold and thus gives an impression of the line
capacity. (A lot of short critical points means the line is used over capacity, because a delay of one train would
affect one or more other trains.)

What the timetable of a line contains is in [../spec.md](../spec.md), "Data Model for the Graphical Timetable".

# Visualizing the long-distance network

TODO: this section might not be up to date, but has some useful notes. When in doubt,
[../pipeline/spec.md](../pipeline/spec.md) or [../todo.md](../todo.md) 
supersede this.

Everything here is clipped at the border with only a select few foreign stations to be included: Basel Bad, Basel SBB,
Salzburg. All other eligible long-distance trains are capped at the border with an arrow and labeled there with the
final destination without showing that on the map. (And there will later be no operational graph timetables on the
foreign sections.)

Most parts of the rail network observe the rule "all direct trains between two stops A and B take the same path, only
with different stopping patterns". And for many of the remaining cases, the two alternative paths can be disambiguated by
mentioning an intermediate station on one of the possible alternative paths. (If the trip doesn't stop there, mention
the station in parentheses or explicitly "non-stop via X".) There are only very few direct high-speed lines with no
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
