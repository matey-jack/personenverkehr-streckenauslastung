package infra

// Define canonical names so the data below can be validated.
// Later we can add metadata like geo coordinates.
val junctions = listOf("Bruchsal Rollenberg", "Waghäusel Saalbach", "Rohrbach")

data class DirectLine(
    val a: String,
    val b: String,
)

// Station names are not directly from the GTFS 'stops' table, but the 'station_of' table created in TripGroups.
// They are persisted in data/reports/stations.csv for easier lookup.
val directLines = listOf(
    /* Hannover–Würzburg line */
    DirectLine("Hannover Hbf", "Sorsum"),
    DirectLine("Hildesheim Hbf", "Sorsum"),
    DirectLine("Sorsum", "Göttingen"),

    DirectLine("Göttingen", "Kassel Bahnhof Wilhelmshöhe"),
    DirectLine("Kassel Bahnhof Wilhelmshöhe", "Fulda"),
    DirectLine("Fulda", "Rohrbach"),
    // Nantenbacher Kurve
    DirectLine("Lohr Bahnhof", "Rohrbach"),
    DirectLine("Rohrbach", "Würzburg"),


    /* Mannheim–Stuttgart line */
    DirectLine("Mannheim Hbf", "Waghäusel Saalbach"),

    // The Karlruhe Branch
    DirectLine("Waghäusel Saalbach", "Graben-Neudorf Bahnhof"),
    // NBS towards Stuttgart
    DirectLine("Waghäusel Saalbach", "Bruchsal Rollenberg"),

    // Heidelberg–Stuttgart
    DirectLine("Ubstadt-Weiher", "Bruchsal Rollenberg"),
    // Karlsruhe–Stuttgart
    DirectLine("Bruchsal", "Bruchsal Rollenberg"),

    // The Stuttgart Branch
    DirectLine("Bruchsal Rollenberg", "Vaihingen (E)"),
    DirectLine("Vaihingen (E)", "Zuffenhausen"),

    /* Berlin–Hannover line */
    /* not in the current timetable due to construction works
        // up to Rathenow, regional trains use the HSL, so it's in the model already.
        DirectLine("Rathenow"),
        // TODO: junctions East and West of Stendal
    */

    /* Köln–Frankfurt line */

    /* Halle/Leipzig–Erfurt */

    /* Erfurt–Nürnberg */
    // TODO: North and South of Coburg junctions.
    // There is a junction north of Bamberg, but it's not for passenger trains, I think.
    DirectLine("Erfurt Hbf", "Bamberg")
    // the 4-track section from Bamberg to Nürnberg is too complicated for our model.
)
