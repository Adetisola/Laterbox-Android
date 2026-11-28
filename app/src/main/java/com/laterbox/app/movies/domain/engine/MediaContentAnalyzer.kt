package com.laterbox.app.movies.domain.engine

import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import java.util.Collections
import java.util.Locale
import kotlin.math.min

enum class PacingIntensity {
    FAST_ADRENALINE,
    BALANCED,
    SLOW_BURN,
    LIGHT_BREEZY
}

enum class VisualFormat {
    ANIMATION,
    ANIME,
    LIVE_ACTION,
    DOCUMENTARY
}

enum class AudienceClass {
    KIDS_FAMILY,
    YOUNG_ADULT,
    GENERAL,
    MATURE
}

data class MediaContentProfile(
    val stableMediaKey: String,
    val kind: MediaKind,
    val normalizedGenres: Set<String>,
    val subgenres: Set<String>,
    val themes: Set<String>,
    val tones: Set<String>,
    val narrativeStructures: Set<String>,
    val characterDynamics: Set<String>,
    val settings: Set<String>,
    val pacingIntensity: PacingIntensity?,
    val visualFormat: VisualFormat,
    val audienceClass: AudienceClass,
    val regionCluster: String?,
    val languageCode: String?,
    val franchiseStem: String?,
    val synopsisTokens: Set<String>,
    val metadataQuality: Double
)

data class ContentSimilarityResult(
    val score: Double,
    val hasMeaningfulSignal: Boolean,
    val sharedThemes: Set<String>,
    val sharedSubgenres: Set<String>,
    val sharedTones: Set<String>,
    val sharedSettings: Set<String>,
    val sharedReasons: List<String>
)

/**
 * Deterministic, lightweight on-device content representation builder for Movies & TV titles.
 *
 * Extracts rich narrative signals (themes, emotional tone, subgenres, narrative structure,
 * character dynamics, setting/world-building, pacing, visual format, audience, regional origin,
 * franchise stem, and meaningful synopsis terms) strictly from metadata actually present in
 * the summary/details, weighted by explicit [MediaContentProfile.metadataQuality].
 */
object MediaContentAnalyzer {

