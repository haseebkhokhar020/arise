package com.arise.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arise.assistant.log.LocalLog
import com.arise.assistant.settings.Settings

/** Restores hands-free wake listening after reboot, only if the user opted in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val s = Settings(context)
        if (!s.autoStartOnBoot || !s.handsFreeEnabled || !s.wakeWordEnabled) return
        LocalLog.i("Boot", "auto-starting wake listening")
        try {
            context.startForegroundService(Intent(context, AriseForegroundService::class.java))
        } catch (e: Exception) {
            LocalLog.e("Boot", "autostart failed ${e.message}")
        }
    }
}
