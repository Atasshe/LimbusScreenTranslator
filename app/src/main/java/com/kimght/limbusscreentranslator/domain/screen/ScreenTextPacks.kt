package com.kimght.limbusscreentranslator.domain.screen

import com.kimght.limbusscreentranslator.domain.markup.DialogueMarkup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class ScreenTextPair(
    val english: String,
    val translated: String,
    val record: String,
    val dialogue: Boolean = false,
)

internal object ScreenTextPacks {
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private val json = Json { isLenient = true }
    private val token = Regex("""\[([A-Za-z][A-Za-z0-9_]{1,64})]""")
    private val sprite = Regex("""<sprite\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val textFields = setOf(
        "content", "name", "nameWithTitle", "title", "subtitle", "desc", "description",
        "dlg", "flavor", "summary", "teller", "place", "panicName", "panicDescription",
        "lowMoraleDescription", "add", "min", "text",
    )

    fun read(
        english: ZipFile,
        englishPrefix: String,
        translated: ZipFile,
        translatedPrefix: String,
        checkCancelled: () -> Unit = {},
    ): List<ScreenTextPair> {
        val originalFiles = files(english, englishPrefix)
        val translatedFiles = files(translated, translatedPrefix)
        val englishTerms = terms(english, originalFiles)
        val translatedTerms = terms(translated, translatedFiles)
        val pairs = linkedSetOf<ScreenTextPair>()
        for ((path, entry) in originalFiles) {
            checkCancelled()
            val translatedEntry = translatedFiles[path] ?: continue
            val original = readRows(english, entry) ?: continue
            val target = readRows(translated, translatedEntry) ?: continue
            for ((id, left, right) in pairedRows(original, target, "id", allowPosition = false)) {
                val story = path.startsWith("storydata/")
                val dialogue = story || path.startsWith("personalityvoicedlg/") ||
                    path.startsWith("battleannouncerdlg/") || path.startsWith("battlespeechbubble")
                val record = if (story) path else "$path#$id"
                collectStrings(left, right) { a, b ->
                    val source = displayText(a, englishTerms)
                    val translation = displayText(b, translatedTerms)
                    if (source.isBlank() || translation.isBlank()) return@collectStrings
                    if (source.length <= 1_200 && translation.length <= 1_600 &&
                        (!source.equals(translation, ignoreCase = true) || (!dialogue && source.length <= 24))
                    ) {
                        pairs += ScreenTextPair(source, translation, record, dialogue)
                    }
                    // Skill panels scroll, so match complete visible effects independently.
                    val sourceLines = source.lines().map(String::trim).filter(String::isNotEmpty)
                    val targetLines = translation.lines().map(String::trim).filter(String::isNotEmpty)
                    if (sourceLines.size > 1 && sourceLines.size == targetLines.size) {
                        sourceLines.zip(targetLines).forEach { (en, local) ->
                            if (en.length <= 480 && local.length <= 660 && !en.equals(local, ignoreCase = true)) {
                                pairs += ScreenTextPair(en, local, record, dialogue)
                            }
                        }
                    }
                }
            }
        }
        return pairs.toList()
    }

    private fun files(archive: ZipFile, prefix: String): Map<String, ZipEntry> =
        archive.entries().asSequence()
            .filter { !it.isDirectory && it.size in 1..MAX_JSON_BYTES && it.name.startsWith(prefix) }
            .map { entry ->
                val path = entry.name.removePrefix(prefix)
                val normalized = path.substringBeforeLast('/', "").let { folder ->
                    val name = path.substringAfterLast('/').removePrefix("EN_")
                    if (folder.isEmpty()) name else "$folder/$name"
                }.lowercase(Locale.ROOT)
                normalized to entry
            }
            .filter { (path, _) -> path.endsWith(".json") }
            .toMap()

    private fun readRows(archive: ZipFile, entry: ZipEntry): JsonArray? {
        val source = archive.getInputStream(entry).bufferedReader().use { it.readText() }
        // Some packs include unrelated config JSON alongside the game strings.
        val root = try {
            json.parseToJsonElement(source.removePrefix("\uFEFF")) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        return root?.get("dataList") as? JsonArray
    }

    private fun terms(archive: ZipFile, files: Map<String, ZipEntry>): Map<String, String> =
        buildMap {
            // Later dictionaries take precedence. The game displays the name for these IDs.
            val keys = files.keys.filter { it.startsWith("battlekeywords") }.sorted()
            for (name in listOf("bufs.json") + keys + "skilltag.json") {
                val entry = files[name] ?: continue
                for (row in readRows(archive, entry).orEmpty()) {
                    val id = field(row, "id") ?: continue
                    val value = field(row, "name") ?: continue
                    if (value.isNotBlank()) put(id, value)
                }
            }
        }

    private fun displayText(raw: String, terms: Map<String, String>): String {
        val expanded = token.replace(raw) { match -> terms[match.groupValues[1]] ?: match.value }
        return DialogueMarkup.parse(sprite.replace(expanded, ""))
            .joinToString("") { it.text }
            .replace("\u00A0", " ")
            .trim()
    }

    private fun field(row: JsonElement, key: String): String? =
        (((row as? JsonObject)?.get(key)) as? JsonPrimitive)?.content
            ?.takeUnless { it.isBlank() || it == "null" }

    private data class PairedRow(val id: String, val english: JsonElement, val translated: JsonElement)

    private fun pairedRows(
        english: JsonArray,
        translated: JsonArray,
        key: String,
        allowPosition: Boolean,
    ): List<PairedRow> {
        val originalIds = english.map { field(it, key) }
        val targetIds = translated.map { field(it, key) }
        if (originalIds.all { it == null } && targetIds.all { it == null }) {
            if (!allowPosition || english.size != translated.size) return emptyList()
            return english.indices.map { PairedRow(it.toString(), english[it], translated[it]) }
        }

        val targetById = translated.zip(targetIds).groupBy({ it.second }, { it.first })
        val sourceCounts = originalIds.groupingBy { it }.eachCount()
        return english.zip(originalIds).mapNotNull { (row, id) ->
            if (id == null || sourceCounts[id] != 1) return@mapNotNull null
            val local = targetById[id]?.singleOrNull() ?: return@mapNotNull null
            PairedRow(id, row, local)
        }
    }

    private fun collectStrings(
        english: JsonElement,
        translated: JsonElement,
        fieldName: String? = null,
        emit: (String, String) -> Unit,
    ) {
        when {
            english is JsonObject && translated is JsonObject -> {
                for ((key, value) in english) {
                    val local = translated[key] ?: continue
                    collectStrings(value, local, key, emit)
                }
            }

            english is JsonArray && translated is JsonArray -> {
                val key = if (fieldName == "levelList") "level" else "id"
                for ((_, en, local) in pairedRows(english, translated, key, allowPosition = true)) {
                    collectStrings(en, local, fieldName, emit)
                }
            }

            fieldName in textFields && english is JsonPrimitive && translated is JsonPrimitive &&
                english.isString && translated.isString -> emit(english.content, translated.content)
        }
    }
}
