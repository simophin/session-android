package org.session.libsignal.utilities

import kotlinx.coroutines.delay
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import java.util.*

fun <V, T : Promise<V, Exception>> retryIfNeeded(maxRetryCount: Int, retryInterval: Long = 1000L, body: () -> T): Promise<V, Exception> {
    var retryCount = 0
    val deferred = deferred<V, Exception>()
    val thread = Thread.currentThread()
    fun retryIfNeeded() {
        body().success {
            deferred.resolve(it)
        }.fail {
            if (retryCount == maxRetryCount) {
                deferred.reject(it)
            } else {
                retryCount += 1
                Timer().schedule(object : TimerTask() {

                    override fun run() {
                        thread.run { retryIfNeeded() }
                    }
                }, retryInterval)
            }
        }
    }
    retryIfNeeded()
    return deferred.promise
}

suspend fun <T> runRetry(maxRetryCount: Int, retryInterval: Long = 1000L, body: suspend () -> T): T {
    var nthRetry = 0

    while (true) {
        try {
            return body()
        } catch (e: Exception) {
            if (nthRetry == maxRetryCount - 1) {
                throw e
            }

            delay(retryInterval)
            nthRetry++
        }
    }
}