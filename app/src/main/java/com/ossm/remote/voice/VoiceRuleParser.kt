package com.ossm.remote.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Who turned the phrase into steps (shown on the Voix panel with the latency). */
enum class VoiceInterpretSource { RULES, GEMMA }

/**
 * What the store knows when a phrase arrives: logical position (0..100) a new plan would start
 * from, whether a motif runs, and the last accepted plan ("encore").
 */
data class VoiceRuleContext(
    val positionPercent: Int = 0,
    val motifRunning: Boolean = false,
    val lastSteps: List<VoiceStep> = emptyList(),
)

/**
 * Voix 2.3.31 — deterministic French translator run BEFORE Gemma. It produces exactly the
 * objects Gemma's JSON produces (steps, motif or modulation); the store then validates them
 * with the same strict validators. Certainty rule: EVERY word of the phrase must be understood
 * by this grammar, otherwise it returns null and the phrase goes to Gemma unchanged. It never
 * clamps a value to make a plan valid (an out-of-bounds plan is rejected by the store).
 *
 * Values follow [VoiceLlmPrompt]: very slow 4000 ms, slow 2500, normal 1200, fast 600,
 * very fast 300, "d'un coup" 200 (the minimum; the user speed maximum still bounds it).
 */
object VoiceRuleParser {
    const val MOVE_VERY_SLOW = 4000
    const val MOVE_SLOW = 2500
    const val MOVE_NORMAL = 1200
    const val MOVE_FAST = 600
    const val MOVE_VERY_FAST = 300
    const val MOVE_SUDDEN = VoiceStepValidator.MIN_MOVE_MS
    const val FEW_SECONDS_MS = 3000
    /** "un peu plus" / "un peu moins" outside a motif: relative logical step. */
    const val RELATIVE_STEP = 15

    // Motifs, identical to the Gemma few-shots of VoiceLlmPrompt.
    val MOTIF_SLOW = VoiceMotif(30, 90, 20, 70, 25)
    val MOTIF_VERY_SLOW = VoiceMotif(30, 80, 20, 60, 30)
    val MOTIF_DEFAULT = VoiceMotif(30, 90, 25, 70, 25)
    val MOTIF_RAMP = VoiceMotif(30, 100, 20, 80, 30)

    fun parse(text: String, context: VoiceRuleContext? = null): VoiceInterpretResult? {
        val raw = merge(tokenize(text))
        if (raw.none { it != SEP }) return null
        val words = raw.filter { it != SEP && it !in POLITENESS }
        if (words.isEmpty()) return null
        modulation(words, context)?.let { return it }
        motif(words)?.let { return VoiceInterpretResult.Motif(it) }
        if (words == listOf("encore") || words == listOf("encore", "une", "fois") || words == listOf("recommence")) {
            val last = context?.lastSteps.orEmpty()
            return if (last.isEmpty()) null else VoiceInterpretResult.Steps(last)
        }
        return sequence(raw.filter { it !in POLITENESS }, context)?.let { VoiceInterpretResult.Steps(it) }
    }

    // ---------------------------------------------------------------- tokens

    private const val SEP = "|"
    private const val HALF = "#half"
    private const val ROUND_TRIP = "#ar"
    private const val SUDDEN = "#sudden"
    private const val THREE_QUARTERS = "#3q"
    private const val NUM_AND = "#numand"

    internal fun tokenize(text: String): List<String> {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val clean = decomposed.replace(Regex("\\p{Mn}+"), "")
            .replace("œ", "oe")
            .replace("%", " pourcent ")
        return Regex("[a-z]+|\\d+(?:[.,]\\d+)?|[,;.!?:]").findAll(clean)
            .map { if (it.value.length == 1 && it.value[0] in ",;.!?:") SEP else it.value }
            .toList()
    }

