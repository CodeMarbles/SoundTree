package app.treecast.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Settings.kt
//
// Extension functions on MainViewModel covering SharedPreferences-backed
// settings and ephemeral UI state
// ─────────────────────────────────────────────────────────────────────────────

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import app.treecast.ui.MainViewModel.Companion.PREF_ALWAYS_SHOW_PLAYER_PILL
import app.treecast.ui.MainViewModel.Companion.PREF_ALWAYS_SHOW_RECORDER_PILL
import app.treecast.ui.MainViewModel.Companion.PREF_AUTO_NAVIGATE
import app.treecast.ui.MainViewModel.Companion.PREF_FUTURE_MODE
import app.treecast.ui.MainViewModel.Companion.PREF_HIDE_PLAYER_ON_LISTEN_TAB
import app.treecast.ui.MainViewModel.Companion.PREF_HIDE_RECORDER_ON_RECORD_TAB
import app.treecast.ui.MainViewModel.Companion.PREF_JUMP_TO_LIBRARY
import app.treecast.ui.MainViewModel.Companion.PREF_LAST_SESSION_OPENED_AT
import app.treecast.ui.MainViewModel.Companion.PREF_LAYOUT_ORDER
import app.treecast.ui.MainViewModel.Companion.PREF_MARK_NUDGE_SECS
import app.treecast.ui.MainViewModel.Companion.PREF_PLAYBACK_SPEED
import app.treecast.ui.MainViewModel.Companion.PREF_PLAYER_WIDGET_VISIBILITY
import app.treecast.ui.MainViewModel.Companion.PREF_RECORDER_WIDGET_VISIBILITY
import app.treecast.ui.MainViewModel.Companion.PREF_SCRUB_BACK_SECS
import app.treecast.ui.MainViewModel.Companion.PREF_SCRUB_FORWARD_SECS
import app.treecast.ui.MainViewModel.Companion.PREF_SHOW_TITLE_BAR
import app.treecast.ui.MainViewModel.Companion.PREF_THEME_MODE
import app.treecast.ui.MainViewModel.Companion.SPEED_MAX
import app.treecast.ui.MainViewModel.Companion.SPEED_MIN
import app.treecast.ui.MainViewModel.Companion.SPEED_STEP
import app.treecast.util.MarkJumpLogic
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Session ───────────────────────────────────────────────────────────────────

fun MainViewModel.onAppClose() = viewModelScope.launch {
    saveCurrentPosition()
    prefs.edit().putLong(PREF_LAST_SESSION_OPENED_AT, System.currentTimeMillis()).apply()
}

/**
 * Last session logic used to drive what the default landing page is when opening the app
 */
fun MainViewModel.getLastSessionOpenedAt(): Long? {
    val v = prefs.getLong(PREF_LAST_SESSION_OPENED_AT, -1L)
    return if (v == -1L) null else v
}

suspend fun MainViewModel.getTotalRecordingTime() = repo.getTotalRecordingTime()

// ── Theme ─────────────────────────────────────────────────────────────────────

fun MainViewModel.setThemeMode(mode: String) {
    _themeMode.value = mode
    prefs.edit().putString(PREF_THEME_MODE, mode).apply()
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}

// ── Navigation prefs ──────────────────────────────────────────────────────────

fun MainViewModel.setAutoNavigateToListen(enabled: Boolean) {
    _autoNavigateToListen.value = enabled
    prefs.edit().putBoolean(PREF_AUTO_NAVIGATE, enabled).apply()
}

fun MainViewModel.setJumpToLibraryOnSave(enabled: Boolean) {
    _jumpToLibraryOnSave.value = enabled
    prefs.edit().putBoolean(PREF_JUMP_TO_LIBRARY, enabled).apply()
}

// ── Playback control prefs ────────────────────────────────────────────────────

fun MainViewModel.setScrubBackSecs(secs: Int) {
    val v = secs.coerceAtLeast(1)
    _scrubBackSecs.value = v
    prefs.edit().putInt(PREF_SCRUB_BACK_SECS, v).apply()
}

fun MainViewModel.setScrubForwardSecs(secs: Int) {
    val v = secs.coerceAtLeast(1)
    _scrubForwardSecs.value = v
    prefs.edit().putInt(PREF_SCRUB_FORWARD_SECS, v).apply()
}

