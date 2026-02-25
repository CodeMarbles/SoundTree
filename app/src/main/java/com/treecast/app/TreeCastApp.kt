package com.treecast.app

import android.app.Application
import com.treecast.app.data.repository.TreeCastRepository

class TreeCastApp : Application() {

    val repository: TreeCastRepository by lazy {
        TreeCastRepository.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
