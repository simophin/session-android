package org.session.libsession.utilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

class NetworkConnectivityProvider(appContext: Context, scope: CoroutineScope = GlobalScope) {
//    val connected: StateFlow<Boolean> = callbackFlow<Boolean> {
//        val activityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//        val callback = object : NetworkCallback() {
//            override fun onAvailable(network: Network) {
//                super.onAvailable(network)
//            }
//        }
//
//        activityManager.registerDefaultNetworkCallback(callback)
//
//        awaitClose { activityManager.unregisterNetworkCallback(callback) }
//    }.stateIn(scope, SharingStarted.WhileSubscribed())
}