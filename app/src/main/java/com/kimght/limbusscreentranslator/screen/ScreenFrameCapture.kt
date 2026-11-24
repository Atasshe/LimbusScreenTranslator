package com.kimght.limbusscreentranslator.screen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kimght.limbusscreentranslator.domain.screen.ScreenOcrLine
import com.kimght.limbusscreentranslator.domain.screen.ScreenRect
import com.kimght.limbusscreentranslator.domain.screen.ScreenReplacement
import com.kimght.limbusscreentranslator.domain.screen.ScreenTextIndex
import com.kimght.limbusscreentranslator.domain.screen.ScreenTextLayout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal data class ScreenTranslationFrame(
    val width: Int,
    val height: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val replacements: List<ScreenReplacement>,
)

@RequiresApi(34)
internal class ScreenFrameCapture(
    private val projection: MediaProjection,
    private val densityDpi: Int,
    private val canRender: (Int, Int) -> Boolean,
    private val onFrame: (ScreenTranslationFrame?) -> Unit,
    private val onCaptureStopped: () -> Unit,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private data class Source(val reader: ImageReader, val width: Int, val height: Int)

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("screen-ocr").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private var nextScan = 0L
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var source: Source? = null
    @Volatile private var index: ScreenTextIndex? = null
    @Volatile private var canReadSource = false
    @Volatile private var revision = 0
    @Volatile private var closed = false
    private var visible = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = onCaptureStopped()

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            updateVisibility()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (closed || width <= 0 || height <= 0) return
            if (source?.width != width || source?.height != height) {
                try {
                    replaceSource(width, height)
                } catch (_: IllegalStateException) {
                    onFailure()
                }
            } else {
                updateVisibility()
            }
        }
    }

    fun start(width: Int, height: Int) {
        projection.registerCallback(callback, main)
        replaceSource(width, height)
    }

    fun setIndex(newIndex: ScreenTextIndex?) {
        index = newIndex
        revision++
        onFrame(null)
    }

    fun updateVisibility() {
        val dimensions = source
        canReadSource = visible && dimensions != null && canRender(dimensions.width, dimensions.height)
        revision++
        onFrame(null)
    }

    private fun replaceSource(width: Int, height: Int) {
        val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / maxOf(width, height))
        val reader = ImageReader.newInstance(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            PixelFormat.RGBA_8888,
            3,
        )
        val oldSource = source
        val newSource = Source(reader, width, height)
        source = newSource
        updateVisibility()

        try {
            val display = virtualDisplay
            if (display == null) {
                virtualDisplay = checkNotNull(projection.createVirtualDisplay(
                    "Limbus screen translation",
                    reader.width, reader.height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null,
                ))
            } else {
                display.resize(reader.width, reader.height, densityDpi)
                display.surface = reader.surface
            }
            reader.setOnImageAvailableListener({ read(newSource) }, worker)
        } finally {
            oldSource?.let { closeReader(it.reader) }
        }
    }

    private fun read(owner: Source) {
        val reader = owner.reader
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        image.use {
            val dictionary = index ?: return
            val now = SystemClock.elapsedRealtime()
            if (closed || owner !== source || !canReadSource || now < nextScan ||
                !busy.compareAndSet(false, true)
            ) return

            nextScan = now + SCAN_INTERVAL
            val frameRevision = revision
            val bitmap = try {
                image.toBitmap()
            } catch (_: RuntimeException) {
                busy.set(false)
                return
            }

            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnCompleteListener(Executor { it.run() }) { task ->
                        try {
                            if (!isCurrent(owner, frameRevision) || index !== dictionary) {
                                return@addOnCompleteListener
                            }
                            if (!task.isSuccessful) {
                                main.post { if (!closed) onFailure() }
                                return@addOnCompleteListener
                            }
                            val visibleText = ocrLines(task.result)
                            val replacements = ScreenTextLayout.translate(visibleText, dictionary)
                            val frame = ScreenTranslationFrame(
                                owner.reader.width, owner.reader.height, owner.width, owner.height, replacements,
                            )
                            main.post { if (isCurrent(owner, frameRevision)) onFrame(frame) }
                        } finally {
                            bitmap.recycle()
                            busy.set(false)
                        }
                    }
            } catch (_: RuntimeException) {
                bitmap.recycle()
                busy.set(false)
                main.post { if (!closed) onFailure() }
            }
        }
    }

    private fun isCurrent(owner: Source, frameRevision: Int): Boolean =
        !closed && canReadSource && owner === source && frameRevision == revision

    private fun ocrLines(text: Text): List<ScreenOcrLine> = buildList {
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bounds = line.boundingBox ?: continue
                add(ScreenOcrLine(line.text, bounds.asScreenRect()))

                // Keep counters next to buttons and skill titles visible.
                val words = line.elements
                if (words.size >= 2 && COUNTER.matches(words.last().text)) {
                    val label = words.dropLast(1)
                    val boxes = label.mapNotNull { it.boundingBox }
                    if (boxes.size != label.size) continue
                    val labelBounds = boxes.map { it.asScreenRect() }.reduce(ScreenRect::union)
                    add(ScreenOcrLine(label.joinToString(" ") { it.text }, labelBounds))
                }
            }
        }
    }

    private fun Rect.asScreenRect() = ScreenRect(left, top, right, bottom)

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val paddedWidth = plane.rowStride / plane.pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(plane.buffer)
            return if (paddedWidth == width) bitmap else {
                Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
            }
        } catch (e: RuntimeException) {
            bitmap.recycle()
            throw e
        }
    }

    private fun closeReader(reader: ImageReader) {
        reader.setOnImageAvailableListener(null, null)
        worker.post { reader.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        canReadSource = false
        index = null
        projection.unregisterCallback(callback)
        virtualDisplay?.release()
        virtualDisplay = null
        source?.reader?.let(::closeReader)
        source = null
        projection.stop()
        worker.post { recognizer.close() }
        workerThread.quitSafely()
    }

    private companion object {
        const val MAX_LONG_EDGE = 1800
        const val SCAN_INTERVAL = 850L
        val COUNTER = Regex("""[×x]?\d{1,8}""", RegexOption.IGNORE_CASE)
    }
}
