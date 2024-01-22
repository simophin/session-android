package network.loki.messenger.libsession_util.util

import kotlin.time.Duration.Companion.seconds

sealed class ExpiryMode(val expirySeconds: Long) {
    object NONE: ExpiryMode(0)
    data class Legacy(private val seconds: Long): ExpiryMode(seconds) // after read
    data class AfterSend(private val seconds: Long): ExpiryMode(seconds)
    data class AfterRead(private val seconds: Long): ExpiryMode(seconds)

    val duration get() = expirySeconds.seconds

    val expiryMillis get() = expirySeconds * 1000L
}
