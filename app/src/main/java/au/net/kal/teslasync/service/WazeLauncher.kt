package au.net.kal.teslasync.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import au.net.kal.teslasync.TeslaSyncApp
import au.net.kal.teslasync.data.Destination

/**
 * Opens Waze for a destination.
 *
 * Android 10+ blocks starting an activity from the background, so there are two tiers:
 *  - If we hold the "Display over other apps" (SYSTEM_ALERT_WINDOW) permission, launch Waze
 *    directly — best UX, the user just taps GO in Waze.
 *  - Otherwise post a heads-up notification the user taps to launch Waze (one extra tap,
 *    but always works).
 */
object WazeLauncher {
    private const val TAG = "WazeLauncher"
    private const val WAZE_PACKAGE = "com.waze"
    private const val NAV_NOTIFICATION_ID = 4711

    fun launch(context: Context, destination: Destination) {
        val intent = wazeIntent(destination)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "Launched Waze directly for ${destination.name}")
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Waze not installed; opening Play Store", e)
                openPlayStore(context)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct launch failed; falling back to notification", e)
                // fall through
            }
        }
        postNavigationNotification(context, destination, intent)
    }

    private fun wazeIntent(destination: Destination): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(WazeUrl.build(destination.latitude, destination.longitude)),
        ).setPackage(WAZE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun postNavigationNotification(context: Context, destination: Destination, wazeIntent: Intent) {
        val pi = PendingIntent.getActivity(
            context,
            0,
            wazeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, TeslaSyncApp.CHANNEL_NAV)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Navigate in Waze")
            .setContentText(destination.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService<NotificationManager>()?.notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun openPlayStore(context: Context) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$WAZE_PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open Play Store", e)
        }
    }
}
