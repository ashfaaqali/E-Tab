package org.weproz.etab

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ETabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force Light Mode globally
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
