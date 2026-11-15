# Simplified infrastructure data, especially (but not only for direct lines)

Most line data in the application is inferred from the routes that trains take: if there is a train route, there must 
be a line it runs on. The algorithm assumes that a train that stops in A, then B, uses the same physical line as a 
train that stops in A, then O, P, Q, ... and then B. Only in rare cases, are there actually two lines from A to B with 
different intermediate stops. Usually they can be told apart because different routes have completely disjoint 
intermediate sets of stops and a different running time as well. To be on the safe side, our inference algorithm writes 
those cases as an output file, so they can be checked against a railway network map.

One thing that we cannot infer easily, however, is when two stations are connected both via a line with intermediate 
stops and another one without intermediate stops. One cannot tell with certainty that some trains take the direct line, 
since they could just be running over the other one, just skipping all stops.

So we introduce a model of "direct lines" and it also includes a simplified form of junctions that we treat like 
stations: any train from any direction can continue in any other direction. In practice, this is usually not possibly 
(there's rather one line branching in two others which themselves have no through-connection), but since we only work 
with given train paths that all work on the real network, none of those is going to  


