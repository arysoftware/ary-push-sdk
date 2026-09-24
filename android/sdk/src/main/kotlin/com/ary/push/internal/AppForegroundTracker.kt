package com.ary.push.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.OnNewIntentProvider
import com.ary.push.internal.log.PushLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether the application is in the foreground.
 *
 * The foreground display policy needs this, and so does the decision about whether a received
 * event has anyone to reach. It is implemented as a counter over activity lifecycle callbacks
 * rather than with `ProcessLifecycleOwner` so the SDK does not add a lifecycle dependency to
 * every host application's build.
 *
 * Nothing here retains an Activity: only a count is kept, so there is no leak even if an
 * Activity is destroyed without a matching stop.
 */
internal class AppForegroundTracker private constructor() :
    Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    /** True while at least one Activity is between `onStart` and `onStop`. */
    val isForeground: Boolean get() = startedActivities.get() > 0

    /** Weak so an Activity the SDK merely observed can still be collected. */
    private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    /**
     * Called with each Activity as it appears, so the SDK can inspect the intent that launched
     * it. A notification the *system* rendered delivers its tap that way and no other, so this
     * hook is the only place the SDK can see it.
     *
     * Assigning it replays the Activity already on screen, because the SDK is routinely
     * initialized *after* that Activity was created -- a Flutter application initializes from
     * Dart, which runs once the engine is up. Without the replay, a cold start from a tap would
     * set this hook a moment too late and lose the very intent it exists to read.
     */
    var onActivityIntent: ((Activity, Intent?) -> Unit)? = null
        set(value) {
            field = value
            val current = lastActivity?.get() ?: return
            if (value != null) {
                observeNewIntents(current)
                notifyIntent(current, current.intent)
            }
        }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        // Clamped at zero: a process started by a background message can see a stop without a
        // matching start after a configuration change. A compare-and-set loop rather than
        // updateAndGet, which is only available from API 24.
        while (true) {
            val current = startedActivities.get()
            if (current <= 0) return
            if (startedActivities.compareAndSet(current, current - 1)) return
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        lastActivity = java.lang.ref.WeakReference(activity)
        observeNewIntents(activity)
        notifyIntent(activity, activity.intent)
    }

    /** Activities already observed, so a replayed one is never given a second listener. */
    private val observedActivities = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )

    /**
     * A tap while the app is in the background reaches a running `singleTop` Activity through
     * onNewIntent, which updates `activity.intent` only if the Activity calls setIntent -- and
     * FlutterActivity, among others, does not. An AndroidX Activity reports new intents, so they
     * are read from there; a plain Activity forwards them through ARYPush.handleIntent.
     */
    private fun observeNewIntents(activity: Activity) {
        val provider = activity as? OnNewIntentProvider ?: return
        if (!observedActivities.add(activity)) return
        provider.addOnNewIntentListener { intent -> notifyIntent(activity, intent) }
    }

    /**
     * Also checked on resume, because a `singleTop` Activity that is already running receives a
     * tap through `onNewIntent` rather than a fresh `onActivityCreated`, and there is no
     * lifecycle callback for that. Handling the same intent twice is harmless: the open is
     * deduplicated before anything acts on it.
     */
    override fun onActivityResumed(activity: Activity) {
        lastActivity = java.lang.ref.WeakReference(activity)
        notifyIntent(activity, activity.intent)
    }

    private fun notifyIntent(activity: Activity, intent: Intent?) {
        val listener = onActivityIntent ?: return
        // Host code, and it runs on the main thread during a lifecycle callback: a throw here
        // would surface as a crash in an Activity the SDK merely observed.
        runCatching { listener(activity, intent) }
            .onFailure { PushLogger.e(it) { "Failed to inspect a launch intent" } }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {
        /**
         * Attaches to the application, returning a tracker.
         *
         * When the context is not an [Application] (which happens in some test harnesses and in
         * a few unusual embedding scenarios) the tracker reports "not foreground", which makes
         * the SDK render notifications rather than assume the user is already looking at them.
         */
        fun attach(context: Context): AppForegroundTracker {
            val tracker = AppForegroundTracker()
            val application = context.applicationContext as? Application
            if (application == null) {
                PushLogger.w { "Context is not an Application; foreground state is unavailable" }
                return tracker
            }
            application.registerActivityLifecycleCallbacks(tracker)
            return tracker
        }
    }
}
