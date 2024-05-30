package org.session.libsession.messaging.sending_receiving.pollers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.crypto.getRandomElementOrNull
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.defaultRequiresAuth
import org.session.libsignal.utilities.hasNamespaces
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

class ClosedGroupPollerV2 {
    private val pollingJobs = hashMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    private fun isPolling(groupPublicKey: String): Boolean {
        return pollingJobs[groupPublicKey]?.isActive == true
    }

    companion object {
        private val minPollInterval = 4 * 1000
        private val maxPollInterval = 4 * 60 * 1000

        @JvmStatic
        val shared = ClosedGroupPollerV2()
    }

    class InsufficientSnodesException() : Exception("No snodes left to poll.")
    class PollingCanceledException() : Exception("Polling canceled.")

    fun start() {
        val storage = MessagingModuleConfiguration.shared.storage
        val allGroupPublicKeys = storage.getAllClosedGroupPublicKeys()
        allGroupPublicKeys.iterator().forEach { startPolling(it) }
    }

    fun startPolling(groupPublicKey: String) {
        synchronized(pollingJobs) {
            if (pollingJobs[groupPublicKey]?.isActive == true) return

            pollingJobs[groupPublicKey] = scope.launch {
                pollForever(groupPublicKey)
            }
        }
    }

    fun stopAll() {
        synchronized(pollingJobs) {
            pollingJobs.values.forEach { it.cancel() }
            pollingJobs.clear()
        }
    }

    fun stopPolling(groupPublicKey: String) {
        synchronized(pollingJobs) {
            pollingJobs.remove(groupPublicKey)?.cancel()
        }
    }

    private suspend fun pollForever(groupPublicKey: String) {
        // Get the received date of the last message in the thread. If we don't have any messages yet, pick some
        // reasonable fake time interval to use instead.
        val storage = MessagingModuleConfiguration.shared.storage
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)

        while (true) {
            val threadID = storage.getThreadId(groupID)
            if (threadID == null) {
                Log.d("Loki", "Stopping group poller due to missing thread for closed group: $groupPublicKey.")
                return
            }
            val lastUpdated = storage.getLastUpdated(threadID)
            val timeSinceLastMessage = if (lastUpdated != -1L) Date().time - lastUpdated else 5 * 60 * 1000
            val limit: Long = 12 * 60 * 60 * 1000
            val a = (maxPollInterval - minPollInterval).toDouble() / limit.toDouble()
            val nextPollInterval = a * min(timeSinceLastMessage, limit) + minPollInterval

            delay(nextPollInterval.toLong())

            try {
                pollOnce(groupPublicKey)
            } catch (e: Exception) {
                Log.e("Loki", "Failed to poll closed group: $groupPublicKey.", e)
            }
        }
    }

    private suspend fun pollOnce(groupPublicKey: String) {
        val swarm = SnodeAPI.getSwarm(groupPublicKey)
        val snode = swarm.getRandomElementOrNull() ?: throw InsufficientSnodesException() // Should be cryptographically secure
        if (!isPolling(groupPublicKey)) { throw PollingCanceledException() }
        val currentForkInfo = SnodeAPI.forkInfo

        val envelopes = when {
            currentForkInfo.defaultRequiresAuth() -> SnodeAPI.getRawMessages(snode, groupPublicKey, requiresAuth = false, namespace = Namespace.UNAUTHENTICATED_CLOSED_GROUP)
                .let { SnodeAPI.parseRawMessagesResponse(it, snode, groupPublicKey, Namespace.UNAUTHENTICATED_CLOSED_GROUP) }
            currentForkInfo.hasNamespaces() -> {
                val unAuthedResult = SnodeAPI.getRawMessages(snode, groupPublicKey, requiresAuth = false, namespace = Namespace.UNAUTHENTICATED_CLOSED_GROUP)
                    .let { SnodeAPI.parseRawMessagesResponse(it, snode, groupPublicKey, Namespace.UNAUTHENTICATED_CLOSED_GROUP) }
                val defaultResult = SnodeAPI.getRawMessages(snode, groupPublicKey, requiresAuth = false, namespace = Namespace.DEFAULT)
                    .let { SnodeAPI.parseRawMessagesResponse(it, snode, groupPublicKey, Namespace.DEFAULT) }
                val format = DateFormat.getTimeInstance()
                if (unAuthedResult.isNotEmpty() || defaultResult.isNotEmpty()) {
                    Log.d("Poller", "@${format.format(Date())}Polled ${unAuthedResult.size} from -10, ${defaultResult.size} from 0")
                }
                unAuthedResult + defaultResult
            }
            else -> SnodeAPI.getRawMessages(snode, groupPublicKey, requiresAuth = false, namespace = Namespace.DEFAULT)
                .let { SnodeAPI.parseRawMessagesResponse(it, snode, groupPublicKey) }
        }

        val parameters = envelopes.map { (envelope, serverHash) ->
            MessageReceiveParameters(envelope.toByteArray(), serverHash = serverHash)
        }

        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).iterator().forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }
    }
}
