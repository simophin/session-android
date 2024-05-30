package org.session.libsession.snode

import android.os.Build
import com.goterl.lazysodium.exceptions.SodiumException
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.messaging.utilities.SodiumUtilities.sodium
import org.session.libsignal.crypto.getRandomElement
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Broadcaster
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.prettifiedDescription
import org.session.libsignal.utilities.runRetry
import java.security.SecureRandom
import java.util.Locale
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.properties.Delegates.observable

object SnodeAPI {
    internal val database: LokiAPIDatabaseProtocol
        get() = SnodeModule.shared.storage
    private val broadcaster: Broadcaster
        get() = SnodeModule.shared.broadcaster

    internal var snodeFailureCount: MutableMap<Snode, Int> = mutableMapOf()
    internal var snodePool: Set<Snode>
        get() = database.getSnodePool()
        set(newValue) { database.setSnodePool(newValue) }
    /**
     * The offset between the user's clock and the Service Node's clock. Used in cases where the
     * user's clock is incorrect.
     */
    internal var clockOffset = 0L

    @JvmStatic
    public val nowWithOffset
        get() = System.currentTimeMillis() + clockOffset

    internal var forkInfo by observable(database.getForkInfo()) { _, oldValue, newValue ->
        if (newValue > oldValue) {
            Log.d("Loki", "Setting new fork info new: $newValue, old: $oldValue")
            database.setForkInfo(newValue)
        }
    }

