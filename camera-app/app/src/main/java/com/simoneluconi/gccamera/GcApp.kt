package com.simoneluconi.gccamera

import android.app.Application
import com.simoneluconi.gccamera.settings.Prefs

class GcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs(this).applyTheme()
    }
}
