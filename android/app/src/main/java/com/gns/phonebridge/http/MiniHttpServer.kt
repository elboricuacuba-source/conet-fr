package com.gns.phonebridge.http

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * Minimal read-only HTTP file server: directory listings as plain HTML links,
 * and file serving with Range support so photos/audio/video open and play
 * straight in the browser instead of needing a dedicated app.
 */
class MiniHttpServer(
    private val rootDir: File,
    private val port: Int,
    private val onLog: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private val activeHandlers = Collections.synchronizedList(mutableListOf<HttpClientHandler>())

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
                    val handler = HttpClientHandler(client, rootDir, onLog)
                    activeHandlers.add(handler)
                    Thread(handler, "http-client-${client.inetAddress.hostAddress}").start()
                } catch (e: Exception) {
                    if (running) onLog("HTTP accept error: ${e.message}")
                }
            }
        }.also { it.name = "http-accept"; it.start() }
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
