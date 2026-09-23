package com.gns.phonebridge.ftp

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * Minimal single-purpose FTP server: enough commands to let a regular FTP
 * client (or FluentFTP on the Windows companion app) browse and transfer
 * files. Passive mode only - that covers every modern FTP client.
 */
class MiniFtpServer(
    private val rootDir: File,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val onLog: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private val activeHandlers = Collections.synchronizedList(mutableListOf<FtpClientHandler>())

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        val socket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        acceptThread = Thread {
            while (running) {
                try {
                    val client = socket.accept()
                    val handler = FtpClientHandler(client, rootDir, username, password, onLog)
                    activeHandlers.add(handler)
                    Thread(handler, "ftp-client-${client.inetAddress.hostAddress}").start()
                } catch (e: Exception) {
                    if (running) onLog("Accept error: ${e.message}")
                }
            }
        }.also { it.name = "ftp-accept"; it.start() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        synchronized(activeHandlers) {
            activeHandlers.forEach { it.forceClose() }
            activeHandlers.clear()
        }
    }
}
