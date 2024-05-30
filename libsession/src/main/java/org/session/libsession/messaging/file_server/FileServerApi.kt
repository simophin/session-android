package org.session.libsession.messaging.file_server

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.RequestBody
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log

object FileServerApi {

    private const val serverPublicKey = "da21e1d886c6fbaea313f75298bd64aab03a97ce985b46bb2dad9f2089c8ee59"
    const val server = "http://filev2.getsession.org"
    const val maxFileSize = 10_000_000 // 10 MB

    sealed class Error(message: String) : Exception(message) {
        object ParsingFailed : Error("Invalid response.")
        object InvalidURL : Error("Invalid URL.")
    }

    data class Request(
            val verb: HTTP.Verb,
            val endpoint: String,
            val queryParameters: Map<String, String> = mapOf(),
            val parameters: Any? = null,
            val headers: Map<String, String> = mapOf(),
            val body: ByteArray? = null,
            /**
         * Always `true` under normal circumstances. You might want to disable
         * this when running over Lokinet.
         */
        val useOnionRouting: Boolean = true
    )

    private fun createBody(body: ByteArray?, parameters: Any?): RequestBody? {
        if (body != null) return RequestBody.create(MediaType.get("application/octet-stream"), body)
        if (parameters == null) return null
        val parametersAsJSON = JsonUtil.toJson(parameters)
        return RequestBody.create(MediaType.get("application/json"), parametersAsJSON)
    }

    private suspend fun send(request: Request): ByteArray {
        val url = HttpUrl.parse(server) ?: throw Error.InvalidURL
        val urlBuilder = HttpUrl.Builder()
            .scheme(url.scheme())
            .host(url.host())
            .port(url.port())
            .addPathSegments(request.endpoint)
        if (request.verb == HTTP.Verb.GET) {
            for ((key, value) in request.queryParameters) {
                urlBuilder.addQueryParameter(key, value)
            }
        }
        val requestBuilder = okhttp3.Request.Builder()
            .url(urlBuilder.build())
            .headers(Headers.of(request.headers))
        when (request.verb) {
            HTTP.Verb.GET -> requestBuilder.get()
            HTTP.Verb.PUT -> requestBuilder.put(createBody(request.body, request.parameters)!!)
            HTTP.Verb.POST -> requestBuilder.post(createBody(request.body, request.parameters)!!)
            HTTP.Verb.DELETE -> requestBuilder.delete(createBody(request.body, request.parameters))
        }
        return if (request.useOnionRouting) {
            try {
                OnionRequestAPI.sendOnionRequest(requestBuilder.build(), server, serverPublicKey).let {
                    it.body ?: throw Error.ParsingFailed
                }
            } catch (e: Exception) {
                when (e) {
                    // No need for the stack trace for HTTP errors
                    is HTTP.HTTPRequestFailedException -> Log.e("Loki", "File server request failed due to error: ${e.message}")
                    else -> Log.e("Loki", "File server request failed", e)
                }

                throw e
            }
        } else {
            throw IllegalStateException("It's currently not allowed to send non onion routed requests.")
        }
    }

    suspend fun upload(file: ByteArray): Long {
        val request = Request(
            verb = HTTP.Verb.POST,
            endpoint = "file",
            body = file,
            headers = mapOf(
                "Content-Disposition" to "attachment",
                "Content-Type" to "application/octet-stream"
            )
        )
        return send(request).let { response ->
            val json = JsonUtil.fromJson(response, Map::class.java)
            val hasId = json.containsKey("id")
            val id = json.getOrDefault("id", null)
            Log.d("Loki-FS", "File Upload Response hasId: $hasId of type: ${id?.javaClass}")
            (id as? String)?.toLong() ?: throw Error.ParsingFailed
        }
    }

    suspend fun download(file: String): ByteArray {
        val request = Request(verb = HTTP.Verb.GET, endpoint = "file/$file")
        return send(request)
    }
}