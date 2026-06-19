package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Storage.kt
//
// Extension functions on MainViewModel covering storage volume management
// ─────────────────────────────────────────────────────────────────────────────

import androidx.lifecycle.viewModelScope
import app.soundtree.storage.AppVolume
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.MainViewModel.Companion.PREF_DEFAULT_STORAGE_UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Persists the user's preferred storage volume.
 * [RecordFragment] reads [defaultStorageUuid] before starting a recording
 * to resolve the output directory.
 */
fun MainViewModel.setDefaultStorageUuid(uuid: String) {
    _defaultStorageUuid.value = uuid
    prefs.edit().putString(PREF_DEFAULT_STORAGE_UUID, uuid).apply()
}

/**
 * Resolves the [AppVolume] the next recording should be written to.
 * Falls back gracefully if the preferred volume is currently unmounted.
 */
fun MainViewModel.resolveRecordingVolume(): AppVolume {
    val preferred = _defaultStorageUuid.value
    return StorageVolumeHelper.getVolumeByUuid(getApplication(), preferred)
        ?: StorageVolumeHelper.getDefaultVolume(getApplication())
}

/**
 * Re-queries the OS for the current set of mounted storage volumes and
 * updates [storageVolumes].
 *
 * Called from:
 *  - Fragment [onResume] so the list stays current when the user navigates back
 *  - [StorageVolumeEventReceiver] / [StorageRootsObserver] on mount/unmount events
 *
 * The [StorageVolumeHelper.getVolumes] call hits [getExternalFilesDirs] and
 * [StatFs], both of which are filesystem operations. During a volume mount or
 * unmount transition, Samsung's storage layer (and others) can throw or block
 * on the main thread. We run the IO on [Dispatchers.IO] and post the result
 * back via [MutableStateFlow.value], which is safe to set from any thread.
 *
 * Callers on the main thread (receivers, observers) are unaffected — this
 * returns immediately and the StateFlow update arrives shortly after.
 */
fun MainViewModel.refreshStorageVolumes() {
    viewModelScope.launch(Dispatchers.IO) {
        val volumes = runCatching {
            StorageVolumeHelper.getVolumes(getApplication())
        }.getOrElse {
            // If the OS throws mid-transition (e.g. volume still mounting),
            // keep the existing list rather than wiping it to empty.
            _storageVolumes.value
        }
        _storageVolumes.value = volumes
    }
}