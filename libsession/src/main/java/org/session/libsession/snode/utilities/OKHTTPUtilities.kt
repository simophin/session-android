package org.session.libsession.snode.utilities

import okhttp3.MultipartBody
import okhttp3.Request
import okio.Buffer
import org.session.libsignal.utilities.Base64
import java.io.IOException

internal fun Request.getHeadersForOnionRequest(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    val contentType = body()?.contentType()
    if (contentType != null) {
        result["content-type"] = contentType.toString()
    }
    val headers = headers()
    for (name in headers.names()) {
        val value = headers.get(name)
        if (value != null) {
            if (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)) {
                result[name] = value.toBoolean()
            } else if (value.toIntOrNull() != null) {
                result[name] = value.toInt()
            } else {
                result[name] = value
            }
        }
    }
    return result
}

internal fun Request.getBodyForOnionRequest(): Any? {
    try {
        val copyOfThis = newBuilder().build()
        val buffer = Buffer()
        val body = copyOfThis.body() ?: return null
        body.writeTo(buffer)
        val bodyAsData = buffer.readByteArray()
        if (body is MultipartBody) {
            val base64EncodedBody: String = Base64.encodeBytes(bodyAsData)
            return mapOf( "fileUpload" to base64EncodedBody )
        } else if (body.contentType()?.toString() == "application/octet-stream") {
            return bodyAsData
        } else {
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            return bodyAsData?.toString(charset)
        }
    } catch (e: IOException) {
        return null
    }
}
