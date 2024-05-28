package org.session.libsession.messaging.sending_receiving.pollers

import android.util.SparseArray
import androidx.core.util.valueIterator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.control.SharedConfigurationMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.utilities.await
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.Snode
import java.lang.Long.min
import kotlin.time.Duration.Companion.days

class Poller @JvmOverloads constructor(
        private val configFactory: ConfigFactoryProtocol,
        initialUserPublicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey().orEmpty(),
        coroutineScope: CoroutineScope = GlobalScope
) {
    private val userPublicKey = MutableStateFlow(initialUserPublicKey)
    private val appVisible = MutableStateFlow(false)

    sealed interface State {
        object Idle : State
        object Polling : State

        data class Error(val exception: Exception) : State

        object CaughtUp : State
    }

    val state: StateFlow<State> = channelFlow {
        combine(userPublicKey, appVisible, ::Pair)
            .distinctUntilChanged()
            .collectLatest { (key, visible) ->
                if (!visible || key.isBlank()) {
                    send(State.Idle)
                    return@collectLatest
                }

                var delayMills = 0L
                var numContinuousError = 0

                while (true) {
                    if (delayMills > 0L) {
                        Log.d("Loki", "Polling delayed for $delayMills ms.")
                        send(State.Idle)
                        delay(delayMills)
                    }

                    send(State.Polling)
                    try {
                        Log.d("Loki", "Polling started.")
                        pollOnce(key)
                        Log.d("Loki", "Polling completed successfully.")
                        send(State.CaughtUp)
                        delayMills = RETRY_INTERVAL
                        numContinuousError = 0
                    }
                    catch (e: CancellationException) {
                        Log.i("Loki", "Polling cancelled.")
                        throw e
                    }
                    catch (e: Exception) {
                        Log.e("Loki", "Polling failed.", e)
                        send(State.Error(e))
                        numContinuousError += 1
                        delayMills = min(MAX_INTERVAL, (numContinuousError * RETRY_INTERVAL * 1.2).toLong())
                    }
                }
            }
    }.stateIn(coroutineScope, SharingStarted.Eagerly, State.Idle)

    // region Settings
    companion object {
        private const val RETRY_INTERVAL: Long = 2 * 1000L
        private const val MAX_INTERVAL: Long = 15 * 1000L
    }
    // endregion

    // region Public API
    fun isCaughtUp(): Boolean = state.value == State.CaughtUp

    fun onAppVisible() {
        appVisible.value = true
    }

    fun onAppBackgrounded() {
        appVisible.value = false
    }

    fun updateUserPublicKey(userPublicKey: String) {
        this.userPublicKey.value = userPublicKey
    }
    // endregion

    // region Private API
    private suspend fun pollOnce(publicKey: String) {
        val polledSnodes = mutableSetOf<Snode>()
        var nodesToPoll = SnodeAPI.getSwarm(publicKey).await() - polledSnodes

        while (nodesToPoll.isNotEmpty()) {
            val nodeToPull = nodesToPoll.random()
            polledSnodes.add(nodeToPull)

            var numRetried = 0

            while (true) {
                try {
                    poll(nodeToPull, publicKey)
                    break
                }
                catch (e: CancellationException) {
                    throw e
                }
                catch (e: Exception) {
                    if (numRetried >= 2) {
                        Log.e("Loki", "Polling failed for $nodeToPull, dropping it", e)
                        SnodeAPI.dropSnodeFromSwarmIfNeeded(nodeToPull, publicKey)
                        break
                    }
                    numRetried += 1
                }
            }

            nodesToPoll = SnodeAPI.getSwarm(publicKey).await() - polledSnodes
        }
    }

    private fun processPersonalMessages(snode: Snode, userPublicKey: String, rawMessages: RawResponse) {
        val messages = SnodeAPI.parseRawMessagesResponse(rawMessages, snode, userPublicKey)
        val parameters = messages.map { (envelope, serverHash) ->
            MessageReceiveParameters(envelope.toByteArray(), serverHash = serverHash)
        }
        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }
    }

    private fun processConfig(snode: Snode,
                              userPublicKey: String,
                              rawMessages: RawResponse,
                              namespace: Int,
                              forConfigObject: ConfigBase?) {
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

    private suspend fun poll(snode: Snode, userPublicKey: String) {
        val requestSparseArray = SparseArray<SnodeAPI.SnodeBatchRequestInfo>()
        // get messages
        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(snode, userPublicKey, maxSize = -2)!!.also { personalMessages ->
            // namespaces here should always be set
            requestSparseArray[personalMessages.namespace!!] = personalMessages
        }
        // get the latest convo info volatile
        val hashesToExtend = mutableSetOf<String>()
        configFactory.getUserConfigs().mapNotNull { config ->
            hashesToExtend += config.currentHashes()
            SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                    snode, userPublicKey,
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
                    publicKey = userPublicKey,
                    newExpiry = SnodeAPI.nowWithOffset + 14.days.inWholeMilliseconds,
                    extend = true
            )?.let { extensionRequest ->
                requests += extensionRequest
            }
        }

        val rawResponses = SnodeAPI.getRawBatchResponse(snode, userPublicKey, requests).await()

        val responseList = (rawResponses["results"] as List<RawResponse>)
        // in case we had null configs, the array won't be fully populated
        // index of the sparse array key iterator should be the request index, with the key being the namespace
        sequenceOf(
                configFactory.user?.configNamespace(),
                configFactory.contacts?.configNamespace(),
                configFactory.userGroups?.configNamespace(),
                configFactory.convoVolatile?.configNamespace())
        .filterNotNull()
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
                        UserProfile::class.java -> processConfig(snode, userPublicKey, body, key, configFactory.user)
                        Contacts::class.java -> processConfig(snode, userPublicKey, body, key, configFactory.contacts)
                        ConversationVolatileConfig::class.java -> processConfig(snode, userPublicKey, body, key, configFactory.convoVolatile)
                        UserGroupsConfig::class.java -> processConfig(snode, userPublicKey, body, key, configFactory.userGroups)
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
                        processPersonalMessages(snode, userPublicKey, body)
                    }
                }
            }
        }
    }
    // endregion
}
