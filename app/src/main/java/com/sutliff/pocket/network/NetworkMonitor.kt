package com.sutliff.pocket.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Best-effort check: does this device currently have a network with the INTERNET capability?
 * Returns true when the connectivity service is unavailable so callers never hard-block on it.
 */
fun isNetworkAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }
}

/**
 * Registers a default-network callback and reports transitions so queued messages can be
 * flushed as soon as connectivity returns. Callbacks are delivered on the registering thread's
 * looper (the main thread when registered from composition); callers should still hop to a
 * coroutine scope before touching UI state.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit
) : ConnectivityManager.NetworkCallback() {

    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Returns false when registration failed (e.g. missing permission). Safe to ignore. */
    fun register(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            cm.registerDefaultNetworkCallback(this)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun unregister() {
        try {
            connectivityManager?.unregisterNetworkCallback(this)
        } catch (e: Exception) {
            // already unregistered — nothing to do
        }
    }

    override fun onAvailable(network: Network) {
        onNetworkAvailable()
    }

    override fun onLost(network: Network) {
        onNetworkLost()
    }

    override fun onUnavailable() {
        onNetworkLost()
    }
}
