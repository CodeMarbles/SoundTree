package com.treecast.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.treecast.app.data.repository.TreeCastRepository

class TreeCastApp : Application() {

    val repository: TreeCastRepository by lazy {
        TreeCastRepository.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        applyThemeFromPrefs()
    }

    private fun applyThemeFromPrefs() {
        val prefs = getSharedPreferences("treecast_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("theme_mode", "system") ?: "system"
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}