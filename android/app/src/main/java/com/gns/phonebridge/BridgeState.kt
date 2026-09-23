package com.gns.phonebridge

data class BridgeState(
    val running: Boolean = false,
    val ip: String? = null,
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val ftpUrl: String?
        get() = ip?.let { "ftp://$username:$password@$it:$port" }
}
