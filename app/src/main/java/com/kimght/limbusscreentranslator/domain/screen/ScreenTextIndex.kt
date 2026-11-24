package com.kimght.limbusscreentranslator.domain.screen

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

private val numbers = Regex("[+-]?[0-9]+%?")
private val placeholders = Regex("""\{([0-9]{1,2})}""")
private val combiningMarks = Regex("\\p{M}+")

private fun unaccent(value: String): String =
    combiningMarks.replace(Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD), "")

internal fun screenTextKey(value: String): String {
    val normalized = unaccent(value).replace('−', '-').replace('×', 'x')
    return buildString(normalized.length) {
        normalized.forEachIndexed { i, char ->
            if (char in 'a'..'z' || char in '0'..'9' ||
                (char in "+-" && normalized.getOrNull(i + 1)?.isDigit() == true) ||
                (char == '%' && normalized.getOrNull(i - 1)?.isDigit() == true)
            ) append(char)
        }
    }
}

internal class ScreenTextIndex(pairs: List<ScreenTextPair>) {
    internal data class Match(val translation: String, val score: Double, val changed: Boolean)
    private data class Hit(val pair: ScreenTextPair, val translation: String, val score: Double)
    private data class Template(
        val pair: ScreenTextPair, val prefix: String, val pattern: Regex, val ids: List<String>,
    )

    private val originals = pairs.asSequence()
        .filterNot { placeholders.containsMatchIn(it.english) }
        .map { screenTextKey(it.english) to it }
        .filter { (key, _) -> key.count { c -> c in 'a'..'z' } >= 3 }
        .groupBy({ it.first }, { it.second })

    private val templates = pairs.asSequence()
        .filter { placeholders.containsMatchIn(it.english) }
        .mapNotNull(::template)
        .groupBy { it.prefix.take(TEMPLATE_PREFIX_LENGTH) }

    private val fuzzyKeys = originals.keys.filter { it.length in MIN_FUZZY_LENGTH..MAX_FUZZY_LENGTH }
    private val byFragment = buildMap {
        fuzzyKeys.forEachIndexed { index, key ->
            val lastStart = key.length - FRAGMENT_LENGTH
            // Sample the original at evenly spaced offsets. OCR can omit spaces,
            // so look for compact character fragments instead of whole words.
            val fragments = (0..4).map { sample -> fragment(key, lastStart * sample / 4) }.distinct()
            fragments.forEach { hash -> getOrPut(hash, ::Postings).add(index) }
        }
    }

    private class Postings {
        private var indices = IntArray(4)
        var size = 0
            private set

        fun add(id: Int) {
            if (size > MAX_POSTINGS) return
            if (size == MAX_POSTINGS) {
                size++
                indices = IntArray(0)
                return
            }
            if (size == indices.size) indices = indices.copyOf(size * 2)
            indices[size++] = id
        }

        fun forEach(action: (Int) -> Unit) {
            for (i in 0 until size) action(indices[i])
        }
    }

    val isEmpty: Boolean get() = originals.isEmpty() && templates.isEmpty()

    fun exactRecords(text: String): Set<String> {
        val key = screenTextKey(text)
        val candidates = exactCandidates(key, text) ?: return emptySet()
        // Generic words are poor anchors for names and descriptions.
        return candidates.mapTo(linkedSetOf()) { it.record }
            .takeIf { it.size <= 24 }.orEmpty()
    }

    fun match(text: String, anchors: Set<String>): Match? {
        if (text.length > 1_600) return null
        val key = screenTextKey(text)
        if (key.length < 3) return null
        val possibleKey = if (key.length >= 16 && text.trimStart('·', '•', ' ').startsWith('l')) {
            "i" + key.drop(1)
        } else {
            key
        }
        val exact = exactCandidates(key, text) ?: exactCandidates(possibleKey, text)
        if (exact != null) {
            val dictionary = exact.filterNot { it.dialogue }
            val hasStoryContext = exact.any { it.record in anchors && it.dialogue }
            val eligible = when {
                hasStoryContext -> exact
                key.length <= 32 && dictionary.isNotEmpty() -> dictionary
                key.length <= 8 -> emptyList()
                else -> exact
            }
            return choose(eligible.map { Hit(it, it.translated, 1.0) }, anchors)
        }

        val dynamic = matchTemplates(key).ifEmpty { matchTemplates(possibleKey) }
        if (dynamic.isNotEmpty()) return choose(dynamic, anchors)

        if (key.length !in MIN_FUZZY_LENGTH..MAX_FUZZY_LENGTH) return null
        val hits = hashMapOf<Int, Int>()
        val fragments = (0..key.length - FRAGMENT_LENGTH).map { fragment(key, it) }.toHashSet()
        for (hash in fragments) {
            val postings = byFragment[hash] ?: continue
            if (postings.size > MAX_POSTINGS) continue
            postings.forEach { id -> hits[id] = (hits[id] ?: 0) + 1 }
        }

        val signature = numberSignature(key)
        val budget = if (key.length < 28) 1 else (key.length * 0.15).toInt()
        val fuzzy = hits.entries.asSequence()
            .filter { (id, _) ->
                val candidate = fuzzyKeys[id]
                abs(candidate.length - key.length) <= budget && numberSignature(candidate) == signature
            }
            .sortedByDescending { it.value }
            .take(100)
            .flatMap { (id, _) ->
                val candidate = fuzzyKeys[id]
                val distance = editDistance(key, candidate, budget)
                if (distance > budget) emptySequence()
                else {
                    val score = 1.0 - distance.toDouble() / maxOf(key.length, candidate.length)
                    originals.getValue(candidate).asSequence().map { Hit(it, it.translated, score) }
                }
            }.toList()
        return choose(fuzzy, anchors)
    }

