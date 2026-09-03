package com.ary.push.sample

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ary.push.ARYPush
import com.ary.push.model.PushPermissionStatus

/**
 * Exercises the public API surface a host application actually uses.
 *
 * Built in code rather than XML so the sample stays a single readable file: the interesting part
 * is which SDK calls appear, not the layout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(button("Request permission") {
                // No ActivityResultLauncher, no onRequestPermissionsResult override.
                ARYPush.requestPermission { granted -> onPermissionResult(granted) }
            })
            addView(button("Log in as USER_123") {
                ARYPush.login("USER_123")
                refresh()
            })
            addView(button("Log out") {
                ARYPush.logout()
                refresh()
            })
            addView(button("Tag: premium, English") {
                ARYPush.addTags(mapOf("subscription" to "premium", "language" to "en"))
                refresh()
            })
            addView(button("Clear tags") {
                ARYPush.removeAllTags()
                refresh()
            })
            addView(button("Subscribe to 'sports'") {
                ARYPush.subscribeToTopic("sports") { refresh() }
            })
        }
        setContentView(layout)

        intent?.getStringExtra(EXTRA_ORDER_ID)?.let { orderId ->
            status.text = "Opened from a notification for order $orderId"
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onPermissionResult(result: PushPermissionStatus) {
        if (result == PushPermissionStatus.DENIED && !ARYPush.getPermissionStatus().isAuthorized) {
            // The prompt is gone for good once the user has denied it; Settings is the only route.
            ARYPush.openNotificationSettings(this)
        }
        refresh()
    }

    private fun refresh() {
        ARYPush.getPushToken { token ->
            status.text = buildString {
                appendLine("Permission: ${ARYPush.getPermissionStatus()}")
                appendLine("Installation: ${ARYPush.getInstallationId()}")
                appendLine("Token: ${token?.take(16)?.plus("...") ?: "none"}")
                appendLine("User: ${ARYPush.getUserId() ?: "logged out"}")
                appendLine("Tags: ${ARYPush.getTags()}")
                appendLine("Topics: ${ARYPush.getSubscribedTopics()}")
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_ORDER_ID = "orderId"
    }
}