    // Settings
    private val maxRetryCount = 6
    private val minimumSnodePoolCount = 12
    private val minimumSwarmSnodeCount = 3
    // Use port 4433 if the API level can handle the network security configuration and enforce pinned certificates
    private val seedNodePort = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) 443 else 4443
    private val seedNodePool by lazy {
        if (useTestnet) {
            setOf( "http://public.loki.foundation:38157" )
        } else {
            setOf(
                "https://seed1.getsession.org:$seedNodePort",
                "https://seed2.getsession.org:$seedNodePort",
                "https://seed3.getsession.org:$seedNodePort",
            )
        }
    }
    private const val snodeFailureThreshold = 3
    private const val useOnionRequests = true

    const val useTestnet = false

    // Error
    internal sealed class Error(val description: String) : Exception(description) {
        object Generic : Error("An error occurred.")
        object ClockOutOfSync : Error("Your clock is out of sync with the Service Node network.")
        object NoKeyPair : Error("Missing user key pair.")
        object SigningFailed : Error("Couldn't sign verification data.")
        // ONS
        object DecryptionFailed : Error("Couldn't decrypt ONS name.")
        object HashingFailed : Error("Couldn't compute ONS name hash.")
        object ValidationFailed : Error("ONS name validation failed.")
    }

    // Batch
    data class SnodeBatchRequestInfo(
        val method: String,
        val params: Map<String, Any>,
        @Transient
        val namespace: Int?
    ) // assume signatures, pubkey and namespaces are attached in parameters if required

    // Internal API
    internal suspend fun invoke(
        method: Snode.Method,
        snode: Snode,
        parameters: Map<String, Any>,
        publicKey: String? = null,
        version: Version = Version.V3
    ): RawResponse {
        val url = "${snode.address}:${snode.port}/storage_rpc/v1"
        if (useOnionRequests) {
            return OnionRequestAPI.sendOnionRequest(method, parameters, snode, version, publicKey).let {
                val body = it.body ?: throw Error.Generic
                JsonUtil.fromJson(body, Map::class.java)
            }
        } else {
            val payload = mapOf( "method" to method.rawValue, "params" to parameters )
            try {
                return withContext(Dispatchers.IO) {
                    val response = HTTP.execute(HTTP.Verb.POST, url, payload).toString()
                    JsonUtil.fromJson(response, Map::class.java)
                }
            } catch (exception: Exception) {
                val httpRequestFailedException = exception as? HTTP.HTTPRequestFailedException
                if (httpRequestFailedException != null) {
                    val error = handleSnodeError(httpRequestFailedException.statusCode, httpRequestFailedException.json, snode, publicKey)
                    if (error != null) {
                        throw error
                    }
                }
                Log.d("Loki", "Unhandled exception: $exception.")
                throw exception
            }
        }
    }

    internal suspend fun getRandomSnode(): Snode {
        val snodePool = this.snodePool
        if (snodePool.count() < minimumSnodePoolCount) {
            val target = seedNodePool.random()
            val url = "$target/json_rpc"
            Log.d("Loki", "Populating snode pool using: $target.")
            val parameters = mapOf(
                "method" to "get_n_service_nodes",
                "params" to mapOf(
                    "active_only" to true,
                    "limit" to 256,
                    "fields" to mapOf("public_ip" to true, "storage_port" to true, "pubkey_x25519" to true, "pubkey_ed25519" to true)
                )
            )
            val response = withContext(Dispatchers.IO) { HTTP.execute(HTTP.Verb.POST, url, parameters, useSeedNodeConnection = true) }
            val json = try {
                JsonUtil.fromJson(response, Map::class.java)
            } catch (exception: Exception) {
                mapOf( "result" to response.toString())
            }
            val intermediate = json["result"] as? Map<*, *>
            val rawSnodes = intermediate?.get("service_node_states") as? List<*>
            if (rawSnodes != null) {
                val snodePool = rawSnodes.mapNotNull { rawSnode ->
                    val rawSnodeAsJSON = rawSnode as? Map<*, *>
                    val address = rawSnodeAsJSON?.get("public_ip") as? String
                    val port = rawSnodeAsJSON?.get("storage_port") as? Int
                    val ed25519Key = rawSnodeAsJSON?.get("pubkey_ed25519") as? String
                    val x25519Key = rawSnodeAsJSON?.get("pubkey_x25519") as? String
                    if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                        Snode("https://$address", port, Snode.KeySet(ed25519Key, x25519Key))
                    } else {
                        Log.d("Loki", "Failed to parse: ${rawSnode?.prettifiedDescription()}.")
                        null
                    }
                }.toMutableSet()
                Log.d("Loki", "Persisting snode pool to database.")
                this.snodePool = snodePool
                try {
                    return snodePool.getRandomElement()
                } catch (exception: Exception) {
                    Log.d("Loki", "Got an empty snode pool from: $target.")
                    throw Error.Generic
                }
            } else {
                Log.d("Loki", "Failed to update snode pool from: ${(rawSnodes as List<*>?)?.prettifiedDescription()}.")
                throw Error.Generic
            }

        } else {
            return snodePool.getRandomElement()
        }
    }

    internal fun dropSnodeFromSwarmIfNeeded(snode: Snode, publicKey: String) {
        val swarm = database.getSwarm(publicKey)?.toMutableSet()
        if (swarm != null && swarm.contains(snode)) {
            swarm.remove(snode)
            database.setSwarm(publicKey, swarm)
        }
    }

    internal suspend fun getSingleTargetSnode(publicKey: String): Snode {
        // SecureRandom() should be cryptographically secure
        return getSwarm(publicKey).let { it.shuffled(SecureRandom()).random() }
    }

    // Public API
    suspend fun getSessionID(onsName: String): String {
        val validationCount = 3
        val sessionIDByteCount = 33
        // Hash the ONS name using BLAKE2b
        val onsName = onsName.toLowerCase(Locale.US)
        val nameAsData = onsName.toByteArray()
        val nameHash = ByteArray(GenericHash.BYTES)
        if (!sodium.cryptoGenericHash(nameHash, nameHash.size, nameAsData, nameAsData.size.toLong())) {
            throw Error.HashingFailed
        }
        val base64EncodedNameHash = Base64.encodeBytes(nameHash)
        // Ask 3 different snodes for the Session ID associated with the given name hash
        val parameters = mapOf(
                "endpoint" to "ons_resolve",
                "params" to mapOf( "type" to 0, "name_hash" to base64EncodedNameHash )
        )

        return coroutineScope {
            val jobs = (1..validationCount).map {
                async {
                    val snode = getRandomSnode()
                    runRetry(maxRetryCount) {
                        invoke(Snode.Method.OxenDaemonRPCCall, snode, parameters)
                    }
                }
            }

            val results = jobs.awaitAll()

            val sessionIDs = mutableListOf<String>()
            for (json in results) {
                val intermediate = json["result"] as? Map<*, *>
                val hexEncodedCiphertext = intermediate?.get("encrypted_value") as? String
                if (hexEncodedCiphertext != null) {
                    val ciphertext = Hex.fromStringCondensed(hexEncodedCiphertext)
                    val isArgon2Based = (intermediate["nonce"] == null)
                    if (isArgon2Based) {
                        // Handle old Argon2-based encryption used before HF16
                        val salt = ByteArray(PwHash.SALTBYTES)
                        val key: ByteArray
                        val nonce = ByteArray(SecretBox.NONCEBYTES)
                        val sessionIDAsData = ByteArray(sessionIDByteCount)
                        try {
                            key = Key.fromHexString(sodium.cryptoPwHash(onsName, SecretBox.KEYBYTES, salt, PwHash.OPSLIMIT_MODERATE, PwHash.MEMLIMIT_MODERATE, PwHash.Alg.PWHASH_ALG_ARGON2ID13)).asBytes
                        } catch (e: SodiumException) {
                            throw Error.HashingFailed
                        }
                        if (!sodium.cryptoSecretBoxOpenEasy(sessionIDAsData, ciphertext, ciphertext.size.toLong(), nonce, key)) {
                            throw Error.DecryptionFailed
                        }
                        sessionIDs.add(Hex.toStringCondensed(sessionIDAsData))
                    } else {
                        val hexEncodedNonce = intermediate["nonce"] as? String
                        if (hexEncodedNonce == null) {
                            throw Error.Generic
                        }
                        val nonce = Hex.fromStringCondensed(hexEncodedNonce)
                        val key = ByteArray(GenericHash.BYTES)
                        if (!sodium.cryptoGenericHash(key, key.size, nameAsData, nameAsData.size.toLong(), nameHash, nameHash.size)) {
                            throw Error.HashingFailed
                        }
                        val sessionIDAsData = ByteArray(sessionIDByteCount)
                        if (!sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(sessionIDAsData, null, null, ciphertext, ciphertext.size.toLong(), null, 0, nonce, key)) {
                            throw Error.DecryptionFailed
                        }
                        sessionIDs.add(Hex.toStringCondensed(sessionIDAsData))
                    }
                } else {
                    throw Error.Generic
                }
            }
            if (sessionIDs.size == validationCount && sessionIDs.toSet().size == 1) {
                sessionIDs.first()
            } else {
                throw Error.ValidationFailed
            }
        }
    }

    suspend fun getSwarm(publicKey: String): Set<Snode> {
        val cachedSwarm = database.getSwarm(publicKey)
        return if (cachedSwarm != null && cachedSwarm.size >= minimumSwarmSnodeCount) {
            cachedSwarm.toSet()
        } else {
            val parameters = mapOf( "pubKey" to publicKey )
            invoke(Snode.Method.GetSwarm, getRandomSnode(), parameters, publicKey).let {
                parseSnodes(it).toSet()
            }.also {
                database.setSwarm(publicKey, it)
            }
        }
    }

    suspend fun getRawMessages(snode: Snode, publicKey: String, requiresAuth: Boolean = true, namespace: Int = 0): RawResponse {
        // Get last message hash
        val lastHashValue = database.getLastMessageHashValue(snode, publicKey, namespace) ?: ""
        val parameters = mutableMapOf<String, Any>(
            "pubKey" to publicKey,
            "last_hash" to lastHashValue,
        )
        // Construct signature
        if (requiresAuth) {
            val userED25519KeyPair = try {
                MessagingModuleConfiguration.shared.getUserED25519KeyPair()
                    ?: throw Error.NoKeyPair
            } catch (e: Exception) {
                Log.e("Loki", "Error getting KeyPair", e)
                throw Error.NoKeyPair
            }
            val timestamp = System.currentTimeMillis() + clockOffset
            val ed25519PublicKey = userED25519KeyPair.publicKey.asHexString
            val signature = ByteArray(Sign.BYTES)
            val verificationData =
                if (namespace != 0) "retrieve$namespace$timestamp".toByteArray()
                else "retrieve$timestamp".toByteArray()
            try {
                sodium.cryptoSignDetached(
                    signature,
                    verificationData,
                    verificationData.size.toLong(),
                    userED25519KeyPair.secretKey.asBytes
                )
            } catch (exception: Exception) {
                throw Error.SigningFailed
            }
            parameters["timestamp"] = timestamp
            parameters["pubkey_ed25519"] = ed25519PublicKey
            parameters["signature"] = Base64.encodeBytes(signature)
        }

        // If the namespace is default (0) here it will be implicitly read as 0 on the storage server
        // we only need to specify it explicitly if we want to (in future) or if it is non-zero
        if (namespace != 0) {
            parameters["namespace"] = namespace
        }

        // Make the request
        return invoke(Snode.Method.Retrieve, snode, parameters, publicKey)
    }

    fun buildAuthenticatedStoreBatchInfo(publicKey: String, namespace: Int, message: SnodeMessage): SnodeBatchRequestInfo? {
        val params = mutableMapOf<String, Any>()
        // load the message data params into the sub request
        // currently loads:
        // pubKey
        // data
        // ttl
        // timestamp
        params.putAll(message.toJSON())
        params["namespace"] = namespace

        // used for sig generation since it is also the value used in timestamp parameter
        val messageTimestamp = message.timestamp

        val userEd25519KeyPair = try {
            MessagingModuleConfiguration.shared.getUserED25519KeyPair() ?: return null
        } catch (e: Exception) {
            return null
        }

        val ed25519PublicKey = userEd25519KeyPair.publicKey.asHexString
        val signature = ByteArray(Sign.BYTES)
        val verificationData = "store$namespace$messageTimestamp".toByteArray()
        try {
            sodium.cryptoSignDetached(
                signature,
                verificationData,
                verificationData.size.toLong(),
                userEd25519KeyPair.secretKey.asBytes
            )
        } catch (e: Exception) {
            Log.e("Loki", "Signing data failed with user secret key", e)
        }
        // timestamp already set
        params["pubkey_ed25519"] = ed25519PublicKey
        params["signature"] = Base64.encodeBytes(signature)
        return SnodeBatchRequestInfo(
            Snode.Method.SendMessage.rawValue,
            params,
            namespace
        )
    }

    /**
     * Message hashes can be shared across multiple namespaces (for a single public key destination)
     * @param publicKey the destination's identity public key to delete from (05...)
     * @param messageHashes a list of stored message hashes to delete from the server
     * @param required indicates that *at least one* message in the list is deleted from the server, otherwise it will return 404
     */
    fun buildAuthenticatedDeleteBatchInfo(publicKey: String, messageHashes: List<String>, required: Boolean = false): SnodeBatchRequestInfo? {
        val params = mutableMapOf(
            "pubkey" to publicKey,
            "required" to required, // could be omitted technically but explicit here
            "messages" to messageHashes
        )
        val userEd25519KeyPair = try {
            MessagingModuleConfiguration.shared.getUserED25519KeyPair() ?: return null
        } catch (e: Exception) {
            return null
        }
        val ed25519PublicKey = userEd25519KeyPair.publicKey.asHexString
        val signature = ByteArray(Sign.BYTES)
        val verificationData = "delete${messageHashes.joinToString("")}".toByteArray()
        try {
            sodium.cryptoSignDetached(
                signature,
                verificationData,
                verificationData.size.toLong(),
                userEd25519KeyPair.secretKey.asBytes
            )
        } catch (e: Exception) {
            Log.e("Loki", "Signing data failed with user secret key", e)
            return null
        }
        params["pubkey_ed25519"] = ed25519PublicKey
        params["signature"] = Base64.encodeBytes(signature)
        return SnodeBatchRequestInfo(
            Snode.Method.DeleteMessage.rawValue,
            params,
            null
        )
    }

    fun buildAuthenticatedRetrieveBatchRequest(snode: Snode, publicKey: String, namespace: Int = 0, maxSize: Int? = null): SnodeBatchRequestInfo? {
        val lastHashValue = database.getLastMessageHashValue(snode, publicKey, namespace) ?: ""
        val params = mutableMapOf<String, Any>(
            "pubkey" to publicKey,
            "last_hash" to lastHashValue,
        )
        val userEd25519KeyPair = try {
            MessagingModuleConfiguration.shared.getUserED25519KeyPair() ?: return null
        } catch (e: Exception) {
            return null
        }
        val ed25519PublicKey = userEd25519KeyPair.publicKey.asHexString
        val timestamp = System.currentTimeMillis() + clockOffset
        val signature = ByteArray(Sign.BYTES)
        val verificationData = if (namespace == 0) "retrieve$timestamp".toByteArray()
        else "retrieve$namespace$timestamp".toByteArray()
        try {
            sodium.cryptoSignDetached(
                signature,
                verificationData,
                verificationData.size.toLong(),
                userEd25519KeyPair.secretKey.asBytes
            )
        } catch (e: Exception) {
            Log.e("Loki", "Signing data failed with user secret key", e)
            return null
        }
        params["timestamp"] = timestamp
        params["pubkey_ed25519"] = ed25519PublicKey
        params["signature"] = Base64.encodeBytes(signature)
        if (namespace != 0) {
            params["namespace"] = namespace
        }
        if (maxSize != null) {
            params["max_size"] = maxSize
        }
        return SnodeBatchRequestInfo(
            Snode.Method.Retrieve.rawValue,
            params,
            namespace
        )
    }

    fun buildAuthenticatedAlterTtlBatchRequest(
        messageHashes: List<String>,
        newExpiry: Long,
        publicKey: String,
        shorten: Boolean = false,
        extend: Boolean = false): SnodeBatchRequestInfo? {
        val params = buildAlterTtlParams(messageHashes, newExpiry, publicKey, extend, shorten) ?: return null
        return SnodeBatchRequestInfo(
            Snode.Method.Expire.rawValue,
            params,
            null
        )
    }

    suspend fun getRawBatchResponse(snode: Snode, publicKey: String, requests: List<SnodeBatchRequestInfo>, sequence: Boolean = false): RawResponse {
        val parameters = mutableMapOf<String, Any>(
            "requests" to requests
        )
        return invoke(if (sequence) Snode.Method.Sequence else Snode.Method.Batch, snode, parameters, publicKey).also { rawResponses ->
            val responseList = (rawResponses["results"] as List<RawResponse>)
            responseList.forEachIndexed { index, response ->
                if (response["code"] as? Int != 200) {
                    Log.w("Loki", "response code was not 200")
                    handleSnodeError(
                        response["code"] as? Int ?: 0,
                        response,
                        snode,
                        publicKey
                    )
                }
            }
        }
    }

    suspend fun getExpiries(messageHashes: List<String>, publicKey: String) : RawResponse {
        val userEd25519KeyPair = MessagingModuleConfiguration.shared.getUserED25519KeyPair() ?: throw NullPointerException("No user key pair")
        val hashes = messageHashes.takeIf { it.size != 1 } ?: (messageHashes + "///////////////////////////////////////////") // TODO remove this when bug is fixed on nodes.
        return runRetry(maxRetryCount) {
            val timestamp = System.currentTimeMillis() + clockOffset
            val signData = "${Snode.Method.GetExpiries.rawValue}$timestamp${hashes.joinToString(separator = "")}".toByteArray()

            val ed25519PublicKey = userEd25519KeyPair.publicKey.asHexString
            val signature = ByteArray(Sign.BYTES)
            try {
                sodium.cryptoSignDetached(
                    signature,
                    signData,
                    signData.size.toLong(),
                    userEd25519KeyPair.secretKey.asBytes
                )
            } catch (e: Exception) {
                Log.e("Loki", "Signing data failed with user secret key", e)
                throw e
            }

            val params = mapOf(
                "pubkey" to publicKey,
                "messages" to hashes,
                "timestamp" to timestamp,
                "pubkey_ed25519" to ed25519PublicKey,
                "signature" to Base64.encodeBytes(signature)
            )
            getSingleTargetSnode(publicKey).let { snode ->
                invoke(Snode.Method.GetExpiries, snode, params, publicKey)
            }
        }
    }

    suspend fun alterTtl(messageHashes: List<String>, newExpiry: Long, publicKey: String, extend: Boolean = false, shorten: Boolean = false): RawResponse {
        return runRetry(maxRetryCount) {
            val params = buildAlterTtlParams(messageHashes, newExpiry, publicKey, extend, shorten)
                ?: throw Exception("Couldn't build signed params for alterTtl request for newExpiry=$newExpiry, extend=$extend, shorten=$shorten")

            invoke(Snode.Method.Expire, getSingleTargetSnode(publicKey), params, publicKey)
        }
    }

    private fun buildAlterTtlParams( // TODO: in future this will probably need to use the closed group subkeys / admin keys for group swarms
        messageHashes: List<String>,
        newExpiry: Long,
        publicKey: String,
        extend: Boolean = false,
        shorten: Boolean = false): Map<String, Any>? {
        val userEd25519KeyPair = MessagingModuleConfiguration.shared.getUserED25519KeyPair() ?: return null
        val params = mutableMapOf(
            "expiry" to newExpiry,
            "messages" to messageHashes,
        )
        if (extend) {
            params["extend"] = true
        } else if (shorten) {
            params["shorten"] = true
        }
        val shortenOrExtend = if (extend) "extend" else if (shorten) "shorten" else ""

        val signData = "${Snode.Method.Expire.rawValue}$shortenOrExtend$newExpiry${messageHashes.joinToString(separator = "")}".toByteArray()

        val ed25519PublicKey = userEd25519KeyPair.publicKey.asHexString
        val signature = ByteArray(Sign.BYTES)
        try {
            sodium.cryptoSignDetached(
                signature,
                signData,
                signData.size.toLong(),
                userEd25519KeyPair.secretKey.asBytes
            )
        } catch (e: Exception) {
            Log.e("Loki", "Signing data failed with user secret key", e)
            return null
        }
        params["pubkey"] = publicKey
        params["pubkey_ed25519"] = ed25519PublicKey
        params["signature"] = Base64.encodeBytes(signature)

        return params
    }

    suspend fun getMessages(publicKey: String): MessageList {
        return runRetry(maxRetryCount) {
           getSingleTargetSnode(publicKey).let { snode ->
               parseRawMessagesResponse(getRawMessages(snode, publicKey), snode, publicKey)
            }
        }
    }

    private suspend fun getNetworkTime(snode: Snode): Pair<Snode,Long> {
        return invoke(Snode.Method.Info, snode, emptyMap()).let { rawResponse ->
            val timestamp = rawResponse["timestamp"] as? Long ?: -1
            snode to timestamp
        }
    }

    suspend fun sendMessage(message: SnodeMessage, requiresAuth: Boolean = false, namespace: Int = 0): RawResponse {
        val destination = message.recipient
        return runRetry(maxRetryCount) {
            val module = MessagingModuleConfiguration.shared
            val userED25519KeyPair = module.getUserED25519KeyPair() ?: throw Error.NoKeyPair
            val parameters = message.toJSON().toMutableMap<String,Any>()
            // Construct signature
            if (requiresAuth) {
                val sigTimestamp = nowWithOffset
                val ed25519PublicKey = userED25519KeyPair.publicKey.asHexString
                val signature = ByteArray(Sign.BYTES)
                // assume namespace here is non-zero, as zero namespace doesn't require auth
                val verificationData = "store$namespace$sigTimestamp".toByteArray()
                try {
                    sodium.cryptoSignDetached(signature, verificationData, verificationData.size.toLong(), userED25519KeyPair.secretKey.asBytes)
                } catch (exception: Exception) {
                    throw Error.SigningFailed
                }
                parameters["sig_timestamp"] = sigTimestamp
                parameters["pubkey_ed25519"] = ed25519PublicKey
                parameters["signature"] = Base64.encodeBytes(signature)
            }
            // If the namespace is default (0) here it will be implicitly read as 0 on the storage server
            // we only need to specify it explicitly if we want to (in future) or if it is non-zero
            if (namespace != 0) {
                parameters["namespace"] = namespace
            }

            invoke(Snode.Method.SendMessage, getSingleTargetSnode(destination), parameters, destination)
        }
    }

    suspend fun deleteMessage(publicKey: String, serverHashes: List<String>): Map<String,Boolean> {
        return runRetry(maxRetryCount) {
            val module = MessagingModuleConfiguration.shared
            val userED25519KeyPair = module.getUserED25519KeyPair() ?: throw Error.NoKeyPair
            val userPublicKey = module.storage.getUserPublicKey() ?: throw Error.NoKeyPair
            val snode = getSingleTargetSnode(publicKey)
            runRetry(maxRetryCount) {
                val signature = ByteArray(Sign.BYTES)
                val verificationData = (Snode.Method.DeleteMessage.rawValue + serverHashes.fold("") { a, v -> a + v }).toByteArray()
                sodium.cryptoSignDetached(signature, verificationData, verificationData.size.toLong(), userED25519KeyPair.secretKey.asBytes)
                val deleteMessageParams = mapOf(
                    "pubkey" to userPublicKey,
                    "pubkey_ed25519" to userED25519KeyPair.publicKey.asHexString,
                    "messages" to serverHashes,
                    "signature" to Base64.encodeBytes(signature)
                )

                val rawResponse =
                invoke(Snode.Method.DeleteMessage, snode, deleteMessageParams, publicKey)
                val swarms = rawResponse["swarm"] as? Map<String, Any> ?: return@runRetry emptyMap()
                val result = swarms.mapNotNull { (hexSnodePublicKey, rawJSON) ->
                    val json = rawJSON as? Map<String, Any> ?: return@mapNotNull null
                    val isFailed = json["failed"] as? Boolean ?: false
                    val statusCode = json["code"] as? String
                    val reason = json["reason"] as? String
                    hexSnodePublicKey to if (isFailed) {
                        Log.e("Loki", "Failed to delete messages from: $hexSnodePublicKey due to error: $reason ($statusCode).")
                        false
                    } else {
                        val hashes = json["deleted"] as List<String> // Hashes of deleted messages
                        val signature = json["signature"] as String
                        val snodePublicKey = Key.fromHexString(hexSnodePublicKey)
                        // The signature looks like ( PUBKEY_HEX || RMSG[0] || ... || RMSG[N] || DMSG[0] || ... || DMSG[M] )
                        val message = (userPublicKey + serverHashes.fold("") { a, v -> a + v } + hashes.fold("") { a, v -> a + v }).toByteArray()
                        sodium.cryptoSignVerifyDetached(Base64.decode(signature), message, message.size, snodePublicKey.asBytes)
                    }
                }
                result.toMap()
        }
        }
    }

    // Parsing
    private fun parseSnodes(rawResponse: Any): List<Snode> {
        val json = rawResponse as? Map<*, *>
        val rawSnodes = json?.get("snodes") as? List<*>
        if (rawSnodes != null) {
            return rawSnodes.mapNotNull { rawSnode ->
                val rawSnodeAsJSON = rawSnode as? Map<*, *>
                val address = rawSnodeAsJSON?.get("ip") as? String
                val portAsString = rawSnodeAsJSON?.get("port") as? String
                val port = portAsString?.toInt()
                val ed25519Key = rawSnodeAsJSON?.get("pubkey_ed25519") as? String
                val x25519Key = rawSnodeAsJSON?.get("pubkey_x25519") as? String
                if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                    Snode("https://$address", port, Snode.KeySet(ed25519Key, x25519Key))
                } else {
                    Log.d("Loki", "Failed to parse snode from: ${rawSnode?.prettifiedDescription()}.")
                    null
                }
            }
        } else {
            Log.d("Loki", "Failed to parse snodes from: ${rawResponse.prettifiedDescription()}.")
            return listOf()
        }
    }

    suspend fun deleteAllMessages(): Map<String,Boolean> {
        return runRetry(maxRetryCount) {
            val module = MessagingModuleConfiguration.shared
            val userED25519KeyPair = module.getUserED25519KeyPair() ?: throw Error.NoKeyPair
            val userPublicKey = module.storage.getUserPublicKey() ?: throw Error.NoKeyPair
            getSingleTargetSnode(userPublicKey).let { snode ->
                runRetry(maxRetryCount) {
                    getNetworkTime(snode).let { (_, timestamp) ->
                        val signature = ByteArray(Sign.BYTES)
                        val verificationData = (Snode.Method.DeleteAll.rawValue + Namespace.ALL + timestamp.toString()).toByteArray()
                        sodium.cryptoSignDetached(signature, verificationData, verificationData.size.toLong(), userED25519KeyPair.secretKey.asBytes)
                        val deleteMessageParams = mapOf(
                            "pubkey" to userPublicKey,
                            "pubkey_ed25519" to userED25519KeyPair.publicKey.asHexString,
                            "timestamp" to timestamp,
                            "signature" to Base64.encodeBytes(signature),
                            "namespace" to Namespace.ALL,
                        )
                        invoke(Snode.Method.DeleteAll, snode, deleteMessageParams, userPublicKey).let {
                            rawResponse -> parseDeletions(userPublicKey, timestamp, rawResponse)
                        }
                    }
                }
            }
        }
    }

    suspend fun updateExpiry(updatedExpiryMs: Long, serverHashes: List<String>): Map<String, Pair<List<String>, Long>> {
        return runRetry(maxRetryCount) {
            val module = MessagingModuleConfiguration.shared
            val userED25519KeyPair = module.getUserED25519KeyPair() ?: throw Error.NoKeyPair
            val userPublicKey = module.storage.getUserPublicKey() ?: throw Error.NoKeyPair
            val updatedExpiryMsWithNetworkOffset = updatedExpiryMs + clockOffset
            val snode = getSingleTargetSnode(userPublicKey)
            runRetry(maxRetryCount) second@ {
                // "expire" || expiry || messages[0] || ... || messages[N]
                val verificationData =
                    (Snode.Method.Expire.rawValue + updatedExpiryMsWithNetworkOffset + serverHashes.fold("") { a, v -> a + v }).toByteArray()
                val signature = ByteArray(Sign.BYTES)
                sodium.cryptoSignDetached(
                    signature,
                    verificationData,
                    verificationData.size.toLong(),
                    userED25519KeyPair.secretKey.asBytes
                )
                val params = mapOf(
                    "pubkey" to userPublicKey,
                    "pubkey_ed25519" to userED25519KeyPair.publicKey.asHexString,
                    "expiry" to updatedExpiryMs,
                    "messages" to serverHashes,
                    "signature" to Base64.encodeBytes(signature)
                )
                val rawResponse = invoke(Snode.Method.Expire, snode, params, userPublicKey)
                val swarms = rawResponse["swarm"] as? Map<String, Any> ?: return@second emptyMap()
                val result = swarms.mapNotNull { (hexSnodePublicKey, rawJSON) ->
                    val json = rawJSON as? Map<String, Any> ?: return@mapNotNull null
                    val isFailed = json["failed"] as? Boolean ?: false
                    val statusCode = json["code"] as? String
                    val reason = json["reason"] as? String
                    hexSnodePublicKey to if (isFailed) {
                        Log.e("Loki", "Failed to update expiry for: $hexSnodePublicKey due to error: $reason ($statusCode).")
                        listOf<String>() to 0L
                    } else {
                        val hashes = json["updated"] as List<String>
                        val expiryApplied = json["expiry"] as Long
                        val signature = json["signature"] as String
                        val snodePublicKey = Key.fromHexString(hexSnodePublicKey)
                        // The signature looks like ( PUBKEY_HEX || RMSG[0] || ... || RMSG[N] || DMSG[0] || ... || DMSG[M] )
                        val message = (userPublicKey + serverHashes.fold("") { a, v -> a + v } + hashes.fold("") { a, v -> a + v }).toByteArray()
                        if (sodium.cryptoSignVerifyDetached(Base64.decode(signature), message, message.size, snodePublicKey.asBytes)) {
                            hashes to expiryApplied
                        } else listOf<String>() to 0L
                    }
                }

                result.toMap()
            }

        }
    }

    fun parseRawMessagesResponse(rawResponse: RawResponse, snode: Snode, publicKey: String, namespace: Int = 0, updateLatestHash: Boolean = true, updateStoredHashes: Boolean = true): List<Pair<SignalServiceProtos.Envelope, String?>> {
        val messages = rawResponse["messages"] as? List<*>
        return if (messages != null) {
            if (updateLatestHash) {
                updateLastMessageHashValueIfPossible(snode, publicKey, messages, namespace)
            }
            val newRawMessages = removeDuplicates(publicKey, messages, namespace, updateStoredHashes)
            return parseEnvelopes(newRawMessages)
        } else {
            listOf()
        }
    }

    private fun updateLastMessageHashValueIfPossible(snode: Snode, publicKey: String, rawMessages: List<*>, namespace: Int) {
        val lastMessageAsJSON = rawMessages.lastOrNull() as? Map<*, *>
        val hashValue = lastMessageAsJSON?.get("hash") as? String
        if (hashValue != null) {
            database.setLastMessageHashValue(snode, publicKey, hashValue, namespace)
        } else if (rawMessages.isNotEmpty()) {
            Log.d("Loki", "Failed to update last message hash value from: ${rawMessages.prettifiedDescription()}.")
        }
    }

    private fun removeDuplicates(publicKey: String, rawMessages: List<*>, namespace: Int, updateStoredHashes: Boolean): List<*> {
        val originalMessageHashValues = database.getReceivedMessageHashValues(publicKey, namespace)?.toMutableSet() ?: mutableSetOf()
        val receivedMessageHashValues = originalMessageHashValues.toMutableSet()
        val result = rawMessages.filter { rawMessage ->
            val rawMessageAsJSON = rawMessage as? Map<*, *>
            val hashValue = rawMessageAsJSON?.get("hash") as? String
            if (hashValue != null) {
                val isDuplicate = receivedMessageHashValues.contains(hashValue)
                receivedMessageHashValues.add(hashValue)
                !isDuplicate
            } else {
                Log.d("Loki", "Missing hash value for message: ${rawMessage?.prettifiedDescription()}.")
                false
            }
        }
        if (originalMessageHashValues != receivedMessageHashValues && updateStoredHashes) {
            database.setReceivedMessageHashValues(publicKey, receivedMessageHashValues, namespace)
        }
        return result
    }

    private fun parseEnvelopes(rawMessages: List<*>): List<Pair<SignalServiceProtos.Envelope, String?>> {
        return rawMessages.mapNotNull { rawMessage ->
            val rawMessageAsJSON = rawMessage as? Map<*, *>
            val base64EncodedData = rawMessageAsJSON?.get("data") as? String
            val data = base64EncodedData?.let { Base64.decode(it) }
            if (data != null) {
                try {
                    Pair(MessageWrapper.unwrap(data), rawMessageAsJSON.get("hash") as? String)
                } catch (e: Exception) {
                    Log.d("Loki", "Failed to unwrap data for message: ${rawMessage.prettifiedDescription()}.")
                    null
                }
            } else {
                Log.d("Loki", "Failed to decode data for message: ${rawMessage?.prettifiedDescription()}.")
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDeletions(userPublicKey: String, timestamp: Long, rawResponse: RawResponse): Map<String, Boolean> {
        val swarms = rawResponse["swarm"] as? Map<String, Any> ?: return mapOf()
        val result = swarms.mapNotNull { (hexSnodePublicKey, rawJSON) ->
            val json = rawJSON as? Map<String, Any> ?: return@mapNotNull null
            val isFailed = json["failed"] as? Boolean ?: false
            val statusCode = json["code"] as? String
            val reason = json["reason"] as? String
            hexSnodePublicKey to if (isFailed) {
                Log.e("Loki", "Failed to delete all messages from: $hexSnodePublicKey due to error: $reason ($statusCode).")
                false
            } else {
                val hashes = (json["deleted"] as Map<String,List<String>>).flatMap { (_, hashes) -> hashes }.sorted() // Hashes of deleted messages
                val signature = json["signature"] as String
                val snodePublicKey = Key.fromHexString(hexSnodePublicKey)
                // The signature looks like ( PUBKEY_HEX || TIMESTAMP || DELETEDHASH[0] || ... || DELETEDHASH[N] )
                val message = (userPublicKey + timestamp.toString() + hashes.joinToString(separator = "")).toByteArray()
                sodium.cryptoSignVerifyDetached(Base64.decode(signature), message, message.size, snodePublicKey.asBytes)
            }
        }
        return result.toMap()
    }

    // endregion

    // Error Handling
    internal fun handleSnodeError(statusCode: Int, json: Map<*, *>?, snode: Snode, publicKey: String? = null): Exception? {
        fun handleBadSnode() {
            val oldFailureCount = snodeFailureCount[snode] ?: 0
            val newFailureCount = oldFailureCount + 1
            snodeFailureCount[snode] = newFailureCount
            Log.d("Loki", "Couldn't reach snode at $snode; setting failure count to $newFailureCount.")
            if (newFailureCount >= snodeFailureThreshold) {
                Log.d("Loki", "Failure threshold reached for: $snode; dropping it.")
                if (publicKey != null) {
                    dropSnodeFromSwarmIfNeeded(snode, publicKey)
                }
                snodePool = snodePool.toMutableSet().minus(snode).toSet()
                Log.d("Loki", "Snode pool count: ${snodePool.count()}.")
                snodeFailureCount[snode] = 0
            }
        }
        when (statusCode) {
            400, 500, 502, 503 -> { // Usually indicates that the snode isn't up to date
                handleBadSnode()
            }
            406 -> {
                Log.d("Loki", "The user's clock is out of sync with the service node network.")
                broadcaster.broadcast("clockOutOfSync")
                return Error.ClockOutOfSync
            }
            421 -> {
                // The snode isn't associated with the given public key anymore
                if (publicKey != null) {
                    fun invalidateSwarm() {
                        Log.d("Loki", "Invalidating swarm for: $publicKey.")
                        dropSnodeFromSwarmIfNeeded(snode, publicKey)
                    }
                    if (json != null) {
                        val snodes = parseSnodes(json)
                        if (snodes.isNotEmpty()) {
                            database.setSwarm(publicKey, snodes.toSet())
                        } else {
                            invalidateSwarm()
                        }
                    } else {
                        invalidateSwarm()
                    }
                } else {
                    Log.d("Loki", "Got a 421 without an associated public key.")
                }
            }
            404 -> {
                Log.d("Loki", "404, probably no file found")
                return Error.Generic
            }
            else -> {
                handleBadSnode()
                Log.d("Loki", "Unhandled response code: ${statusCode}.")
                return Error.Generic
            }
        }
        return null
    }
}

// Type Aliases
typealias RawResponse = Map<*, *>
typealias MessageList = List<Pair<SignalServiceProtos.Envelope, String?>>
