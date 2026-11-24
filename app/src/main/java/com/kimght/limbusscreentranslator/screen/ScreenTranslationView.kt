package com.kimght.limbusscreentranslator.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.kimght.limbusscreentranslator.domain.screen.ScreenReplacement
import kotlin.math.floor

internal class ScreenTranslationView(context: Context) : View(context) {
    private data class Label(val box: RectF, val layout: StaticLayout)

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff140f0c.toInt() }
    private var labels: List<Label> = emptyList()
    private var frame: ScreenTranslationFrame? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun show(next: ScreenTranslationFrame?) {
        if (frame == next) return
        frame = next
        labels = next?.replacements?.mapNotNull { fit(it, next.width, next.height) }.orEmpty()
        visibility = if (labels.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        canvas.save()
        canvas.scale(width.toFloat() / current.width, height.toFloat() / current.height)
        for ((box, text) in labels) {
            canvas.drawRoundRect(box, 2f, 2f, background)
            canvas.save()
            canvas.clipRect(box)
            canvas.translate(box.left + HORIZONTAL_PADDING, box.centerY() - text.height / 2f)
            text.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun fit(value: ScreenReplacement, frameWidth: Int, frameHeight: Int): Label? {
        val source = value.bounds
        val box = RectF(
            (source.left - 3).coerceIn(0, frameWidth).toFloat(),
            (source.top - 2).coerceIn(0, frameHeight).toFloat(),
            (source.right + 3).coerceIn(0, frameWidth).toFloat(),
            (source.bottom + 2).coerceIn(0, frameHeight).toFloat(),
        )
        val availableWidth = floor(box.width() - 2 * HORIZONTAL_PADDING).toInt()
        if (availableWidth < 10 || box.height() < 8) return null
        val text = value.text.replace(Regex("""\s+"""), " ").trim()
        val estimatedSize = box.height() / value.sourceLines
        val minSize = maxOf(7f, estimatedSize * 0.45f)
        fun layout(size: Float): StaticLayout {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xfff9e8c9.toInt()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = size
            }
            val alignment = if (value.centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
        }

        var best = layout(minSize)
        // Leave the source visible when a replacement would become illegible.
        if (best.height > box.height()) return null
        var low = minSize
        var high = maxOf(minSize, estimatedSize * 0.92f)
        repeat(6) {
            val mid = (low + high) / 2
            val attempt = layout(mid)
            if (attempt.height <= box.height()) {
                best = attempt
                low = mid
            } else {
                high = mid
            }
        }
        return Label(box, best)
    }

    private companion object {
        const val HORIZONTAL_PADDING = 2f
    }
}
