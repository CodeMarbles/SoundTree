package com.treecast.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.treecast.app.TreeCastApp
import com.treecast.app.util.OrphanRecordingScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Transparent splash that checks last session and decides whether to
 * open directly into "quick record" mode or show the normal home screen.
 *
 * A session gap of > 4 hours counts as "returning after a long time."
 *
 * On every launch the splash also runs [OrphanRecordingScanner.scan] in
 * parallel with the session check. Any orphan results are forwarded to
 * [MainActivity] via intent extras; MainActivity shows
 * [com.treecast.app.ui.recovery.OrphanRecoveryDialogFragment] if the lists
 * are non-empty.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (application as TreeCastApp).repository

        lifecycleScope.launch(Dispatchers.IO) {

            // ── Run session check and orphan scan concurrently ─────────
            val sessionDeferred = async {
                repo.getLastSession()
            }
            val orphanDeferred = async {
                val knownPaths = repo.getKnownFilePaths()
                OrphanRecordingScanner.scan(applicationContext, knownPaths)
            }

            val lastSession  = sessionDeferred.await()
            val orphans      = orphanDeferred.await()

            val longAbsence = if (lastSession?.closedAt != null) {
                val gapMs = System.currentTimeMillis() - lastSession.closedAt
                gapMs >= TimeUnit.HOURS.toMillis(4)
            } else {
                true // first ever launch
            }

            withContext(Dispatchers.Main) {
                val intent = Intent(this@SplashActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_QUICK_RECORD, longAbsence)

                    // Forward orphan scan results. MainActivity checks these in
                    // onCreate and shows OrphanRecoveryDialogFragment if non-empty.
                    if (orphans.isNotEmpty()) {
                        val playable = orphans.filter { it.isPlayable }
                        val corrupt  = orphans.filter { !it.isPlayable }
                        putStringArrayListExtra(
                            MainActivity.EXTRA_ORPHAN_PLAYABLE_PATHS,
                            ArrayList(playable.map { it.file.absolutePath })
                        )
                        putExtra(
                            MainActivity.EXTRA_ORPHAN_PLAYABLE_DURATIONS_MS,
                            playable.map { it.durationMs }.toLongArray()
                        )
                        putStringArrayListExtra(
                            MainActivity.EXTRA_ORPHAN_CORRUPT_PATHS,
                            ArrayList(corrupt.map { it.file.absolutePath })
                        )
                    }
                }
                startActivity(intent)
                finish()
            }
        }
    }
}