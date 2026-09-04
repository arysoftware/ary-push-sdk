package com.ary.push.flutter

import android.content.Context
import com.ary.push.ARYPush
import com.ary.push.model.PushNotification
import com.ary.push.model.PushPermissionStatus
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * The Android half of the Flutter bridge.
 *
 * Deliberately thin. Every decision about FCM, rendering, tokens, storage and deduplication is
 * made by the native SDK; this class translates method calls and forwards events. There is no
 * second notification engine here, because two engines would drift and the Dart one could not
 * run when the app is terminated.
 *
 * Engine lifecycle is the subtle part. A hot restart, a hot reload or a recreated
 * `FlutterEngine` tears down the Dart side while the native SDK keeps running, so listeners are
 * attached exactly once per engine attachment and removed on detach. Without that, every hot
 * restart would leave another listener behind and the app would show duplicate notifications
 * that disappear on a cold start.
 */
public class ARYPushPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var applicationContext: Context

    private var eventSink: EventChannel.EventSink? = null

    /**
     * Events that occurred before Dart was listening.
     *
     * Bounded, because a device that receives many messages before the engine attaches must not
     * accumulate them forever. Notification opens are not buffered here: the native SDK persists
     * those itself, which is what makes them survive a terminated launch rather than merely a
     * slow one.
     */
    private val pendingEvents = ArrayDeque<Map<String, Any?>>()

    private val receivedListener: (PushNotification) -> Unit = { notification ->
        emit("received", notification.toMap())
    }

    private val openedListener: (PushNotification) -> Unit = { notification ->
        emit("opened", notification.toMap())
    }

    private val tokenListener: (String) -> Unit = { token ->
        emit("token", mapOf("token" to token))
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Detaching without removing these would leave the previous engine's listeners alive
        // and produce duplicate events after every hot restart.
        detachListeners()
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        pendingEvents.clear()
    }

    // ------------------------------------------------------------------ events

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        attachListeners()

        // Anything that happened while Dart was not listening is delivered now, in order.
        while (pendingEvents.isNotEmpty()) {
            events?.success(pendingEvents.removeFirst())
        }
    }

    override fun onCancel(arguments: Any?) {
        detachListeners()
        eventSink = null
    }

    private fun attachListeners() {
        // The SDK's listener collections ignore duplicates, so re-attaching after a hot restart
        // cannot register the same listener twice.
        ARYPush.addNotificationReceivedListener(receivedListener)
        ARYPush.addNotificationOpenedListener(openedListener)
        ARYPush.addTokenRefreshListener(tokenListener)
    }

    private fun detachListeners() {
        ARYPush.removeNotificationReceivedListener(receivedListener)
        ARYPush.removeNotificationOpenedListener(openedListener)
        ARYPush.removeTokenRefreshListener(tokenListener)
    }

    private fun emit(type: String, payload: Map<String, Any?>) {
        val event = mapOf("type" to type, "payload" to payload)
        val sink = eventSink
        if (sink == null) {
            if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
            pendingEvents.addLast(event)
            return
        }
        sink.success(event)
    }


    // ------------------------------------------------------------------ method calls

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> {
                ARYPush.initialize(
                    applicationContext,
                    FlutterConfigMapper.from(call.arguments)
                )
                result.success(null)
            }

            "isInitialized" -> result.success(ARYPush.isInitialized)

            // Answered asynchronously: the system prompt is shown and the result reported once
            // the user has decided.
            "requestPermission" ->
                ARYPush.requestPermission { status -> result.success(status.toWire()) }

            "getPermissionStatus" ->
                result.success(ARYPush.getPermissionStatus().toWire())

            "openNotificationSettings" -> {
                ARYPush.openNotificationSettings(applicationContext)
                result.success(null)
            }

            "getInstallationId" -> result.success(ARYPush.getInstallationId())

            "getPushToken" -> ARYPush.getPushToken { token -> result.success(token) }

            "getPushProvider" -> result.success("fcm")

            "login" -> {
                val userId = call.argument<String>("userId")
                if (userId.isNullOrBlank()) {
                    result.error("invalid_argument", "userId must not be blank", null)
                } else {
                    ARYPush.login(userId)
                    result.success(null)
                }
            }

            "logout" -> {
                ARYPush.logout()
                result.success(null)
            }

            "getUserId" -> result.success(ARYPush.getUserId())

            "addTags" -> {
                ARYPush.addTags(call.stringMap("tags"))
                result.success(null)
            }

            "removeTags" -> {
                ARYPush.removeTags(call.stringList("keys").toSet())
                result.success(null)
            }

            "removeAllTags" -> {
                ARYPush.removeAllTags()
                result.success(null)
            }

            "getTags" -> result.success(ARYPush.getTags())

            "subscribeToTopic" ->
                ARYPush.subscribeToTopic(call.argument<String>("topic").orEmpty()) { ok ->
                    result.success(ok)
                }

            "unsubscribeFromTopic" ->
                ARYPush.unsubscribeFromTopic(call.argument<String>("topic").orEmpty()) { ok ->
                    result.success(ok)
                }

            "getSegments" ->
                // Answered asynchronously: membership is read from the backend.
                ARYPush.getSegments { segments -> result.success(segments.map { it.toMap() }) }

            "getSubscribedTopics" ->
                result.success(ARYPush.getSubscribedTopics().toList())

            "getInitialNotification" ->
                result.success(ARYPush.consumeInitialNotification()?.toMap())

            "trackEvent" -> {
                ARYPush.trackEvent(
                    call.argument<String>("name").orEmpty(),
                    call.stringMap("properties")
                )
                result.success(null)
            }

            "flush" -> {
                ARYPush.flush()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun MethodCall.stringMap(key: String): Map<String, String> =
        argument<Map<*, *>>(key)
            ?.entries
            ?.mapNotNull { entry ->
                val entryKey = entry.key ?: return@mapNotNull null
                val entryValue = entry.value ?: return@mapNotNull null
                entryKey.toString() to entryValue.toString()
            }
            ?.toMap()
            .orEmpty()

    private fun MethodCall.stringList(key: String): List<String> =
        argument<List<*>>(key)?.mapNotNull { it?.toString() }.orEmpty()

    /**
     * Maps the Kotlin enum onto the value the Dart enum parses.
     *
     * Kotlin uses SCREAMING_SNAKE_CASE and Dart uses lowerCamelCase, so the mapping is explicit
     * rather than derived: a rename on either side should break here, loudly, instead of
     * silently degrading every status to "not determined".
     */
    private fun PushPermissionStatus.toWire(): String = when (this) {
        PushPermissionStatus.NOT_DETERMINED -> "notDetermined"
        PushPermissionStatus.GRANTED -> "granted"
        PushPermissionStatus.DENIED -> "denied"
        PushPermissionStatus.PROVISIONAL -> "provisional"
        PushPermissionStatus.EPHEMERAL -> "ephemeral"
        PushPermissionStatus.RESTRICTED -> "restricted"
    }

    private companion object {
        const val METHOD_CHANNEL = "ary_push/methods"
        const val EVENT_CHANNEL = "ary_push/events"
        const val MAX_PENDING_EVENTS = 50
    }
}
