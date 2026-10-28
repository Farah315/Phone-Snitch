package com.farah.phonesnitch.data.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Opens another app from the launcher. */
class AppLauncher(private val context: Context) {

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
