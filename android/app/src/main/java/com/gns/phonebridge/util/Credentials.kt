package com.gns.phonebridge.util

import java.security.SecureRandom

object Credentials {

    private const val CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    /** Short random password, regenerated every time the server starts. */
    fun randomPassword(length: Int = 6): String =
        (1..length).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")
}