    private fun matchTemplates(key: String): List<Hit> =
        templates[key.take(TEMPLATE_PREFIX_LENGTH)].orEmpty().mapNotNull { template ->
            val match = template.pattern.matchEntire(key) ?: return@mapNotNull null
            val values = template.ids.zip(match.groupValues.drop(1))
            val substitutions = values.toMap()
            if (values.any { (id, value) -> substitutions[id] != value }) return@mapNotNull null
            val translation = placeholders.replace(template.pair.translated) {
                substitutions[it.groupValues[1]] ?: it.value
            }
            if (placeholders.containsMatchIn(translation)) return@mapNotNull null
            Hit(template.pair, translation, 1.0)
        }

    private fun exactCandidates(key: String, ocr: String): List<ScreenTextPair>? {
        val candidates = originals[key] ?: return null
        // A heading and a bullet in a description can normalize to the same words.
        val marker = leadingMarker(ocr)
        return candidates.filter { leadingMarker(it.english) == marker }.ifEmpty { candidates }
    }

    private fun leadingMarker(text: String): Char? = text.trimStart().firstOrNull()?.let { first ->
        when (first) {
            '-', '·', '•' -> '-'
            '[', '(', '※', '"' -> first
            else -> null
        }
    }

    private fun choose(hits: List<Hit>, anchors: Set<String>): Match? {
        if (hits.isEmpty()) return null
        fun rank(hit: Hit) = hit.score + if (hit.pair.record in anchors) 0.08 else 0.0
        val ordered = hits.sortedByDescending(::rank)
        val winner = ordered.first()
        val conflicting = ordered.firstOrNull {
            !it.translation.equals(winner.translation, ignoreCase = true)
        }
        if (conflicting != null && rank(winner) - rank(conflicting) < 0.035) return null
        val changed = !winner.translation.trim().equals(winner.pair.english.trim(), ignoreCase = true)
        return Match(winner.translation, winner.score, changed)
    }

    private fun template(pair: ScreenTextPair): Template? {
        val matches = placeholders.findAll(pair.english).toList()
        val prefix = screenTextKey(pair.english.substring(0, matches.first().range.first))
        if (prefix.length < TEMPLATE_PREFIX_LENGTH || pair.english.length > MAX_FUZZY_LENGTH) return null
        val pattern = buildString {
            var start = 0
            for (match in matches) {
                append(Regex.escape(screenTextKey(pair.english.substring(start, match.range.first))))
                append("([0-9]{1,9})")
                start = match.range.last + 1
                // Preserve percent signs in a template, as for literal numbers.
                if (pair.english.getOrNull(start) == '%') {
                    append('%')
                    start++
                }
            }
            append(Regex.escape(screenTextKey(pair.english.substring(start))))
        }
        return Template(pair, prefix, Regex(pattern), matches.map { it.groupValues[1] })
    }

    private fun fragment(key: String, start: Int): Int {
        var hash = 0
        for (i in start until start + FRAGMENT_LENGTH) hash = 31 * hash + key[i].code
        return hash
    }

    private fun numberSignature(key: String): List<String> =
        numbers.findAll(key).map { it.value }.toList()

    private fun editDistance(a: String, b: String, budget: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var row = IntArray(b.length + 1)
        for (i in a.indices) {
            row[0] = i + 1
            var min = row[0]
            for (j in b.indices) {
                val substitution = prev[j] + if (a[i] == b[j]) 0 else 1
                row[j + 1] = minOf(row[j] + 1, prev[j + 1] + 1, substitution)
                min = minOf(min, row[j + 1])
            }
            if (min > budget) return min
            val swap = prev
            prev = row
            row = swap
        }
        return prev[b.length]
    }

    private companion object {
        const val TEMPLATE_PREFIX_LENGTH = 6
        const val FRAGMENT_LENGTH = 6
        const val MIN_FUZZY_LENGTH = 12
        const val MAX_FUZZY_LENGTH = 480
        const val MAX_POSTINGS = 2_500
    }
}
