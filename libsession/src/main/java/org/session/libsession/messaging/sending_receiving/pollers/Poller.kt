package org.session.libsession.messaging.sending_receiving.pollers

import android.util.SparseArray
import androidx.core.util.valueIterator
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.resolve
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.control.SharedConfigurationMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.Snode
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.days

class Poller(private val configFactory: ConfigFactoryProtocol) {
//    var userPublicKey = MessagingModuleConfiguration.shared.storage.getUserPublicKey() ?: ""
    var isCaughtUp = false
    private set

    private var pollingJob: Job? = null

    // region Settings
    companion object {
        private const val retryInterval: Long = 2 * 1000
        private const val maxInterval: Long = 15 * 1000
    }
    // endregion

    // region Public API
    @Synchronized
    fun startIfNeeded() {
        if (pollingJob?.isActive == true) {
            return
        }

        pollingJob = GlobalScope.launch {
            pollForever(MessagingModuleConfiguration.shared.storage.getUserPublicKey().orEmpty())
        }
        Log.d("Loki", "Started polling.")
    }

    @Synchronized
    fun stopIfNeeded() {
        Log.d("Loki", "Stopped polling.")
        pollingJob?.cancel()
        pollingJob = null
    }
    // endregion

    // region Private API
    private suspend fun pollForever(publicKey: String) {
        var nthRetried = 0
        while (true) {
            try {
                val usedNodeIDs = mutableSetOf<String>()
                val allNodes = SnodeAPI.getSwarm(publicKey)
                val nextNode = checkNotNull(allNodes.randomOrNull()) { "No node exists in the swam." }

                Log.i("Loki", "Polling started with ${allNodes.size} nodes.")
                usedNodeIDs += nextNode.id

                while (true) {
                    try {
                        pollOnce(nextNode, publicKey)
                    } catch (e: Exception) {
                        TODO("Not yet implemented")
                    }
                }

                nthRetried = 0
            } catch (e: Exception) {
                nthRetried += 1
                val delayMills = (retryInterval * 1.2 * nthRetried).toLong().coerceAtMost(maxInterval)
                Log.e("Loki", "Polling failed, nextPoll at ${delayMills}ms", e)
                delay(delayMills)
            }
        }
    }

