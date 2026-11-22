# Findings about the GTFS data

What the Delfi data set of 2026-11-09 turned out to contain, where that differs from what one would expect. Numbers refer to the filtered rail data in `data/rail`.

## A GTFS route is not a "Linie" (the line as communicated to passengers)

- The same Linie is often split over several routes: 197 combinations of route_short_name and route_type occur on more than one route_id. Berlin's S1 has four route_ids, one per variant of the Linie.
- One route can bundle unrelated sections. RS3 contains trips Achern–Ottenhöfen as well as trips around Ulm; RB45 contains Limburg–Gießen and Gießen–Fulda.
- A short branch section can be the most frequent trip of a route whose other trips run elsewhere: Seebrugg–Titisee on Freiburg's S1, probably the portion of trains that split at Titisee, published as trips of its own.

## A station can have several station IDs

- Large stations have a separate ID for their underground platforms, where other lines call than above ground: "Hauptbahnhof (tief)" in Stuttgart, "Hauptbahnhof (U, Tram)" in München, "Frankfurt (Main) Hauptbahnhof tief". Other stations simply appear under two IDs, with the same name (Leipzig Hbf, Rostock Hbf) or a different one ("Altona" and "Hamburg-Altona"). `stationParts` in `TripGroups.kt` joins these.
- Tram-train stops in front of a main station are stations of their own: "KA Hauptbahnhof (Vorplatz)", "Heilbronn Hbf/Willy-Brandt-Pl.", "Chemnitz, Hbf Bahnhofstr". So are "Frankfurt (Main) Flughafen Fernbahnhof" and "Regionalbahnhof". These stay separate.
- About 55 Czech stations appear once with a numeric ID and once with a `cz:` ID, for example "Decin hl.n." and "Liberec". These stay separate as well.

## Long-distance stations

What `./gradlew longDistanceStations` shows:

- Long-distance and regional trips often stop at different stop IDs of a station, but these share the station part of the ID. So once stops are mapped to stations, regional routes stop at each of the 50 stations with the most long-distance trip days.
- Among the top 150, the stations without regional routes are foreign ones, Limburg (Lahn) Süd, which only long-distance trains serve, and "Flensburg [Grenze]".
- Border points are stations in the data: "Flensburg [Grenze]", "Schöna(Gr)", "Emmerich(Gr)", "Frankfurt(Oder), Grenze".

## Station names

- A few stations are named without their town: Stuttgart's main station is just "Hauptbahnhof", its underground part "Hauptbahnhof (tief)", München's "Hauptbahnhof (U, Tram)", "Ostbahnhof" and "Pasing", and the station of Brunnen (Oberbayern) just "Bahnhof".
- Names start with "S " or "S+U " where S-Bahn or U-Bahn trains call, mostly in Berlin: "S+U Berlin Hauptbahnhof".
- Main stations are spelled in four ways: "Kassel Hauptbahnhof", "Cottbus, Hauptbahnhof", "Hamburg Hbf", "Eschweiler, Hbf".

## Route names

- Between Erfurt and Gera two routes are called RB21: one of Erfurter Bahn that stops everywhere, and one of Erfurter Bahn Express that stops like a regional express. `routeNameFixes` in `TripGroups.kt` names the express one RE21.

## Long-distance routes barely have a typical trip

Routes of type 101 and 102 have up to about 200 different station patterns each, and the most frequent one often covers only 5 to 15% of the trip days. A route core says much less about such a route than about a regional one.

## Route type 100 contains bus-like routes

Some routes of type 100 are served at street stops, for example line 20 in Regensburg and line 430 in Ludwigsburg.

## Irregular day patterns

GTFS gives the days a trip runs through its service: a weekly pattern between a start and an end date (`calendar`),
plus single dates that are added or removed (`calendar_dates`).

In the Delfi data set of 2026-11-09 every service covers the same seven weeks, 2026-10-24 to 2026-12-12. Of its 9,422
rail services, 3,505 have no weekly pattern at all and run only on the dates added for them. Such services are
one-offs and construction timetables, but also regular trips that Delfi publishes as a list of dates.

So the number of trips says little about how typical a kind of trip is; the number of days they run does (see
"trip day" under "Timetable terms" in `spec.md`).

## Leaving out rarely-run trips changes little

Once frequencies are counted in trip days, ignoring trips that run on only a few days hardly changes which pattern is the most frequent or the closest to the majority stations: the most frequent pattern equals the majority stations on 76% of the routes without a minimum and on 81% with a minimum of 20 days. The minimum removes whole routes, though: 134 of 1,129 at 20 days.

## Reconstructing lines

What the five testing routes of `spec.md`, "The old test page for showing the derived infrastructure data", show about its rules:

- Trips that take another way between two stations of a line are common around junctions. Without the rule for detours, the RB33 line gets Werder (Havel), Potsdam Golm and Potsdam Park Sanssouci between Pirschheide and Charlottenhof, and the RE2 line gets Flughafen BER and Lichtenberg. The numbers of the rule matter little: with a factor of 1.2 and 2 minutes per stop the lines are the same.
- Trains stand before a terminus: RB33 takes much longer over its last hop into Potsdam Hbf than the trains passing through. That is why the rule for detours allows the 3 minutes for the stop at the end of a hop as well.
- Some trip always stops at both of two neighbouring stations, so no pair is ordered by travel time only, and no trip goes against the order of a line.
- The S-Bahn of Berlin would add 18 stations to the RE2 line between Königs Wusterhausen and Jungfernheide, among them those of the Ringbahn.
- A station where no route core stops is often served only at the edge of the day: the only train stopping at every station of the RE2 line leaves Cottbus at 03:42.