    /** Multi-word expressions become one token BEFORE splitting on "et"/"puis". */
    private fun merge(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        fun at(k: Int) = tokens.getOrNull(i + k)
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "s" && at(1) == "il" && (at(2) == "te" || at(2) == "vous") && at(3) == "plait" -> i += 4
                t == "et" && (at(1) == "puis" || at(1) == "ensuite" || at(1) == "apres") -> { out += SEP; i += 2 }
                t in setOf("puis", "ensuite", "apres", "et") -> { out += SEP; i += 1 }
                (t == "va" || t == "vas") && at(1) == "et" && at(2) == "vient" -> { out += ROUND_TRIP; i += 3 }
                (t == "aller" || t == "allers") && at(1) == "et" && (at(2) == "retour" || at(2) == "retours") -> { out += ROUND_TRIP; i += 3 }
                (t == "aller" || t == "allers") && (at(1) == "retour" || at(1) == "retours") -> { out += ROUND_TRIP; i += 2 }
                t == "allerretour" || t == "allersretours" -> { out += ROUND_TRIP; i += 1 }
                (t == "d" || t == "du") && at(1) == "un" && at(2) == "coup" -> { out += SUDDEN; i += 3 }
                t == "mi" && at(1) == "chemin" -> { out += "moitie"; i += 2 }
                t == "mi" && at(1) == "course" -> { out += "moitie"; i += 2 }
                t == "pour" && at(1) == "cent" -> { out += "pourcent"; i += 2 }
                t == "trois" && (at(1) == "quart" || at(1) == "quarts") -> { out += THREE_QUARTERS; i += 2 }
                t == "et" -> { out += SEP; i += 1 }
                else -> { out += t; i += 1 }
            }
        }
        // "et demie" and "vingt et un" were split above: undo those two cases.
        val fixed = ArrayList<String>(out.size)
        var k = 0
        while (k < out.size) {
            val t = out[k]
            val next = out.getOrNull(k + 1)
            val prev = fixed.lastOrNull()
            if (t == SEP && (next == "demie" || next == "demi") && prev != null && prev in SECOND_UNITS) {
                fixed += HALF; k += 2; continue
            }
            if (t == SEP && (next == "un" || next == "une" || next == "onze") && prev != null && prev in TENS) {
                fixed += NUM_AND; k += 1; continue
            }
            fixed += t; k += 1
        }
        return fixed
    }

    private val POLITENESS = setOf(
        "stp", "svp", "euh", "hum", "ok", "okay", "bon", "alors", "oui", "maintenant",
        "please", "voila", "bien",
    )

    // ---------------------------------------------------------------- numbers

    private val UNITS = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
        "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
    )
    private val TENS = mapOf(
        "vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
        "soixante" to 60,
    )
    private val SECOND_UNITS = setOf("seconde", "secondes", "s", "sec", "secs")

    /** French number (digits or words up to "cent") at [i]: value and tokens consumed. */
    internal fun number(tokens: List<String>, i: Int): Pair<Double, Int>? {
        val t = tokens.getOrNull(i) ?: return null
        if (t[0].isDigit()) return t.replace(',', '.').toDoubleOrNull()?.let { it to 1 }
        if (t == "cent") return 100.0 to 1
        var value: Int
        var j: Int
        when {
            t == "quatre" && (tokens.getOrNull(i + 1) == "vingt" || tokens.getOrNull(i + 1) == "vingts") -> {
                value = 80; j = i + 2
            }
            t in TENS -> { value = TENS.getValue(t); j = i + 1 }
            t == "dix" && tokens.getOrNull(i + 1) in setOf("sept", "huit", "neuf") ->
                return (10 + UNITS.getValue(tokens[i + 1])).toDouble() to 2
            t in UNITS -> return UNITS.getValue(t).toDouble() to 1
            else -> return null
        }
        val n = tokens.getOrNull(j)
        if (n == NUM_AND && tokens.getOrNull(j + 1) in setOf("un", "une", "onze")) {
            value += if (tokens[j + 1] == "onze") 11 else 1
            j += 2
        } else if (n == "dix" && tokens.getOrNull(j + 1) in setOf("sept", "huit", "neuf") && (value == 60 || value == 80)) {
            value += 10 + UNITS.getValue(tokens[j + 1]); j += 2
        } else if (n != null && n in UNITS && n != "zero" && n != "une") {
            val u = UNITS.getValue(n)
            if (u in 1..9 || ((value == 60 || value == 80) && u in 10..16)) { value += u; j += 1 }
        }
        return value.toDouble() to (j - i)
    }

    // ---------------------------------------------------------------- modulations / motifs

    private val MODULATION_FILLERS = setOf("un", "peu", "va", "vas", "allez", "y", "encore", "beaucoup", "tu", "peux", "bien")

    private val MODULATION_TABLE: Map<List<String>, VoiceModulation> = buildMap {
        fun add(m: VoiceModulation, vararg phrases: String) = phrases.forEach { this[it.split(' ')] = m }
        add(VoiceModulation.FASTER, "plus vite", "plus rapide", "plus rapidement", "accelere", "accelerer", "acceleres", "accelerez", "plus vite que ca")
        add(VoiceModulation.SLOWER, "plus lentement", "plus lent", "plus doucement", "moins vite", "moins rapide", "moins rapidement", "ralentis", "ralenti", "ralentir", "ralentit", "ralentissez")
        add(VoiceModulation.DEEPER, "plus profond", "plus profondement", "plus loin", "plus au fond")
        add(VoiceModulation.SHALLOWER, "moins profond", "moins profondement", "moins loin")
        add(
            VoiceModulation.HOLD,
            "garde ce rythme", "garde le rythme", "garde ce rythme la", "garde ca", "n augmente plus", "n accelere plus",
            "continue comme ca", "continue", "continue a ce rythme", "reste a ce rythme", "reste sur ce rythme",
        )
    }

    private fun modulation(words: List<String>, context: VoiceRuleContext?): VoiceInterpretResult? {
        val core = words.filter { it !in MODULATION_FILLERS }
        if (core.isEmpty()) return null
        MODULATION_TABLE[core]?.let { return VoiceInterpretResult.Modulate(it) }
        // "un peu plus" / "un peu moins": modulation while a motif runs, relative position otherwise.
        val sign = when (core) {
            listOf("plus") -> 1
            listOf("moins") -> -1
            else -> return null
        }
        val ctx = context ?: return null
        if (ctx.motifRunning) {
            return VoiceInterpretResult.Modulate(if (sign > 0) VoiceModulation.DEEPER else VoiceModulation.SHALLOWER)
        }
        val target = (ctx.positionPercent + sign * RELATIVE_STEP).coerceIn(0, 100)
        return VoiceInterpretResult.Steps(listOf(VoiceStep(target, MOVE_NORMAL, 0)))
    }

    private val START_VERBS = listOf(
        listOf("commence"), listOf("commences"), listOf("commencez"), listOf("demarre"), listOf("demarres"),
        listOf("demarrez"), listOf("debute"), listOf("vas", "y"), listOf("va", "y"), listOf("allez", "y"),
        listOf("on", "y", "va"), listOf("c", "est", "parti"), listOf("on", "commence"), listOf("on", "demarre"),
    )

    private fun motif(words: List<String>): VoiceMotif? {
        val verb = START_VERBS.filter { words.size >= it.size && words.subList(0, it.size) == it }
            .maxByOrNull { it.size } ?: return null
        var rest = words.drop(verb.size).filter { it != SEP && it != "a" }
        var ramp = false
        val accel = rest.indexOfFirst { it == "accelere" || it == "accelerer" || it == "monte" }
        if (accel >= 0) {
            val tail = rest.drop(accel + 1)
            if (tail.isNotEmpty() && tail != listOf("petit", "petit") && tail != listOf("progressivement") &&
                tail != listOf("doucement") && tail != listOf("peu", "peu")
            ) return null
            ramp = true
            rest = rest.take(accel)
        }
        val base = when (rest) {
            emptyList<String>() -> MOTIF_DEFAULT
            listOf("lentement"), listOf("doucement"), listOf("lent") -> MOTIF_SLOW
            listOf("tout", "doucement"), listOf("tres", "lentement"), listOf("tres", "doucement"),
            listOf("tout", "lentement") -> MOTIF_VERY_SLOW
            else -> return null
        }
        return if (ramp) MOTIF_RAMP else base
    }

    // ---------------------------------------------------------------- sequences

    private val ARTICLES = setOf(
        "a", "au", "aux", "la", "le", "les", "l", "de", "du", "des", "d", "jusqu", "jusque", "y", "t", "te", "toi",
        "me", "moi", "vers", "ca", "on", "fais", "fait", "faire", "je", "veux", "voudrais", "tu", "peux", "pourrais",
        "tout", "position", "point", "bien", "un", "une", "comme", "ce", "cette", "ton", "ta", "en", "dans",
    )
    private val MOVE_VERBS = setOf(
        "va", "vas", "aller", "allez", "descends", "descend", "descendre", "enfonce", "enfonces", "pousse", "pousses",
        "rentre", "rentres", "entre", "plonge", "avance", "avances", "viens", "bouge", "place", "mets", "glisse",
        "positionne", "monte",
    )
    private val RETURN_VERBS = setOf(
        "reviens", "revient", "revenir", "revenez", "ressors", "ressort", "ressortir", "sors", "sort", "sortir",
        "remonte", "remontes", "remonter", "recule", "recules", "reculer", "retourne", "retournes", "retire",
        "retires", "retirer",
    )
    private val HOLD_VERBS = setOf(
        "reste", "restes", "rester", "restez", "restant", "attends", "attend", "attendre", "attendez", "tiens",
        "tient", "tenir", "maintiens", "maintient", "maintenir", "patiente", "pendant", "garde",
    )
    private val POSITIONS = mapOf(
        "fond" to 100, "moitie" to 50, "milieu" to 50, THREE_QUARTERS to 75, "quart" to 25,
        "debut" to 0, "depart" to 0, "sortie" to 0, "entree" to 0,
    )
    private val SPEEDS = mapOf(
        "lentement" to MOVE_SLOW, "lent" to MOVE_SLOW, "lents" to MOVE_SLOW, "lente" to MOVE_SLOW,
        "lentes" to MOVE_SLOW, "doucement" to MOVE_SLOW, "doux" to MOVE_SLOW, "douce" to MOVE_SLOW,
        "normalement" to MOVE_NORMAL, "normal" to MOVE_NORMAL, "normale" to MOVE_NORMAL,
        "vite" to MOVE_FAST, "rapidement" to MOVE_FAST, "rapide" to MOVE_FAST, "rapides" to MOVE_FAST,
        SUDDEN to MOVE_SUDDEN, "brusquement" to MOVE_SUDDEN,
    )
    private val VERY_SPEEDS = mapOf(
        "lentement" to MOVE_VERY_SLOW, "lent" to MOVE_VERY_SLOW, "lents" to MOVE_VERY_SLOW,
        "lente" to MOVE_VERY_SLOW, "doucement" to MOVE_VERY_SLOW, "vite" to MOVE_VERY_FAST,
        "rapidement" to MOVE_VERY_FAST, "rapide" to MOVE_VERY_FAST, "rapides" to MOVE_VERY_FAST,
    )

    /** What one clause (between "puis/ensuite/et/,") says. */
    private class Clause {
        var target: Int? = null
        var isReturn = false
        var isHoldVerb = false
        var moveMs: Int? = null
        var holdMs: Int? = null
        var bareDurationMs: Int? = null
        var roundTrips: Int? = null
        var times: Int? = null
        var again = false
        var meaningful = false
    }

    private fun sequence(tokens: List<String>, context: VoiceRuleContext?): List<VoiceStep>? {
        val clauses = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (t in tokens) {
            if (t == SEP) {
                if (current.isNotEmpty()) clauses += current
                current = ArrayList()
            } else current += t
        }
        if (current.isNotEmpty()) clauses += current
        if (clauses.isEmpty()) return null
        val steps = ArrayList<VoiceStep>()
        var explicitSpeedOnLast = false
        for (words in clauses) {
            val c = parseClause(words) ?: return null
            if (!c.meaningful) continue
            val hold = c.holdMs ?: c.bareDurationMs?.takeIf { c.isHoldVerb }
            if (c.bareDurationMs != null && !c.isHoldVerb) return null
            when {
                c.roundTrips != null -> {
                    if (c.target != null && c.target != 100) return null
                    if (c.isReturn || c.times != null || c.again) return null
                    val move = c.moveMs ?: MOVE_NORMAL
                    repeat(c.roundTrips!!) {
                        steps += VoiceStep(100, move, hold ?: 0)
                        steps += VoiceStep(0, move, 0)
                    }
                    explicitSpeedOnLast = c.moveMs != null
                }
                c.target != null || c.isReturn -> {
                    if (c.times != null || c.again) return null
                    steps += VoiceStep(c.target ?: 0, c.moveMs ?: MOVE_NORMAL, hold ?: 0)
                    explicitSpeedOnLast = c.moveMs != null
                }
                c.times != null || c.again -> {
                    if (c.moveMs != null || hold != null) return null
                    val base: List<VoiceStep> = if (steps.isNotEmpty()) steps.toList() else context?.lastSteps.orEmpty()
                    if (base.isEmpty()) return null
                    val total = when {
                        c.again -> (if (steps.isNotEmpty()) 1 else 0) + (c.times ?: 1)
                        else -> c.times!!
                    }
                    if (total < 1) return null
                    steps.clear()
                    repeat(total) { steps += base }
                }
                hold != null -> {
                    if (c.moveMs != null || steps.isEmpty()) return null
                    val last = steps.removeAt(steps.size - 1)
                    steps += last.copy(holdMs = last.holdMs + hold)
                }
                c.moveMs != null -> {
                    // A speed on its own ("…, lentement") qualifies the previous move.
                    if (steps.isEmpty() || explicitSpeedOnLast) return null
                    val last = steps.removeAt(steps.size - 1)
                    steps += last.copy(moveMs = c.moveMs!!)
                    explicitSpeedOnLast = true
                }
                else -> return null
            }
        }
        return steps.takeIf { it.isNotEmpty() }
    }

    private fun parseClause(words: List<String>): Clause? {
        val c = Clause()
        var i = 0
        var previous: String? = null
        while (i < words.size) {
            val t = words[i]
            val next = words.getOrNull(i + 1)
            when {
                t == "tres" && next != null && next in VERY_SPEEDS -> {
                    if (c.moveMs != null) return null
                    c.moveMs = VERY_SPEEDS.getValue(next); c.meaningful = true; i += 2
                }
                t == "tout" && (next == "doucement" || next == "lentement") -> {
                    if (c.moveMs != null) return null
                    c.moveMs = MOVE_VERY_SLOW; c.meaningful = true; i += 2
                }
                t in SPEEDS -> {
                    if (c.moveMs != null) return null
                    c.moveMs = SPEEDS.getValue(t); c.meaningful = true; i += 1
                }
                t == "quelques" && next in SECOND_UNITS -> {
                    if (!assignDuration(c, previous, FEW_SECONDS_MS)) return null
                    i += 2
                }
                (t == "demi" || t == "demie") && next in SECOND_UNITS -> {
                    if (!assignDuration(c, previous, 500)) return null
                    i += 2
                }
                (t == "une" || t == "un") && (next == "demi" || next == "demie") && words.getOrNull(i + 2) in SECOND_UNITS -> {
                    if (!assignDuration(c, previous, 500)) return null
                    i += 3
                }
                t == ROUND_TRIP -> {
                    if (c.roundTrips != null) return null
                    c.roundTrips = 1; c.meaningful = true; i += 1
                }
                t in POSITIONS -> {
                    if (c.target != null) return null
                    c.target = POSITIONS.getValue(t); c.meaningful = true; i += 1
                }
                t == "encore" -> { c.again = true; c.meaningful = true; i += 1 }
                t in RETURN_VERBS -> { c.isReturn = true; c.meaningful = true; i += 1 }
                t in HOLD_VERBS -> { c.isHoldVerb = true; i += 1 }
                t in MOVE_VERBS -> i += 1
                else -> {
                    val parsed = number(words, i)
                    if (parsed != null) {
                        val (value, used) = parsed
                        val unit = words.getOrNull(i + used)
                        when {
                            unit in SECOND_UNITS -> {
                                var ms = value * 1000.0
                                var consumed = used + 1
                                if (words.getOrNull(i + consumed) == HALF) { ms += 500.0; consumed += 1 }
                                if (!assignDuration(c, previous, ms.roundToInt())) return null
                                i += consumed
                            }
                            unit == "fois" -> {
                                if (c.times != null || value < 1 || value != Math.floor(value)) return null
                                c.times = value.toInt(); c.meaningful = true; i += used + 1
                            }
                            unit == ROUND_TRIP -> {
                                if (c.roundTrips != null || value < 1 || value != Math.floor(value)) return null
                                c.roundTrips = value.toInt(); c.meaningful = true; i += used + 1
                            }
                            unit == "pourcent" -> {
                                if (!setPercent(c, value)) return null
                                i += used + 1
                            }
                            unit == "quart" || unit == "quarts" -> {
                                if (value != 1.0 || c.target != null) return null
                                c.target = 25; c.meaningful = true; i += used + 1
                            }
                            (value == 1.0 && (t == "un" || t == "une")) -> i += 1 // article
                            previous == "a" || previous == "jusqu" || previous == "vers" -> {
                                if (!setPercent(c, value)) return null
                                i += used
                            }
                            else -> return null
                        }
                    } else if (t in ARTICLES) {
                        i += 1
                    } else return null
                }
            }
            previous = t
        }
        return c
    }

    private fun setPercent(c: Clause, value: Double): Boolean {
        if (c.target != null || value != Math.floor(value) || value < 0 || value > 100) return false
        c.target = value.toInt(); c.meaningful = true
        return true
    }

    /** "en N s" = travel time; otherwise a hold (only accepted later with a hold verb). */
    private fun assignDuration(c: Clause, previous: String?, ms: Int): Boolean {
        c.meaningful = true
        if (previous == "en" || previous == "dans") {
            if (c.moveMs != null) return false
            c.moveMs = ms
            return true
        }
        if (c.holdMs != null || c.bareDurationMs != null) return false
        if (previous == "pendant") c.holdMs = ms else c.bareDurationMs = ms
        return true
    }
}
