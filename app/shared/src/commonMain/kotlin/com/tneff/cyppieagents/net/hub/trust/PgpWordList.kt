package com.tneff.cyppieagents.net.hub.trust

/**
 * CYP-482 — the canonical **PGP Word List** (Juola & Zimmermann 1995 "biometric word list"): two 256-word
 * lists, [EVEN] (two-syllable) and [ODD] (three-syllable), used **alternately by byte position** so that a
 * transposition / duplication / omission during a spoken OOB fingerprint compare is caught (a swapped pair
 * lands a word in the wrong list). Each word maps to a byte value 0..255 in canonical (alphabetical) order;
 * purpose-built for verbal fingerprint comparison (phonetically optimised via a genetic algorithm).
 *
 * **Non-localized security artifact:** the words MUST render **identically** on the app AND the hub console
 * (a DE/EN split would silently break the positional OOB compare) — only the *label* is localized, never the
 * words. **Vendored, NOT hand-transcribed:** cross-verified byte-for-byte across two independent
 * implementations — messerli-informatik-ag/pgp-word-list (C#) and thblt/pgp-words (Python), which agree; a
 * third disclaimed copy (a gist) was the OUTLIER (13 wrong EVEN entries, e.g. basalt/dosage/Glasgow) and was
 * REJECTED. Integrity is pinned by [PGP_LIST_SHA256] (a regression tooth recomputes it over the embedded lists).
 */
object PgpWordList {

    /** Byte value 0..255 -> the EVEN-position (two-syllable) word. */
    val EVEN: List<String> = listOf(
        "aardvark", "absurd", "accrue", "acme", "adrift", "adult", "afflict", "ahead",
        "aimless", "Algol", "allow", "alone", "ammo", "ancient", "apple", "artist",
        "assume", "Athens", "atlas", "Aztec", "baboon", "backfield", "backward", "banjo",
        "beaming", "bedlamp", "beehive", "beeswax", "befriend", "Belfast", "berserk", "billiard",
        "bison", "blackjack", "blockade", "blowtorch", "bluebird", "bombast", "bookshelf", "brackish",
        "breadline", "breakup", "brickyard", "briefcase", "Burbank", "button", "buzzard", "cement",
        "chairlift", "chatter", "checkup", "chisel", "choking", "chopper", "Christmas", "clamshell",
        "classic", "classroom", "cleanup", "clockwork", "cobra", "commence", "concert", "cowbell",
        "crackdown", "cranky", "crowfoot", "crucial", "crumpled", "crusade", "cubic", "dashboard",
        "deadbolt", "deckhand", "dogsled", "dragnet", "drainage", "dreadful", "drifter", "dropper",
        "drumbeat", "drunken", "Dupont", "dwelling", "eating", "edict", "egghead", "eightball",
        "endorse", "endow", "enlist", "erase", "escape", "exceed", "eyeglass", "eyetooth",
        "facial", "fallout", "flagpole", "flatfoot", "flytrap", "fracture", "framework", "freedom",
        "frighten", "gazelle", "Geiger", "glitter", "glucose", "goggles", "goldfish", "gremlin",
        "guidance", "hamlet", "highchair", "hockey", "indoors", "indulge", "inverse", "involve",
        "island", "jawbone", "keyboard", "kickoff", "kiwi", "klaxon", "locale", "lockup",
        "merit", "minnow", "miser", "Mohawk", "mural", "music", "necklace", "Neptune",
        "newborn", "nightbird", "Oakland", "obtuse", "offload", "optic", "orca", "payday",
        "peachy", "pheasant", "physique", "playhouse", "Pluto", "preclude", "prefer", "preshrunk",
        "printer", "prowler", "pupil", "puppy", "python", "quadrant", "quiver", "quota",
        "ragtime", "ratchet", "rebirth", "reform", "regain", "reindeer", "rematch", "repay",
        "retouch", "revenge", "reward", "rhythm", "ribcage", "ringbolt", "robust", "rocker",
        "ruffled", "sailboat", "sawdust", "scallion", "scenic", "scorecard", "Scotland", "seabird",
        "select", "sentence", "shadow", "shamrock", "showgirl", "skullcap", "skydive", "slingshot",
        "slowdown", "snapline", "snapshot", "snowcap", "snowslide", "solo", "southward", "soybean",
        "spaniel", "spearhead", "spellbind", "spheroid", "spigot", "spindle", "spyglass", "stagehand",
        "stagnate", "stairway", "standard", "stapler", "steamship", "sterling", "stockman", "stopwatch",
        "stormy", "sugar", "surmount", "suspense", "sweatband", "swelter", "tactics", "talon",
        "tapeworm", "tempest", "tiger", "tissue", "tonic", "topmost", "tracker", "transit",
        "trauma", "treadmill", "Trojan", "trouble", "tumor", "tunnel", "tycoon", "uncut",
        "unearth", "unwind", "uproot", "upset", "upshot", "vapor", "village", "virus",
        "Vulcan", "waffle", "wallet", "watchword", "wayside", "willow", "woodlark", "Zulu",
    )

