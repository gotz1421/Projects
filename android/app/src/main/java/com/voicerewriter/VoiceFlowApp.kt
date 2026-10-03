package com.voicerewriter

import android.app.Application

/** Process-wide setup that must run before any screen, service or receiver reads it. */
class VoiceFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}
