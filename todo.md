# Todo

Increments to implement, test and review one at a time.

Maybe create a visual glossary of all the terms, maybe as a simple Mermaid flowchart. But only after cleaning the doc.
Make sure to include all terms, even those defined outside the "definitions" section.

## Reconstructing lines, regional part

Pragmatic definitions: 
 - Regional routes are route_type 106 and 109, except the S-Bahn Berlin and Hamburg, because those have so many stops 
   they crowd out the graph. (Also they mostly have their own tracks, thus don't interfere with other rail services.) 
 - Stopping sequences that occur only in a single trip are to be included as valuable (usually off-hours) input data.
 - But trips that occur only on less than 10 (configurable) specific dates (instead of a weekly pattern) should be 
   excluded to remove extreme cases and clutter.

Validate this the following way:

- derive the entire network (considering regional trains only) and store it as Json
- then create an interactive network viewer where the user selects a set of hub stations and the app shows the network
  for all (the core parts of) the regional routes that touch those stations.
- visualize that network as SVG using the station-coordinates
  + (the SVG content needs to be recentered whenever new stations enter or leave it)
- the visualization should contain all junctions and terminals as labeled nodes.
- route core ends should not be labeled stations; this information can be seen from the graph, because the route path 
  ends at a point between junctions and the route core name includes the name of the end stations.
- optionally lines are just straight SVG lines or they can be shown as line segments between all the stops.
- On the side of this, show a list of all routes and when clicking on one of them, highlight it in the graph.
- Also, when hovering over a line, show a tooltip with all the route cores passing there, and below a separator all
  the other routes with at least one trip passing there.

## Reconstructing lines, long-distance part

similar; to be described.

## Adding junctions that are not stations

In the GTFS-derived data, lines always branch of at stations, which is an acceptable approximation for regional lines
where the distance from the station to the actual physical junction is not large. On long-distance lines this is more
pressing, because the distances and thus errors are so much bigger. On local lines with only a few km between stations,
junctions will only be displaced by that much, because the derivation algorithm always assumes the station to be a
junction.

Thus, we'll later define another manual input format for defining the positions of such junctions. But for now, we'll
build everything above in a way that it gives as good results as possible, even with the missing
junctions.

## Later

- [ ] Decide what to do about trains on separate tracks other than the S-Bahn of Berlin and Hamburg: the other S-Bahn
  networks still fill lines.
- Path finding for trains (which stations are passed without stopping).
- Train-physics model and fitting of acceleration, braking, maximum speed and buffer times.
- Input file formats for path hints, train parameters and exact line data.
- Graphical timetable visualization and critical-point (Zugfolgezeit) summary.
