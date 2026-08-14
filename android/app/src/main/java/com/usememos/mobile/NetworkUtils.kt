package com.usememos.mobile

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Returns the first non-loopback IPv4 address (the LAN address other
     * devices can use to reach the server), or null if not connected.
     */
    fun lanIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}