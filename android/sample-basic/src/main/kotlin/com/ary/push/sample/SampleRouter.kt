package com.ary.push.sample

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ary.push.model.PushNotification

/**
 * Where the host application, not the SDK, decides what a notification means.
 *
 * The SDK delivers `data` verbatim and stops. Deciding that `action=open_order` leads to the
 * order screen is application knowledge, and putting it in the SDK would tie one push
 * implementation to one application's navigation graph.
 */
object SampleRouter {

    fun route(context: Context, notification: PushNotification) {
        Log.i("SampleRouter", "Notification opened: ${notification.id} data=${notification.data}")

        val intent = when (notification.action) {
            "open_order" -> Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ORDER_ID, notification.data["orderId"])

            else -> Intent(context, MainActivity::class.java)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }
}
