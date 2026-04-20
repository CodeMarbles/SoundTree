package app.soundtree.ui

// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel_Storage.kt
//
// Extension functions on MainViewModel covering storage volume management
// ─────────────────────────────────────────────────────────────────────────────

import app.soundtree.storage.AppVolume
import app.soundtree.storage.StorageVolumeHelper
import app.soundtree.ui.MainViewModel.Companion.PREF_DEFAULT_STORAGE_UUID

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
 * updates [storageVolumes]. Called from fragment [onResume] so the list
 * stays current when the user connects or removes a drive.
 */
fun MainViewModel.refreshStorageVolumes() {
    _storageVolumes.value = StorageVolumeHelper.getVolumes(getApplication())
}