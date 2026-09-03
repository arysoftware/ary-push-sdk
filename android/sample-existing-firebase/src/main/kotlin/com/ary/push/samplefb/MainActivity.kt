package com.ary.push.samplefb

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ary.push.ARYPush
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Shows the SDK and the application's own Firebase usage coexisting.
 *
 * Both token reads return the same FCM token: there is one Firebase project, one registration
 * and one token. The SDK adds an installation identity on top, it does not add a second token.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(status)
            }
        )

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val appToken = task.result
            ARYPush.getPushToken { sdkToken ->
                status.text = buildString {
                    appendLine("Application's own FCM token: ${appToken?.take(16)}...")
                    appendLine("Token as seen by the SDK:    ${sdkToken?.take(16)}...")
                    appendLine("Same token: ${appToken == sdkToken}")
                    appendLine()
                    appendLine("SDK installation: ${ARYPush.getInstallationId()}")
                    appendLine("SDK permission:   ${ARYPush.getPermissionStatus()}")
                }
            }
        }
    }
}