    private fun processPersonalMessages(snode: Snode, rawMessages: RawResponse, userPublicKey: String) {
        val messages = SnodeAPI.parseRawMessagesResponse(rawMessages, snode, userPublicKey)
        val parameters = messages.map { (envelope, serverHash) ->
            MessageReceiveParameters(envelope.toByteArray(), serverHash = serverHash)
        }
        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }
    }

    private fun processConfig(snode: Snode, rawMessages: RawResponse, namespace: Int, forConfigObject: ConfigBase?, userPublicKey: String) {
        if (forConfigObject == null) return

        val messages = SnodeAPI.parseRawMessagesResponse(
            rawMessages,
            snode,
            userPublicKey,
            namespace,
            updateLatestHash = true,
            updateStoredHashes = true,
        )

        if (messages.isEmpty()) {
            // no new messages to process
            return
        }

        var latestMessageTimestamp: Long? = null
        messages.forEach { (envelope, hash) ->
            try {
                val (message, _) = MessageReceiver.parse(data = envelope.toByteArray(),
                    // assume no groups in personal poller messages
                    openGroupServerID = null, currentClosedGroups = emptySet()
                )
                // sanity checks
                if (message !is SharedConfigurationMessage) {
                    Log.w("Loki", "shared config message handled in configs wasn't SharedConfigurationMessage but was ${message.javaClass.simpleName}")
                    return@forEach
                }
                val merged = forConfigObject.merge(hash!! to message.data).firstOrNull { it == hash }
                if (merged != null) {
                    // We successfully merged the hash, we can now update the timestamp
                    latestMessageTimestamp = if ((message.sentTimestamp ?: 0L) > (latestMessageTimestamp ?: 0L)) { message.sentTimestamp } else { latestMessageTimestamp }
                }
            } catch (e: Exception) {
                Log.e("Loki", e)
            }
        }
        // process new results
        // latestMessageTimestamp should always be non-null if the config object needs dump
        if (forConfigObject.needsDump() && latestMessageTimestamp != null) {
            configFactory.persist(forConfigObject, latestMessageTimestamp ?: SnodeAPI.nowWithOffset)
        }
    }

    private suspend fun pollOnce(snode: Snode, publicKey: String) {
        val requestSparseArray = SparseArray<SnodeAPI.SnodeBatchRequestInfo>()
        // get messages
        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(snode, publicKey, maxSize = -2)!!.also { personalMessages ->
            // namespaces here should always be set
            requestSparseArray[personalMessages.namespace!!] = personalMessages
        }
        // get the latest convo info volatile
        val hashesToExtend = mutableSetOf<String>()
        configFactory.getUserConfigs().mapNotNull { config ->
            hashesToExtend += config.currentHashes()
            SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                snode, publicKey,
                config.configNamespace(),
                maxSize = -8
            )
        }.forEach { request ->
            // namespaces here should always be set
            requestSparseArray[request.namespace!!] = request
        }

        val requests =
            requestSparseArray.valueIterator().asSequence().toMutableList()

        if (hashesToExtend.isNotEmpty()) {
            SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                messageHashes = hashesToExtend.toList(),
                publicKey = publicKey,
                newExpiry = SnodeAPI.nowWithOffset + 14.days.inWholeMilliseconds,
                extend = true
            )?.let { extensionRequest ->
                requests += extensionRequest
            }
        }

        val rawResponses = SnodeAPI.getRawBatchResponse(snode, publicKey, requests)
        isCaughtUp = true
        val responseList = (rawResponses["results"] as List<RawResponse>)
        // in case we had null configs, the array won't be fully populated
        // index of the sparse array key iterator should be the request index, with the key being the namespace
        sequenceOf(
            configFactory.user?.configNamespace(),
            configFactory.contacts?.configNamespace(),
            configFactory.userGroups?.configNamespace(),
            configFactory.convoVolatile?.configNamespace()
        ).filterNotNull()
            .map { it to requestSparseArray.indexOfKey(it) }
            .filter { (_, i) -> i >= 0 }
            .forEach { (key, requestIndex) ->
            responseList.getOrNull(requestIndex)?.let { rawResponse ->
                if (rawResponse["code"] as? Int != 200) {
                    Log.e("Loki", "Batch sub-request had non-200 response code, returned code ${(rawResponse["code"] as? Int) ?: "[unknown]"}")
                    return@forEach
                }
                val body = rawResponse["body"] as? RawResponse
                if (body == null) {
                    Log.e("Loki", "Batch sub-request didn't contain a body")
                    return@forEach
                }
                if (key == Namespace.DEFAULT) {
                    return@forEach // continue, skip default namespace
                } else {
                    when (ConfigBase.kindFor(key)) {
                        UserProfile::class.java -> processConfig(snode, body, key, configFactory.user, publicKey)
                        Contacts::class.java -> processConfig(snode, body, key, configFactory.contacts, publicKey)
                        ConversationVolatileConfig::class.java -> processConfig(snode, body, key, configFactory.convoVolatile, publicKey)
                        UserGroupsConfig::class.java -> processConfig(snode, body, key, configFactory.userGroups, publicKey)
                    }
                }
            }
        }

        // the first response will be the personal messages (we want these to be processed after config messages)
        val personalResponseIndex = requestSparseArray.indexOfKey(Namespace.DEFAULT)
        if (personalResponseIndex >= 0) {
            responseList.getOrNull(personalResponseIndex)?.let { rawResponse ->
                if (rawResponse["code"] as? Int != 200) {
                    Log.e("Loki", "Batch sub-request for personal messages had non-200 response code, returned code ${(rawResponse["code"] as? Int) ?: "[unknown]"}")
                } else {
                    val body = rawResponse["body"] as? RawResponse
                    if (body == null) {
                        Log.e("Loki", "Batch sub-request for personal messages didn't contain a body")
                    } else {
                        processPersonalMessages(snode, body, publicKey)
                    }
                }
            }
        }
    }
    // endregion
}
