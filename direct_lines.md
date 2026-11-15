# Simplified infrastructure data, especially (but not only for direct lines)

Most line data in the application is inferred from the routes that trains take: if there is a train, there most be a 
route 







# What follows is the old model, just FYI, but this is 

A direct line is a railway line that is entered by hand in `data/manual/direct_lines.yaml`, because the GTFS data cannot
show where it runs: long-distance trains pass it without stopping, and it has junctions that are no stations. High-speed
lines are the typical case.

This page describes the model, the file, and how to fill the file from public sources such as Wikipedia.

## Model

- A **node** is a station or a junction.
    - A station is one of the GTFS data, given by its station ID.
    - A junction is a place without a station where lines meet. It has a name and a position.
- A **segment** is the track between two neighbouring nodes, with its length in km. There is no station between its two
  nodes. A segment is entered once, even if several direct lines run over it.
- A **direct line** is a list of nodes in the order a train passes them. Each two neighbours in the list are the ends of
  a segment. Trains run on it in both directions. It starts and ends at a station.
- The **junction rule**: a train may run from X through a junction on to Y only if some direct line has X, the junction
  and Y in a row. So a spur that leaves a main line at a junction is a direct line of its own, which shares a segment
  with the main line.

At a station all movements are possible, so the rule is about junctions only.

Example: the high-speed line from Erfurt splits at the junction Halle-Planena into a branch to Halle and one towards
Leipzig. One direct line has Erfurt Hbf, Halle-Planena, Gröbers; another has Erfurt Hbf, Halle-Planena, Halle (Saale)
Hbf. No direct line has Halle (Saale) Hbf, Halle-Planena, Gröbers in a row, so no train runs from Halle through
Halle-Planena towards Leipzig.

## File

This example is shortened: the real line has more nodes between Halle-Planena and Leipzig Hbf.

```yaml
stations:
  Erfurt Hbf: de:16051:8010101
  Halle (Saale) Hbf: de:15002:8010159
  Leipzig Hbf: de:14713:8010205

junctions:
  Halle-Planena: { lat: 51.409923, lon: 11.962941 }

segments:
  - { from: Erfurt Hbf, to: Halle-Planena, km: 83.306 }
  - { from: Halle-Planena, to: Halle (Saale) Hbf, km: 8.9 }
  - { from: Halle-Planena, to: Leipzig Hbf, km: 37.5 }

direct_lines:
  - name: NBS Erfurt–Leipzig
    nodes: [ Erfurt Hbf, Halle-Planena, Leipzig Hbf ]
  - nodes: [ Erfurt Hbf, Halle-Planena, Halle (Saale) Hbf ]
```

- `stations` gives each station a name for use in this file and its station ID. The name is free: it need not be the
  name in the data.
- `junctions` gives each junction its name and its position in degrees.
- `segments` lists the segments. `from` and `to` may be in either order.
- `direct_lines` lists the direct lines. `name` is optional; a line without one is shown by its two ends.

A comment says where a number comes from: the line number and the km points it was calculated from.

## Filling the file

### 1. Find the line

The German Wikipedia article of a line has a route diagram ("Streckenband") in its infobox, with the km point of every
station and junction. The wikitext is easier to read than the page:
`https://de.wikipedia.org/w/index.php?title=<title>&action=raw`. Each row of the diagram is a line starting with `{{BS`:

| Icon in the row                   | Meaning                                                         | Use                                      |
|-----------------------------------|-----------------------------------------------------------------|------------------------------------------|
| `BHF`, `HST`, `SBHF`, `SHST`      | station or halt with passenger trains                           | a station node                           |
| `ABZ…`                            | a line branches off or joins; the text says "nach …" or "von …" | a junction, if trains use the connection |
| `BST` with "(Abzw)"               | junction with a name of its own                                 | a junction                               |
| `DST`, `ÜST`, `BST` otherwise     | places for operations only                                      | leave out                                |
| `KRZ…`, `TUNNEL`, `BRÜCKE`, `STR` | crossings, tunnels, bridges                                     | leave out                                |

Take the line number ("DE-STRECKENNR") and the km points from the diagram.

### 2. Choose the nodes

- Every station of the diagram where passenger trains stop is a node, also if only regional or S-Bahn trains stop there.
- A junction is a node if scheduled passenger trains use the connection. Connections for freight or for operations only
  are left out.
- A junction right at a station needs no node: the station stands for it.

### 3. Find out which way each junction can be passed

The diagram shows it: the text of an `ABZ` row says "nach X" where the line branches off in the direction of the
diagram, and "von X" where it joins. The article often describes the connecting curves in its text, and the articles of
the connecting curve or of the junction say which trains use them.

Each way through a junction that trains use becomes a direct line with at least the node before the junction, the
junction, and the node after it.

### 4. End every spur at a station

A connecting curve usually ends at a junction of another line, between two stations of that line. Continue the spur
along that line to the next station, in the direction the trains run, and leave that junction out. The article of the
other line has the km points for this.

Example: the curve from Bruchsal Rollenberg towards Heidelberg is 2.8 km long and ends at the junction Ubstadt-Weiher
(Abzw) of the Rheintalbahn at km 46.600. The next station towards Heidelberg is Ubstadt-Weiher at km 46.291. So the
segment runs from Bruchsal Rollenberg to Ubstadt-Weiher and is 3.1 km long.

### 5. Calculate the lengths

The length of a segment is the difference of the km points of its nodes. Check these cases:

- A jump in the km count ("Kilometrierungssprung", icon `KMW`) between the two nodes: the row gives the km points before
  and after it.
- A segment over two lines: add the parts, each from its own km count.
- A length that the article only gives in its text, like "8,9 Kilometer lange Verbindung".

Give lengths with the precision of the source.

### 6. Find the station IDs

`data/reports/stations.csv` lists the ID and name of every station of the data, sorted by name. `./gradlew directLines`
writes it.

- Names in the data differ from the usual ones: "Vaihingen (E)", "Hockenheim, Bahnhof", or just "Nordbahnhof" for
  Stuttgart Nord. Search for a part of the name.
- If several stations have the name, the first five digits after `de:` tell them apart: they are the key of the
  district.

### 7. Find the positions of the junctions

The route diagram or the article of the junction often has a `{{Coordinate|NS=…|EW=…}}`. Otherwise take the position
from a map. Say so in a comment if a position is only rough.

### 8. Check the file

`./gradlew directLines` reads the file and fails with a list of what is wrong:

- a name in a segment or a line that is neither a station nor a junction, or is both;
- a station ID that is not in the data, or has two names in the file;
- a segment that is listed twice, has the same node at both ends, or has no positive length;
- two neighbours on a line without a segment between them;
- a line that has a node twice, or ends at a junction;
- a segment, station or junction that is on no line;
- a key that the file format does not have.

If the file is right, the step prints each direct line with its length, and the stations whose name in the file differs
from the name in the data. Compare the lengths with those in the articles, and the names to see that each ID is the
station that was meant.
