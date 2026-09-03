package com.ary.push.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
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
