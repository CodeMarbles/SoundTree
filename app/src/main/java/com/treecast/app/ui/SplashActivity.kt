package com.treecast.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.treecast.app.TreeCastApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        val repo = (application as TreeCastApp).repository

        lifecycleScope.launch(Dispatchers.IO) {
            val lastSession = repo.getLastSession()
            val longAbsence = if (lastSession?.closedAt != null) {
                val gapMs = System.currentTimeMillis() - lastSession.closedAt
                gapMs >= TimeUnit.HOURS.toMillis(4)
            } else {
                true // first ever launch
            }

            withContext(Dispatchers.Main) {
                val intent = Intent(this@SplashActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_QUICK_RECORD, longAbsence)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