fun MainViewModel.setPlaybackSpeed(speed: Float) {
    // Round to nearest 0.05 step to avoid floating-point drift
    // (e.g. slider producing 1.2999999 instead of 1.30).
    val rounded = (speed / SPEED_STEP).roundToInt() * SPEED_STEP
    val clamped = rounded.coerceIn(SPEED_MIN, SPEED_MAX)
    _playbackSpeed.value = clamped
    prefs.edit().putFloat(PREF_PLAYBACK_SPEED, clamped).apply()
    mediaController?.setPlaybackParameters(PlaybackParameters(clamped))
}

fun MainViewModel.setMarkNudgeSecs(secs: Float) {
    val v = secs.coerceIn(1f, 30f)
    _markNudgeSecs.value = v
    prefs.edit().putFloat(PREF_MARK_NUDGE_SECS, v).apply()
}

fun MainViewModel.setMarkRewindThresholdSecs(secs: Float) {
    val v = secs.coerceIn(0.5f, 5.0f)
    _markRewindThresholdSecs.value = v
    prefs.edit().putFloat(MarkJumpLogic.PREF_REWIND_THRESHOLD_SECS, v).apply()
}

// ── Layout / chrome prefs ─────────────────────────────────────────────────────

fun MainViewModel.setLayoutOrder(order: List<LayoutElement>) {
    _layoutOrder.value = order
    prefs.edit().putString(PREF_LAYOUT_ORDER, LayoutElement.toOrderString(order)).apply()
}

fun MainViewModel.setShowTitleBar(show: Boolean) {
    _showTitleBar.value = show
    prefs.edit().putBoolean(PREF_SHOW_TITLE_BAR, show).apply()
}

// ── Widget visibility prefs ───────────────────────────────────────────────────

fun MainViewModel.setRecorderWidgetVisibility(mode: RecorderWidgetVisibility) {
    _recorderWidgetVisibility.value = mode
    prefs.edit().putString(PREF_RECORDER_WIDGET_VISIBILITY, mode.name).apply()
}

fun MainViewModel.setPlayerWidgetVisibility(mode: PlayerWidgetVisibility) {
    _playerWidgetVisibility.value = mode
    prefs.edit().putString(PREF_PLAYER_WIDGET_VISIBILITY, mode.name).apply()
}

fun MainViewModel.setAlwaysShowPlayerPill(show: Boolean) {
    _alwaysShowPlayerPill.value = show
    prefs.edit().putBoolean(PREF_ALWAYS_SHOW_PLAYER_PILL, show).apply()
}

fun MainViewModel.setAlwaysShowRecorderPill(show: Boolean) {
    _alwaysShowRecorderPill.value = show
    prefs.edit().putBoolean(PREF_ALWAYS_SHOW_RECORDER_PILL, show).apply()
}

fun MainViewModel.setHideRecorderOnRecordTab(hide: Boolean) {
    _hideRecorderOnRecordTab.value = hide
    prefs.edit().putBoolean(PREF_HIDE_RECORDER_ON_RECORD_TAB, hide).apply()
}

fun MainViewModel.setHidePlayerOnListenTab(hide: Boolean) {
    _hidePlayerOnListenTab.value = hide
    prefs.edit().putBoolean(PREF_HIDE_PLAYER_ON_LISTEN_TAB, hide).apply()
}

// ── Ephemeral UI state ────────────────────────────────────────────────────────

fun MainViewModel.setTopTitle(title: String) { _topTitle.value = title }

fun MainViewModel.setCurrentPage(page: Int) { _currentPage.value = page }

fun MainViewModel.selectRecording(id: Long) { _selectedRecordingId.value = id }

/**
 * Called by [LibraryFragment.openTopicDetails] and [LibraryFragment.navigateToTopicDetails]
 * to set which topic the Details tab should display.
 * Passing null clears the selection (Details tab becomes greyed out again).
 */
fun MainViewModel.setLibraryDetailsTopic(id: Long?) { _libraryDetailsTopicId.value = id }

fun MainViewModel.setLocked(locked: Boolean) { _isLocked.value = locked }

fun MainViewModel.setPlayerPillMinimized(minimized: Boolean) {
    _playerPillMinimized.value = minimized
}

fun MainViewModel.setRecorderPillMinimized(minimized: Boolean) {
    _recorderPillMinimized.value = minimized
}

// ── Dev options ───────────────────────────────────────────────────────────────

fun MainViewModel.setFutureMode(enabled: Boolean) {
    _futureMode.value = enabled
    prefs.edit().putBoolean(PREF_FUTURE_MODE, enabled).apply()
}