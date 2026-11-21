# Todo

Increments to implement, test and review one at a time.

BEFORE CODING ANYTHING:
 - a lot of obsolete things from the spec need to be removed
 - then the rest needs to adapt to the new terminology

Maybe create a visual glossary of all the terms, maybe as a simple Mermaid Flowchart. But only after cleaning the doc. 
Make sure to include all terms, even those defined outside the "definitions" section. 

## Reconstructing lines, regional part

Validate this the following way:
 - given a set of hub stations, get all regional train trips stopping at any of those stations and derive the 
   part-network from that. (Optionally exclude any trip parts that go beyond the ends of core routes.)
 - visualize that network as SVG using the station-coordinates
 - the visualization should contain all junctions and terminals as labeled nodes.
 - optionally lines are just straight SVG lines or they can be shown as line segments between all the stops.

On the side of this, show a list of all core routes and when clicking on one of them, highlight it in the graph.
Also, when hovering over a line, show a tool tip with all the core routes passing there, and below a separator all the 
other routes with at least one trip passing there.  

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
