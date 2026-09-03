package com.ary.push.internal.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.ary.push.internal.log.PushLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells the sync queue when it is worth trying the network again.
 *
 * This is a callback registration, not a service and not a poll: the SDK never keeps a process
 * alive to watch connectivity. When the OS reports a usable network, the queue drains; the rest
 * of the time this costs nothing.
 */
internal class ConnectivityMonitor(
    context: Context,
    private val onAvailable: () -> Unit
) {

    private val appContext = context.applicationContext
    private val registered = AtomicBoolean(false)

    private val manager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            PushLogger.d { "Network available; draining pending operations" }
            onAvailable()
        }
    }

    /** True when a network with validated internet access is currently available. */
    val isOnline: Boolean
        @SuppressLint("MissingPermission")
        get() = try {
            val cm = manager ?: return true // Assume online rather than block synchronisation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
                capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (t: Throwable) {
            // Some OEM builds throw from ConnectivityManager. Failing open is correct: the
            // request itself will report the real outcome.
            PushLogger.w(t) { "Connectivity check failed; assuming online" }
            true
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!registered.compareAndSet(false, true)) return
        val cm = manager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (t: Throwable) {
            registered.set(false)
            PushLogger.w(t) { "Could not register network callback; queue drains on next SDK call" }
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            PushLogger.w(t) { "Could not unregister network callback" }
        }
    }
}
