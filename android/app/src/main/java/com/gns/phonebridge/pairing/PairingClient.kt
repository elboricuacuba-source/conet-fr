package com.gns.phonebridge.pairing

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to the small pairing socket the Windows app opens next to its QR
 * code. The QR only carries the PC's address; the phone calls back with its
 * own FTP server details once it has scanned it.
 */
object PairingClient {
    private const val PROTOCOL = "GNSBRIDGE2"

    data class PcInfo(val ip: String, val port: Int, val token: String)

    /** Parses the text encoded in the PC's QR code: GNSBRIDGE2|ip|port|token */
    fun parsePcQr(text: String): PcInfo? {
        val parts = text.trim().split("|")
        if (parts.size != 4 || parts[0] != PROTOCOL) return null
        val port = parts[2].toIntOrNull() ?: return null
        if (parts[1].isBlank() || parts[3].isBlank()) return null
        return PcInfo(ip = parts[1], port = port, token = parts[3])
    }

    /** Connects to the PC and hands over our server addresses. Returns true if the PC acknowledged it. */
    fun sendPairing(
        pc: PcInfo,
        androidIp: String,
        ftpPort: Int,
        httpPort: Int,
        username: String,
        password: String,
    ): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(pc.ip, pc.port), 5000)
            socket.soTimeout = 5000
            val message = "$PROTOCOL|${pc.token}|$androidIp|$ftpPort|$httpPort|$username|$password\n"
            socket.getOutputStream().write(message.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader().readLine()
            return response == "OK"
        }
    }
}
