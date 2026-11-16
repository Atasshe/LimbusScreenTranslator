package com.kimght.limbusscreentranslator.overlay

import com.kimght.limbusscreentranslator.domain.model.DialogueLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutsideTouchNavigationTest {
    private val reading = OverlayUiState(
        present = true,
        advanceOnScreenTouch = true,
        episodeKey = "7-1",
        lines = listOf(
            DialogueLine(index = 0, speakerName = null, title = null, place = null, text = "Hello"),
        ),
    )

    @Test
    fun `outside touches navigate only while reading an enabled dialogue panel`() {
        assertTrue(shouldAdvanceOnOutsideTouch(reading))
        listOf(
            reading.copy(advanceOnScreenTouch = false),
            reading.copy(present = false),
            reading.copy(minimized = true),
            reading.copy(portrait = true),
            reading.copy(mode = OverlayMode.CHAPTER),
            reading.copy(mode = OverlayMode.RESIZE),
            reading.copy(noActiveLocalization = true),
            reading.copy(episodeUnavailable = true),
            reading.copy(episodeKey = null),
            reading.copy(lines = emptyList()),
        ).forEach { assertFalse(shouldAdvanceOnOutsideTouch(it)) }
    }
}
