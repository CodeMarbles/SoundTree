package com.treecast.app.ui

/**
 * Controls when the Mini Recorder widget is shown above the bottom nav.
 *
 * NEVER          — always hidden
 * WHILE_RECORDING — visible only while a recording is active (RECORDING or PAUSED)
 * ALWAYS         — always visible
 */
enum class RecorderWidgetVisibility {
    NEVER, WHILE_RECORDING, ALWAYS;

    companion object {
        fun fromString(s: String?) = values().firstOrNull { it.name == s } ?: WHILE_RECORDING
    }
}