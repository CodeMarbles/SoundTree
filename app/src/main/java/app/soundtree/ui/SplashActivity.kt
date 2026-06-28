package app.soundtree.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

/**
 * Transparent splash that checks last session and decides whether to
 * open directly into "quick record" mode or show the normal home screen.
 *
 * A session gap of > 4 hours counts as "returning after a long time."
 */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lastOpenedAt = getSharedPreferences("soundtree_settings", MODE_PRIVATE)
            .getLong("last_session_opened_at", -1L).takeIf { it != -1L }
        val longAbsence = if (lastOpenedAt != null) {
            System.currentTimeMillis() - lastOpenedAt >= TimeUnit.HOURS.toMillis(4)
        } else {
            true // first ever launch
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_QUICK_RECORD, longAbsence)
        }
        startActivity(intent)
        finish()
    }
}