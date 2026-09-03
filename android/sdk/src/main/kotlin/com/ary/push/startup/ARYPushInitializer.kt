package com.ary.push.startup

import android.content.Context
import androidx.startup.Initializer
import com.ary.push.internal.ManifestConfigReader
import com.ary.push.internal.PushCore
import com.ary.push.internal.log.PushLogger

/**
 * Optional automatic initialization through AndroidX Startup.
 *
 * Off unless the host application opts in:
 *
 * ```xml
 * <meta-data android:name="com.ary.push.auto_init" android:value="true" />
 * ```
 *
 * Opt-in rather than automatic, because the benefit is one line of host code and the cost is
 * running SDK initialization at a point in startup that the application does not control.
 * Reliability is worth more than that line: explicit `ARYPush.initialize(context)` is always
 * supported, always wins, and remains the documented path.
 *
 * This is separate from the SDK working in a cold-started process. A background message or a
 * notification tap initializes the SDK regardless of this setting, because those paths cannot
 * assume any host code has run.
 */
public class ARYPushInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        if (!ManifestConfigReader.isAutoInitEnabled(context)) return
        try {
            PushCore.ensureInitialized(context.applicationContext)
        } catch (t: Throwable) {
            // Startup initializers run during Application creation. Throwing here would turn an
            // SDK problem into an application that cannot launch at all.
            PushLogger.e(t) { "Automatic initialization failed" }
        }
    }

    /** No dependencies: the SDK initializes itself and nothing else. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
