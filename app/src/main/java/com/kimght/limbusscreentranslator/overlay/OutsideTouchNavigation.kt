package com.kimght.limbusscreentranslator.overlay

/** Outside touches carry only the first down event, not a complete gesture. */
internal fun shouldAdvanceOnOutsideTouch(state: OverlayUiState): Boolean =
    state.advanceOnScreenTouch &&
        state.present &&
        !state.minimized &&
        !state.portrait &&
        state.mode == OverlayMode.DIALOGUE &&
        !state.noActiveLocalization &&
        !state.episodeUnavailable &&
        state.episodeKey != null &&
        state.lines.isNotEmpty()
