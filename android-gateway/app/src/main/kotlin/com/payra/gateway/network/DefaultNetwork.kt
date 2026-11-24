package com.payra.gateway.network

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

// Collect on the main thread so the initial snapshot and callbacks are ordered.
internal fun observeDefaultNetwork(manager: ConnectivityManager): Flow<Network?> = callbackFlow<Network?> {
    var current: Network? = null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            trySend(network)
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                trySend(null)
            }
        }
    }
    manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    current = manager.activeNetwork
    trySend(current)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.conflate().distinctUntilChanged()
