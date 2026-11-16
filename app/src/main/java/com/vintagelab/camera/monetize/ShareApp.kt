package com.vintagelab.camera.monetize

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vintagelab.camera.App
import com.vintagelab.camera.R
import java.time.LocalDate

/**
 * SPEC 4.4: "Share the app". The system chooser reports the chosen target through [ShareRewardReceiver], so opening the sheet
 * and backing out earns nothing. Whether the message was really sent cannot be known; [SharePoints] caps the reward per day.
 */
object ShareApp {
    fun storeUrl(context: Context) = "https://play.google.com/store/apps/details?id=${context.packageName}"

    fun launch(activity: Activity) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_app_message, storeUrl(activity)))
        }
        // Explicit intent, so FLAG_MUTABLE is allowed: the chooser adds EXTRA_CHOSEN_COMPONENT to it.
        val callback = PendingIntent.getBroadcast(
            activity, 0, Intent(activity, ShareRewardReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_app_title), callback.intentSender))
        } catch (e: ActivityNotFoundException) {
            // nothing can share text
        }
    }
}

class ShareRewardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val monetize = (context.applicationContext as App).monetize
        monetize.entitlements.onAppShared(monetize.remote.config.value, LocalDate.now().toEpochDay())
    }
}
