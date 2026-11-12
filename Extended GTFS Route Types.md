# Extended GTFS Route Types

GTFS defines a number of route types that developers can use to describe the type of service for
a particular route, such as bus versus rail versus ferry, with the `route_type` column
in the [`routes.txt` file](https://developers.google.com/transit/gtfs/reference/routes-file). To support a more rich set of
types, an extension has been proposed to let you specify route types using Hierarchical Vehicle
Type (HVT) codes from the European TPEG standard. For more information, see the
[original
discussion](http://groups.google.com/group/gtfs-changes/msg/ed917a69cf8c5bef).

The following table outlines the proposed route types. Note that Google Maps doesn't support all
route types. We specifically note the supported types in the table.

| Code | Description | Supported | Examples |
|---|---|---|---|
| **100** | **Railway Service** | Yes | Not applicable (N/A) |
| 101 | High Speed Rail Service | Yes | TGV (FR), ICE (DE), Eurostar (GB) |
| 102 | Long Distance Trains | Yes | InterCity/EuroCity |
| 103 | Inter Regional Rail Service | Yes | InterRegio (DE), Cross County Rail (GB) |
| 104 | Car Transport Rail Service | No | N/A |
| 105 | Sleeper Rail Service | Yes | GNER Sleeper (GB) |
| 106 | Regional Rail Service | Yes | TER (FR), Regionalzug (DE) |
| 107 | Tourist Railway Service | Yes | Romney, Hythe \& Dymchurch (GB) |
| 108 | Rail Shuttle (Within Complex) | Yes | Gatwick Shuttle (GB), Sky Line (DE) |
| 109 | Suburban Railway | Yes | S-Bahn (DE), RER (FR), S-tog (Kopenhagen) |
| 110 | Replacement Rail Service | No | N/A |
| 111 | Special Rail Service | No | N/A |
| 112 | Lorry Transport Rail Service | No | N/A |
| 113 | All Rail Services | No | N/A |
| 114 | Cross-Country Rail Service | No | N/A |
| 115 | Vehicle Transport Rail Service | No | N/A |
| 116 | Rack and Pinion Railway | No | Rochers de Naye (CH), Dolderbahn (CH) |
| 117 | Additional Rail Service | No | N/A |
| **200** | **Coach Service** | Yes | N/A |
| 201 | International Coach Service | Yes | EuroLine, Touring |
| 202 | National Coach Service | Yes | National Express (GB) |
| 203 | Shuttle Coach Service | No | Roissy Bus (FR), Reading-Heathrow (GB) |
| 204 | Regional Coach Service | Yes | N/A |
| 205 | Special Coach Service | No | N/A |
| 206 | Sightseeing Coach Service | No | N/A |
| 207 | Tourist Coach Service | No | N/A |
| 208 | Commuter Coach Service | No | N/A |
| 209 | All Coach Services | No | N/A |
| **400** | **Urban Railway Service** | Yes | N/A |
| 401 | Metro Service | Yes | Métro de Paris |
| 402 | Underground Service | Yes | London Underground, U-Bahn |
| 403 | Urban Railway Service | Yes | N/A |
| 404 | All Urban Railway Services | No | N/A |
| 405 | Monorail | Yes | N/A |
| **700** | **Bus Service** | Yes | N/A |
| 701 | Regional Bus Service | Yes | Eastbourne-Maidstone (GB) |
| 702 | Express Bus Service | Yes | X19 Wokingham-Heathrow (GB) |
| 703 | Stopping Bus Service | No | 38 London: Clapton Pond-Victoria (GB) |
| 704 | Local Bus Service | Yes | N/A |
| 705 | Night Bus Service | No | N prefixed buses in London (GB) |
| 706 | Post Bus Service | No | Maidstone P4 (GB) |
| 707 | Special Needs Bus | No | N/A |
| 708 | Mobility Bus Service | No | N/A |
| 709 | Mobility Bus for Registered Disabled | No | N/A |
| 710 | Sightseeing Bus | No | N/A |
| 711 | Shuttle Bus | No | 747 Heathrow-Gatwick Airport Service (GB) |
| 712 | School Bus | No | N/A |
| 713 | School and Public Service Bus | No | N/A |
| 714 | Rail Replacement Bus Service | No | N/A |
| 715 | Demand and Response Bus Service | Yes | N/A |
| 716 | All Bus Services | No | N/A |
| **800** | **Trolleybus Service** | Yes | N/A |
| **900** | **Tram Service** | Yes | N/A |
| 901 | City Tram Service | No | N/A |
| 902 | Local Tram Service | No | Munich (DE), Brussels (BE), Croydon (GB) |
| 903 | Regional Tram Service | No | N/A |
| 904 | Sightseeing Tram Service | No | Blackpool Seafront (GB) |
| 905 | Shuttle Tram Service | No | N/A |
| 906 | All Tram Services | No | N/A |
| **1000** | **Water Transport Service** | Yes | N/A |
| **1100** | **Air Service** | No | N/A |
| **1200** | **Ferry Service** | Yes | N/A |
| **1300** | **Aerial Lift Service** | Yes | Telefèric de Montjuïc (ES), Saleve (CH), Roosevelt Island Tramway (US) |
| 1301 | Telecabin Service | Yes | N/A |
| 1302 | Cable Car Service | No | N/A |
| 1303 | Elevator Service | No | N/A |
| 1304 | Chair Lift Service | No | N/A |
| 1305 | Drag Lift Service | No | N/A |
| 1306 | Small Telecabin Service | No | N/A |
| 1307 | All Telecabin Services | No | N/A |
| **1400** | **Funicular Service** | Yes | Rigiblick (Zürich, CH) |
| **1500** | **Taxi Service** | No | N/A |
| 1501 | Communal Taxi Service | Yes | Marshrutka (RU), dolmuş (TR) |
| 1502 | Water Taxi Service | No | N/A |
| 1503 | Rail Taxi Service | No | N/A |
| 1504 | Bike Taxi Service | No | N/A |
| 1505 | Licensed Taxi Service | No | N/A |
| 1506 | Private Hire Service Vehicle | No | N/A |
| 1507 | All Taxi Services | No | N/A |
| **1700** | **Miscellaneous Service** | Yes | N/A |
| 1702 | Horse-drawn Carriage | Yes | N/A |
