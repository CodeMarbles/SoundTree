package app.treecast.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.treecast.TreeCastApp
import app.treecast.util.OrphanRecordingScanner
import kotlinx.coroutines.Dispatchers
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
 * [app.treecast.ui.recovery.OrphanRecoveryDialogFragment] if the lists
 * are non-empty.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (application as app.treecast.TreeCastApp).repository

        lifecycleScope.launch(Dispatchers.IO) {
            val knownPaths = repo.getKnownFilePaths()
            val orphans      = OrphanRecordingScanner.scan(applicationContext, knownPaths)

            val lastOpenedAt = getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
                .getLong("last_session_opened_at", -1L).takeIf { it != -1L }
            val longAbsence = if (lastOpenedAt != null) {
                System.currentTimeMillis() - lastOpenedAt >= TimeUnit.HOURS.toMillis(4)
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