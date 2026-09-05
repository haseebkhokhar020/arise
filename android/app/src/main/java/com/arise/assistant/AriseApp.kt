package com.arise.assistant

import android.app.Application
import com.arise.assistant.engine.AriseEngine
import com.arise.assistant.log.LocalLog
import com.arise.assistant.service.AriseForegroundService
import com.arise.assistant.settings.Settings

class AriseApp : Application() {
    lateinit var engine: AriseEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = AriseEngine(this)
        LocalLog.d("App", "Arise started v${com.arise.assistant.util.Util.versionName(this)}")
        // restore hands-free listening if it was active and permitted
        if (Settings(this).handsFreeEnabled) {
            AriseForegroundService.startIfPermitted(this, engine)
        }
    }

    companion object {
        lateinit var instance: AriseApp
            private set
    }
}
