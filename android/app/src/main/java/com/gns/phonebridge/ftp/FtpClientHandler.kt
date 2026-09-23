package com.gns.phonebridge.ftp

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale

/** Handles one FTP control connection end-to-end (single-threaded, blocking IO). */
class FtpClientHandler(
    private val control: Socket,
    private val rootDir: File,
    private val username: String,
    private val password: String,
    private val onLog: (String) -> Unit,
) : Runnable {

    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter
    private var authenticated = false
    private var enteredUser = false
    private var currentDir: File = rootDir
    private var pasvServer: ServerSocket? = null
    @Volatile private var closed = false

    fun forceClose() {
        closed = true
        runCatching { pasvServer?.close() }
        runCatching { control.close() }
    }

    override fun run() {
        try {
            control.soTimeout = 5 * 60 * 1000
            reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
            writer = OutputStreamWriter(control.getOutputStream(), Charsets.UTF_8)
            reply(220, "Conet FR FTP ready")

            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val spaceIdx = line.indexOf(' ')
                val cmd = (if (spaceIdx == -1) line else line.substring(0, spaceIdx)).trim().uppercase(Locale.US)
                val arg = if (spaceIdx == -1) "" else line.substring(spaceIdx + 1).trim()
                onLog("<< $cmd ${if (cmd == "PASS") "***" else arg}")
                if (!handle(cmd, arg)) break
            }
        } catch (e: Exception) {
            onLog("Session error: ${e.message}")
        } finally {
            forceClose()
        }
    }

    /** Returns false when the session should end. */
    private fun handle(cmd: String, arg: String): Boolean {
        when (cmd) {
            "USER" -> {
                enteredUser = arg == username
                reply(331, "Password required")
            }
            "PASS" -> {
                authenticated = enteredUser && arg == password
                if (authenticated) reply(230, "Login successful")
                else reply(530, "Login incorrect")
            }
            "SYST" -> reply(215, "UNIX Type: L8")
            "FEAT" -> multiline(211, "Features", listOf("UTF8"), "211 End")
            "OPTS" -> reply(200, "OK")
            "NOOP" -> reply(200, "OK")
            "TYPE" -> reply(200, "Type set")
            "PWD", "XPWD" -> reply(257, "\"${relativePath(currentDir)}\" is current directory")
            "CWD" -> requireAuth {
                val target = resolvePath(arg)
                if (target != null && target.isDirectory) {
                    currentDir = target
                    reply(250, "Directory changed")
                } else reply(550, "Directory not found")
            }
            "CDUP" -> requireAuth {
                if (currentDir != rootDir) currentDir = currentDir.parentFile ?: rootDir
                reply(200, "OK")
            }
            "PASV" -> requireAuth { enterPassiveMode() }
            "LIST", "NLST" -> requireAuth { sendListing(cmd == "NLST") }
            "RETR" -> requireAuth { sendFile(arg) }
            "STOR" -> requireAuth { receiveFile(arg) }
            "DELE" -> requireAuth { deleteFile(arg) }
            "MKD", "XMKD" -> requireAuth { makeDirectory(arg) }
            "RMD", "XRMD" -> requireAuth { removeDirectory(arg) }
            "SIZE" -> requireAuth {
                val f = resolvePath(arg)
                if (f != null && f.isFile) reply(213, f.length().toString())
                else reply(550, "File not found")
            }
            "MDTM" -> requireAuth {
                val f = resolvePath(arg)
                if (f != null && f.exists()) reply(213, timestamp(f.lastModified()))
                else reply(550, "File not found")
            }
            "QUIT" -> {
                reply(221, "Bye")
                return false
            }
            else -> reply(502, "Command not implemented")
        }
        return true
    }

    private inline fun requireAuth(block: () -> Unit) {
        if (!authenticated) reply(530, "Not logged in") else block()
    }

    // --- Path handling -----------------------------------------------------

    private fun relativePath(dir: File): String {
        val rel = dir.toRelativeString(rootDir)
        return if (rel.isEmpty()) "/" else "/$rel".replace(File.separatorChar, '/')
    }

    /** Resolves an FTP path argument against [currentDir], staying inside [rootDir]. */
    private fun resolvePath(arg: String): File? {
        if (arg.isBlank()) return currentDir
        val candidate = if (arg.startsWith("/")) File(rootDir, arg) else File(currentDir, arg)
        val rootCanonical = rootDir.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        return if (candidateCanonical.path == rootCanonical.path ||
            candidateCanonical.path.startsWith(rootCanonical.path + File.separator)
        ) candidateCanonical else null
    }

    // --- Data connection (PASV only) ---------------------------------------

    private fun enterPassiveMode() {
        runCatching { pasvServer?.close() }
        val server = ServerSocket(0, 1, control.localAddress)
        pasvServer = server
        val addr = (control.localAddress.hostAddress ?: "0.0.0.0").split(".").joinToString(",")
        val p1 = server.localPort shr 8
        val p2 = server.localPort and 0xFF
        reply(227, "Entering Passive Mode ($addr,$p1,$p2)")
    }

    private fun openDataConnection(): Socket? {
        val server = pasvServer ?: run { reply(425, "Use PASV first"); return null }
        return try {
            server.soTimeout = 15_000
            server.accept()
        } catch (e: Exception) {
            reply(425, "Could not open data connection")
            null
        } finally {
            runCatching { server.close() }
            pasvServer = null
        }
    }

    // --- File operations -----------------------------------------------------

    private fun sendListing(namesOnly: Boolean) {
        val data = openDataConnection() ?: return
        reply(150, "Here comes the directory listing")
        try {
            data.getOutputStream().writer(Charsets.UTF_8).use { out ->
                val entries = currentDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in entries) {
                    val line = if (namesOnly) f.name else formatUnixListLine(f)
                    out.write("$line\r\n")
                }
                out.flush()
            }
            reply(226, "Transfer complete")
        } catch (e: Exception) {
            reply(451, "Transfer failed: ${e.message}")
        } finally {
            runCatching { data.close() }
        }
    }

    private fun sendFile(arg: String) {
        val file = resolvePath(arg)
        if (file == null || !file.isFile) {
            reply(550, "File not found")
            return
        }
        val data = openDataConnection() ?: return
        reply(150, "Opening data connection for ${file.name} (${file.length()} bytes)")
        try {
            file.inputStream().use { input ->
                data.getOutputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            reply(226, "Transfer complete")
        } catch (e: Exception) {
            reply(451, "Transfer failed: ${e.message}")
        } finally {
            runCatching { data.close() }
        }
    }

    private fun receiveFile(arg: String) {
        val file = resolvePath(arg) ?: File(currentDir, arg.substringAfterLast('/'))
        val data = openDataConnection() ?: return
        reply(150, "Ready to receive ${file.name}")
        try {
            data.getInputStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            reply(226, "Transfer complete")
        } catch (e: Exception) {
            reply(451, "Transfer failed: ${e.message}")
        } finally {
            runCatching { data.close() }
        }
    }

    private fun deleteFile(arg: String) {
        val f = resolvePath(arg)
        if (f != null && f.isFile && f.delete()) reply(250, "Deleted") else reply(550, "Delete failed")
    }

    private fun makeDirectory(arg: String) {
        val f = resolvePath(arg)
        if (f != null && !f.exists() && f.mkdirs()) reply(257, "\"${relativePath(f)}\" created")
        else reply(550, "Create directory failed")
    }

    private fun removeDirectory(arg: String) {
        val f = resolvePath(arg)
        if (f != null && f.isDirectory && f.delete()) reply(250, "Removed") else reply(550, "Remove failed")
    }

    private fun formatUnixListLine(f: File): String {
        val perms = if (f.isDirectory) "drwxrwxrwx" else "-rw-rw-rw-"
        val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        return "$perms 1 owner group ${f.length()} ${fmt.format(f.lastModified())} ${f.name}"
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(millis)

    // --- Protocol helpers ----------------------------------------------------

    private fun reply(code: Int, message: String) {
        writer.write("$code $message\r\n")
        writer.flush()
    }

    private fun multiline(code: Int, header: String, lines: List<String>, closing: String) {
        writer.write("$code-$header\r\n")
        lines.forEach { writer.write(" $it\r\n") }
        writer.write("$closing\r\n")
        writer.flush()
    }
}
