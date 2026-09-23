package com.gns.phonebridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Returns the phone's local IPv4 address on Wi-Fi/hotspot, or null if not connected. */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
