package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * NetworkMonitor — 监听网络状态变化，驱动离线队列。
 *
 * 注册：在 AgentService.onCreate() 注册，onDestroy() 取消注册。
 * 权限：<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 */
class NetworkMonitor(private val context: Context) {

    var onNetworkChanged: ((Boolean) -> Unit)? = null

    private var wasOnline = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                val online = isOnline()
                if (online != wasOnline) {
                    wasOnline = online
                    onNetworkChanged?.invoke(online)
                }
            }
        }
    }

    fun start() {
        val filter = android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(receiver, filter)
        wasOnline = isOnline()
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}