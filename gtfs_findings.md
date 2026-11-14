# Findings about the GTFS data

What the Delfi data set of 2026-11-09 turned out to contain, where that differs from what one would expect. Numbers refer to the filtered rail data in `data/rail`.

## A GTFS route is not a line

- The same line is often split over several routes: 197 combinations of route_short_name and route_type occur on more than one route_id. Berlin's S1 has four route_ids, one per variant of the line.
- One route can bundle unrelated sections. RS3 contains trips Achern–Ottenhöfen as well as trips around Ulm; RB45 contains Limburg–Gießen and Gießen–Fulda.
- A short branch section can be the most frequent trip of a route whose other trips run elsewhere: Seebrugg–Titisee on Freiburg's S1, probably the portion of trains that split at Titisee, published as trips of its own.

## Long-distance routes barely have a typical trip

Routes of type 101 and 102 have up to about 200 different station patterns each, and the most frequent one often covers only 5 to 15% of the trip-days. A core route says much less about such a route than about a regional one.

## Route type 100 contains bus-like routes

Some routes of type 100 are served at street stops, for example line 20 in Regensburg and line 430 in Ludwigsburg.

## Leaving out rarely-run trips changes little

Once frequencies are counted in trip-days, ignoring trips that run on only a few days hardly changes which pattern is the most frequent or the closest to the majority stations: the most frequent pattern equals the majority stations on 76% of the routes without a minimum and on 81% with a minimum of 20 days. The minimum removes whole routes, though: 134 of 1,129 at 20 days. `./gradlew coreRouteCheck -PminDays=…` shows the effect.
