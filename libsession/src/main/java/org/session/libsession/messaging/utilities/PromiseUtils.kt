package org.session.libsession.messaging.utilities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.komponents.kovenant.CancelablePromise
import nl.komponents.kovenant.Promise
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Suspend await on a [Promise]. Exception will be thrown as is.
 */
suspend fun <V> Promise<V, Throwable>.await(): V {
    return suspendCancellableCoroutine { cont ->
        success(cont::resume).fail(cont::resumeWithException)

        if (this is CancelablePromise) {
            cont.invokeOnCancellation { this.cancel(CancellationException()) }
        }
    }
}
