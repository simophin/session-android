package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OnionResponse
import org.session.libsession.snode.Version
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.runRetry

@SuppressLint("StaticFieldLeak")
object PushRegistryV1 {
    private val TAG = PushRegistryV1::class.java.name

    val context = MessagingModuleConfiguration.shared.context
    private const val maxRetryCount = 4

    private val server = Server.LEGACY

    suspend fun register(
        device: Device,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        token: String? = TextSecurePreferences.getPushToken(context),
        publicKey: String? = TextSecurePreferences.getLocalNumber(context),
        legacyGroupPublicKeys: Collection<String> = MessagingModuleConfiguration.shared.storage.getAllClosedGroupPublicKeys()
    ) = when {
        isPushEnabled -> runRetry(maxRetryCount) {
            Log.d(TAG, "register() called")
            try {
                doRegister(token, publicKey, device, legacyGroupPublicKeys)
            } catch (e: Exception) {
                Log.d(TAG, "Couldn't register for FCM due to error", e)
                throw e
            }
        }
        else -> {}
    }

    private suspend fun doRegister(token: String?, publicKey: String?, device: Device, legacyGroupPublicKeys: Collection<String>) {
        Log.d(TAG, "doRegister() called")

        token ?: return
        publicKey ?: return

        val parameters = mapOf(
            "token" to token,
            "pubKey" to publicKey,
            "device" to device.value,
            "legacyGroupPublicKeys" to legacyGroupPublicKeys
        )

        val url = "${server.url}/register_legacy_groups_only"
        val body = RequestBody.create(
            MediaType.get("application/json"),
            JsonUtil.toJson(parameters)
        )
        val request = Request.Builder().url(url).post(body).build()
        val response = sendOnionRequest(request)
        when (response.code) {
            null, 0 -> throw Exception("error: ${response.message}.")
            else -> Log.d(TAG, "registerV1 success")
        }
    }

    /**
     * Unregister push notifications for 1-1 conversations as this is now done in FirebasePushManager.
     */
    suspend fun unregister() {
        Log.d(TAG, "unregisterV1 requested")

        val token = TextSecurePreferences.getPushToken(context) ?: return

        return runRetry(maxRetryCount) {
            val parameters = mapOf("token" to token)
            val url = "${server.url}/unregister"
            val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
            val request = Request.Builder().url(url).post(body).build()
            val response = sendOnionRequest(request)
            when (response.code) {
                null, 0 -> Log.d(TAG, "error: ${response.message}.")
                else -> Log.d(TAG, "unregisterV1 success")
            }
        }
    }

    // Legacy Closed Groups

    suspend fun subscribeGroup(
        closedGroupPublicKey: String,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) {
        if (isPushEnabled) {
            performGroupOperation("subscribe_closed_group", closedGroupPublicKey, publicKey)
        }
    }

    suspend fun unsubscribeGroup(
        closedGroupPublicKey: String,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) {
        if (isPushEnabled) {
            performGroupOperation("unsubscribe_closed_group", closedGroupPublicKey, publicKey)
        }
    }
    private suspend fun performGroupOperation(
        operation: String,
        closedGroupPublicKey: String,
        publicKey: String
    ) {
        val parameters = mapOf("closedGroupPublicKey" to closedGroupPublicKey, "pubKey" to publicKey)
        val url = "${server.url}/$operation"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body).build()

        return runRetry(maxRetryCount) {
            val response = sendOnionRequest(request)
            when (response.code) {
                0, null -> throw Exception(response.message)
            }
        }
    }

    private suspend fun sendOnionRequest(request: Request): OnionResponse = OnionRequestAPI.sendOnionRequest(
        request,
        server.url,
        server.publicKey,
        Version.V2
    )
}
