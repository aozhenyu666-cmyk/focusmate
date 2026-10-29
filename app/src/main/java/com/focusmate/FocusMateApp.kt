package com.focusmate

import android.app.Application
import com.focusmate.runtime.GuardRuntime

class FocusMateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GuardRuntime.install(this)
    }
}
