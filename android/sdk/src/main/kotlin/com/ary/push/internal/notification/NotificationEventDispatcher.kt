package com.ary.push.internal.notification

import android.os.Handler
import android.os.Looper
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.PushNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Delivers notification events to the host application, and refuses to lose one.
 *
 * The hard case is the terminated application. The user taps a notification, Android starts the
 * process, and the open event exists before any host code has run, let alone registered a
 * listener. Dropping it there is the single most visible bug a push SDK can have: the user taps
 * an order notification and lands on the home screen.
 *
 * So an open with nobody listening is persisted, and replayed to the first listener that
 * appears. Received events are not persisted: a message nobody was listening for is stale by the
 * time the application starts, and the notification itself is already in the shade.
 *
 * Callbacks always run on the main thread, because host code will navigate from them.
 */
internal class NotificationEventDispatcher(private val storage: StorageManager) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val receivedListeners = CopyOnWriteArrayList<(PushNotification) -> Unit>()
    private val openedListeners = CopyOnWriteArrayList<(PushNotification) -> Unit>()

    // ------------------------------------------------------------------ registration

    fun addReceivedListener(listener: (PushNotification) -> Unit) {
        receivedListeners.addIfAbsent(listener)
    }

    fun removeReceivedListener(listener: (PushNotification) -> Unit) {
        receivedListeners.remove(listener)
    }

    fun addOpenedListener(listener: (PushNotification) -> Unit) {
        openedListeners.addIfAbsent(listener)
        // Replay before returning, so a listener registered in Application.onCreate still sees
        // the tap that started the process.
        consumePendingOpen()?.let { pending ->
            PushLogger.i { "Replaying pending notification open to a newly attached listener" }
            deliver(listener, pending)
        }
    }

    fun removeOpenedListener(listener: (PushNotification) -> Unit) {
        openedListeners.remove(listener)
    }

    val hasOpenedListeners: Boolean get() = openedListeners.isNotEmpty()

    // ------------------------------------------------------------------ dispatch

    fun dispatchReceived(notification: PushNotification) {
        PushLogger.i { "Notification received: ${notification.id}" }
        receivedListeners.forEach { listener -> deliver(listener, notification) }
    }

    fun dispatchOpened(notification: PushNotification) {
        PushLogger.i {
            "Notification opened: ${notification.id}" +
                notification.actionId?.let { " (action=$it)" }.orEmpty()
        }
        if (openedListeners.isEmpty()) {
            persistPendingOpen(notification)
            return
        }
        openedListeners.forEach { listener -> deliver(listener, notification) }
    }

    // ------------------------------------------------------------------ pending open

    /**
     * Stores an open that nobody was listening for.
     *
     * Written durably: the tap usually happens while the process is still starting, and an
     * `apply()` that has not reached disk when the system kills the process is a lost event.
     */
    private fun persistPendingOpen(notification: PushNotification) {
        PushLogger.d { "No open listeners yet; persisting notification open ${notification.id}" }
        storage.putString(
            StorageManager.KEY_PENDING_OPEN,
            NotificationCodec.encode(notification),
            durable = true
        )
    }

    /** Reads and clears the pending open, if there is one. */
    fun consumePendingOpen(): PushNotification? {
        val raw = storage.getString(StorageManager.KEY_PENDING_OPEN) ?: return null
        storage.putString(StorageManager.KEY_PENDING_OPEN, null, durable = true)
        return NotificationCodec.decode(raw)
    }

    /** Reads the pending open without clearing it. */
    fun peekPendingOpen(): PushNotification? =
        NotificationCodec.decode(storage.getString(StorageManager.KEY_PENDING_OPEN))

    private fun deliver(listener: (PushNotification) -> Unit, notification: PushNotification) {
        val invoke = {
            // Host listeners are foreign code called from a system callback. One that throws
            // must not take down FCM's thread, the trampoline activity, or the other listeners.
            runCatching { listener(notification) }
                .onFailure { PushLogger.e(it) { "Notification listener threw" } }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) invoke() else mainHandler.post(invoke)
    }

    /** Detaches every listener. Used when a Flutter engine is destroyed and by tests. */
    fun clearListeners() {
        receivedListeners.clear()
        openedListeners.clear()
    }
}
