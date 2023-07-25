package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import nl.komponents.kovenant.Promise
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OnionResponse
import org.session.libsession.snode.Version
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.emptyPromise
import org.session.libsignal.utilities.retryIfNeeded
import org.session.libsignal.utilities.sideEffect

@SuppressLint("StaticFieldLeak")
object PushManagerV1 {
    private const val TAG = "PushManagerV1"

    val context = MessagingModuleConfiguration.shared.context
    private const val maxRetryCount = 4

    private val server = Server.LEGACY

    fun register(
        isUsingFCM: Boolean = TextSecurePreferences.isUsingFCM(context),
        token: String? = TextSecurePreferences.getFCMToken(context),
        publicKey: String? = TextSecurePreferences.getLocalNumber(context),
        legacyGroupPublicKeys: Collection<String> = MessagingModuleConfiguration.shared.storage.getAllClosedGroupPublicKeys()
    ): Promise<*, Exception> =
        if (!isUsingFCM) {
            emptyPromise()
        } else retryIfNeeded(maxRetryCount) {
            doRegister(token, publicKey, legacyGroupPublicKeys)
        } fail { exception ->
            Log.d(TAG, "Couldn't register for FCM due to error: $exception.")
        }

    private fun doRegister(token: String?, publicKey: String?, legacyGroupPublicKeys: Collection<String>): Promise<*, Exception> {
        Log.d(TAG, "registerV1 requested")

        token ?: return emptyPromise()
        publicKey ?: return emptyPromise()

        val parameters = mapOf(
            "token" to token,
            "pubKey" to publicKey,
            "legacyGroupPublicKeys" to legacyGroupPublicKeys
        )

        val url = "${server.url}/register_legacy_groups_only"
        val body = RequestBody.create(
            MediaType.get("application/json"),
            JsonUtil.toJson(parameters)
        )
        val request = Request.Builder().url(url).post(body).build()

        return sendOnionRequest(request) sideEffect { response ->
            when (response.code) {
                null, 0 -> throw Exception("error: ${response.message}.")
            }
        } success {
            Log.d(TAG, "registerV1 success")
        }
    }

    /**
     * Unregister push notifications for 1-1 conversations as this is now done in FirebasePushManager.
     */
    fun unregister(): Promise<*, Exception> {
        Log.d(TAG, "unregisterV1 requested")

        val token = TextSecurePreferences.getFCMToken(context) ?: emptyPromise()

        return retryIfNeeded(maxRetryCount) {
            val parameters = mapOf("token" to token)
            val url = "${server.url}/unregister"
            val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
            val request = Request.Builder().url(url).post(body).build()

            sendOnionRequest(request) success {
                when (it.code) {
                    null, 0 -> throw Exception("error: ${it.message}.")
                    else -> Log.d(TAG, "unregisterV1 success")
                }
            }
        }
    }

    // Legacy Closed Groups

    fun subscribeGroup(
        closedGroupPublicKey: String,
        isUsingFCM: Boolean = TextSecurePreferences.isUsingFCM(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) = if (isUsingFCM) {
        performGroupOperation("subscribe_closed_group", closedGroupPublicKey, publicKey)
    } else emptyPromise()

    fun unsubscribeGroup(
        closedGroupPublicKey: String,
        isUsingFCM: Boolean = TextSecurePreferences.isUsingFCM(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) = if (isUsingFCM) {
        performGroupOperation("unsubscribe_closed_group", closedGroupPublicKey, publicKey)
    } else emptyPromise()

    private fun performGroupOperation(
        operation: String,
        closedGroupPublicKey: String,
        publicKey: String
    ): Promise<*, Exception> {
        val parameters = mapOf("closedGroupPublicKey" to closedGroupPublicKey, "pubKey" to publicKey)
        val url = "${server.url}/$operation"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body).build()

        return retryIfNeeded(maxRetryCount) {
            sendOnionRequest(request) sideEffect {
                when (it.code) {
                    0, null -> throw Exception(it.message)
                }
            }
        }
    }

    private fun sendOnionRequest(request: Request): Promise<OnionResponse, Exception> = OnionRequestAPI.sendOnionRequest(
        request,
        server.url,
        server.publicKey,
        Version.V2
    )
}
