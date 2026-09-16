package com.moto.voice.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Lightweight synchronous check for internet availability. Used by the UI status card. */
object NetworkState {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * v1.4.1 — "wifi" / "cellular" / "other" / "none". Logged at every YouTube launch to test
     * the rider's own observation against the failures: YouTube's "Connect your devices"
     * (cast) prompt only appears when a castable device is on the same network — i.e. on
     * home Wi‑Fi — and a cold YouTube stuck behind that prompt never registers a
     * MediaSession, which is exactly what `launchBlocked(noSession)` looks like.
     */
    fun transportName(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }
}