    /** Byte value 0..255 -> the ODD-position (three-syllable) word. */
    val ODD: List<String> = listOf(
        "adroitness", "adviser", "aftermath", "aggregate", "alkali", "almighty", "amulet", "amusement",
        "antenna", "applicant", "Apollo", "armistice", "article", "asteroid", "Atlantic", "atmosphere",
        "autopsy", "Babylon", "backwater", "barbecue", "belowground", "bifocals", "bodyguard", "bookseller",
        "borderline", "bottomless", "Bradbury", "bravado", "Brazilian", "breakaway", "Burlington", "businessman",
        "butterfat", "Camelot", "candidate", "cannonball", "Capricorn", "caravan", "caretaker", "celebrate",
        "cellulose", "certify", "chambermaid", "Cherokee", "Chicago", "clergyman", "coherence", "combustion",
        "commando", "company", "component", "concurrent", "confidence", "conformist", "congregate", "consensus",
        "consulting", "corporate", "corrosion", "councilman", "crossover", "crucifix", "cumbersome", "customer",
        "Dakota", "decadence", "December", "decimal", "designing", "detector", "detergent", "determine",
        "dictator", "dinosaur", "direction", "disable", "disbelief", "disruptive", "distortion", "document",
        "embezzle", "enchanting", "enrollment", "enterprise", "equation", "equipment", "escapade", "Eskimo",
        "everyday", "examine", "existence", "exodus", "fascinate", "filament", "finicky", "forever",
        "fortitude", "frequency", "gadgetry", "Galveston", "getaway", "glossary", "gossamer", "graduate",
        "gravity", "guitarist", "hamburger", "Hamilton", "handiwork", "hazardous", "headwaters", "hemisphere",
        "hesitate", "hideaway", "holiness", "hurricane", "hydraulic", "impartial", "impetus", "inception",
        "indigo", "inertia", "infancy", "inferno", "informant", "insincere", "insurgent", "integrate",
        "intention", "inventive", "Istanbul", "Jamaica", "Jupiter", "leprosy", "letterhead", "liberty",
        "maritime", "matchmaker", "maverick", "Medusa", "megaton", "microscope", "microwave", "midsummer",
        "millionaire", "miracle", "misnomer", "molasses", "molecule", "Montana", "monument", "mosquito",
        "narrative", "nebula", "newsletter", "Norwegian", "October", "Ohio", "onlooker", "opulent",
        "Orlando", "outfielder", "Pacific", "pandemic", "Pandora", "paperweight", "paragon", "paragraph",
        "paramount", "passenger", "pedigree", "Pegasus", "penetrate", "perceptive", "performance", "pharmacy",
        "phonetic", "photograph", "pioneer", "pocketful", "politeness", "positive", "potato", "processor",
        "provincial", "proximate", "puberty", "publisher", "pyramid", "quantity", "racketeer", "rebellion",
        "recipe", "recover", "repellent", "replica", "reproduce", "resistor", "responsive", "retraction",
        "retrieval", "retrospect", "revenue", "revival", "revolver", "sandalwood", "sardonic", "Saturday",
        "savagery", "scavenger", "sensation", "sociable", "souvenir", "specialist", "speculate", "stethoscope",
        "stupendous", "supportive", "surrender", "suspicious", "sympathy", "tambourine", "telephone", "therapist",
        "tobacco", "tolerance", "tomorrow", "torpedo", "tradition", "travesty", "trombonist", "truncated",
        "typewriter", "ultimate", "undaunted", "underfoot", "unicorn", "unify", "universe", "unravel",
        "upcoming", "vacancy", "vagabond", "vertigo", "Virginia", "visitor", "vocalist", "voyager",
        "warranty", "Waterloo", "whimsical", "Wichita", "Wilmington", "Wyoming", "yesteryear", "Yucatan",
    )

    /** SHA-256 of `EVEN.join("\n") + "\n" + ODD.join("\n")` — pins the vendored artifact against drift/typos. */
    const val PGP_LIST_SHA256: String = "e9b7b0052233a74aa56724ed3f79c272036aed68d8f4c0856e833c8043b9ac62"
}
