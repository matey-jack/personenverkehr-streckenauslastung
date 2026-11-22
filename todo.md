# Todo

Increments to implement, test and review one at a time.

Maybe create a visual glossary of all the terms, maybe as a simple Mermaid Flowchart. But only after cleaning the doc. 
Make sure to include all terms, even those defined outside the "definitions" section. 

## preliminaries

- update the concept of route core in the code, and also track "route core candidates", which are start/end pairs that 
  have a trip day count of at least 60% of the winning route core. If there are such candidates, name the original 
  route as "a" and the candidates as "b", "c", and so on, similar to the current implementation that only has "a" and 
  "b".

- fix lines.html minimally, just so it keeps working with the new definition. (Bare minimum is that it still compiles.)

- extend hub_stations.html so that it shows min/median/max intermediate stops and travel time for each core 
  route (and candidate) instead of just one value for each. 
  + The tooltip on the route should show the most frequent stop sequence (by trip days) of all trips that 
    include both ends of the core route. (Which means including longer trips, but not shorter ones, in this count.)

## Reconstructing lines, regional part

Validate this the following way:
 - derive the entire network and store it as Json
 - then create an interactive network viewer where the user selects a set of hub stations and the app shows the network 
   for all (the core parts of) the routes that touch those stations.
 - visualize that network as SVG using the station-coordinates
 - the visualization should contain all junctions and terminals as labeled nodes.
 - optionally lines are just straight SVG lines or they can be shown as line segments between all the stops.
 - On the side of this, show a list of all routes and when clicking on one of them, highlight it in the graph.
 - Also, when hovering over a line, show a tool tip with all the core routes passing there, and below a separator all 
   the other routes with at least one trip passing there.  

## Reconstructing lines, long-distance part

similar; to be described.

## Adding junctions that are not stations

I think that the entire DirectLines model needs to be rethought, because the line reconstruction mechanism solves a 
large part of the problem already. And what remains needs another solution: add junctions that are not stations.

On long-distance lines this is more pressing, because the distances and thus errors are so much bigger. On local lines 
with only a few km between stations, junctions will only be displaced by that much, because the derivation algorithm 
always assumes the station to be a junction.

In any case, we'll build everything above in a way that it gives as good results as possible, even with the missing 
junctions.


## Later

- [ ] Decide what to do about trains on separate tracks other than the S-Bahn of Berlin and Hamburg: the other S-Bahn networks still fill lines.
- Path finding for trains (which stations are passed without stopping).
- Train-physics model and fitting of acceleration, braking, maximum speed and buffer times.
- Input file formats for path hints, train parameters and exact line data.
- Graphical timetable visualization and critical-point (Zugfolgezeit) summary.
