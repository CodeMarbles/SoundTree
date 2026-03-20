package com.treecast.app.util

/**
 * Pure stateless mark-jump resolution. No Android dependencies.
 * Canonical source of truth for "where does a jump land?" —
 * used by both MainViewModel and PlaybackService.
 */
object MarkJumpLogic {

    /** SharedPreferences key — referenced by both VM and Service. */
    const val PREF_REWIND_THRESHOLD_SECS = "mark_rewind_threshold_secs"
    const val DEFAULT_REWIND_THRESHOLD_SECS = 2f

    /** Marks within this window ahead of the playhead are treated as "not ahead". */
    const val FORWARD_DEAD_ZONE_MS = 500L

    sealed class JumpTarget {
        data class ToMark(val positionMs: Long) : JumpTarget()
        object ToTrackStart : JumpTarget()  // prev fallback — no marks behind
        object NoTarget : JumpTarget()      // next only — nothing ahead
    }

    fun findTarget(
        marks: List<Long>,          // positionMs values only
        currentPositionMs: Long,
        forward: Boolean,
        rewindThresholdMs: Long
    ): JumpTarget = if (forward) {
        marks
            .filter { it > currentPositionMs + FORWARD_DEAD_ZONE_MS }
            .minOrNull()
            ?.let { JumpTarget.ToMark(it) }
            ?: JumpTarget.NoTarget
    } else {
        marks
            .filter { it < currentPositionMs - rewindThresholdMs }
            .maxOrNull()
            ?.let { JumpTarget.ToMark(it) }
            ?: JumpTarget.ToTrackStart
    }
}