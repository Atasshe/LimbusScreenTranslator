package com.kimght.limbusscreentranslator.domain.screen

import kotlin.math.abs

internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0

    fun union(other: ScreenRect): ScreenRect = ScreenRect(
        minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom),
    )

    fun overlaps(other: ScreenRect): Boolean {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return w > 0 && h > 0 && w * h > minOf(width * height, other.width * other.height) * 0.2
    }
}

internal data class ScreenOcrLine(val text: String, val bounds: ScreenRect)

internal data class ScreenReplacement(
    val text: String,
    val bounds: ScreenRect,
    val sourceLines: Int,
    val centered: Boolean,
)

internal object ScreenTextLayout {
    private data class Region(val lines: List<ScreenOcrLine>) {
        val text: String = lines.joinToString(" ") { it.text }
        val bounds: ScreenRect = lines.map { it.bounds }.reduce(ScreenRect::union)
        val centered: Boolean = lines.size == 1 || lines.all {
            abs(it.bounds.centerX - bounds.centerX) < bounds.height * 0.8
        }
    }

    fun translate(lines: List<ScreenOcrLine>, index: ScreenTextIndex): List<ScreenReplacement> {
        val visible = lines.asSequence()
            .filter {
                it.bounds.width > 0 && it.bounds.height > 4 && it.text.length >= 2 &&
                    it.bounds.width <= (it.text.length + 1) * it.bounds.height * 1.2
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(180)
            .toList()
        val joinedRows = joinFragments(visible)
        val regions = (regions(visible) + regions(joinedRows)).distinctBy { it.text to it.bounds }
        val anchors = regions.mapNotNull {
            val records = index.exactRecords(it.text)
            if (records.isEmpty()) null else Triple(it.bounds, screenTextKey(it.text), records)
        }

        val matches = regions.mapNotNull { region ->
            val key = screenTextKey(region.text)
            val nearRecords = anchors.asSequence()
                .filter { (bounds, otherKey, _) -> otherKey != key && nearby(bounds, region.bounds) }
                .flatMap { (_, _, records) -> records.asSequence() }
                .toSet()
            val match = index.match(region.text, nearRecords) ?: return@mapNotNull null
            if (!match.changed) return@mapNotNull null
            region to match
        }.sortedWith(
            compareByDescending<Pair<Region, ScreenTextIndex.Match>> { (region, match) ->
                match.score + minOf(region.text.length, 160) * 0.00055
            }.thenByDescending { it.first.lines.size },
        )

        val replacements = mutableListOf<ScreenReplacement>()
        for ((region, match) in matches) {
            if (replacements.none { it.bounds.overlaps(region.bounds) }) {
                replacements += ScreenReplacement(
                    match.translation, region.bounds, region.lines.size, region.centered,
                )
            }
        }
        return replacements
    }

    private fun nearby(a: ScreenRect, b: ScreenRect): Boolean {
        val gap = maxOf(a.top - b.bottom, b.top - a.bottom, 0)
        val horizontalGap = maxOf(a.left - b.right, b.left - a.right, 0)
        val unit = minOf(a.height, b.height).coerceAtLeast(12)
        return gap <= unit * 8 && horizontalGap <= unit * 5 && !a.overlaps(b)
    }

    private fun joinFragments(lines: List<ScreenOcrLine>): List<ScreenOcrLine> {
        val rows = lines.toMutableList()
        for (start in rows.indices) {
            var current = rows[start]
            while (true) {
                val a = current.bounds
                val next = rows.indices.filter { it > start }.filter { index ->
                    val b = rows[index].bounds
                    val unit = minOf(a.height, b.height)
                    b.left - a.right in -unit..(unit * 1.7).toInt() &&
                        abs(a.centerY - b.centerY) <= unit * 0.65
                }.minByOrNull { rows[it].bounds.left } ?: break
                val fragment = rows.removeAt(next)
                current = ScreenOcrLine(current.text + " " + fragment.text, a.union(fragment.bounds))
                rows[start] = current
            }
            // The list shrinks when fragments are joined.
            if (start >= rows.lastIndex) break
        }
        return rows
    }

    private fun regions(lines: List<ScreenOcrLine>): List<Region> = buildList {
        for (line in lines) {
            val group = mutableListOf(line)
            add(Region(group.toList()))
            while (group.size < 5) {
                val current = group.last().bounds
                val next = lines.filter { follows(current, it.bounds) }
                    .minByOrNull { it.bounds.top } ?: break
                group += next
                add(Region(group.toList()))
            }
        }
    }

    private fun follows(a: ScreenRect, b: ScreenRect): Boolean {
        val unit = minOf(a.height, b.height)
        val dy = b.centerY - a.centerY
        if (dy < maxOf(a.height, b.height) * 0.70 || dy > unit * 2.7) return false
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        return overlap >= minOf(a.width, b.width) * 0.56 &&
            (abs(a.left - b.left) <= unit * 2.2 || abs(a.centerX - b.centerX) <= unit * 2.4)
    }
}