    private val profileCache: MutableMap<String, MediaContentProfile> = Collections.synchronizedMap(
        object : LinkedHashMap<String, MediaContentProfile>(512, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, MediaContentProfile>?
            ): Boolean = size > 512
        }
    )

    private data class WeightedCue(
        val id: String,
        val displayLabel: String,
        val terms: Set<String>
    )

    private val THEME_CUES = listOf(
        WeightedCue("grief_loss", "Grief & Healing", setOf("grief", "mourning", "bereaved", "widow", "widower", "late wife", "late husband", "tragic loss", "coping", "aftermath")),
        WeightedCue("redemption", "Redemption & Second Chances", setOf("redemption", "second chance", "atonement", "forgive", "past sins", "reform", "reclaim")),
        WeightedCue("revenge_justice", "Vengeance & Justice", setOf("revenge", "vengeance", "retribution", "payback", "vendetta", "vigilante", "hunt down", "avenge")),
        WeightedCue("survival", "Survival Against the Odds", setOf("survival", "survive", "stranded", "trapped", "shipwrecked", "wilderness", "ration", "outlast", "desperate escape")),
        WeightedCue("identity_self", "Identity & Belonging", setOf("identity", "self-discovery", "double life", "alter ego", "amnesia", "who she really", "who he really", "finding herself", "finding himself")),
        WeightedCue("family_bond", "Family Bonds", setOf("estranged father", "estranged mother", "siblings", "family secret", "reunite", "inheritance", "dynasty", "ancestral", "bloodline", "daughter", "son")),
        WeightedCue("coming_of_age", "Coming of Age", setOf("coming-of-age", "coming of age", "teenager", "adolescen", "growing up", "rite of passage", "graduation", "first love", "high school")),
        WeightedCue("heist_caper", "Heist & Intricate Ploy", setOf("heist", "robbery", "con artist", "vault", "pull off one last", "master thief", "counterfeit", "sting operation", "caper")),
        WeightedCue("conspiracy_coverup", "Conspiracy & Cover-Up", setOf("conspiracy", "cover-up", "coverup", "whistleblower", "corrupt", "shadowy", "framed", "deep state", "syndicate", "blackmail")),
        WeightedCue("war_cost", "Cost of Conflict", setOf("wartime", "frontline", "platoon", "soldiers", "battlefield", "occupation", "resistance", "veteran", "pow", "trench")),
        WeightedCue("time_memory", "Time, Memory & Reality", setOf("time loop", "time travel", "multiverse", "parallel universe", "timeline", "memory", "dreams within", "simulation", "quantum", "alternate reality")),
        WeightedCue("ai_technology", "Technology & AI", setOf("artificial intelligence", "android", "cyborg", "algorithm", "hacker", "cyberpunk", "virtual reality", "sentient", "robotics", "neural")),
        WeightedCue("space_exploration", "Space & First Contact", setOf("spacecraft", "astronaut", "alien", "extraterrestrial", "colony", "cosmos", "starship", "orbit", "exoplanet", "mars")),
        WeightedCue("supernatural_curse", "Supernatural & Occult", setOf("haunted", "ghost", "demon", "curse", "exorcism", "witch", "paranormal", "possession", "occult", "spirit")),
        WeightedCue("love_devotion", "Love & Devotion", setOf("soulmate", "forbidden love", "rekindle", "arranged marriage", "wedding", "romance", "courtship", "devotion", "heartbreak")),
        WeightedCue("betrayal", "Betrayal & Trust", setOf("betrayal", "betrayed", "treachery", "double-cross", "double cross", "informant", "mole", "unfaithful")),
        WeightedCue("power_ambition", "Ambition & Power", setOf("empire", "throne", "power struggle", "ruthless", "billionaire", "monarch", "rise to power", "mafia", "cartel", "kingpin")),
        WeightedCue("isolation", "Isolation & Paranoia", setOf("isolated", "quarantine", "lighthouse", "bunker", "cabin", "locked room", "paranoia", "cabin fever")),
        WeightedCue("found_family", "Found Family", setOf("misfit", "misfits", "ragtag", "unlikely allies", "band together", "crew of", "chosen family")),
        WeightedCue("mystery_investigation", "Mystery & Sleuthing", setOf("detective", "investigat", "cold case", "disappearance", "missing person", "clues", "whodunit", "forensic", "autopsy", "unresolved")),
        WeightedCue("sports_triumph", "Underdog Triumph", setOf("underdog", "championship", "coach", "tournament", "olympic", "boxer", "training camp", "comeback season")),
        WeightedCue("political_intrigue", "Political & Royal Intrigue", setOf("senator", "president", "parliament", "coup", "diplomat", "espionage", "cold war", "intelligence agency", "embassy")),
        WeightedCue("folklore_myth", "Myth & Folklore", setOf("mythology", "legend", "prophecy", "ancient", "folklore", "gods", "deity", "chosen one", "realm"))
    )

    private val TONE_CUES = listOf(
        WeightedCue("dark_gritty", "Dark & Gritty", setOf("brutal", "gritty", "noir", "bleak", "sinister", "merciless", "underworld", "blood-soaked", "ruthless", "grim", "harrowing")),
        WeightedCue("tense_suspenseful", "Tense & Suspenseful", setOf("ticking clock", "suspense", "hostage", "stalker", "cat-and-mouse", "relentless", "manhunt", "deadly game", "high-stakes", "edge-of-your-seat")),
        WeightedCue("uplifting_warm", "Heartwarming & Uplifting", setOf("heartwarming", "inspiring", "touching", "hopeful", "feel-good", "uplifting", "joy", "charming", "kindness", "tenderness")),
        WeightedCue("whimsical_playful", "Whimsical & Playful", setOf("whimsical", "quirky", "eccentric", "mischief", "magical", "delightful", "zany", "absurd", "hijinks")),
        WeightedCue("melancholic_poignant", "Poignant & Reflective", setOf("poignant", "bittersweet", "melanchol", "nostalgic", "tender", "quietly", "intimate portrait", "fading")),
        WeightedCue("satirical_witty", "Witty & Satirical", setOf("satire", "satirical", "razor-sharp", "dark comedy", "deadpan", "mockumentary", "parody", "banter", "sardonic")),
        WeightedCue("epic_awe", "Grand & Epic", setOf("epic", "sweeping", "colossal", "odyssey", "destiny", "cataclysm", "battle for", "spectacle", "legendary")),
        WeightedCue("romantic_intimate", "Romantic", setOf("passionate", "romantic", "sparks fly", "affair", "love story", "crush", "yearning"))
    )

    private val STRUCTURE_CUES = listOf(
        WeightedCue("whodunit_investigation", "Mystery Investigation", setOf("murder mystery", "whodunit", "solve the", "detective", "clues", "suspects", "cold case", "interrogation")),
        WeightedCue("nonlinear_puzzle", "Puzzle Narrative", setOf("time loop", "flashback", "non-linear", "nonlinear", "fragmented", "unreliable narrator", "mind-bending", "twist")),
        WeightedCue("heist_mission", "Team Mission", setOf("heist", "infiltrate", "mission", "recruit a team", "extraction", "undercover")),
        WeightedCue("slow_burn_character", "Character Study", setOf("portrait of", "intimate study", "quiet life", "marriage", "estranged", "character study")),
        WeightedCue("quest_journey", "Odyssey / Road Journey", setOf("cross-country", "road trip", "pilgrimage", "expedition", "quest", "voyage", "odyssey")),
        WeightedCue("procedural", "Case-by-Case Procedural", setOf("precinct", "law firm", "hospital", "emergency room", "firehouse", "unit", "task force")),
        WeightedCue("origin_biopic", "Biographical Rise", setOf("based on the true story", "true story", "biopic", "memoir", "chronicles the life", "real-life"))
    )

    private val DYNAMICS_CUES = listOf(
        WeightedCue("buddy_duo", "Duo / Partnership", setOf("unlikely pair", "two friends", "best friends", "partnered with", "odd couple", "duo")),
        WeightedCue("rivals_enemies", "Rivalry & Cat-and-Mouse", setOf("rival", "nemesis", "archenemy", "cat-and-mouse", "bitter feud", "square off")),
        WeightedCue("mentor_apprentice", "Mentor & Protege", setOf("mentor", "protege", "apprentice", "teacher", "master and student", "rookie")),
        WeightedCue("parent_child", "Parent & Child", setOf("single mother", "single father", "mother and daughter", "father and son", "protect her child", "save his daughter", "save his son")),
        WeightedCue("ensemble_cast", "Ensemble Cast", setOf("ensemble", "strangers", "passengers", "residents of", "neighbors", "roommates", "group of friends")),
        WeightedCue("detective_suspect", "Investigator & Suspect", setOf("chief suspect", "prime suspect", "serial killer", "profiler", "interrogat"))
    )

    private val SETTING_CUES = listOf(
        WeightedCue("outer_space", "Deep Space", setOf("spaceship", "space station", "astronaut", "galaxy", "alien planet", "mars", "moon", "orbit")),
        WeightedCue("dystopian_future", "Dystopian Future", setOf("dystopian", "post-apocalyptic", "apocalypse", "wasteland", "totalitarian", "ruins of", "outbreak")),
        WeightedCue("historical_period", "Period Setting", setOf("19th century", "18th century", "victorian", "medieval", "world war", "1920s", "1940s", "1950s", "1960s", "1970s", "ancient rome", "tudor")),
        WeightedCue("urban_noir", "Gritty Metropolis", setOf("underbelly", "alleyway", "metropolis", "mean streets", "precinct", "gangland", "neon-lit")),
        WeightedCue("small_town", "Small Town Secrets", setOf("small town", "sleepy town", "rural village", "quiet town", "coastal town", "midwestern town")),
        WeightedCue("fantasy_realm", "Fantasy World", setOf("kingdom", "sorcery", "magic", "wizard", "dragon", "enchanted", "elves", "crown")),
        WeightedCue("high_school_campus", "School & Campus", setOf("high school", "college", "university", "boarding school", "freshman", "dorm", "campus")),
        WeightedCue("courtroom_legal", "Legal & Courtroom", setOf("courtroom", "trial", "attorney", "prosecutor", "jury", "verdict", "defense lawyer")),
        WeightedCue("hospital_medical", "Medical", setOf("hospital", "surgeon", "icu", "paramedic", "clinic", "medical resident")),
        WeightedCue("wilderness_sea", "Wilderness & Sea", setOf("arctic", "jungle", "desert", "mountain", "ocean", "island", "submarine", "expedition"))
    )

    private val GENERIC_GENRES = setOf(
        "drama", "movie", "movies", "tv", "series", "film", "entertainment", "general", "other"
    )

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "their", "they", "them",
        "she", "her", "his", "him", "who", "whom", "whose", "when", "where", "while", "what",
        "which", "after", "before", "about", "over", "under", "between", "through", "during",
        "must", "will", "would", "could", "should", "have", "has", "had", "been", "being",
        "were", "was", "are", "but", "not", "all", "one", "two", "new", "old", "own", "out",
        "life", "world", "story", "young", "man", "woman", "finds", "find", "discovers",
        "begins", "takes", "makes", "becomes", "years", "soon", "when", "more", "most",
        "only", "some", "such", "than", "then", "very", "just", "back", "even", "way",
        "down", "each", "many", "forced", "against", "order", "secret", "between", "into",
        "himself", "herself", "themselves", "another", "together", "around", "season", "episode",
        "movie", "film", "series", "show"
    )

    fun analyze(item: MovieTitleSummary, details: MediaDetails? = null): MediaContentProfile {
        val effectiveSummary = details?.fullDescription?.takeIf { it.isNotBlank() }
            ?: item.summary?.takeIf { it.isNotBlank() }
            ?: ""
        val effectiveCountry = details?.summary?.country?.takeIf { it.isNotBlank() } ?: item.country.orEmpty()
        val effectiveLanguage = details?.summary?.language?.takeIf { it.isNotBlank() } ?: item.language.orEmpty()
        val effectiveTags: List<String> = listOfNotNull(
            details?.directorOrCreator?.takeIf { it.isNotBlank() },
            details?.networkOrStudio?.takeIf { it.isNotBlank() }
        ) + details?.cast.orEmpty().take(4).map { it.name }.filter { it.isNotBlank() }
        val effectiveGenres = (item.genres + details?.summary?.genres.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
        val cacheKey = buildString {
            append(item.stableMediaKey)
            append('|')
            append(item.kind.name)
            append('|')
            append(effectiveGenres.joinToString(","))
            append('|')
            append(effectiveCountry)
            append('|')
            append(effectiveLanguage)
            append('|')
            append(effectiveSummary.hashCode())
            append('|')
            append(effectiveTags.joinToString(",").hashCode())
        }
        profileCache.get(cacheKey)?.let { return it }

        val normalizedGenres = effectiveGenres.mapTo(linkedSetOf()) { canonicalGenreToken(it) }
        val rawGenreText = effectiveGenres.joinToString(" ").lowercase(Locale.US)
        val combinedText = buildString {
            append(item.title)
            append(' ')
            append(effectiveGenres.joinToString(" "))
            append(' ')
            append(effectiveTags.joinToString(" "))
            append(' ')
            append(effectiveSummary)
        }.lowercase(Locale.US)

        val visualFormat = inferVisualFormat(
            normalizedGenres = normalizedGenres,
            rawText = "$rawGenreText $combinedText",
            country = effectiveCountry,
            language = effectiveLanguage
        )
        val audienceClass = inferAudienceClass(
            normalizedGenres = normalizedGenres,
            rawText = "$rawGenreText $combinedText",
            visualFormat = visualFormat
        )

        val themes = matchCues(THEME_CUES, combinedText)
        val tones = inferTones(normalizedGenres, combinedText)
        val narrativeStructures = matchCues(STRUCTURE_CUES, combinedText)
        val characterDynamics = matchCues(DYNAMICS_CUES, combinedText)
        val settings = matchCues(SETTING_CUES, combinedText)
        val subgenres = inferSubgenres(
            normalizedGenres = normalizedGenres,
            visualFormat = visualFormat,
            audienceClass = audienceClass,
            regionCluster = normalizeRegionCluster(effectiveCountry, effectiveLanguage),
            combinedText = combinedText
        )
        val pacing = inferPacing(normalizedGenres, themes, tones, combinedText, item.durationMinutes)
        val regionCluster = normalizeRegionCluster(effectiveCountry, effectiveLanguage)
        val languageCode = effectiveLanguage.trim().lowercase(Locale.US).takeIf { it.isNotBlank() }
        val franchiseStem = extractFranchiseStem(item.title)
        val synopsisTokens = extractSynopsisTokens(item.title, effectiveSummary, effectiveTags)
        val quality = computeMetadataQuality(
            effectiveSummary = effectiveSummary,
            normalizedGenres = normalizedGenres,
            hasCountryOrLanguage = regionCluster != null || languageCode != null,
            hasYear = item.releaseYear != null,
            hasRating = (item.rating ?: 0.0) > 0.0,
            extractedSignalsCount = themes.size + tones.size + subgenres.size + settings.size
        )

        val profile = MediaContentProfile(
            stableMediaKey = item.stableMediaKey,
            kind = item.kind,
            normalizedGenres = normalizedGenres,
            subgenres = subgenres,
            themes = themes,
            tones = tones,
            narrativeStructures = narrativeStructures,
            characterDynamics = characterDynamics,
            settings = settings,
            pacingIntensity = pacing,
            visualFormat = visualFormat,
            audienceClass = audienceClass,
            regionCluster = regionCluster,
            languageCode = languageCode,
            franchiseStem = franchiseStem,
            synopsisTokens = synopsisTokens,
            metadataQuality = quality
        )
        profileCache.put(cacheKey, profile)
        return profile
    }

    fun computeContentSimilarity(
        anchor: MediaContentProfile,
        candidate: MediaContentProfile
    ): ContentSimilarityResult {
        // Explicit visual format guard: don't match live-action grit casually into kids animation or vice-versa
        if (anchor.audienceClass == AudienceClass.KIDS_FAMILY && candidate.audienceClass == AudienceClass.MATURE) {
            return ContentSimilarityResult(0.0, false, emptySet(), emptySet(), emptySet(), emptySet(), emptyList())
        }
        if (anchor.audienceClass == AudienceClass.MATURE && candidate.audienceClass == AudienceClass.KIDS_FAMILY) {
            return ContentSimilarityResult(0.0, false, emptySet(), emptySet(), emptySet(), emptySet(), emptyList())
        }

        val sharedThemes = anchor.themes intersect candidate.themes
        val sharedSubgenres = anchor.subgenres intersect candidate.subgenres
        val sharedTones = anchor.tones intersect candidate.tones
        val sharedStructures = anchor.narrativeStructures intersect candidate.narrativeStructures
        val sharedDynamics = anchor.characterDynamics intersect candidate.characterDynamics
        val sharedSettings = anchor.settings intersect candidate.settings
        val sharedSynopsisTokens = anchor.synopsisTokens intersect candidate.synopsisTokens

        val specificAnchorGenres = anchor.normalizedGenres.filterNot { it in GENERIC_GENRES }.toSet()
        val specificCandidateGenres = candidate.normalizedGenres.filterNot { it in GENERIC_GENRES }.toSet()
        val sharedSpecificGenres = specificAnchorGenres intersect specificCandidateGenres
        val sharedGenericGenres = (anchor.normalizedGenres intersect candidate.normalizedGenres) - sharedSpecificGenres

        val themeSim = jaccardOrOverlap(anchor.themes, candidate.themes)
        val subgenreSim = jaccardOrOverlap(anchor.subgenres, candidate.subgenres)
        val toneSim = jaccardOrOverlap(anchor.tones, candidate.tones)
        val structureSim = jaccardOrOverlap(anchor.narrativeStructures, candidate.narrativeStructures)
        val dynamicsSim = jaccardOrOverlap(anchor.characterDynamics, candidate.characterDynamics)
        val settingSim = jaccardOrOverlap(anchor.settings, candidate.settings)
        val synopsisSim = if (anchor.synopsisTokens.isNotEmpty() && candidate.synopsisTokens.isNotEmpty()) {
            val denom = min(anchor.synopsisTokens.size, candidate.synopsisTokens.size).coerceAtLeast(4).toDouble()
            (sharedSynopsisTokens.size / denom).coerceIn(0.0, 1.0)
        } else 0.0
        val specificGenreSim = jaccardOrOverlap(specificAnchorGenres, specificCandidateGenres)

        val franchiseBonus = if (
            anchor.franchiseStem != null &&
            anchor.franchiseStem == candidate.franchiseStem
        ) 0.28 else 0.0

        val regionMatch = anchor.regionCluster != null && anchor.regionCluster == candidate.regionCluster
        val regionBonus = if (regionMatch && (sharedThemes.isNotEmpty() || sharedTones.isNotEmpty() || sharedSpecificGenres.isNotEmpty())) {
            0.09
        } else if (regionMatch && candidate.kind == anchor.kind) {
            0.03
        } else 0.0

        val formatAlignmentBonus = when {
            anchor.visualFormat == VisualFormat.ANIME && candidate.visualFormat == VisualFormat.ANIME -> 0.14
            anchor.visualFormat == VisualFormat.ANIMATION && candidate.visualFormat == VisualFormat.ANIMATION -> 0.10
            anchor.visualFormat == VisualFormat.DOCUMENTARY && candidate.visualFormat == VisualFormat.DOCUMENTARY -> 0.14
            anchor.visualFormat == VisualFormat.ANIME && candidate.visualFormat != VisualFormat.ANIME -> -0.06
            anchor.visualFormat == VisualFormat.DOCUMENTARY && candidate.visualFormat != VisualFormat.DOCUMENTARY -> -0.10
            else -> 0.0
        }

        val pacingBonus = if (anchor.pacingIntensity != null && anchor.pacingIntensity == candidate.pacingIntensity) {
            0.05
        } else 0.0

        // Notice MediaKind itself does NOT award automatic points:
        // Watching a TV series will match a deep-cut Movie if story/themes/tone/world align,
        // and won't match an unrelated TV series that only shares "Drama".
        val weightedStoryScore = (themeSim * 0.28) +
            (subgenreSim * 0.20) +
            (toneSim * 0.15) +
            (synopsisSim * 0.18) +
            (specificGenreSim * 0.14) +
            (settingSim * 0.09) +
            (structureSim * 0.08) +
            (dynamicsSim * 0.06) +
            franchiseBonus +
            regionBonus +
            formatAlignmentBonus +
            pacingBonus +
            if (sharedGenericGenres.isNotEmpty() && sharedThemes.isEmpty() && sharedTones.isEmpty() && sharedSynopsisTokens.size < 2) {
                0.02
            } else if (sharedGenericGenres.isNotEmpty()) {
                0.04
            } else 0.0

        val confidenceFactor = ((anchor.metadataQuality * 0.55) + (candidate.metadataQuality * 0.45))
            .coerceIn(0.40, 1.0)
        val finalScore = (weightedStoryScore * (0.65 + 0.35 * confidenceFactor)).coerceIn(0.0, 1.0)

        val deepMatches = sharedThemes.size + sharedSubgenres.size + sharedTones.size +
            sharedStructures.size + sharedSettings.size + sharedDynamics.size +
            (if (sharedSynopsisTokens.size >= 2) 1 else 0) +
            (if (franchiseBonus > 0.0) 2 else 0)

        val hasMeaningfulSignal = (deepMatches >= 1 && finalScore >= 0.18) ||
            (sharedSpecificGenres.size >= 2 && finalScore >= 0.22)

        val reasons = buildList {
            if (franchiseBonus > 0.0) add("Same series universe")
            sharedThemes.firstOrNull()?.let { cueLabel(THEME_CUES, it)?.let(::add) }
            sharedSubgenres.firstOrNull()?.let { add(prettyToken(it)) }
            sharedTones.firstOrNull()?.let { cueLabel(TONE_CUES, it)?.let(::add) }
            sharedSettings.firstOrNull()?.let { cueLabel(SETTING_CUES, it)?.let(::add) }
            if (isEmpty() && sharedSpecificGenres.isNotEmpty()) {
                add(prettyToken(sharedSpecificGenres.first()))
            }
        }.distinct().take(2)

        return ContentSimilarityResult(
            score = finalScore,
            hasMeaningfulSignal = hasMeaningfulSignal,
            sharedThemes = sharedThemes,
            sharedSubgenres = sharedSubgenres,
            sharedTones = sharedTones,
            sharedSettings = sharedSettings,
            sharedReasons = reasons
        )
    }

    internal fun canonicalGenreToken(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
        return when {
            cleaned in setOf("sci-fi", "scifi", "science fiction", "sci fi", "sci-fi & fantasy") -> "sci_fi"
            cleaned in setOf("tv movie", "tv") -> "drama"
            cleaned in setOf("action & adventure", "action") -> "action"
            cleaned in setOf("war & politics") -> "war"
            cleaned in setOf("kids", "children", "family") -> "family"
            else -> cleaned.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        }.ifBlank { "other" }
    }

    internal fun normalizeRegionCluster(country: String?, language: String?): String? {
        val c = country.orEmpty().trim().lowercase(Locale.US)
        val l = language.orEmpty().trim().lowercase(Locale.US)
        return when {
            "nigeria" in c || "nollywood" in c || "ghana" in c -> "west_african"
            "korea" in c || l == "ko" || "korean" in l -> "korean"
            "india" in c || l in setOf("hi", "ta", "te", "ml", "hindi", "tamil", "telugu") -> "indian"
            "japan" in c || l == "ja" || "japanese" in l -> "japanese"
            "china" in c || "taiwan" in c || "hong kong" in c || l in setOf("zh", "mandarin", "cantonese") -> "chinese"
            "united kingdom" in c || c == "uk" || "britain" in c || "ireland" in c || "australia" in c || "canada" in c -> "uk_commonwealth"
            "france" in c || "germany" in c || "spain" in c || "italy" in c || "sweden" in c || "denmark" in c || "norway" in c ||
                l in setOf("fr", "de", "es", "it", "sv", "da", "no") -> "european"
            "mexico" in c || "brazil" in c || "argentina" in c || "colombia" in c || l == "pt" -> "latin_american"
            "united states" in c || c == "usa" || c == "us" -> "hollywood_us"
            else -> null
        }
    }

    internal fun extractFranchiseStem(title: String): String? {
        var working = title.lowercase(Locale.US).trim()
        if (working.length < 4) return null
        // Strip colon/dash subtitle after a distinct stem (e.g., "Dune: Part Two" -> "dune")
        val sepIdx = listOf(working.indexOf(':'), working.indexOf(" - "), working.indexOf(" part ")).filter { it > 0 }.minOrNull()
        if (sepIdx != null && sepIdx >= 4) {
            working = working.substring(0, sepIdx).trim()
        }
        working = working
            .replace(Regex("""\b(season|book|volume|vol\.?|chapter)\s*\d+\b"""), "")
            .replace(Regex("""\b(part\s*(one|two|three|four|i|ii|iii|iv|v|\d+))\b"""), "")
            .replace(Regex("""\s+\b(ii|iii|iv|v|[2-9])\b$"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
        return working.takeIf { it.length >= 4 && !it.startsWith("the_") } ?: working.removePrefix("the_").takeIf { it.length >= 5 }
    }

    private fun matchCues(cues: List<WeightedCue>, lowerText: String): Set<String> {
        if (lowerText.isBlank()) return emptySet()
        val matched = linkedSetOf<String>()
        for (cue in cues) {
            if (cue.terms.any { term -> containsTerm(lowerText, term) }) {
                matched.add(cue.id)
            }
        }
        return matched
    }

    private fun inferTones(normalizedGenres: Set<String>, lowerText: String): Set<String> {
        val tones = matchCues(TONE_CUES, lowerText).toMutableSet()
        if ("horror" in normalizedGenres || "thriller" in normalizedGenres || "crime" in normalizedGenres) {
            if ("comedy" !in normalizedGenres && "dark_gritty" !in tones) {
                tones.add("tense_suspenseful")
            }
        }
        if ("comedy" in normalizedGenres && "horror" !in normalizedGenres) {
            tones.add("whimsical_playful")
        }
        if ("romance" in normalizedGenres) {
            tones.add("romantic_intimate")
        }
        if ("adventure" in normalizedGenres || "fantasy" in normalizedGenres || "sci_fi" in normalizedGenres) {
            tones.add("epic_awe")
        }
        return tones
    }

    private fun inferSubgenres(
        normalizedGenres: Set<String>,
        visualFormat: VisualFormat,
        audienceClass: AudienceClass,
        regionCluster: String?,
        combinedText: String
    ): Set<String> {
        val out = linkedSetOf<String>()
        if ("romance" in normalizedGenres && "comedy" in normalizedGenres) out.add("romantic_comedy")
        if (("thriller" in normalizedGenres || "mystery" in normalizedGenres) &&
            ("psychological" in combinedText || "paranoia" in combinedText || "mind" in combinedText || "memory" in combinedText)
        ) {
            out.add("psychological_thriller")
        }
        if ("crime" in normalizedGenres && ("detective" in combinedText || "noir" in combinedText || "cartel" in combinedText || "underworld" in combinedText)) {
            out.add("crime_noir")
        }
        if ("sci_fi" in normalizedGenres && ("dystopian" in combinedText || "apocalyp" in combinedText || "simulation" in combinedText || "surveillance" in combinedText)) {
            out.add("sci_fi_dystopia")
        }
        if ("sci_fi" in normalizedGenres && ("space" in combinedText || "galaxy" in combinedText || "alien" in combinedText || "starship" in combinedText)) {
            out.add("space_opera")
        }
        if ("horror" in normalizedGenres && ("haunted" in combinedText || "demon" in combinedText || "ghost" in combinedText || "witch" in combinedText || "curse" in combinedText)) {
            out.add("supernatural_horror")
        }
        if (visualFormat == VisualFormat.ANIME) out.add("anime")
        if (visualFormat == VisualFormat.ANIMATION && audienceClass == AudienceClass.MATURE) out.add("adult_animation")
        if (regionCluster == "west_african") out.add("nollywood_cinema")
        if (regionCluster == "korean") out.add("korean_storytelling")
        if (regionCluster == "indian") out.add("indian_cinema")
        if ("action" in normalizedGenres && ("martial arts" in combinedText || "kung fu" in combinedText || "samurai" in combinedText || "assassin" in combinedText)) {
            out.add("martial_arts_action")
        }
        if ("history" in normalizedGenres || "historical" in combinedText || "biopic" in combinedText || "true story" in combinedText) {
            out.add("historical_biopic")
        }
        return out
    }

    private fun inferVisualFormat(
        normalizedGenres: Set<String>,
        rawText: String,
        country: String,
        language: String
    ): VisualFormat {
        val isDoc = "documentary" in normalizedGenres || "docuseries" in rawText || "documentary" in rawText
        if (isDoc) return VisualFormat.DOCUMENTARY
        val hasAnimeHint = "anime" in normalizedGenres || "anime" in rawText ||
            (("animation" in normalizedGenres || "animated" in rawText) &&
                (country.contains("japan", ignoreCase = true) || language.equals("ja", ignoreCase = true) || language.contains("japanese", ignoreCase = true)))
        if (hasAnimeHint) return VisualFormat.ANIME
        val hasAnimation = "animation" in normalizedGenres || "animated" in rawText || "cartoon" in rawText
        if (hasAnimation) return VisualFormat.ANIMATION
        return VisualFormat.LIVE_ACTION
    }

    private fun inferAudienceClass(
        normalizedGenres: Set<String>,
        rawText: String,
        visualFormat: VisualFormat
    ): AudienceClass {
        val matureHits = "horror" in normalizedGenres || "erotic" in rawText || "adult animation" in rawText ||
            "brutal" in rawText || "serial killer" in rawText || "cartel" in rawText
        if (matureHits) return AudienceClass.MATURE
        val kidsHits = "family" in normalizedGenres || "kids" in normalizedGenres ||
            (visualFormat == VisualFormat.ANIMATION && ("talking" in rawText || "princess" in rawText || "toys" in rawText || "fairy" in rawText || "disney" in rawText || "pixar" in rawText))
        if (kidsHits) return AudienceClass.KIDS_FAMILY
        if ("coming of age" in rawText || "high school" in rawText || "teen" in rawText) return AudienceClass.YOUNG_ADULT
        return AudienceClass.GENERAL
    }

    private fun inferPacing(
        normalizedGenres: Set<String>,
        themes: Set<String>,
        tones: Set<String>,
        combinedText: String,
        durationMinutes: Int?
    ): PacingIntensity? {
        if ("action" in normalizedGenres || "heist_caper" in themes || "tense_suspenseful" in tones || "relentless" in combinedText) {
            return PacingIntensity.FAST_ADRENALINE
        }
        if ("comedy" in normalizedGenres || "romance" in normalizedGenres || "whimsical_playful" in tones) {
            return PacingIntensity.LIGHT_BREEZY
        }
        if ("slow_burn_character" in matchCues(STRUCTURE_CUES, combinedText) ||
            "melancholic_poignant" in tones ||
            (durationMinutes != null && durationMinutes >= 135 && "drama" in normalizedGenres)
        ) {
            return PacingIntensity.SLOW_BURN
        }
        return if (normalizedGenres.isNotEmpty() || combinedText.length >= 40) PacingIntensity.BALANCED else null
    }

    private fun extractSynopsisTokens(title: String, summary: String, tags: List<String>): Set<String> {
        val raw = "$title $summary ${tags.joinToString(" ")}"
        if (raw.isBlank()) return emptySet()
        return raw.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 4 && it !in STOP_WORDS && !it.all { ch -> ch.isDigit() } }
            .distinct()
            .take(28)
            .toSet()
    }

    private fun computeMetadataQuality(
        effectiveSummary: String,
        normalizedGenres: Set<String>,
        hasCountryOrLanguage: Boolean,
        hasYear: Boolean,
        hasRating: Boolean,
        extractedSignalsCount: Int
    ): Double {
        var q = 0.20
        val len = effectiveSummary.trim().length
        q += when {
            len >= 120 -> 0.36
            len >= 50 -> 0.24
            len >= 15 -> 0.12
            else -> 0.0
        }
        val specificCount = normalizedGenres.count { it !in GENERIC_GENRES }
        q += when {
            specificCount >= 2 -> 0.18
            specificCount == 1 -> 0.11
            normalizedGenres.isNotEmpty() -> 0.04
            else -> 0.0
        }
        if (hasCountryOrLanguage) q += 0.08
        if (hasYear) q += 0.04
        if (hasRating) q += 0.04
        q += (extractedSignalsCount.coerceAtMost(5) * 0.025)
        return q.coerceIn(0.20, 1.0)
    }

    private fun containsTerm(text: String, term: String): Boolean {
        if (' ' in term || '-' in term) return text.contains(term)
        val idx = text.indexOf(term)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
        return beforeOk
    }

    private fun jaccardOrOverlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }.toDouble()
        if (intersection == 0.0) return 0.0
        val union = (a.size + b.size - intersection).coerceAtLeast(1.0)
        return (intersection / union).coerceIn(0.0, 1.0)
    }

    private fun cueLabel(cues: List<WeightedCue>, id: String): String? =
        cues.firstOrNull { it.id == id }?.displayLabel

    private fun prettyToken(token: String): String =
        token.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
}
